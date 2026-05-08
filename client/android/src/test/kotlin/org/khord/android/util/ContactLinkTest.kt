package org.khord.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContactLinkTest {

    private val sampleJson = """{"fingerprint":"abcdef0123456789","relayMailbox":"mb-1"}"""

    @Test
    fun encode_then_parse_roundtrips_json_unchanged() {
        val link = ContactLink.encode(sampleJson)
        assertEquals(sampleJson, ContactLink.toJson(link))
    }

    @Test
    fun encode_produces_khord_contact_uri_with_url_safe_base64() {
        val link = ContactLink.encode(sampleJson)
        assert(link.startsWith("khord://contact/")) { "unexpected prefix: $link" }
        // Base64url uses [A-Za-z0-9_-]; standard '+' / '/' / '=' must be absent.
        val payload = link.removePrefix("khord://contact/")
        assert(payload.none { it == '+' || it == '/' || it == '=' }) {
            "non-url-safe base64: $payload"
        }
    }

    @Test
    fun parse_accepts_raw_json_passthrough() {
        assertEquals(sampleJson, ContactLink.toJson(sampleJson))
    }

    @Test
    fun parse_accepts_raw_json_with_leading_whitespace() {
        assertEquals(sampleJson, ContactLink.toJson("   $sampleJson  \n"))
    }

    @Test
    fun parse_accepts_bare_base64url_without_uri_prefix() {
        val link = ContactLink.encode(sampleJson)
        val bare = link.removePrefix("khord://contact/")
        assertEquals(sampleJson, ContactLink.toJson(bare))
    }

    @Test
    fun parse_is_case_insensitive_on_uri_scheme() {
        val link = ContactLink.encode(sampleJson)
        val mixed = link.replaceFirst("khord://contact/", "Khord://Contact/")
        assertEquals(sampleJson, ContactLink.toJson(mixed))
    }

    @Test
    fun parse_rejects_empty_input() {
        assertFailsWith<IllegalArgumentException> { ContactLink.toJson("") }
        assertFailsWith<IllegalArgumentException> { ContactLink.toJson("   ") }
    }

    @Test
    fun parse_rejects_uri_with_no_payload() {
        assertFailsWith<IllegalArgumentException> {
            ContactLink.toJson("khord://contact/")
        }
    }

    @Test
    fun parse_rejects_invalid_base64() {
        // Asterisk isn't valid in base64url; should bubble up as IAE.
        assertFailsWith<IllegalArgumentException> {
            ContactLink.toJson("khord://contact/!!!***")
        }
    }
}
