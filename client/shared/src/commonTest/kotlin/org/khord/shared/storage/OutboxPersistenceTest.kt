package org.khord.shared.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Outbox + delivery-status persistence contract (ADR 030, #59). Exercises
 * the storage operations that [org.khord.shared.protocol.orchestrator.Messaging.drainOutbox]
 * composes — insert/load order, status + attempt transitions, targeted and
 * full clears — against [InMemoryPersistence]. The same contract backs
 * DbPersistence (verified by the SQLDelight codegen + the migration test).
 */
class OutboxPersistenceTest {

    private fun textItem(uuid: String, createdAt: String, fp: String = "contact-a") =
        OutboxItem(
            contactFingerprint = fp,
            body = "msg $uuid",
            messageUuid = uuid,
            createdAt = createdAt,
        )

    @Test
    fun insert_then_load_returns_oldest_first() = runTest {
        val p = InMemoryPersistence()
        // Insert out of chronological order; load must sort by created_at.
        p.insertOutbox(textItem("u2", "2026-05-30T00:00:02Z"))
        p.insertOutbox(textItem("u1", "2026-05-30T00:00:01Z"))
        p.insertOutbox(textItem("u3", "2026-05-30T00:00:03Z"))

        val loaded = p.loadOutbox()
        assertEquals(listOf("u1", "u2", "u3"), loaded.map { it.messageUuid })
        assertTrue(loaded.all { it.id != 0L }, "ids must be assigned on insert")
    }

    @Test
    fun delivered_item_is_removed_and_message_marked_sent() = runTest {
        val p = InMemoryPersistence()
        seedContact(p, "contact-a")
        p.saveMessage(
            "contact-a", MessageDirection.SENT, "hi", "2026-05-30T00:00:00Z",
            messageUuid = "u1", deliveryStatus = "pending",
        )
        p.insertOutbox(textItem("u1", "2026-05-30T00:00:00Z"))

        // Simulate a successful drain: drop the queue row, mark the msg sent.
        val item = p.loadOutbox().single()
        p.deleteOutboxItem(item.id)
        p.updateMessageDeliveryStatus("u1", "sent")

        assertTrue(p.loadOutbox().isEmpty())
        assertEquals("sent", p.loadMessages("contact-a").single().deliveryStatus)
    }

    @Test
    fun failed_attempt_increments_and_item_stays() = runTest {
        val p = InMemoryPersistence()
        p.insertOutbox(textItem("u1", "2026-05-30T00:00:00Z"))
        val id = p.loadOutbox().single().id

        p.updateOutboxStatus(id, "sending", null)
        p.incrementOutboxAttempts(id)
        p.updateOutboxStatus(id, "pending", "connection refused")

        val item = p.loadOutbox().single()
        assertEquals(1, item.attempts)
        assertEquals("pending", item.status)
        assertEquals("connection refused", item.lastError)
    }

    @Test
    fun max_retries_marks_failed() = runTest {
        val p = InMemoryPersistence()
        seedContact(p, "contact-a")
        p.saveMessage(
            "contact-a", MessageDirection.SENT, "hi", "2026-05-30T00:00:00Z",
            messageUuid = "u1", deliveryStatus = "pending",
        )
        p.insertOutbox(textItem("u1", "2026-05-30T00:00:00Z"))
        val id = p.loadOutbox().single().id
        repeat(10) { p.incrementOutboxAttempts(id) }

        // The drain caps at 10 attempts → failed.
        assertEquals(10, p.loadOutbox().single().attempts)
        p.updateOutboxStatus(id, "failed", "Max retries exceeded")
        p.updateMessageDeliveryStatus("u1", "failed")

        assertEquals("failed", p.loadOutbox().single().status)
        assertEquals("failed", p.loadMessages("contact-a").single().deliveryStatus)
    }

    @Test
    fun reset_by_uuid_requeues_a_failed_item() = runTest {
        val p = InMemoryPersistence()
        p.insertOutbox(textItem("u1", "2026-05-30T00:00:00Z"))
        val id = p.loadOutbox().single().id
        repeat(10) { p.incrementOutboxAttempts(id) }
        p.updateOutboxStatus(id, "failed", "Max retries exceeded")

        p.resetOutboxByUuid("u1")
        val item = p.loadOutbox().single()
        assertEquals(0, item.attempts)
        assertEquals("pending", item.status)
        assertNull(item.lastError)
    }

    @Test
    fun delete_by_uuid_and_for_contact() = runTest {
        val p = InMemoryPersistence()
        p.insertOutbox(textItem("u1", "t1", fp = "contact-a"))
        p.insertOutbox(textItem("u2", "t2", fp = "contact-a"))
        p.insertOutbox(textItem("u3", "t3", fp = "contact-b"))

        p.deleteOutboxByUuid("u1")
        assertEquals(listOf("u2", "u3"), p.loadOutbox().map { it.messageUuid })

        p.deleteOutboxForContact("contact-a")
        assertEquals(listOf("u3"), p.loadOutbox().map { it.messageUuid })
    }

    @Test
    fun panic_clears_outbox() = runTest {
        val p = InMemoryPersistence()
        p.insertOutbox(textItem("u1", "t1"))
        p.insertOutbox(textItem("u2", "t2"))
        assertEquals(2, p.loadOutbox().size)

        p.panic()
        assertTrue(p.loadOutbox().isEmpty(), "panic must wipe the outbox")
    }

    @Test
    fun image_outbox_item_round_trips_blobs() = runTest {
        val p = InMemoryPersistence()
        val full = ByteArray(512) { it.toByte() }
        val thumb = ByteArray(40) { (it * 2).toByte() }
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(24) { 9 }
        p.insertOutbox(
            OutboxItem(
                groupId = "group-1",
                body = "caption",
                fullImage = full,
                thumbnail = thumb,
                mediaKey = key,
                mediaNonce = nonce,
                messageUuid = "img-1",
                createdAt = "2026-05-30T00:00:00Z",
            ),
        )
        val item = p.loadOutbox().single()
        assertEquals("group-1", item.groupId)
        assertNull(item.contactFingerprint)
        assertContentEquals(full, item.fullImage)
        assertContentEquals(thumb, item.thumbnail)
        assertContentEquals(key, item.mediaKey)
        assertContentEquals(nonce, item.mediaNonce)
    }

    @Test
    fun received_messages_have_null_delivery_status() = runTest {
        val p = InMemoryPersistence()
        seedContact(p, "contact-a")
        p.saveMessage(
            "contact-a", MessageDirection.RECEIVED, "incoming", "2026-05-30T00:00:00Z",
            messageUuid = "r1",
        )
        assertNull(p.loadMessages("contact-a").single().deliveryStatus)
    }

    private suspend fun seedContact(p: Persistence, fp: String) {
        p.saveContact(
            org.khord.shared.protocol.wire.QrPayload(
                identityKey = "AAAA", fingerprint = fp,
                keyServer = "https://ks", relayServer = "https://rs",
                relayMailbox = "mailbox-id-22-chars-zzzz",
            ),
            displayName = "Bob",
        )
    }
}
