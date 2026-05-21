package org.khord.shared.storage

import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.protocol.wire.QrPayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryPersistenceTest {

    private suspend fun aliceIdentity(): IdentityKey {
        Crypto.ensureInitialized()
        return IdentityKey.fromSeedPhrase("alice mem persist")
    }

    @Test
    fun identity_save_load_round_trip() = runTest {
        val p = InMemoryPersistence()
        assertNull(p.loadIdentity())
        val id = aliceIdentity()
        p.saveIdentity(IdentityRecord(id, "https://ks", "https://rs", "2026-05-04T00:00:00Z"))
        val loaded = p.loadIdentity()!!
        assertEquals(id.fingerprint, loaded.identity.fingerprint)
        assertContentEquals(id.ed25519PublicKey, loaded.identity.ed25519PublicKey)
        assertEquals("https://ks", loaded.keyServerUrl)
    }

    @Test
    fun opk_lifecycle() = runTest {
        val p = InMemoryPersistence()
        p.saveOpkBatch(mapOf(1 to ByteArray(32) { 1 }, 2 to ByteArray(32) { 2 }))
        assertEquals(2, p.loadAllOpkSecrets().size)
        p.deleteOneTimePreKey(1)
        val remaining = p.loadAllOpkSecrets()
        assertEquals(1, remaining.size)
        assertTrue(2 in remaining)
    }

    @Test
    fun deleteAllOneTimePreKeys_wipes_the_store_for_registration_retry() = runTest {
        // Models the registration-retry path that was crashing with a
        // UNIQUE constraint violation on DbPersistence: register()
        // generated OPKs with key_ids 1..N, persisted them, then a later
        // step failed; on retry, register() generated the same key_ids
        // and saveOpkBatch hit the dupe. With deleteAllOneTimePreKeys
        // wired in at the top of register(), this two-saves-with-same-ids
        // pattern must just work.
        val p = InMemoryPersistence()
        p.saveOpkBatch((1..5).associateWith { ByteArray(32) { b -> (it * b).toByte() } })
        assertEquals(5, p.loadAllOpkSecrets().size)

        p.deleteAllOneTimePreKeys()
        assertEquals(0, p.loadAllOpkSecrets().size)

        // Re-insert with the SAME key_ids — must succeed.
        p.saveOpkBatch((1..5).associateWith { ByteArray(32) { b -> (it + b).toByte() } })
        assertEquals(5, p.loadAllOpkSecrets().size)
    }

    @Test
    fun contact_save_load() = runTest {
        val p = InMemoryPersistence()
        val qr = QrPayload(
            identityKey = "AAAA",
            fingerprint = "0".repeat(64),
            keyServer = "https://ks",
            relayServer = "https://rs",
            relayMailbox = "mailbox-id-22-chars-aaaa",
        )
        p.saveContact(qr, displayName = "Alice")
        val loaded = p.loadContact("0".repeat(64))!!
        assertEquals(qr, loaded.qr)
        assertEquals("Alice", loaded.displayName)
        assertEquals(1, p.loadAllContacts().size)

        p.updateContactDisplayName("0".repeat(64), "Renamed")
        assertEquals("Renamed", p.loadContact("0".repeat(64))!!.displayName)
    }

    @Test
    fun deleteContact_removes_contact_session_and_all_messages_for_that_contact() = runTest {
        val p = InMemoryPersistence()
        val aliceFp = "a".repeat(64)
        val bobFp = "b".repeat(64)
        val aliceQr = QrPayload(
            identityKey = "AAAA",
            fingerprint = aliceFp,
            keyServer = "https://ks",
            relayServer = "https://rs",
            relayMailbox = "alice-mailbox-id-22-aaaa",
        )
        val bobQr = aliceQr.copy(fingerprint = bobFp, relayMailbox = "bob-mailbox-id-22-bbbbbb")
        p.saveContact(aliceQr, "Alice")
        p.saveContact(bobQr, "Bob")
        p.saveMessage(aliceFp, MessageDirection.SENT, "hi alice", "2026-05-05T00:00:00Z")
        p.saveMessage(aliceFp, MessageDirection.RECEIVED, "hey", "2026-05-05T00:00:01Z")
        p.saveMessage(bobFp, MessageDirection.SENT, "hi bob", "2026-05-05T00:00:02Z")

        p.deleteContact(aliceFp)

        // Alice + her messages + her session gone.
        assertNull(p.loadContact(aliceFp))
        assertEquals(0, p.loadMessages(aliceFp).size)
        assertNull(p.loadSession(aliceFp))
        // Bob completely untouched.
        assertEquals("Bob", p.loadContact(bobFp)!!.displayName)
        assertEquals(1, p.loadMessages(bobFp).size)
        assertEquals(1, p.loadAllContacts().size)
    }

    @Test
    fun deleteContact_is_a_noop_for_unknown_fingerprint() = runTest {
        val p = InMemoryPersistence()
        // Just shouldn't throw — store stays empty.
        p.deleteContact("z".repeat(64))
        assertEquals(0, p.loadAllContacts().size)
    }

    @Test
    fun saveContact_defaults_status_to_accepted_and_set_promotes_pending() = runTest {
        val p = InMemoryPersistence()
        val fp = "a".repeat(64)
        val qr = QrPayload(
            identityKey = "AAAA",
            fingerprint = fp,
            keyServer = "x",
            relayServer = "y",
            relayMailbox = "m" + "0".repeat(21),
        )
        p.saveContact(qr, "Alice") // default ACCEPTED
        assertEquals(ContactStatus.ACCEPTED, p.loadContact(fp)!!.status)
        assertEquals(0, p.loadPendingContacts().size)

        p.setContactStatus(fp, ContactStatus.PENDING)
        assertEquals(ContactStatus.PENDING, p.loadContact(fp)!!.status)
        assertEquals(1, p.loadPendingContacts().size)

        p.setContactStatus(fp, ContactStatus.ACCEPTED)
        assertEquals(0, p.loadPendingContacts().size)
    }

    @Test
    fun loadPendingContacts_returns_only_pending_rows() = runTest {
        val p = InMemoryPersistence()
        val aliceFp = "a".repeat(64)
        val bobFp = "b".repeat(64)
        val carolFp = "c".repeat(64)
        fun qr(fp: String) = QrPayload(
            identityKey = "AAAA", fingerprint = fp,
            keyServer = "x", relayServer = "y",
            relayMailbox = "m" + fp.take(21),
        )
        p.saveContact(qr(aliceFp), "Alice", ContactStatus.ACCEPTED)
        p.saveContact(qr(bobFp), "Bob", ContactStatus.PENDING)
        p.saveContact(qr(carolFp), "Carol", ContactStatus.PENDING)

        val pending = p.loadPendingContacts().map { it.qr.fingerprint }.toSet()
        assertEquals(setOf(bobFp, carolFp), pending)
        assertEquals(3, p.loadAllContacts().size)
    }

    @Test
    fun pending_mailbox_lifecycle() = runTest {
        val p = InMemoryPersistence()
        p.savePendingMailbox("mid-1", "tok-1")
        p.savePendingMailbox("mid-2", "tok-2")
        assertEquals(2, p.loadPendingMailboxes().size)
        p.deletePendingMailbox("mid-1")
        assertEquals(mapOf("mid-2" to "tok-2"), p.loadPendingMailboxes())
    }

    @Test
    fun messages_preserve_insertion_order() = runTest {
        val p = InMemoryPersistence()
        val fp = "0".repeat(64)
        // Need a contact for message FK semantics in DB; in-memory variant
        // doesn't enforce, but we still test ordering.
        for (i in 1..10) {
            p.saveMessage(fp, MessageDirection.SENT, "msg $i", "2026-05-05T00:00:0${i % 10}Z")
        }
        val msgs = p.loadMessages(fp)
        assertEquals((1..10).map { "msg $it" }, msgs.map { it.body })
    }

    @Test
    fun panic_wipes_everything() = runTest {
        val p = InMemoryPersistence()
        val id = aliceIdentity()
        p.saveIdentity(IdentityRecord(id, "https://ks", "https://rs", "2026-05-04T00:00:00Z"))
        p.saveOpkBatch(mapOf(1 to ByteArray(32)))
        p.saveContact(QrPayload(
            identityKey = "AA", fingerprint = "0".repeat(64),
            keyServer = "x", relayServer = "y", relayMailbox = "m" + "0".repeat(21),
        ))
        p.savePendingMailbox("m1", "t1")
        p.saveMessage("0".repeat(64), MessageDirection.SENT, "hi", "ts")
        p.saveKeyServerToken("tok", "exp")

        p.panic()

        assertNull(p.loadIdentity())
        assertNull(p.loadSignedPreKey())
        assertEquals(0, p.loadAllOpkSecrets().size)
        assertEquals(0, p.loadAllContacts().size)
        assertEquals(0, p.loadPendingMailboxes().size)
        assertEquals(0, p.loadMessages("0".repeat(64)).size)
        assertNull(p.loadKeyServerToken())
    }
}
