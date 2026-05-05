package org.khord.shared.protocol.orchestrator

import org.khord.shared.crypto.Session

/**
 * One end of a per-contact encrypted conversation.
 *
 * Wraps the crypto-layer [Session] (which holds the live `RatchetState`)
 * with the surrounding metadata Khord needs to actually drive a
 * conversation: who the contact is, where to send messages, where to
 * receive them, and which sequence we've polled up to.
 *
 * **Concurrency:** single-threaded. The wrapped ratchet state is mutable
 * and not coroutine-safe — encrypt/decrypt calls on the same instance
 * must be sequenced by the caller. Persistence + locking land in the
 * next phase (see DEFERRED.md).
 */
class ContactSession internal constructor(
    /** The contact's public identity (Ed25519 32 B) — fixed for this contact. */
    val contactIdentityKey: ByteArray,
    /** The contact's fingerprint — hex(SHA-256(contactIdentityKey)). */
    val contactFingerprint: String,
    /** The mailbox on the relay server where I send messages TO this contact. */
    val outboundMailboxId: String,
    /** The base URL of the relay server hosting [outboundMailboxId]. */
    val outboundRelayServer: String,
    /** The mailbox on my relay server where I receive messages FROM this contact. */
    val inboundMailboxId: String,
    /** The bearer token authorising me to drain [inboundMailboxId]. */
    internal val inboundBearerToken: String,
    /** Live Double Ratchet session (X3DH-bootstrapped). */
    internal val session: Session,
    /** Highest sequence number I've fetched from [inboundMailboxId]. Updated as we poll. */
    internal var lastFetchedSequence: Long = 0L,
)
