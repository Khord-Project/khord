package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication

/**
 * X3DH key agreement — Signal X3DH spec §3.3 / §3.4.
 *
 * The output of X3DH is a 32-byte shared secret SK that seeds the Double
 * Ratchet. Khord uses Ed25519 for the long-lived identity key and
 * derives X25519 for DH (see ADR 006 / [KeyConversion]). The signed
 * pre-key MUST be signature-verified before any DH operation
 * (X3DH §4.5 — bypass-to-key-substitution attack otherwise).
 *
 * This file implements both sides — Alice ([initiate]) and Bob ([respond]).
 * They are symmetric: same DH ordering, same KDF, same AD construction.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object X3dh {

    /**
     * Curve discriminator F per X3DH §2.2 — 32 bytes of `0xFF` for X25519.
     * Prepended to the DH concatenation before HKDF-Extract to prevent
     * cross-curve confusion when the spec is extended (X25519 vs X448).
     */
    private val F: ByteArray = ByteArray(32) { 0xFF.toByte() }

    /** HKDF salt for SK derivation: zero-filled HashLen bytes (X3DH §2.2). */
    private val SALT: ByteArray = ByteArray(32)

    /** Domain-separation `info` for the SK derivation — Khord-specific. */
    private val INFO: ByteArray = "khord-x3dh-v1".encodeToByteArray()

    /**
     * The output of [initiate] — the bytes Alice ships to Bob plus the SK
     * she'll feed into RatchetInitAlice.
     */
    data class InitiatorOutput(
        /** Alice's identity key (Ed25519 pub) — copied to the wire. */
        val identityKeyEd25519: ByteArray,
        /** Alice's ephemeral X25519 public key. */
        val ephemeralPublicKey: ByteArray,
        /** Which signed pre-key of Bob's was used. */
        val signedPreKeyId: Int,
        /** Which one-time pre-key of Bob's was used (null = none was available). */
        val oneTimePreKeyId: Int?,
        /** The 32-byte shared secret. NOT sent on the wire — Alice keeps it. */
        val sharedSecret: ByteArray,
        /** Associated data for the first AEAD payload (X3DH §3.3). */
        val associatedData: ByteArray,
    )

    /**
     * Inputs Bob needs to recompute SK from Alice's initial message
     * (X3DH §3.4). Bob looks up the SPK and (optional) OPK secrets by ID
     * before calling [respond].
     */
    data class ResponderInput(
        val initiatorIdentityKeyEd25519: ByteArray,
        val initiatorEphemeralPublicKey: ByteArray,
        val responderIdentity: IdentityKey,
        /** Bob's signed pre-key SECRET (X25519). */
        val signedPreKeySecret: ByteArray,
        /** Bob's OPK secret (X25519), if Alice indicated she used one. */
        val oneTimePreKeySecret: ByteArray?,
    )

    /** Alice's perspective — X3DH §3.3. */
    fun initiate(
        initiator: IdentityKey,
        bobBundle: PreKeyBundle,
    ): InitiatorOutput {
        // Step 1 (X3DH §3.3, §4.5): verify SPK signature BEFORE any DH op.
        // Failure here means the bundle is forged or corrupted; abort.
        val spkValid = PreKeys.verifySignedPreKey(
            bobBundle.signedPreKey,
            bobBundle.identityKeyEd25519,
        )
        require(spkValid) { "signed pre-key signature did not verify" }

        // Step 2: generate ephemeral keypair EK_A.
        val ek = PreKeys.generateX25519KeyPair()

        // Step 3: convert Bob's Ed25519 IK to its X25519 form for DH.
        val bobIkX25519 = KeyConversion.ed25519PkToX25519(bobBundle.identityKeyEd25519)

        // Step 4: four (or three) DH operations (X3DH §3.3).
        //   DH1 = DH(IK_A, SPK_B)        -- Alice's identity ↔ Bob's SPK
        //   DH2 = DH(EK_A, IK_B)         -- Alice's ephemeral ↔ Bob's identity
        //   DH3 = DH(EK_A, SPK_B)        -- Alice's ephemeral ↔ Bob's SPK
        //   DH4 = DH(EK_A, OPK_B)        -- optional, only if OPK present
        val dh1 = dh(initiator.x25519SecretKey, bobBundle.signedPreKey.publicKey)
        val dh2 = dh(ek.secretKey, bobIkX25519)
        val dh3 = dh(ek.secretKey, bobBundle.signedPreKey.publicKey)
        val dh4 = bobBundle.oneTimePreKey?.let { dh(ek.secretKey, it.publicKey) }

        // Step 5: SK = HKDF(F || DH1 || DH2 || DH3 [|| DH4]) — X3DH §2.2/§3.3.
        val km = concat(F, dh1, dh2, dh3, dh4 ?: ByteArray(0))
        val sk = Hkdf.derive(salt = SALT, ikm = km, info = INFO, length = 32)

        // Wipe DH outputs and KM — they leak SK if exfiltrated.
        dh1.wipe(); dh2.wipe(); dh3.wipe(); dh4?.wipe(); km.wipe()
        ek.secretKey.wipe()  // ephemeral — never stored again

        // AD per X3DH §3.3: Encode(IK_A) || Encode(IK_B). Khord-specific
        // choice: Encode(IK) is the Ed25519 public-key bytes (32 B each).
        val ad = initiator.ed25519PublicKey + bobBundle.identityKeyEd25519

        return InitiatorOutput(
            identityKeyEd25519 = initiator.ed25519PublicKey,
            ephemeralPublicKey = ek.publicKey,
            signedPreKeyId = bobBundle.signedPreKey.keyId,
            oneTimePreKeyId = bobBundle.oneTimePreKey?.keyId,
            sharedSecret = sk,
            associatedData = ad,
        )
    }

    /**
     * Bob's perspective — X3DH §3.4. Returns the SK derived from his side.
     * Bob is responsible for deleting `oneTimePreKeySecret` after a
     * successful X3DH (X3DH §3.4 — forward secrecy depends on this).
     */
    fun respond(input: ResponderInput): ByteArray {
        // Bob computes the same DHs as Alice but with his private keys.
        //   DH1 = DH(SPK_B, IK_A_Curve25519)
        //   DH2 = DH(IK_B, EK_A)
        //   DH3 = DH(SPK_B, EK_A)
        //   DH4 = DH(OPK_B, EK_A)        -- if present
        val aliceIkX25519 = KeyConversion.ed25519PkToX25519(input.initiatorIdentityKeyEd25519)
        val dh1 = dh(input.signedPreKeySecret, aliceIkX25519)
        val dh2 = dh(input.responderIdentity.x25519SecretKey, input.initiatorEphemeralPublicKey)
        val dh3 = dh(input.signedPreKeySecret, input.initiatorEphemeralPublicKey)
        val dh4 = input.oneTimePreKeySecret?.let {
            dh(it, input.initiatorEphemeralPublicKey)
        }

        val km = concat(F, dh1, dh2, dh3, dh4 ?: ByteArray(0))
        val sk = Hkdf.derive(salt = SALT, ikm = km, info = INFO, length = 32)

        dh1.wipe(); dh2.wipe(); dh3.wipe(); dh4?.wipe(); km.wipe()

        return sk
    }

    /** Build the AD on Bob's side — must match Alice's. */
    fun associatedDataFor(
        initiatorIdentityKeyEd25519: ByteArray,
        responder: IdentityKey,
    ): ByteArray = initiatorIdentityKeyEd25519 + responder.ed25519PublicKey

    /** X25519 DH = `crypto_scalarmult(secret, peerPublic)`. */
    private fun dh(secret: ByteArray, peerPublic: ByteArray): ByteArray {
        require(secret.size == 32 && peerPublic.size == 32) {
            "X25519 DH inputs must be 32 bytes each"
        }
        return ScalarMultiplication.scalarMultiplication(
            secret.toUByteArray(),
            peerPublic.toUByteArray(),
        ).toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (p in parts) {
            p.copyInto(out, destinationOffset = offset)
            offset += p.size
        }
        return out
    }
}
