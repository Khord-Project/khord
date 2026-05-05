package org.khord.shared.protocol

import org.khord.shared.protocol.wire.QrPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrRoundTripTest {

    private val sample = QrPayload(
        identityKey = "AAAA==",
        fingerprint = "0".repeat(64),
        keyServer = "https://ks.example",
        relayServer = "https://rs.example",
        relayMailbox = "abcDEF12345_67890qrst",
    )

    @Test
    fun qr_payload_round_trips_through_json() {
        val json = KhordJson.encodeToString(QrPayload.serializer(), sample)
        assertTrue("\"identity_key\"" in json)
        assertTrue("\"key_server\"" in json)
        assertTrue("\"relay_server\"" in json, "PROTOCOL.md §9 amendment 2: relay_server present")
        assertTrue("\"relay_mailbox\"" in json)
        val decoded = KhordJson.decodeFromString(QrPayload.serializer(), json)
        assertEquals(sample, decoded)
    }

    @Test
    fun qr_payload_unknown_fields_are_tolerated() {
        val futureJson = """
            {"version":2,"identity_key":"AAA","fingerprint":"0","key_server":"x",
             "relay_server":"y","relay_mailbox":"m","new_field":"ignored"}
        """.trimIndent()
        val decoded = KhordJson.decodeFromString(QrPayload.serializer(), futureJson)
        assertEquals(2, decoded.version)
        assertEquals("m", decoded.relayMailbox)
    }
}
