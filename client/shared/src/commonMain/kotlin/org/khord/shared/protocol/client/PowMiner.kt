package org.khord.shared.protocol.client

import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Hashcash-style proof-of-work miner — PROTOCOL.md §5.6.
 *
 * Mirrors the relay server's verifier byte-for-byte:
 *
 *   digest = SHA-256( utf8(mailbox_id) || utf8(decimal_nonce) )
 *   solved = leading_zero_bits(digest) >= difficulty_bits
 *
 * `nonce` is the unsigned-integer nonce as decimal ASCII. The miner
 * iterates from 0 upward and returns the first solving nonce as a
 * decimal-ASCII string.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object PowMiner {

    fun mine(mailboxId: String, difficultyBits: Int): String {
        require(difficultyBits >= 0) { "difficulty must be non-negative" }
        val midBytes = mailboxId.encodeToByteArray()
        var nonce = 0L
        while (true) {
            val nonceStr = nonce.toString()
            val input = midBytes + nonceStr.encodeToByteArray()
            val digest = Hash.sha256(input.toUByteArray()).toByteArray()
            if (leadingZeroBits(digest) >= difficultyBits) return nonceStr
            nonce++
        }
    }

    internal fun leadingZeroBits(digest: ByteArray): Int {
        var count = 0
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v == 0) {
                count += 8
                continue
            }
            // 8 - bitLength(v) for non-zero 8-bit values.
            var probe = 0x80
            while (probe > 0 && (v and probe) == 0) {
                count++
                probe = probe ushr 1
            }
            break
        }
        return count
    }
}

/**
 * Mailbox-ID generation per PROTOCOL.md §5.1 — at least 22 base64url chars,
 * sourced from 16 random bytes (~128 bits).
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
internal object Mailboxes {
    /** Generate a fresh random mailbox ID — `base64url(random_16_bytes)` no padding. */
    fun newId(): String =
        Base64.UrlSafe.encode(LibsodiumRandom.buf(16).toByteArray()).trimEnd('=')
}
