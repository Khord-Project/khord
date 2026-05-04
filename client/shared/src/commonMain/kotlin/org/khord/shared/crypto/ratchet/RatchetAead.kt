package org.khord.shared.crypto.ratchet

import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import org.khord.shared.crypto.Random

/**
 * AEAD layer for the Double Ratchet — XChaCha20-Poly1305-IETF.
 *
 * Per Amendment 3 of the crypto investigation: Khord uses
 * `crypto_aead_xchacha20poly1305_ietf` instead of `crypto_secretbox`
 * because it has native associated-data support, accepts a 24-byte nonce
 * (random nonces are safe at that width), and removes the need for a
 * derived-nonce side-channel.
 *
 *   nonce       : 24 random bytes per message, prepended to ciphertext
 *   associated  : `additional_data` argument — passed verbatim
 *   key         : the per-message symmetric key from KDF_CK (32 bytes)
 *
 * Nonce reuse safety: each message key is used exactly once (the symmetric
 * ratchet guarantees this), so even with random nonces the (key, nonce)
 * pair is unique with overwhelming probability — and nonce reuse across
 * different keys is harmless for IETF XChaCha20-Poly1305.
 *
 * Wire format produced by [encrypt]: `nonce || aead_ciphertext`
 * where `aead_ciphertext` is what libsodium returns (plaintext + Poly1305 tag).
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object RatchetAead {

    private const val NONCE_LEN: Int = 24

    /**
     * Encrypt with key + AD. Returns `nonce || aead_ciphertext`.
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        require(key.size == 32) { "AEAD key must be 32 bytes" }
        val nonce = Random.bytes(NONCE_LEN)
        val ct = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            message = plaintext.toUByteArray(),
            associatedData = ad.toUByteArray(),
            nonce = nonce.toUByteArray(),
            key = key.toUByteArray(),
        ).toByteArray()
        val out = ByteArray(NONCE_LEN + ct.size)
        nonce.copyInto(out, destinationOffset = 0)
        ct.copyInto(out, destinationOffset = NONCE_LEN)
        return out
    }

    /**
     * Decrypt `nonce || aead_ciphertext`. Throws on tamper / wrong key.
     */
    fun decrypt(key: ByteArray, payload: ByteArray, ad: ByteArray): ByteArray {
        require(key.size == 32) { "AEAD key must be 32 bytes" }
        require(payload.size > NONCE_LEN) { "AEAD payload too short" }
        val nonce = payload.copyOfRange(0, NONCE_LEN)
        val ct = payload.copyOfRange(NONCE_LEN, payload.size)
        try {
            return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = ct.toUByteArray(),
                associatedData = ad.toUByteArray(),
                nonce = nonce.toUByteArray(),
                key = key.toUByteArray(),
            ).toByteArray()
        } catch (e: AeadCorrupedOrTamperedDataException) {
            throw AeadAuthenticationFailed("decrypt failed: tag/AD/key mismatch")
        }
    }
}

/**
 * Thrown when XChaCha20-Poly1305 authentication fails. The underlying
 * libsodium exception name is misleading ("CorrupedOrTampered…" — typo
 * in the upstream library); we wrap it in a stable Khord-named exception.
 */
internal class AeadAuthenticationFailed(message: String) : Exception(message)
