package org.khord.shared.crypto.ratchet

import org.khord.shared.crypto.Hkdf

/**
 * The two KDFs used by the Double Ratchet — DR §3.3 + §7.2.
 *
 * KDF_RK (root-key step):
 *   HKDF-SHA-256(salt = rk, IKM = dh_out, info = "khord-rr-v1", L = 64)
 *   The 64 bytes are split into the new root key (32 B) and the new chain
 *   key (32 B) — sender or receiver depending on which DH was just done.
 *
 * KDF_CK (chain-key step):
 *   mk    = HMAC-SHA-256(ck, 0x01)
 *   ck'   = HMAC-SHA-256(ck, 0x02)
 *   The two distinct constants 0x01 / 0x02 give domain separation between
 *   the message-key derivation and the chain advance.
 *
 * `info` strings are deliberate: they namespace this protocol so a user's
 * libsodium key cannot be replayed against a different Khord HKDF call site.
 */
internal object RatchetKdf {

    private val INFO_ROOT_KEY: ByteArray = "khord-rr-v1".encodeToByteArray()
    private val MK_CONSTANT: ByteArray = byteArrayOf(0x01)
    private val CK_CONSTANT: ByteArray = byteArrayOf(0x02)

    /**
     * KDF_RK — DR §7.2 with HKDF-SHA-256.
     * Returns Pair(newRootKey, newChainKey), each 32 bytes.
     */
    fun rk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.derive(salt = rk, ikm = dhOut, info = INFO_ROOT_KEY, length = 64)
        val newRk = out.copyOfRange(0, 32)
        val newCk = out.copyOfRange(32, 64)
        return newRk to newCk
    }

    /**
     * KDF_CK — DR §7.2 with HMAC-SHA-256 + 0x01 / 0x02 constants.
     * Returns Pair(newChainKey, messageKey), each 32 bytes.
     */
    fun ck(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = Hkdf.hmacSha256(ck, MK_CONSTANT)
        val newCk = Hkdf.hmacSha256(ck, CK_CONSTANT)
        return newCk to mk
    }
}
