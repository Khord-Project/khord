package org.khord.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 5869 §A.1, §A.2, §A.3 — HKDF-SHA-256 official test vectors.
 *
 * If any of these byte-for-byte comparisons fail, every higher-level
 * derivation in Khord (X3DH SK, Double Ratchet root key) is wrong. These
 * tests are the foundation of the crypto module's correctness.
 */
class HkdfTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun rfc5869_a1_basic_test_case_with_sha256() = runTest {
        Crypto.ensureInitialized()
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val l = 42
        val expectedPrk = hex(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        )
        val expectedOkm = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        )
        assertContentEquals(expectedPrk, Hkdf.extract(salt, ikm))
        assertContentEquals(expectedOkm, Hkdf.derive(salt, ikm, info, l))
    }

    @Test
    fun rfc5869_a2_test_case_with_sha256_and_longer_inputs_outputs() = runTest {
        Crypto.ensureInitialized()
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f" +
            "101112131415161718191a1b1c1d1e1f" +
            "202122232425262728292a2b2c2d2e2f" +
            "303132333435363738393a3b3c3d3e3f" +
            "404142434445464748494a4b4c4d4e4f"
        )
        val salt = hex(
            "606162636465666768696a6b6c6d6e6f" +
            "707172737475767778797a7b7c7d7e7f" +
            "808182838485868788898a8b8c8d8e8f" +
            "909192939495969798999a9b9c9d9e9f" +
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        )
        val info = hex(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
            "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
            "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
            "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
            "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
        )
        val l = 82
        val expectedPrk = hex(
            "06a6b88c5853361a06104c9ceb35b45c" +
            "ef760014904671014a193f40c15fc244"
        )
        val expectedOkm = hex(
            "b11e398dc80327a1c8e7f78c596a4934" +
            "4f012eda2d4efad8a050cc4c19afa97c" +
            "59045a99cac7827271cb41c65e590e09" +
            "da3275600c2f09b8367793a9aca3db71" +
            "cc30c58179ec3e87c14c01d5c1f3434f" +
            "1d87"
        )
        assertContentEquals(expectedPrk, Hkdf.extract(salt, ikm))
        assertContentEquals(expectedOkm, Hkdf.derive(salt, ikm, info, l))
    }

    @Test
    fun rfc5869_a3_test_case_with_sha256_zero_length_salt_and_info() = runTest {
        Crypto.ensureInitialized()
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = ByteArray(0)
        val info = ByteArray(0)
        val l = 42
        val expectedPrk = hex(
            "19ef24a32c717b167f33a91d6f648bdf" +
            "96596776afdb6377ac434c1c293ccb04"
        )
        val expectedOkm = hex(
            "8da4e775a563c18f715f802a063c5a31" +
            "b8a11f5c5ee1879ec3454e5f3c738d2d" +
            "9d201395faa4b61a96c8"
        )
        assertContentEquals(expectedPrk, Hkdf.extract(salt, ikm))
        assertContentEquals(expectedOkm, Hkdf.derive(salt, ikm, info, l))
    }

    @Test
    fun expand_rejects_oversize_request() = runTest {
        Crypto.ensureInitialized()
        val prk = ByteArray(32) { 0x42 }
        assertFailsWith<IllegalArgumentException> {
            Hkdf.expand(prk, info = ByteArray(0), length = 255 * 32 + 1)
        }
    }

    @Test
    fun expand_rejects_zero_length() = runTest {
        Crypto.ensureInitialized()
        val prk = ByteArray(32) { 0x42 }
        assertFailsWith<IllegalArgumentException> {
            Hkdf.expand(prk, info = ByteArray(0), length = 0)
        }
    }

    @Test
    fun derive_returns_exactly_requested_length_at_max_boundary() = runTest {
        Crypto.ensureInitialized()
        val out = Hkdf.derive(
            salt = ByteArray(0),
            ikm = ByteArray(32),
            info = ByteArray(0),
            length = 255 * 32,
        )
        assertEquals(255 * 32, out.size)
    }
}
