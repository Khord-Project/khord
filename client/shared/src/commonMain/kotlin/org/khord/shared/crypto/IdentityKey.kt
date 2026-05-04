package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.signature.Signature

/**
 * The long-lived per-user identity key — see ADR 003 (key as identity) and
 * PROTOCOL.md §3.1.
 *
 * The Ed25519 keypair is derived deterministically from a seed phrase via
 * Argon2id. The X25519 form is derived from the Ed25519 keypair for use in
 * X3DH and Double Ratchet DH operations. The fingerprint (full 64-char hex
 * SHA-256 of the Ed25519 public key) is the user's stable public identifier.
 */
@OptIn(ExperimentalUnsignedTypes::class)
data class IdentityKey internal constructor(
    val ed25519PublicKey: ByteArray,
    internal val ed25519SecretKey: ByteArray,
    val x25519PublicKey: ByteArray,
    internal val x25519SecretKey: ByteArray,
    val fingerprint: String,
) {
    companion object {
        /**
         * Derive an identity key from `seedPhrase` per PROTOCOL.md §3.1.
         *
         *   raw_seed              = Argon2id(seed_phrase, "khord-identity01", MODERATE, MODERATE)
         *   (ed_pk, ed_sk)        = crypto_sign_seed_keypair(raw_seed)
         *   x_pk                  = crypto_sign_ed25519_pk_to_curve25519(ed_pk)
         *   x_sk                  = crypto_sign_ed25519_sk_to_curve25519(ed_sk)
         *   fingerprint           = hex(SHA-256(ed_pk))   (64 chars, no truncation)
         *
         * The raw seed is wiped from memory after the keypair is derived.
         */
        fun fromSeedPhrase(seedPhrase: String): IdentityKey {
            val rawSeed = Argon2.deriveSeed(seedPhrase)
            try {
                val keyPair = Signature.seedKeypair(rawSeed.toUByteArray())
                val edPk = keyPair.publicKey.toByteArray()
                val edSk = keyPair.secretKey.toByteArray()
                val xPk = KeyConversion.ed25519PkToX25519(edPk)
                val xSk = KeyConversion.ed25519SkToX25519(edSk)
                val fp = Hash.sha256(edPk.toUByteArray()).toByteArray().toHex()
                return IdentityKey(
                    ed25519PublicKey = edPk,
                    ed25519SecretKey = edSk,
                    x25519PublicKey = xPk,
                    x25519SecretKey = xSk,
                    fingerprint = fp,
                )
            } finally {
                rawSeed.wipe()
            }
        }
    }

    // Avoid the default ByteArray identity equality — tests compare fingerprints.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is IdentityKey && fingerprint == other.fingerprint)

    override fun hashCode(): Int = fingerprint.hashCode()

    override fun toString(): String = "IdentityKey(fingerprint=$fingerprint)"
}

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val sb = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        sb[i * 2] = HEX[b ushr 4]
        sb[i * 2 + 1] = HEX[b and 0x0f]
    }
    return sb.concatToString()
}

internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
