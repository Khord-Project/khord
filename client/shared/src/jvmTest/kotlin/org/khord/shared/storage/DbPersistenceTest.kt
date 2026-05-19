package org.khord.shared.storage

import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.crypto.PreKeys
import org.khord.shared.crypto.X25519KeyPair
import org.khord.shared.crypto.ratchet.RatchetState
import org.khord.shared.protocol.wire.QrPayload
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SQLDelight-driven persistence tests against an on-disk SQLite database
 * in a tempdir. Each test creates its own DB so the suite is parallel-safe.
 */
class DbPersistenceTest {

    private val tempDir = createTempDirectory("khord-persistence-test").toFile().apply {
        deleteOnExit()
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private suspend fun openTestDb(name: String = "test-${System.nanoTime()}.db"): Persistence {
        Crypto.ensureInitialized()  // InMemoryKeyStore samples random bytes via libsodium
        val dbPath = Path(tempDir.absolutePath, name).absolutePathString()
        return openDbPersistence(dbPath, InMemoryKeyStore())
    }

    private suspend fun aliceIdentity(): IdentityKey {
        Crypto.ensureInitialized()
        return IdentityKey.fromSeedPhrase("alice db persist ${System.nanoTime()}")
    }

    @Test
    fun identity_round_trip() = runTest {
        val p = openTestDb()
        try {
            val id = aliceIdentity()
            p.saveIdentity(IdentityRecord(id, "https://ks", "https://rs", "2026-05-04T00:00:00Z"))
            val loaded = p.loadIdentity()!!
            assertEquals(id.fingerprint, loaded.identity.fingerprint)
            assertContentEquals(id.ed25519PublicKey, loaded.identity.ed25519PublicKey)
            assertContentEquals(id.ed25519SecretKey, loaded.identity.ed25519SecretKey)
            assertContentEquals(id.x25519PublicKey, loaded.identity.x25519PublicKey)
            assertContentEquals(id.x25519SecretKey, loaded.identity.x25519SecretKey)
            assertEquals("https://ks", loaded.keyServerUrl)
            assertEquals("https://rs", loaded.relayServerUrl)
        } finally {
            p.close()
        }
    }

    @Test
    fun deleteAllOneTimePreKeys_unblocks_registration_retry() = runTest {
        // Reproduction of the production bug: a tester's first
        // registration crashed mid-flight after OPKs were persisted but
        // before the Key Server uploadBundle succeeded. On retry,
        // Messaging.register() generated OPKs with the same key_ids
        // (PreKeys.generateOneTimePreKeys is deterministic on its
        // range), and the second saveOpkBatch crashed with
        // "UNIQUE constraint failed: one_time_pre_key.key_id". Asserts:
        //   1. A second save with overlapping ids WILL throw — confirms
        //      the bug is real at the persistence layer.
        //   2. After deleteAllOneTimePreKeys, the same save succeeds —
        //      confirms the fix works.
        val p = openTestDb()
        try {
            p.saveOpkBatch((1..5).associateWith { ByteArray(32) { b -> (it * b).toByte() } })
            assertEquals(5, p.loadAllOpkSecrets().size)

            // Re-insert with overlapping ids must blow up — that's the
            // production bug we're fixing.
            var threw = false
            try {
                p.saveOpkBatch((1..5).associateWith { ByteArray(32) })
            } catch (_: Throwable) {
                threw = true
            }
            assertTrue(threw, "expected duplicate-key insert to throw")

            // After wipe, the same insert succeeds.
            p.deleteAllOneTimePreKeys()
            assertEquals(0, p.loadAllOpkSecrets().size)
            p.saveOpkBatch((1..5).associateWith { ByteArray(32) { b -> (it + b).toByte() } })
            assertEquals(5, p.loadAllOpkSecrets().size)
        } finally {
            p.close()
        }
    }

    @kotlin.test.Test
    fun spk_and_opk_round_trip() = runTest {
        val p = openTestDb()
        try {
            Crypto.ensureInitialized()
            val identity = IdentityKey.fromSeedPhrase("spk roundtrip")
            val spkGen = PreKeys.generateSignedPreKey(identity, keyId = 7)
            p.saveSignedPreKey(SignedPreKeyRecord(
                keyId = spkGen.signedPreKey.keyId,
                publicKey = spkGen.signedPreKey.publicKey,
                secretKey = spkGen.secretKey,
            ))
            val loaded = p.loadSignedPreKey()!!
            assertEquals(7, loaded.keyId)
            assertContentEquals(spkGen.signedPreKey.publicKey, loaded.publicKey)
            assertContentEquals(spkGen.secretKey, loaded.secretKey)

            val opks = (1..5).associateWith { ByteArray(32) { i -> (it * i).toByte() } }
            p.saveOpkBatch(opks)
            val loadedOpks = p.loadAllOpkSecrets()
            assertEquals(5, loadedOpks.size)
            for ((id, secret) in opks) {
                assertContentEquals(secret, loadedOpks[id])
            }

            p.deleteOneTimePreKey(3)
            assertEquals(4, p.loadAllOpkSecrets().size)
            assertFalse(3 in p.loadAllOpkSecrets())
        } finally {
            p.close()
        }
    }

    @Test
    fun contact_and_pending_mailbox_lifecycle() = runTest {
        val p = openTestDb()
        try {
            val qr = QrPayload(
                identityKey = "AAAA",
                fingerprint = "f".repeat(64),
                keyServer = "https://ks",
                relayServer = "https://rs",
                relayMailbox = "mailbox-id-22-chars-zzzz",
            )
            p.saveContact(qr, displayName = "Bob")
            val loaded = p.loadContact(qr.fingerprint)!!
            assertEquals(qr.fingerprint, loaded.qr.fingerprint)
            assertEquals(qr.relayMailbox, loaded.qr.relayMailbox)
            assertEquals("Bob", loaded.displayName)

            p.updateContactDisplayName(qr.fingerprint, "Bob (renamed)")
            assertEquals("Bob (renamed)", p.loadContact(qr.fingerprint)!!.displayName)

            p.savePendingMailbox("mid-1", "tok-1")
            assertEquals(mapOf("mid-1" to "tok-1"), p.loadPendingMailboxes())
            p.deletePendingMailbox("mid-1")
            assertEquals(0, p.loadPendingMailboxes().size)
        } finally {
            p.close()
        }
    }

    @Test
    fun session_round_trip_with_ratchet_state() = runTest {
        val p = openTestDb()
        try {
            // Prerequisite: contact row (FK to session).
            val fp = "a".repeat(64)
            p.saveContact(QrPayload(
                identityKey = "AAAA", fingerprint = fp,
                keyServer = "x", relayServer = "y",
                relayMailbox = "mailbox-id-22-chars-aaaa",
            ))
            val state = RatchetState(
                DHs = X25519KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                DHr = ByteArray(32) { 3 },
                RK = ByteArray(32) { 4 },
                CKs = ByteArray(32) { 5 },
                CKr = null,
                Ns = 1, Nr = 0, PN = 0,
            )
            val record = SessionRecord(
                contactFingerprint = fp,
                inboundMailbox = "in-mailbox",
                inboundBearerToken = "in-tok",
                outboundMailbox = "out-mailbox",
                outboundRelayServer = "https://other-rs",
                associatedData = ByteArray(64) { (it % 256).toByte() },
                ratchetState = state,
                lastFetchedSequence = 5,
                updatedAt = "2026-05-05T00:00:00Z",
            )
            p.saveSession(record)
            val loaded = p.loadSession(fp)!!
            assertEquals(fp, loaded.contactFingerprint)
            assertEquals("in-mailbox", loaded.inboundMailbox)
            assertEquals(5, loaded.lastFetchedSequence)
            assertContentEquals(state.RK, loaded.ratchetState.RK)
            assertContentEquals(state.DHs.publicKey, loaded.ratchetState.DHs.publicKey)
        } finally {
            p.close()
        }
    }

    @Test
    fun messages_preserve_order_under_load() = runTest {
        val p = openTestDb()
        try {
            val fp = "b".repeat(64)
            p.saveContact(QrPayload(
                identityKey = "AAAA", fingerprint = fp,
                keyServer = "x", relayServer = "y",
                relayMailbox = "mailbox-id-22-chars-bbbb",
            ))
            for (i in 1..100) {
                p.saveMessage(fp, MessageDirection.SENT, "msg-$i", "2026-05-05T00:00:00Z")
            }
            val loaded = p.loadMessages(fp)
            assertEquals(100, loaded.size)
            assertEquals((1..100).map { "msg-$it" }, loaded.map { it.body })
        } finally {
            p.close()
        }
    }

    @Test
    fun panic_wipes_db_and_deletes_file() = runTest {
        Crypto.ensureInitialized()
        val name = "panic-${System.nanoTime()}.db"
        val dbPath = Path(tempDir.absolutePath, name).absolutePathString()
        val p = openDbPersistence(dbPath, InMemoryKeyStore())
        Crypto.ensureInitialized()
        val id = IdentityKey.fromSeedPhrase("panic-test")
        p.saveIdentity(IdentityRecord(id, "https://ks", "https://rs", "2026-05-05T00:00:00Z"))

        assertTrue(Path(dbPath).exists())
        p.panic()
        assertFalse(Path(dbPath).exists(), "panic must delete the DB file")
    }

    @Test
    fun key_server_token_round_trip() = runTest {
        val p = openTestDb()
        try {
            assertNull(p.loadKeyServerToken())
            p.saveKeyServerToken("token-1", "2026-05-05T00:15:00Z")
            val loaded = p.loadKeyServerToken()!!
            assertEquals("token-1", loaded.token)
            p.clearKeyServerToken()
            assertNull(p.loadKeyServerToken())
        } finally {
            p.close()
        }
    }
}
