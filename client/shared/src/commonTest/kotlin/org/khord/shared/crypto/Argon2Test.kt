package org.khord.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Argon2Test {

    @Test
    fun salt_is_exactly_16_ascii_bytes() {
        // Locked invariant — PROTOCOL.md §3.1, amendment 1.
        assertContentEquals(
            "khord-identity01".encodeToByteArray(),
            Argon2.SALT,
        )
        assertEquals(16, Argon2.SALT.size)
    }

    @Test
    fun derive_is_deterministic_for_same_seed_phrase() = runTest {
        Crypto.ensureInitialized()
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon"
        val first = Argon2.deriveSeed(phrase)
        val second = Argon2.deriveSeed(phrase)
        assertContentEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun derive_yields_different_output_for_different_phrases() = runTest {
        Crypto.ensureInitialized()
        val a = Argon2.deriveSeed("phrase one")
        val b = Argon2.deriveSeed("phrase two")
        assertNotEquals(a.toList(), b.toList())
    }
}
