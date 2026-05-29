package org.khord.shared.crypto

import org.khord.shared.crypto.ratchet.AeadAuthenticationFailed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCryptoTest {

    @Test
    fun full_image_round_trips() = runTest {
        Crypto.ensureInitialized()
        val key = MediaCrypto.newKey()
        val nonce = MediaCrypto.newNonce()
        assertEquals(32, key.size)
        assertEquals(24, nonce.size)

        val plaintext = ByteArray(4096) { (it * 31 + 7).toByte() }
        val ciphertext = MediaCrypto.encrypt(key, nonce, plaintext)
        // ciphertext carries the 16-byte Poly1305 tag, no nonce prefix.
        assertEquals(plaintext.size + 16, ciphertext.size)
        assertContentEquals(plaintext, MediaCrypto.decrypt(key, nonce, ciphertext))
    }

    @Test
    fun thumbnail_nonce_is_deterministic_and_distinct() {
        val imageNonce = ByteArray(24) { it.toByte() }
        val a = MediaCrypto.thumbnailNonce(imageNonce)
        val b = MediaCrypto.thumbnailNonce(imageNonce)
        assertContentEquals(a, b) // deterministic — recipient can reproduce it
        assertEquals(24, a.size)
        assertFalse(imageNonce.contentEquals(a)) // never reuses the image nonce
    }

    @Test
    fun thumbnail_round_trips_under_derived_nonce() = runTest {
        Crypto.ensureInitialized()
        val key = MediaCrypto.newKey()
        val imageNonce = MediaCrypto.newNonce()
        val thumbNonce = MediaCrypto.thumbnailNonce(imageNonce)

        val thumb = ByteArray(200) { (it * 13).toByte() }
        val enc = MediaCrypto.encrypt(key, thumbNonce, thumb)
        assertContentEquals(thumb, MediaCrypto.decrypt(key, thumbNonce, enc))
    }

    @Test
    fun wrong_nonce_fails_authentication() = runTest {
        Crypto.ensureInitialized()
        val key = MediaCrypto.newKey()
        val nonce = MediaCrypto.newNonce()
        val ciphertext = MediaCrypto.encrypt(key, nonce, "secret".encodeToByteArray())
        assertFailsWith<AeadAuthenticationFailed> {
            MediaCrypto.decrypt(key, MediaCrypto.newNonce(), ciphertext)
        }
    }

    @Test
    fun tampered_ciphertext_fails_authentication() = runTest {
        Crypto.ensureInitialized()
        val key = MediaCrypto.newKey()
        val nonce = MediaCrypto.newNonce()
        val ciphertext = MediaCrypto.encrypt(key, nonce, "secret".encodeToByteArray())
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()
        assertFailsWith<AeadAuthenticationFailed> {
            MediaCrypto.decrypt(key, nonce, ciphertext)
        }
    }
}
