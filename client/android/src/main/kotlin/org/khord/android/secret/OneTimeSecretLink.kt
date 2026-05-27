package org.khord.android.secret

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * One-time secret link sharing (#23, app side).
 *
 * Encrypts the user's contact link client-side with AES-256-GCM,
 * uploads ONLY the ciphertext + nonce to the khord.org secret
 * endpoint, and returns a URL whose fragment carries the key:
 *
 *     https://khord.org/s/{id}#{base64url-key}
 *
 * The key lives only in the URL fragment, which browsers never send
 * to the server — so khord.org stores an opaque blob it cannot
 * decrypt. The viewer at /s/{id} reads the fragment and decrypts
 * client-side via Web Crypto.
 *
 * **Cipher must be AES-256-GCM to match the Web Crypto viewer** —
 * not the XChaCha20-Poly1305 used elsewhere in Khord. Java's
 * `AES/GCM/NoPadding` produces `ciphertext || 16-byte-tag`, which is
 * exactly what Web Crypto's `crypto.subtle.decrypt({name:'AES-GCM'})`
 * expects, with a 12-byte IV and 128-bit tag. The formats line up
 * with no reframing.
 *
 * Base64: ciphertext + nonce use standard base64 (no line wrapping);
 * the key uses base64url without padding (URL-fragment safe). These
 * use java.util.Base64 (API 26+, == our minSdk) rather than
 * android.util.Base64 so the crypto path is unit-testable on the
 * plain JVM without Robolectric — identical byte output either way.
 */
object OneTimeSecretLink {

    private const val ENDPOINT = "https://khord.org/api/secret"
    private const val VIEWER_PREFIX = "https://khord.org/s/"

    @Serializable
    private data class SecretRequest(val ciphertext: String, val nonce: String)

    @Serializable
    private data class SecretResponse(val id: String? = null)

    /**
     * Result of the local encryption step — base64-encoded exactly as
     * the endpoint + viewer expect. Exposed (internal) so tests can
     * assert the wire format without hitting the network.
     */
    internal data class Encrypted(
        val ciphertextB64: String,
        val nonceB64: String,
        val keyB64Url: String,
    )

    /**
     * Pure, network-free encryption step: generate a 256-bit key + a
     * 12-byte IV, AES-256-GCM-encrypt [plaintext], and base64-encode
     * everything. Zeroes the key bytes before returning (the key
     * survives only as the base64url string destined for the URL
     * fragment).
     */
    internal fun encrypt(plaintext: String): Encrypted {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv),
            )
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return Encrypted(
                ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext),
                nonceB64 = Base64.getEncoder().encodeToString(iv),
                keyB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(key),
            )
        } finally {
            // Don't keep the raw key in memory longer than needed.
            key.fill(0)
        }
    }

    /**
     * Create a one-time link for [contactLink]. Encrypts locally,
     * POSTs the ciphertext + nonce to the endpoint, and assembles the
     * viewer URL with the key in the fragment. The key is never sent
     * to the server. Returns failure on any network / server error so
     * the caller can fall back to the regular contact link.
     */
    suspend fun create(http: HttpClient, contactLink: String): Result<String> = runCatching {
        val enc = encrypt(contactLink)
        val response = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(SecretRequest(ciphertext = enc.ciphertextB64, nonce = enc.nonceB64))
        }
        if (response.status.value != 201) {
            error("Server returned ${response.status}")
        }
        val id = response.body<SecretResponse>().id
            ?: error("No ID in response")
        "$VIEWER_PREFIX$id#${enc.keyB64Url}"
    }
}
