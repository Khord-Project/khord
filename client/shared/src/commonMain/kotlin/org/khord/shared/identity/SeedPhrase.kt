package org.khord.shared.identity

import com.ionspin.kotlin.crypto.hash.Hash
import org.khord.shared.crypto.Random

/**
 * BIP39-compliant seed-phrase encoding/decoding using the standard
 * English wordlist.
 *
 * **Khord uses BIP39 only for the user-facing wordlist mapping.** The
 * key-derivation step (PROTOCOL.md §3.1) does NOT use BIP39's
 * `PBKDF2(mnemonic, "mnemonic"+passphrase)` — it feeds the UTF-8 of
 * the joined phrase to Argon2id with the fixed Khord salt. So a phrase
 * generated here is verifiable against any standard BIP39 tool (each
 * word in the canonical list, checksum bits valid), but the identity
 * key cannot be reproduced by such tools.
 *
 * Encoding rules (canonical BIP39, English):
 *   - 128-bit entropy → 12 words (132-bit total: 128 entropy + 4 checksum)
 *   - 256-bit entropy → 24 words (264-bit total: 256 entropy + 8 checksum)
 *   - Checksum = first `entropy_bits / 32` bits of SHA-256(entropy)
 *   - Concatenate (entropy || checksum), split into 11-bit groups,
 *     each group indexes into the 2048-word list.
 *
 * For the PoC we always use 12 words (128 bits entropy).
 */
@OptIn(ExperimentalUnsignedTypes::class)
object SeedPhrase {

    private const val WORDS_PER_PHRASE_DEFAULT = 12
    private const val ENTROPY_BYTES_DEFAULT = 16  // 128 bits → 12 words

    /** Generate a fresh 12-word phrase from CSPRNG bytes. */
    fun generate(): List<String> {
        val entropy = Random.bytes(ENTROPY_BYTES_DEFAULT)
        return fromEntropy(entropy)
    }

    /** Encode entropy bytes to a BIP39 phrase. Standard. */
    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size == 16 || entropy.size == 20 || entropy.size == 24 ||
                entropy.size == 28 || entropy.size == 32) {
            "BIP39 entropy must be 16/20/24/28/32 bytes; got ${entropy.size}"
        }
        val entropyBits = entropy.size * 8
        val checksumBits = entropyBits / 32
        val totalBits = entropyBits + checksumBits

        // checksum = first `checksumBits` bits of SHA-256(entropy)
        val digest = Hash.sha256(entropy.toUByteArray()).toByteArray()
        val checksumByte = digest[0].toInt() and 0xff

        // Build a long bit string and chunk into 11-bit indexes.
        val bits = BooleanArray(totalBits)
        for (i in entropy.indices) {
            val b = entropy[i].toInt() and 0xff
            for (k in 0 until 8) {
                bits[i * 8 + k] = (b shr (7 - k)) and 1 == 1
            }
        }
        for (k in 0 until checksumBits) {
            bits[entropyBits + k] = (checksumByte shr (7 - k)) and 1 == 1
        }

        val words = ArrayList<String>(totalBits / 11)
        var offset = 0
        while (offset < totalBits) {
            var idx = 0
            for (k in 0 until 11) {
                idx = (idx shl 1) or (if (bits[offset + k]) 1 else 0)
            }
            words += BIP39_ENGLISH_WORDS[idx]
            offset += 11
        }
        return words
    }

    /**
     * Decode a phrase back to entropy. Throws [IllegalArgumentException]
     * if a word is unknown or the checksum doesn't match — i.e., the
     * user mistyped at least one word.
     */
    fun toEntropy(words: List<String>): ByteArray {
        require(words.size in setOf(12, 15, 18, 21, 24)) {
            "BIP39 phrase length must be 12/15/18/21/24; got ${words.size}"
        }
        val totalBits = words.size * 11
        val entropyBits = totalBits * 32 / 33
        val checksumBits = totalBits - entropyBits
        val entropyBytes = entropyBits / 8

        val bits = BooleanArray(totalBits)
        for ((i, word) in words.withIndex()) {
            val idx = WORD_INDEX[word]
                ?: throw IllegalArgumentException("not a BIP39 word: '$word'")
            for (k in 0 until 11) {
                bits[i * 11 + k] = (idx shr (10 - k)) and 1 == 1
            }
        }

        val entropy = ByteArray(entropyBytes)
        for (i in 0 until entropyBytes) {
            var b = 0
            for (k in 0 until 8) {
                b = (b shl 1) or (if (bits[i * 8 + k]) 1 else 0)
            }
            entropy[i] = b.toByte()
        }

        val expectedChecksum = Hash.sha256(entropy.toUByteArray()).toByteArray()[0].toInt() and 0xff
        var actualChecksum = 0
        for (k in 0 until checksumBits) {
            actualChecksum = (actualChecksum shl 1) or (if (bits[entropyBits + k]) 1 else 0)
        }
        val expectedTrimmed = expectedChecksum shr (8 - checksumBits)
        require(actualChecksum == expectedTrimmed) { "BIP39 checksum mismatch — at least one word is wrong" }
        return entropy
    }

    /** Canonical Khord-side concatenation: words joined with single spaces, lowercase. */
    fun toCanonicalString(words: List<String>): String =
        words.joinToString(" ") { it.lowercase() }

    private val WORD_INDEX: Map<String, Int> by lazy {
        BIP39_ENGLISH_WORDS.withIndex().associate { (i, w) -> w to i }
    }
}
