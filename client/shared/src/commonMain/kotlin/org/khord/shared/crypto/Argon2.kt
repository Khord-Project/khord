package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.pwhash.PasswordHash

/**
 * Identity-key seed derivation — PROTOCOL.md §3.1.
 *
 * Deterministically derives a 32-byte raw seed from a user-controlled seed
 * phrase via Argon2id. The output is fed to `crypto_sign_seed_keypair` to
 * produce the long-lived Ed25519 identity key (see [IdentityKey]).
 *
 * Every parameter below is LOAD-BEARING. Changing any value would produce
 * a different identity key from the same seed phrase, breaking recovery
 * for every existing user. Independent client implementations must match
 * these values exactly. See ADR 014 (key backup) for the recovery story.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object Argon2 {

    /** Output size matches Ed25519 seed length (32 bytes). */
    const val SEED_LEN: Int = 32

    /**
     * Salt — fixed 16 ASCII bytes per PROTOCOL.md §3.1. Argon2id requires
     * exactly `crypto_pwhash_SALTBYTES = 16`. The string is intentionally
     * trivial to reproduce: any independent implementation can write the
     * literal bytes byte-for-byte.
     */
    val SALT: ByteArray = "khord-identity01".encodeToByteArray().also {
        require(it.size == 16) { "SALT must be exactly 16 bytes" }
    }

    /**
     * Argon2id parameters — PROTOCOL.md §3.1 specifies MODERATE.
     *
     *   crypto_pwhash_OPSLIMIT_MODERATE  = 3
     *   crypto_pwhash_MEMLIMIT_MODERATE  = 256 MiB = 268_435_456
     *   crypto_pwhash_ALG_ARGON2ID13     = 2  (Argon2id v1.3)
     */
    const val OPSLIMIT_MODERATE: ULong = 3uL
    const val MEMLIMIT_MODERATE: Int = 268_435_456
    const val ALG_ARGON2ID13: Int = 2

    /**
     * Derive a 32-byte seed from `seedPhrase` per PROTOCOL.md §3.1.
     *
     * @param seedPhrase the user's mnemonic (BIP39-style) or chosen passphrase
     * @return 32 bytes suitable for `crypto_sign_seed_keypair`
     */
    fun deriveSeed(seedPhrase: String): ByteArray {
        val out = PasswordHash.pwhash(
            outputLength = SEED_LEN,
            password = seedPhrase,
            salt = SALT.toUByteArray(),
            opsLimit = OPSLIMIT_MODERATE,
            memLimit = MEMLIMIT_MODERATE,
            algorithm = ALG_ARGON2ID13,
        )
        return out.toByteArray()
    }
}
