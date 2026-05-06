package org.khord.shared.identity

import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeedPhraseTest {

    @Test
    fun wordlist_has_exactly_2048_canonical_entries() {
        assertEquals(2048, BIP39_ENGLISH_WORDS.size)
        assertEquals("abandon", BIP39_ENGLISH_WORDS.first())
        assertEquals("zoo", BIP39_ENGLISH_WORDS.last())
    }

    @Test
    fun generate_produces_12_canonical_words() = runTest {
        Crypto.ensureInitialized()
        val phrase = SeedPhrase.generate()
        assertEquals(12, phrase.size)
        assertTrue(phrase.all { it in BIP39_ENGLISH_WORDS },
            "every word must be in the canonical BIP39 list: $phrase")
    }

    @Test
    fun fromEntropy_then_toEntropy_round_trips_for_random_input() = runTest {
        Crypto.ensureInitialized()
        val entropy = org.khord.shared.crypto.Random.bytes(16)
        val phrase = SeedPhrase.fromEntropy(entropy)
        val recovered = SeedPhrase.toEntropy(phrase)
        assertContentEquals(entropy, recovered)
    }

    /** Canonical BIP39 vector — Trezor reference, all-zero entropy. */
    @Test
    fun bip39_vector_zero_entropy_matches_canonical_phrase() = runTest {
        Crypto.ensureInitialized()
        val zero = ByteArray(16)
        val expected = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "about",
        )
        assertEquals(expected, SeedPhrase.fromEntropy(zero))
    }

    /** Canonical BIP39 vector — all-0x80 entropy (high-bit set on every byte). */
    @Test
    fun bip39_vector_legal_winner_thank_year() = runTest {
        Crypto.ensureInitialized()
        val entropy = ByteArray(16) { 0x80.toByte() }
        // From the Trezor BIP39 test vectors:
        // 80808080... → "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"
        val expected = listOf(
            "letter", "advice", "cage", "absurd",
            "amount", "doctor", "acoustic", "avoid",
            "letter", "advice", "cage", "above",
        )
        assertEquals(expected, SeedPhrase.fromEntropy(entropy))
    }

    @Test
    fun toEntropy_rejects_non_bip39_word() {
        val bad = listOf("abandon", "abandon", "abandon", "abandon",
                          "abandon", "abandon", "abandon", "abandon",
                          "abandon", "abandon", "abandon", "notaword")
        assertFailsWith<IllegalArgumentException> { SeedPhrase.toEntropy(bad) }
    }

    @Test
    fun toEntropy_rejects_bad_checksum() {
        // 12 valid words, but the last one is wrong → checksum fails.
        val bad = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "zoo",  // should be "about"
        )
        assertFailsWith<IllegalArgumentException> { SeedPhrase.toEntropy(bad) }
    }

    @Test
    fun canonical_string_is_lowercase_space_joined() {
        val words = listOf("Abandon", "ABILITY", "able", "about",
                           "above", "absent", "absorb", "abstract",
                           "absurd", "abuse", "access", "accident")
        assertEquals(
            "abandon ability able about above absent absorb abstract absurd abuse access accident",
            SeedPhrase.toCanonicalString(words),
        )
    }
}
