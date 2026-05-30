package org.khord.shared.crypto

import org.khord.shared.crypto.ratchet.DoubleRatchet
import org.khord.shared.crypto.ratchet.EncryptedMessage
import org.khord.shared.crypto.ratchet.RatchetState

/**
 * A per-contact encrypted session — combines X3DH bootstrap (PROTOCOL.md §6)
 * with the ongoing Double Ratchet (PROTOCOL.md §7).
 *
 * One [Session] instance is held per contact. The [associatedData] is set
 * at session start (Encode(IK_A) || Encode(IK_B)) and reused for every
 * subsequent message — it binds the AEAD output to the long-term identity
 * keys involved in the X3DH bootstrap (X3DH §3.3).
 *
 * The session does not own the long-term identity key — that's the user's
 * [IdentityKey], used only at bootstrap. After bootstrap, the session
 * advances entirely on per-message ratchet keys.
 */
internal class Session(
    private val ratchetState: RatchetState,
    val associatedData: ByteArray,
) {

    /** Alice's first send and every subsequent send. */
    fun encrypt(plaintext: ByteArray): EncryptedMessage {
        return DoubleRatchet.encrypt(ratchetState, plaintext, associatedData)
    }

    /** Decrypt an incoming message; throws if the AEAD/state check fails. */
    fun decrypt(headerBytes: ByteArray, ciphertext: ByteArray): ByteArray {
        return DoubleRatchet.decrypt(ratchetState, headerBytes, ciphertext, associatedData)
    }

    /** Snapshot the current ratchet state for persistence (the live mutable instance). */
    internal fun ratchetStateForPersistence(): RatchetState = ratchetState

    /**
     * Snapshot the SENDING chain for copy-on-encrypt rollback (ADR 030).
     * [encrypt] advances only CKs + Ns in place; when a send is queued for
     * offline retry we roll those back so the retry re-encrypts from the
     * identical position. Deliberately NOT a full-state snapshot: the
     * receiving chain is mutated by an unsynchronised receiveMessages() and
     * must never be clobbered by a send rollback.
     */
    internal fun snapshotSendChain(): org.khord.shared.crypto.ratchet.SendChainSnapshot =
        ratchetState.snapshotSendChain()

    /** Roll the sending chain back after a failed send. */
    internal fun restoreSendChain(snapshot: org.khord.shared.crypto.ratchet.SendChainSnapshot) {
        ratchetState.restoreSendChain(snapshot)
    }

    companion object {
        /**
         * Alice's session-bootstrap from a fresh X3DH initiator output.
         */
        fun fromInitiator(initiatorOutput: X3dh.InitiatorOutput, bobSignedPreKeyPub: ByteArray): Session {
            val rs = DoubleRatchet.initAlice(
                sk = initiatorOutput.sharedSecret,
                bobSignedPreKeyPub = bobSignedPreKeyPub,
            )
            return Session(rs, initiatorOutput.associatedData)
        }

        /**
         * Bob's session-bootstrap when receiving an X3DH initial message.
         * Caller must have already run [X3dh.respond] to derive `sk`.
         */
        fun fromResponder(
            sk: ByteArray,
            bobSignedPreKeyPair: X25519KeyPair,
            associatedData: ByteArray,
        ): Session {
            val rs = DoubleRatchet.initBob(sk, bobSignedPreKeyPair)
            return Session(rs, associatedData)
        }

        /**
         * Reconstruct a session from a previously-persisted ratchet state +
         * its associated data. Used by [org.khord.shared.protocol.orchestrator.Messaging.load]
         * after the persistence layer has reloaded state from the DB.
         */
        fun fromExistingRatchet(
            ratchetState: RatchetState,
            associatedData: ByteArray,
        ): Session = Session(ratchetState, associatedData)
    }
}
