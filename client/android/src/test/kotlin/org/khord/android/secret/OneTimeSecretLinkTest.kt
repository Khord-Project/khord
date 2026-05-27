package org.khord.android.secret

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the one-time-link crypto produces output the khord.org
 * Web Crypto viewer can decrypt. The viewer does, in JS:
 *
 *   key   = base64url-decode(location.hash)        // 32 bytes
 *   iv    = base64-decode(nonce)                    // 12 bytes
 *   ct    = base64-decode(ciphertext)               // ct || 16B tag
 *   pt    = AES-GCM-decrypt({iv, tagLength:128}, key, ct)
 *
 * These tests reproduce that decrypt path with javax.crypto (the
 * same primitive Web Crypto's AES-GCM maps to) and assert a clean
 * round-trip + the exact base64 framing.
 */
class OneTimeSecretLinkTest {

    @Test
    fun encrypt_output_round_trips_through_the_web_crypto_decrypt_path() {
        val plaintext = "khord://contact/eyJpZGVudGl0eUtleSI6IkFBQUEifQ"
        val enc = OneTimeSecretLink.encrypt(plaintext)

        // Decode exactly as the JS viewer would.
        val key = Base64.getUrlDecoder().decode(enc.keyB64Url)
        val iv = Base64.getDecoder().decode(enc.nonceB64)
        val ct = Base64.getDecoder().decode(enc.ciphertextB64)

        assertEquals(32, key.size, "AES-256 key must be 32 bytes")
        assertEquals(12, iv.size, "GCM IV must be 12 bytes")
        // Java GCM output is ciphertext || 16-byte tag, so it's at
        // least tag-length larger than the (here non-empty) plaintext.
        assertTrue(ct.size >= 16, "ciphertext must carry the 128-bit GCM tag")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        val decrypted = cipher.doFinal(ct).toString(Charsets.UTF_8)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun key_fragment_is_urlsafe_base64_without_padding() {
        val enc = OneTimeSecretLink.encrypt("anything")
        // URL-fragment-safe: no +, /, or = (padding). base64url uses
        // - and _ instead of + and /.
        assertTrue('+' !in enc.keyB64Url, "key must not contain '+'")
        assertTrue('/' !in enc.keyB64Url, "key must not contain '/'")
        assertTrue('=' !in enc.keyB64Url, "key must be unpadded")
        // 32 bytes → 43 base64url chars (ceil(32*4/3) without padding).
        assertEquals(43, enc.keyB64Url.length)
    }

    @Test
    fun each_call_uses_a_fresh_key_and_nonce() {
        val a = OneTimeSecretLink.encrypt("same text")
        val b = OneTimeSecretLink.encrypt("same text")
        assertTrue(a.keyB64Url != b.keyB64Url, "keys must differ per call")
        assertTrue(a.nonceB64 != b.nonceB64, "nonces must differ per call")
        assertTrue(a.ciphertextB64 != b.ciphertextB64, "ciphertext must differ per call")
    }
}
