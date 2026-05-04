package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.hash.Hash

/**
 * HKDF-SHA-256 (RFC 5869), built on a hand-rolled HMAC-SHA-256 layered over
 * libsodium's SHA-256 primitive.
 *
 * Why not libsodium's `crypto_auth_hmacsha256` directly? That entry point
 * is hardcoded to a 32-byte key (it calls the streaming HMAC init with
 * `keylen = crypto_auth_hmacsha256_KEYBYTES`), and reads past the end of
 * the buffer if a shorter key is supplied. RFC 5869 §2.2 explicitly allows
 * arbitrary-length salt — Khord's HKDF call sites pass salts of 0, 13, 32,
 * and 80 bytes. We must therefore handle key preprocessing on our side,
 * which means owning the HMAC step. ~25 lines of code, verified against
 * RFC 5869 §A.1-A.3 vectors.
 *
 * See:
 *   - RFC 5869 (HKDF)
 *   - RFC 2104 (HMAC)
 *   - X3DH §2.2, Double Ratchet §7.2 (call sites)
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object Hkdf {

    private const val HASH_LEN: Int = 32   // SHA-256 output length
    private const val BLOCK_LEN: Int = 64  // SHA-256 block size

    /**
     * RFC 2104 HMAC-SHA-256.
     *
     * Standard key preprocessing:
     *   K' = if |K| > B then H(K) else K       (zero-padded to B in either branch)
     *   inner = H((K' XOR ipad) || message)
     *   outer = H((K' XOR opad) || inner)
     *
     * Acceptable key lengths: 0..2^32 (any). Output: 32 bytes.
     */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val k0 = ByteArray(BLOCK_LEN).also { paddedKey ->
            val source = if (key.size > BLOCK_LEN) {
                Hash.sha256(key.toUByteArray()).toByteArray()
            } else {
                key
            }
            source.copyInto(paddedKey, destinationOffset = 0)
        }

        val ipad = ByteArray(BLOCK_LEN) { (k0[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(BLOCK_LEN) { (k0[it].toInt() xor 0x5c).toByte() }

        val inner = Hash.sha256((ipad + message).toUByteArray()).toByteArray()
        val outer = Hash.sha256((opad + inner).toUByteArray()).toByteArray()

        // Wipe intermediates — ipad/opad encode the key under XOR.
        k0.fill(0)
        ipad.fill(0)
        opad.fill(0)
        inner.fill(0)
        return outer
    }

    /**
     * RFC 5869 §2.2 — HKDF-Extract.
     *
     * Returns the pseudorandom key: PRK = HMAC-SHA-256(salt, IKM).
     * If `salt` is empty, RFC 5869 specifies a string of HashLen zero
     * bytes — we honour that here so callers can pass an empty ByteArray.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val key = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(key, ikm)
    }

    /**
     * RFC 5869 §2.3 — HKDF-Expand.
     *
     *   T(0) = empty
     *   T(i) = HMAC-SHA-256(PRK, T(i-1) || info || i)   for i = 1..N
     *   OKM  = first `length` bytes of T(1) || T(2) || … || T(N)
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) {
            "HKDF-Expand length must be 1..255*HashLen"
        }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var counter: Byte = 1
        var written = 0
        while (written < length) {
            val msg = ByteArray(t.size + info.size + 1)
            t.copyInto(msg, destinationOffset = 0)
            info.copyInto(msg, destinationOffset = t.size)
            msg[msg.size - 1] = counter
            t = hmacSha256(prk, msg)
            val take = minOf(HASH_LEN, length - written)
            t.copyInto(out, destinationOffset = written, endIndex = take)
            written += take
            counter = (counter + 1).toByte()
        }
        return out
    }

    /** RFC 5869 — HKDF = Extract-then-Expand. */
    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }
}
