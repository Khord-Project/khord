package org.khord.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityKeyTest {

    @Test
    fun seed_phrase_to_identity_is_deterministic() = runTest {
        Crypto.ensureInitialized()
        val phrase = "correct horse battery staple"
        val a = IdentityKey.fromSeedPhrase(phrase)
        val b = IdentityKey.fromSeedPhrase(phrase)
        assertContentEquals(a.ed25519PublicKey, b.ed25519PublicKey)
        assertContentEquals(a.x25519PublicKey, b.x25519PublicKey)
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun fingerprint_is_64_hex_chars() = runTest {
        Crypto.ensureInitialized()
        val key = IdentityKey.fromSeedPhrase("any phrase")
        assertEquals(64, key.fingerprint.length)
        assertEquals(true, key.fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun ed25519_public_is_32_bytes_secret_is_64_bytes() = runTest {
        Crypto.ensureInitialized()
        val key = IdentityKey.fromSeedPhrase("phrase")
        assertEquals(32, key.ed25519PublicKey.size)
        assertEquals(64, key.ed25519SecretKey.size)
    }

    @Test
    fun x25519_keys_are_32_bytes() = runTest {
        Crypto.ensureInitialized()
        val key = IdentityKey.fromSeedPhrase("phrase")
        assertEquals(32, key.x25519PublicKey.size)
        assertEquals(32, key.x25519SecretKey.size)
    }

    @Test
    fun different_phrases_yield_different_fingerprints() = runTest {
        Crypto.ensureInitialized()
        val a = IdentityKey.fromSeedPhrase("alice phrase")
        val b = IdentityKey.fromSeedPhrase("bob phrase")
        assertNotEquals(a.fingerprint, b.fingerprint)
    }
}
