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
    fun contact_status_round_trip_and_load_pending() = runTest {
        val p = openTestDb()
        try {
            val aliceFp = "a".repeat(64)
            val bobFp = "b".repeat(64)
            p.saveContact(QrPayload(
                identityKey = "AAAA", fingerprint = aliceFp,
                keyServer = "https://ks", relayServer = "https://rs",
                relayMailbox = "alice-mailbox-id-22-aaaa",
            ), "Alice", ContactStatus.ACCEPTED)
            p.saveContact(QrPayload(
                identityKey = "BBBB", fingerprint = bobFp,
                keyServer = "https://ks", relayServer = "https://rs",
                relayMailbox = "bob-mailbox-id-22-bbbbbb",
            ), "Bob", ContactStatus.PENDING)

            assertEquals(ContactStatus.ACCEPTED, p.loadContact(aliceFp)!!.status)
            assertEquals(ContactStatus.PENDING, p.loadContact(bobFp)!!.status)
            assertEquals(listOf(bobFp), p.loadPendingContacts().map { it.qr.fingerprint })

            // Promote Bob → accepted.
            p.setContactStatus(bobFp, ContactStatus.ACCEPTED)
            assertEquals(ContactStatus.ACCEPTED, p.loadContact(bobFp)!!.status)
            assertEquals(0, p.loadPendingContacts().size)
        } finally {
            p.close()
        }
    }

    @Test
    fun deleteContact_cascades_to_session_and_messages_via_explicit_transaction() = runTest {
        val p = openTestDb()
        try {
            val aliceFp = "a".repeat(64)
            val bobFp = "b".repeat(64)
            p.saveContact(QrPayload(
                identityKey = "AAAA", fingerprint = aliceFp,
                keyServer = "https://ks", relayServer = "https://rs",
                relayMailbox = "alice-mailbox-id-22-aaaa",
            ), "Alice")
            p.saveContact(QrPayload(
                identityKey = "BBBB", fingerprint = bobFp,
                keyServer = "https://ks", relayServer = "https://rs",
                relayMailbox = "bob-mailbox-id-22-bbbbbb",
            ), "Bob")
            // Alice gets a session row + messages, Bob just a contact row.
            val state = RatchetState(
                DHs = X25519KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                DHr = ByteArray(32) { 3 },
                RK = ByteArray(32) { 4 },
                CKs = ByteArray(32) { 5 },
                CKr = null,
                Ns = 0, Nr = 0, PN = 0,
            )
            p.saveSession(SessionRecord(
                contactFingerprint = aliceFp,
                inboundMailbox = "in-alice", inboundBearerToken = "tok",
                outboundMailbox = "out-alice", outboundRelayServer = "https://rs",
                associatedData = ByteArray(64),
                ratchetState = state, lastFetchedSequence = 0,
                updatedAt = "2026-05-20T00:00:00Z",
            ))
            for (i in 1..3) {
                p.saveMessage(aliceFp, MessageDirection.SENT, "m$i", "2026-05-20T00:0$i:00Z")
            }

            p.deleteContact(aliceFp)

            assertNull(p.loadContact(aliceFp))
            assertNull(p.loadSession(aliceFp))
            assertEquals(0, p.loadMessages(aliceFp).size)
            // Bob's row untouched.
            assertEquals("Bob", p.loadContact(bobFp)!!.displayName)
            assertEquals(1, p.loadAllContacts().size)
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
    fun message_uuid_round_trip_and_edit_via_uuid() = runTest {
        val p = openTestDb()
        try {
            val fp = "c".repeat(64)
            p.saveContact(QrPayload(
                identityKey = "AAAA", fingerprint = fp,
                keyServer = "x", relayServer = "y",
                relayMailbox = "mailbox-id-22-chars-cccc",
            ))
            val uuid = "01234567-89ab-cdef-0123-456789abcdef"
            p.saveMessage(fp, MessageDirection.SENT, "no uuid", "ts1", messageUuid = null)
            p.saveMessage(fp, MessageDirection.SENT, "original", "ts2", messageUuid = uuid)

            val before = p.loadMessages(fp)
            assertEquals(2, before.size)
            assertEquals(null, before[0].messageUuid)
            assertEquals(uuid, before[1].messageUuid)
            assertEquals(false, before[1].edited)

            val lookup = p.findMessageByUuid(uuid)
            assertEquals(fp, lookup?.contactFingerprint)
            assertEquals(MessageDirection.SENT, lookup?.direction)

            p.updateMessageBodyByUuid(uuid, "edited via uuid")

            val after = p.loadMessages(fp)
            // Original row (no uuid) is untouched.
            assertEquals("no uuid", after[0].body)
            assertEquals(false, after[0].edited)
            // Edited row reflects the new body + edited=true.
            assertEquals("edited via uuid", after[1].body)
            assertEquals(true, after[1].edited)
        } finally {
            p.close()
        }
    }

    @Test
    fun group_message_uuid_round_trip_and_edit_via_uuid() = runTest {
        val p = openTestDb()
        try {
            val sender = "d".repeat(64)
            val groupId = "1234".repeat(8)
            val uuid = "fedcba98-7654-3210-fedc-ba9876543210"
            p.saveGroup(groupId, "G", createdByFingerprint = sender, isAdmin = true)
            p.saveGroupMessage(
                groupId, sender, "Alice", "group hi", "ts",
                MessageDirection.SENT, messageUuid = uuid,
            )
            val lookup = p.findGroupMessageByUuid(uuid)
            assertEquals(groupId, lookup?.groupId)
            assertEquals(sender, lookup?.senderFingerprint)

            p.updateGroupMessageBodyByUuid(uuid, "group edited")
            val msgs = p.loadGroupMessages(groupId)
            assertEquals(1, msgs.size)
            assertEquals("group edited", msgs[0].body)
            assertEquals(true, msgs[0].edited)
        } finally {
            p.close()
        }
    }

    @Test
    fun verified_flag_round_trip_and_preserved_across_saveContact() = runTest {
        val p = openTestDb()
        try {
            val fp = "e".repeat(64)
            val qr = QrPayload(
                identityKey = "AAAA", fingerprint = fp,
                keyServer = "x", relayServer = "y",
                relayMailbox = "mailbox-id-22-chars-eeee",
            )
            p.saveContact(qr, "Alice")
            assertEquals(false, p.isContactVerified(fp))
            assertEquals(false, p.loadContact(fp)!!.verified)

            p.setContactVerified(fp, true)
            assertEquals(true, p.isContactVerified(fp))
            assertEquals(true, p.loadContact(fp)!!.verified)

            // Re-upsert (e.g. reply_info brought a fresh display name).
            // verified MUST survive — only setContactVerified is allowed
            // to mutate that column directly.
            p.saveContact(qr, "Alice (new)")
            assertEquals(true, p.loadContact(fp)!!.verified)

            p.setContactVerified(fp, false)
            assertEquals(false, p.isContactVerified(fp))
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
