package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import org.khord.shared.crypto.ratchet.AeadAuthenticationFailed

/**
 * One-time symmetric crypto for image attachments (ADR 029).
 *
 * Each image gets a fresh 32-byte key used exactly twice — once for the
 * full image, once for the thumbnail — with two distinct nonces. This is
 * deliberately NOT the Double Ratchet: the ratchet protects the message
 * channel, but a large blob doesn't belong on that channel. The per-image
 * key travels to the recipient inside the E2E-encrypted message payload;
 * the encrypted blob is uploaded to the relay's media endpoint separately,
 * so the relay only ever holds opaque bytes it cannot decrypt.
 *
 * Cipher is `crypto_aead_xchacha20poly1305_ietf` — the same primitive
 * [org.khord.shared.crypto.ratchet.RatchetAead] uses — but here the nonce
 * is caller-supplied (and sent on the wire as `media_nonce`) rather than
 * generated-and-prepended, because the recipient needs it out-of-band to
 * fetch and decrypt. Output is bare `ciphertext || Poly1305-tag`, no nonce
 * prefix.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object MediaCrypto {

    const val KEY_LEN: Int = 32
    const val NONCE_LEN: Int = 24

    private val NO_AD: UByteArray = UByteArray(0)

    /** Fresh 32-byte one-time key. */
    fun newKey(): ByteArray = Random.bytes(KEY_LEN)

    /** Fresh 24-byte nonce for the full image. */
    fun newNonce(): ByteArray = Random.bytes(NONCE_LEN)

    /**
     * Derive the thumbnail's nonce from the image nonce by flipping the low
     * bit of the last byte. The result is guaranteed distinct from the image
     * nonce — so the one-time key never encrypts two plaintexts under the
     * same nonce — yet deterministic, so the recipient reproduces it from
     * `media_nonce` alone without a second wire field.
     */
    fun thumbnailNonce(imageNonce: ByteArray): ByteArray {
        require(imageNonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        val t = imageNonce.copyOf()
        t[t.size - 1] = (t[t.size - 1].toInt() xor 0x01).toByte()
        return t
    }

    /** Encrypt [plaintext] under [key] + [nonce]. Returns `ciphertext || tag`. */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "media key must be $KEY_LEN bytes" }
        require(nonce.size == NONCE_LEN) { "media nonce must be $NONCE_LEN bytes" }
        return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            message = plaintext.toUByteArray(),
            associatedData = NO_AD,
            nonce = nonce.toUByteArray(),
            key = key.toUByteArray(),
        ).toByteArray()
    }

    /** Decrypt `ciphertext || tag`. Throws [AeadAuthenticationFailed] on mismatch. */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "media key must be $KEY_LEN bytes" }
        require(nonce.size == NONCE_LEN) { "media nonce must be $NONCE_LEN bytes" }
        try {
            return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = ciphertext.toUByteArray(),
                associatedData = NO_AD,
                nonce = nonce.toUByteArray(),
                key = key.toUByteArray(),
            ).toByteArray()
        } catch (e: AeadCorrupedOrTamperedDataException) {
            throw AeadAuthenticationFailed("media decrypt failed: tag/key/nonce mismatch")
        }
    }
}
