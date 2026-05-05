package org.khord.shared.protocol

import org.khord.shared.protocol.wire.WireEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvelopeRoundTripTest {

    @Test
    fun x3dh_initial_round_trips_through_json() {
        val original = WireEnvelope.X3dhInitial(
            ikA = "AAAA",
            ekA = "BBBB",
            spkId = 7,
            opkId = 42,
            header = "HHH",
            ciphertext = "CCC",
        )
        val json = KhordJson.encodeToString(WireEnvelope.serializer(), original)
        // Verify the wire form uses snake_case + the type discriminator.
        assertTrue("\"type\":\"x3dh_initial\"" in json, "discriminator missing: $json")
        assertTrue("\"ik_a\":\"AAAA\"" in json, "snake_case not applied: $json")
        assertTrue("\"opk_id\":42" in json, "opk_id missing: $json")

        val decoded = KhordJson.decodeFromString(WireEnvelope.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun ratchet_round_trips_without_x3dh_metadata() {
        val original = WireEnvelope.Ratchet(header = "HEADERB64", ciphertext = "CTB64")
        val json = KhordJson.encodeToString(WireEnvelope.serializer(), original)
        assertTrue("\"type\":\"ratchet\"" in json, "discriminator: $json")
        assertTrue("\"ik_a\"" !in json, "x3dh fields must be absent: $json")

        val decoded = KhordJson.decodeFromString(WireEnvelope.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun x3dh_initial_omits_opk_id_when_null() {
        val original = WireEnvelope.X3dhInitial(
            ikA = "AAAA",
            ekA = "BBBB",
            spkId = 1,
            opkId = null,
            header = "H", ciphertext = "C",
        )
        val json = KhordJson.encodeToString(WireEnvelope.serializer(), original)
        // Either `"opk_id":null` or omitted is acceptable for round-trip;
        // confirm round-trip preserves null.
        val decoded = KhordJson.decodeFromString(WireEnvelope.serializer(), json) as WireEnvelope.X3dhInitial
        assertEquals(null, decoded.opkId)
    }

    @Test
    fun unknown_extra_fields_are_tolerated() {
        // A future client adds a "delivery_hint" — older clients must still parse.
        val futureJson = """{"type":"ratchet","version":1,"header":"H","ciphertext":"C","delivery_hint":"fast"}"""
        val decoded = KhordJson.decodeFromString(WireEnvelope.serializer(), futureJson) as WireEnvelope.Ratchet
        assertEquals("H", decoded.header)
        assertEquals("C", decoded.ciphertext)
    }
}
