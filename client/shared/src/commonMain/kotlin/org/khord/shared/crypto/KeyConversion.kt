package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.signature.Signature

/**
 * Ed25519 ↔ X25519 key conversion via libsodium.
 *
 * Khord uses Ed25519 for the long-lived identity key (signature operations)
 * and the X25519 form for Diffie-Hellman in X3DH and the Double Ratchet
 * (see ADR 006). The two key forms are deterministically derived from the
 * same Ed25519 keypair via the standard birational map between the Edwards
 * and Montgomery curves (libsodium's `crypto_sign_ed25519_*_to_curve25519`).
 *
 * **Khord-specific choice (vs Signal X3DH spec):** Signal's published X3DH
 * spec uses XEdDSA, signing directly with the Montgomery key. Khord signs
 * with the Ed25519 form and converts to X25519 only for DH. The two
 * approaches yield equivalent security; this is purely a wire-format and
 * library-call distinction. See ADR 006 for rationale.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object KeyConversion {

    /** 32-byte Ed25519 public key → 32-byte X25519 public key. */
    fun ed25519PkToX25519(ed25519Pk: ByteArray): ByteArray {
        require(ed25519Pk.size == 32) { "Ed25519 public key must be 32 bytes" }
        return Signature.ed25519PkToCurve25519(ed25519Pk.toUByteArray()).toByteArray()
    }

    /** 64-byte Ed25519 secret key → 32-byte X25519 secret scalar. */
    fun ed25519SkToX25519(ed25519Sk: ByteArray): ByteArray {
        require(ed25519Sk.size == 64) { "Ed25519 secret key must be 64 bytes" }
        return Signature.ed25519SkToCurve25519(ed25519Sk.toUByteArray()).toByteArray()
    }
}
