package org.khord.shared.protocol

import com.ionspin.kotlin.crypto.hash.Hash
import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.protocol.client.PowMiner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for PowMiner / Mailboxes.
 *
 * The miner mirrors the relay server's verifier byte-for-byte; the test
 * here proves the encoding (UTF-8 mailbox || decimal-ASCII nonce) and the
 * leading-zero counting agree with libsodium's SHA-256.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class PowMinerTest {

    @Test
    fun leading_zero_bits_known_values() {
        // 0x00, 0x40 → 8 zeros (byte 0) + 1 zero before the high bit of 0x40 = 9
        assertEquals(9, PowMiner.leadingZeroBits(byteArrayOf(0x00, 0x40)))
        assertEquals(0, PowMiner.leadingZeroBits(byteArrayOf(0x80.toByte())))
        assertEquals(7, PowMiner.leadingZeroBits(byteArrayOf(0x01)))
        assertEquals(15, PowMiner.leadingZeroBits(byteArrayOf(0x00, 0x01)))
        // 0x20 = 0010_0000 → 2 leading zeros
        assertEquals(2, PowMiner.leadingZeroBits(byteArrayOf(0x20)))
    }

    @Test
    fun mining_at_low_difficulty_returns_a_solution() = runTest {
        Crypto.ensureInitialized()
        val mailboxId = "test-mailbox-id-22-chars"
        val nonce = PowMiner.mine(mailboxId, difficultyBits = 4)

        // Independently re-verify: the returned nonce must satisfy the puzzle.
        val digest = Hash.sha256(
            (mailboxId.encodeToByteArray() + nonce.encodeToByteArray()).toUByteArray()
        ).toByteArray()
        assertTrue(PowMiner.leadingZeroBits(digest) >= 4)
        assertTrue(nonce.all { it.isDigit() }, "nonce must be decimal ASCII")
    }

    @Test
    fun mining_at_8_bits_completes_quickly() = runTest {
        Crypto.ensureInitialized()
        // 8 bits = ~256 hashes mean — well under 100 ms even on slow CI.
        val nonce = PowMiner.mine("any-mailbox-id-22-chars", difficultyBits = 8)
        val digest = Hash.sha256(
            ("any-mailbox-id-22-chars".encodeToByteArray() + nonce.encodeToByteArray()).toUByteArray()
        ).toByteArray()
        assertTrue(PowMiner.leadingZeroBits(digest) >= 8)
    }
}
