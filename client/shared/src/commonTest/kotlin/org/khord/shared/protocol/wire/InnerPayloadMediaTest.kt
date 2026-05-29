package org.khord.shared.protocol.wire

import org.khord.shared.protocol.KhordJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerPayloadMediaTest {

    @Test
    fun media_fields_round_trip_as_snake_case() {
        val original = InnerPayload(
            type = "text",
            timestamp = "2026-05-28T00:00:00Z",
            body = "look at this",
            messageUuid = "uuid-1",
            mediaId = "abc123",
            mediaKey = "a2V5",
            mediaNonce = "bm9uY2U=",
            mediaRelay = "https://relay.example.org",
            thumbnail = "dGh1bWI=",
        )
        val json = KhordJson.encodeToString(InnerPayload.serializer(), original)
        assertTrue("\"media_id\":\"abc123\"" in json, json)
        assertTrue("\"media_key\":\"a2V5\"" in json, json)
        assertTrue("\"media_nonce\":\"bm9uY2U=\"" in json, json)
        assertTrue("\"media_relay\":\"https://relay.example.org\"" in json, json)
        assertTrue("\"thumbnail\":\"dGh1bWI=\"" in json, json)

        val decoded = KhordJson.decodeFromString(InnerPayload.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun plain_text_payload_decodes_with_null_media_fields() {
        // A pre-ADR-029 message: no media keys at all. Must decode cleanly.
        val legacy = """{"type":"text","version":1,"timestamp":"t","body":"hi"}"""
        val decoded = KhordJson.decodeFromString(InnerPayload.serializer(), legacy)
        assertEquals("hi", decoded.body)
        assertNull(decoded.mediaId)
        assertNull(decoded.mediaKey)
        assertNull(decoded.mediaNonce)
        assertNull(decoded.mediaRelay)
        assertNull(decoded.thumbnail)
    }

    @Test
    fun image_only_message_allows_empty_body() {
        val original = InnerPayload(
            type = "text",
            timestamp = "t",
            body = "",
            mediaId = "id",
            mediaKey = "k",
            mediaNonce = "n",
            mediaRelay = "https://r",
            thumbnail = "tn",
        )
        val decoded = KhordJson.decodeFromString(
            InnerPayload.serializer(),
            KhordJson.encodeToString(InnerPayload.serializer(), original),
        )
        assertEquals("", decoded.body)
        assertEquals("id", decoded.mediaId)
    }
}
