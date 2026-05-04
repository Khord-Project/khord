package org.khord.shared.crypto.ratchet

import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import org.khord.shared.crypto.PreKeys
import org.khord.shared.crypto.X25519KeyPair
import org.khord.shared.crypto.wipe

/**
 * The Double Ratchet state machine — Signal Double Ratchet spec §3.
 *
 * Every public function in this object has a one-to-one mapping to the
 * pseudocode in DR §3.4 / §3.5. This file deliberately stays close to
 * the spec — the Khord-specific choices (XChaCha20-Poly1305, HKDF/HMAC
 * recipes, header serialisation) are isolated in [RatchetAead],
 * [RatchetKdf], and [RatchetHeader].
 *
 * Critical safety property: `decrypt` operates on a snapshot and only
 * commits the state mutation on successful AEAD verification. A failed
 * decrypt leaves state untouched — without this, a tampered or replayed
 * frame would advance the receiving chain and silently corrupt the
 * session.
 */
internal object DoubleRatchet {

    /** RatchetInitAlice — DR §3.3. */
    fun initAlice(sk: ByteArray, bobSignedPreKeyPub: ByteArray): RatchetState {
        val dhs = PreKeys.generateX25519KeyPair()
        val dhOut = dh(dhs.secretKey, bobSignedPreKeyPub)
        val (rk, cks) = RatchetKdf.rk(sk, dhOut)
        dhOut.wipe()
        return RatchetState(
            DHs = dhs,
            DHr = bobSignedPreKeyPub.copyOf(),
            RK = rk,
            CKs = cks,
            CKr = null,
            Ns = 0,
            Nr = 0,
            PN = 0,
        )
    }

    /** RatchetInitBob — DR §3.3. Bob reuses his SPK as the initial DHs. */
    fun initBob(sk: ByteArray, bobSignedPreKeyPair: X25519KeyPair): RatchetState {
        return RatchetState(
            DHs = X25519KeyPair(
                bobSignedPreKeyPair.publicKey.copyOf(),
                bobSignedPreKeyPair.secretKey.copyOf(),
            ),
            DHr = null,
            RK = sk.copyOf(),
            CKs = null,
            CKr = null,
            Ns = 0,
            Nr = 0,
            PN = 0,
        )
    }

    /**
     * RatchetEncrypt — DR §3.4.
     *
     *   CKs, mk = KDF_CK(CKs)
     *   header  = HEADER(DHs, PN, Ns)
     *   Ns      = Ns + 1
     *   ct      = ENCRYPT(mk, plaintext, AD || header_bytes)
     */
    fun encrypt(
        state: RatchetState,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedMessage {
        val cks = state.CKs ?: error("CKs is null — Bob must receive before sending")
        val (newCks, mk) = RatchetKdf.ck(cks)
        cks.wipe()
        state.CKs = newCks

        val header = RatchetHeader(
            dhPublicKey = state.DHs.publicKey,
            previousChainLength = state.PN,
            messageNumber = state.Ns,
        )
        state.Ns += 1

        val headerBytes = header.toBytes()
        val ad = associatedData + headerBytes
        val ciphertext = RatchetAead.encrypt(mk, plaintext, ad)
        mk.wipe()

        return EncryptedMessage(headerBytes, ciphertext)
    }

    /**
     * RatchetDecrypt — DR §3.5. Implements the copy-on-decrypt invariant:
     * state mutations are committed only on AEAD success.
     */
    fun decrypt(
        state: RatchetState,
        headerBytes: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val header = RatchetHeader.fromBytes(headerBytes)
        val ad = associatedData + headerBytes

        // First check: is this a skipped key we already cached?
        val skippedKey = SkippedKey(header.dhPublicKey, header.messageNumber)
        val cachedMk = state.MKSKIPPED[skippedKey]
        if (cachedMk != null) {
            val plaintext = RatchetAead.decrypt(cachedMk, ciphertext, ad)
            state.MKSKIPPED.remove(skippedKey)
            cachedMk.wipe()
            return plaintext
        }

        // Otherwise — try the regular receiving chain. Snapshot first.
        val snap = state.snapshot()
        try {
            if (state.DHr == null || !header.dhPublicKey.contentEquals(state.DHr!!)) {
                skipMessageKeys(state, header.previousChainLength)
                dhRatchet(state, header)
            }
            skipMessageKeys(state, header.messageNumber)
            val ckr = state.CKr ?: error("CKr is null after DH ratchet")
            val (newCkr, mk) = RatchetKdf.ck(ckr)
            ckr.wipe()
            state.CKr = newCkr
            state.Nr += 1

            val plaintext = RatchetAead.decrypt(mk, ciphertext, ad)
            mk.wipe()
            return plaintext
        } catch (e: Throwable) {
            state.restoreFrom(snap)
            throw e
        }
    }

    /**
     * SkipMessageKeys — DR §3.5. Caches message keys for sequence numbers
     * `[Nr, until)` so out-of-order arrivals can still decrypt.
     *
     * Bounded: refuses to skip more than [MAX_SKIP_PER_CHAIN] in one shot
     * or to retain more than [MAX_SKIP_TOTAL] entries overall. The cap
     * makes a malicious sender unable to force unbounded memory use.
     */
    private fun skipMessageKeys(state: RatchetState, until: Int) {
        if (state.Nr + MAX_SKIP_PER_CHAIN < until) {
            throw RatchetSkipLimitExceeded(
                "skip would exceed MAX_SKIP_PER_CHAIN ($MAX_SKIP_PER_CHAIN)"
            )
        }
        val ckr = state.CKr ?: return
        val dhr = state.DHr ?: return
        var current = ckr
        while (state.Nr < until) {
            val (newCkr, mk) = RatchetKdf.ck(current)
            if (state.MKSKIPPED.size >= MAX_SKIP_TOTAL) {
                mk.wipe()
                newCkr.wipe()
                throw RatchetSkipLimitExceeded(
                    "skipped-keys store would exceed MAX_SKIP_TOTAL ($MAX_SKIP_TOTAL)"
                )
            }
            state.MKSKIPPED[SkippedKey(dhr.copyOf(), state.Nr)] = mk
            current.wipe()
            current = newCkr
            state.Nr += 1
        }
        state.CKr = current
    }

    /**
     * DHRatchet — DR §3.5. Triggered when an incoming header reveals a
     * new remote DH public key.
     */
    private fun dhRatchet(state: RatchetState, header: RatchetHeader) {
        state.PN = state.Ns
        state.Ns = 0
        state.Nr = 0
        state.DHr = header.dhPublicKey.copyOf()

        // First half: derive new RK + CKr from DH(my old DHs, peer's new DH).
        val recvDh = dh(state.DHs.secretKey, state.DHr!!)
        val (rkAfterRecv, ckr) = RatchetKdf.rk(state.RK, recvDh)
        recvDh.wipe()
        state.RK.wipe()
        state.RK = rkAfterRecv
        state.CKr = ckr

        // Second half: roll my own DHs forward, derive new RK + CKs.
        val oldDhs = state.DHs
        val freshDhs = PreKeys.generateX25519KeyPair()
        oldDhs.secretKey.wipe()
        state.DHs = freshDhs
        val sendDh = dh(state.DHs.secretKey, state.DHr!!)
        val (rkAfterSend, cks) = RatchetKdf.rk(state.RK, sendDh)
        sendDh.wipe()
        state.RK.wipe()
        state.RK = rkAfterSend
        state.CKs = cks
    }

    /** X25519 DH on internal scalars. */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun dh(secret: ByteArray, peerPub: ByteArray): ByteArray =
        ScalarMultiplication.scalarMultiplication(
            secret.toUByteArray(),
            peerPub.toUByteArray(),
        ).toByteArray()
}

/**
 * The output of [DoubleRatchet.encrypt]. Callers serialise both halves
 * and ship them inside the relay-server-bound blob (PROTOCOL.md §6.2).
 */
internal data class EncryptedMessage(
    val headerBytes: ByteArray,
    val ciphertext: ByteArray,
)

/** Thrown when the receiver would have to skip more than the cap allows. */
internal class RatchetSkipLimitExceeded(message: String) : Exception(message)
