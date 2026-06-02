package org.khord.shared.protocol.wire

import org.khord.shared.protocol.KhordJson
import org.khord.shared.storage.ContactStatus
import org.khord.shared.storage.InMemoryPersistence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capability-notice wire + persistence contract (feat/capability-notice).
 * The full send/receive fan-out runs through Messaging over the network
 * (integration / on-device); here we lock down the serializable payload and
 * the local contact flag that the receive handler updates.
 */
class CapabilityNoticeTest {

    @Test
    fun capability_notice_round_trips_snake_case() {
        val payload = InnerPayload(
            type = "capability_notice",
            timestamp = "2026-06-02T00:00:00Z",
            imagesAccepted = false,
        )
        val json = KhordJson.encodeToString(InnerPayload.serializer(), payload)
        assertTrue("\"type\":\"capability_notice\"" in json, json)
        assertTrue("\"images_accepted\":false" in json, json)

        val decoded = KhordJson.decodeFromString(InnerPayload.serializer(), json)
        assertEquals("capability_notice", decoded.type)
        assertEquals(false, decoded.imagesAccepted)
    }

    @Test
    fun non_capability_payload_has_null_flag() {
        val text = """{"type":"text","version":1,"timestamp":"t","body":"hi"}"""
        assertNull(KhordJson.decodeFromString(InnerPayload.serializer(), text).imagesAccepted)
    }

    @Test
    fun contact_images_accepted_defaults_true_and_round_trips() = runTest {
        val p = InMemoryPersistence()
        val fp = "f".repeat(64)
        p.saveContact(qr(fp), displayName = "Bob", status = ContactStatus.ACCEPTED)
        assertTrue(p.loadContact(fp)!!.imagesAccepted, "default must be accepts-images")

        p.setContactImagesAccepted(fp, false)
        assertFalse(p.loadContact(fp)!!.imagesAccepted)
    }

    @Test
    fun resaving_a_contact_preserves_the_learned_flag() = runTest {
        // A display-name refresh (saveContact upsert) must not reset the
        // learned image preference — only setContactImagesAccepted may.
        val p = InMemoryPersistence()
        val fp = "a".repeat(64)
        p.saveContact(qr(fp), displayName = "Bob", status = ContactStatus.ACCEPTED)
        p.setContactImagesAccepted(fp, false)

        p.saveContact(qr(fp), displayName = "Bob renamed", status = ContactStatus.ACCEPTED)

        assertFalse(p.loadContact(fp)!!.imagesAccepted, "flag must survive re-upsert")
        assertEquals("Bob renamed", p.loadContact(fp)!!.displayName)
    }

    private fun qr(fp: String) = QrPayload(
        identityKey = "AAAA", fingerprint = fp,
        keyServer = "https://ks", relayServer = "https://rs",
        relayMailbox = "mailbox-id-22-chars-zzzz",
    )
}
