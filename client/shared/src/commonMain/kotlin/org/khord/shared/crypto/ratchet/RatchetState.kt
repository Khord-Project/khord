package org.khord.shared.crypto.ratchet

import org.khord.shared.crypto.X25519KeyPair

/**
 * Per-session Double Ratchet state — DR §3.2.
 *
 * Mutable on purpose: the encrypt/decrypt operations advance the state in
 * place. Decrypt MUST be performed copy-on-write — see [snapshot] / [restoreFrom].
 *
 *   DHs        : sending DH keypair (X25519, 32+32 B)
 *   DHr        : remote DH public key (32 B; null until first ratchet)
 *   RK         : root key (32 B)
 *   CKs        : sending chain key (32 B; null on Bob until first send)
 *   CKr        : receiving chain key (32 B; null until first receive)
 *   Ns         : sending message number
 *   Nr         : receiving message number
 *   PN         : # messages in the previous sending chain
 *   MKSKIPPED  : skipped message keys, keyed by (DH-pub, n)
 */
internal class RatchetState(
    var DHs: X25519KeyPair,
    var DHr: ByteArray?,
    var RK: ByteArray,
    var CKs: ByteArray?,
    var CKr: ByteArray?,
    var Ns: Int,
    var Nr: Int,
    var PN: Int,
    val MKSKIPPED: MutableMap<SkippedKey, ByteArray> = mutableMapOf(),
) {
    /** Snapshot for copy-on-decrypt: an immutable view we can roll back to. */
    fun snapshot(): RatchetSnapshot = RatchetSnapshot(
        DHs = DHs.copy(publicKey = DHs.publicKey.copyOf(), secretKey = DHs.secretKey.copyOf()),
        DHr = DHr?.copyOf(),
        RK = RK.copyOf(),
        CKs = CKs?.copyOf(),
        CKr = CKr?.copyOf(),
        Ns = Ns,
        Nr = Nr,
        PN = PN,
        MKSKIPPED = MKSKIPPED.mapValues { it.value.copyOf() }.toMutableMap(),
    )

    /**
     * Snapshot ONLY the sending-chain fields that [DoubleRatchet.encrypt]
     * mutates (CKs + Ns). Used to roll back a queued/failed send (ADR 030)
     * WITHOUT touching the receiving chain — a full [snapshot]/[restoreFrom]
     * would clobber receive progress that a concurrent, unsynchronised
     * receiveMessages() may have committed while the send's relay POST was
     * in flight.
     */
    fun snapshotSendChain(): SendChainSnapshot = SendChainSnapshot(CKs = CKs?.copyOf(), Ns = Ns)

    /** Roll the sending chain back to a [snapshotSendChain] result. */
    fun restoreSendChain(s: SendChainSnapshot) {
        CKs = s.CKs?.copyOf()
        Ns = s.Ns
    }

    /** Restore state from a snapshot — used to roll back after a failed decrypt. */
    fun restoreFrom(s: RatchetSnapshot) {
        DHs = X25519KeyPair(s.DHs.publicKey.copyOf(), s.DHs.secretKey.copyOf())
        DHr = s.DHr?.copyOf()
        RK = s.RK.copyOf()
        CKs = s.CKs?.copyOf()
        CKr = s.CKr?.copyOf()
        Ns = s.Ns
        Nr = s.Nr
        PN = s.PN
        MKSKIPPED.clear()
        MKSKIPPED.putAll(s.MKSKIPPED.mapValues { it.value.copyOf() })
    }
}

/**
 * Composite key for skipped-message-keys map. Custom equals/hashCode
 * required because ByteArray's default identity equality is wrong.
 */
internal class SkippedKey(val dhPub: ByteArray, val n: Int) {
    override fun equals(other: Any?): Boolean = other is SkippedKey &&
        n == other.n && dhPub.contentEquals(other.dhPub)

    override fun hashCode(): Int = dhPub.contentHashCode() * 31 + n
}

/** Sending-chain-only snapshot for encrypt rollback (ADR 030). */
internal data class SendChainSnapshot(
    val CKs: ByteArray?,
    val Ns: Int,
)

internal data class RatchetSnapshot(
    val DHs: X25519KeyPair,
    val DHr: ByteArray?,
    val RK: ByteArray,
    val CKs: ByteArray?,
    val CKr: ByteArray?,
    val Ns: Int,
    val Nr: Int,
    val PN: Int,
    val MKSKIPPED: MutableMap<SkippedKey, ByteArray>,
)

/**
 * Khord-specific MKSKIPPED bounds — PROTOCOL.md §7 (recommended values
 * for interoperability). Lower bound: tolerate routine packet loss.
 * Upper bound: prevent an attacker from forcing unbounded memory use.
 */
internal const val MAX_SKIP_PER_CHAIN = 1000
internal const val MAX_SKIP_TOTAL = 2000
