package org.khord.shared.storage

import org.khord.shared.crypto.IdentityKey
import org.khord.shared.crypto.ratchet.RatchetState
import org.khord.shared.protocol.wire.QrPayload

/**
 * Single-file API for everything the messaging layer must persist.
 *
 * Two implementations:
 *   - [InMemoryPersistence] — process-local, used by existing crypto/orchestrator
 *     tests and as a sensible default when no DB is configured.
 *   - [DbPersistence] — SQLDelight-backed, used by the integration test and
 *     production.
 *
 * Persistence-fault behaviour for the PoC: any save call may throw on
 * I/O failure. Callers surface the error; the in-memory state stays
 * mutated. On the next restart, the in-memory state is rebuilt from the
 * DB and the failed save's mutation is silently lost. Production
 * hardening (write-ahead pattern) is a follow-up. See investigation Q7
 * issue 8.
 */
internal interface Persistence {

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * Load the previously-registered identity, or null if no identity has
     * been registered yet on this device.
     */
    suspend fun loadIdentity(): IdentityRecord?
    suspend fun saveIdentity(record: IdentityRecord)
    /** Mark the existing identity row as fully registered with the Key Server. */
    suspend fun markRegisteredAtServer()
    /** Update only the display name on the existing identity row. */
    suspend fun updateMyDisplayName(displayName: String)

    // ── Pre-keys ────────────────────────────────────────────────────────────

    suspend fun saveSignedPreKey(record: SignedPreKeyRecord)
    suspend fun loadSignedPreKey(): SignedPreKeyRecord?

    /** Add a batch of OPK secrets at registration. */
    suspend fun saveOpkBatch(secretsByKeyId: Map<Int, ByteArray>)
    suspend fun loadAllOpkSecrets(): Map<Int, ByteArray>
    /** Remove an OPK secret after a successful X3DH respond consumed it. */
    suspend fun deleteOneTimePreKey(keyId: Int)
    /**
     * Wipe every OPK secret from the store. Called from the start of
     * [Messaging.register] so that retrying registration after a
     * partial-failure first attempt doesn't collide with the previous
     * attempt's already-persisted key_ids (UNIQUE constraint on
     * one_time_pre_key.key_id). Safe to call on an empty store.
     */
    suspend fun deleteAllOneTimePreKeys()

    // ── Contacts ────────────────────────────────────────────────────────────

    /**
     * Upsert a contact row. New contacts default to
     * [ContactStatus.ACCEPTED] because the orchestrator's primary
     * caller is [Messaging.storeContact] which represents
     * user-initiated contacts. The X3DH-initial receive path passes
     * [ContactStatus.PENDING] explicitly. Existing rows keep their
     * status on UPSERT unless the caller explicitly overrides it via
     * [setContactStatus].
     *
     * Implementation detail for INSERT OR REPLACE-style upserts: the
     * value passed here REPLACES the on-disk status. Callers who want
     * to preserve an existing status should read it first.
     */
    suspend fun saveContact(
        qr: QrPayload,
        displayName: String = "",
        status: ContactStatus = ContactStatus.ACCEPTED,
    )
    suspend fun loadContact(fingerprint: String): ContactInfo?
    suspend fun loadAllContacts(): List<ContactInfo>

    /** Contacts whose first X3DH initial hasn't yet been approved by the local user. */
    suspend fun loadPendingContacts(): List<ContactInfo>

    suspend fun updateContactDisplayName(fingerprint: String, displayName: String)

    /** Flip an existing contact's acceptance status. No-op if the fingerprint is unknown. */
    suspend fun setContactStatus(fingerprint: String, status: ContactStatus)

    /**
     * Stash (or clear, when null) a deferred protocol payload on a
     * contact row. Used to hold an inbound group_invite from a
     * still-pending contact until the user accepts; the orchestrator
     * then re-processes the payload and writes null back here.
     */
    suspend fun setContactPendingPayload(fingerprint: String, payload: String?)

    /**
     * Remove a contact and every record that depends on it: the
     * pairwise Double Ratchet session row, every persisted message,
     * and the contact row itself. The relay-side mailbox binding is
     * effectively dropped too because the WebSocket subscription is
     * keyed off the session (caller is responsible for telling the
     * push service to refresh after this returns).
     *
     * Local-only. Does NOT notify the contact and does NOT touch the
     * Key/Relay servers — by design, per the "no server-side trace"
     * stance.
     *
     * On [DbPersistence] this is one DELETE statement against the
     * contact table; the message and session tables cascade-delete
     * via ON DELETE CASCADE foreign keys.
     */
    suspend fun deleteContact(fingerprint: String)

    // ── Pending mailboxes ───────────────────────────────────────────────────

    suspend fun savePendingMailbox(mailboxId: String, bearerToken: String)
    suspend fun loadPendingMailboxes(): Map<String, String>
    suspend fun deletePendingMailbox(mailboxId: String)

    // ── Sessions ────────────────────────────────────────────────────────────

    suspend fun saveSession(record: SessionRecord)
    suspend fun loadSession(contactFingerprint: String): SessionRecord?
    suspend fun loadAllSessions(): List<SessionRecord>
    suspend fun updateRatchetState(contactFingerprint: String, state: RatchetState)
    suspend fun updateLastFetchedSequence(contactFingerprint: String, sequence: Long)

    // ── Messages ────────────────────────────────────────────────────────────

    suspend fun saveMessage(
        contactFingerprint: String,
        direction: MessageDirection,
        body: String,
        timestamp: String,
        messageUuid: String? = null,
    )
    suspend fun loadMessages(contactFingerprint: String): List<StoredMessage>

    /**
     * Look up a 1:1 message by its UUID. Returns null if no row matches
     * (forged UUID, message deleted, or pre-alpha.14 message without
     * a UUID). Used by [Messaging.editMessage] to locate the sender's
     * own message + by [Messaging.handleMessageEdit] to find the
     * target of an inbound edit before sender verification.
     */
    suspend fun findMessageByUuid(messageUuid: String): MessageLookup?

    /**
     * Replace the body of a 1:1 message identified by UUID and flag
     * it as edited. Caller verifies sender identity before calling.
     * No-op if the UUID doesn't match anything.
     */
    suspend fun updateMessageBodyByUuid(messageUuid: String, newBody: String)

    // ── Groups (ADR 023) ────────────────────────────────────────────────────
    //
    // Client-side group state. The Relay Server has no concept of groups;
    // every group operation fans out via per-member pairwise Double Ratchet
    // channels. Each device maintains its own view of the group; consistency
    // depends on message delivery.

    /**
     * Upsert a group row. `isAdmin` is true for the local user when they
     * created the group; false on every receiving member's device.
     */
    suspend fun saveGroup(
        groupId: String,
        groupName: String,
        createdByFingerprint: String,
        isAdmin: Boolean,
    )

    suspend fun loadGroups(): List<GroupRecord>

    suspend fun loadGroup(groupId: String): GroupRecord?

    suspend fun updateGroupName(groupId: String, newName: String)

    /** Remove a group + cascade-delete its members + messages. */
    suspend fun deleteGroup(groupId: String)

    /**
     * Upsert a member row (group_id, fingerprint) — display_name may be
     * updated by a later call when fresher info arrives via reply_info.
     */
    suspend fun addGroupMember(groupId: String, fingerprint: String, displayName: String)

    suspend fun removeGroupMember(groupId: String, fingerprint: String)

    suspend fun loadGroupMembers(groupId: String): List<GroupMemberRecord>

    suspend fun saveGroupMessage(
        groupId: String,
        senderFingerprint: String,
        senderDisplayName: String,
        body: String,
        timestamp: String,
        direction: MessageDirection,
        messageUuid: String? = null,
    )

    suspend fun loadGroupMessages(groupId: String): List<GroupMessageRecord>

    /** Group equivalent of [findMessageByUuid]. */
    suspend fun findGroupMessageByUuid(messageUuid: String): GroupMessageLookup?

    /** Group equivalent of [updateMessageBodyByUuid]. */
    suspend fun updateGroupMessageBodyByUuid(messageUuid: String, newBody: String)

    // ── Key-server token cache ─────────────────────────────────────────────

    suspend fun saveKeyServerToken(token: String, expiresAt: String)
    suspend fun loadKeyServerToken(): KeyServerTokenRecord?
    suspend fun clearKeyServerToken()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Wipe every record from the underlying store and clear any associated
     * key material. Irreversible. After this call, the store is empty
     * (further reads return null/empty); the instance remains usable so
     * a fresh registration could begin against the same store.
     *
     * For [DbPersistence], the database file is deleted on disk.
     */
    suspend fun panic()

    /** Release resources (DB connections, in-memory caches). */
    suspend fun close()
}

// ── Records / value types ───────────────────────────────────────────────────

internal data class IdentityRecord(
    val identity: IdentityKey,
    val keyServerUrl: String,
    val relayServerUrl: String,
    val createdAt: String,
    /**
     * Whether the pre-key bundle has been successfully uploaded to the Key
     * Server. False until Messaging.register's uploadBundle call returns
     * successfully — used by Messaging.load to detect a partial-registration
     * crash and recover.
     */
    val registeredAtServer: Boolean = false,
    /**
     * User's chosen display name (PROTOCOL.md §8). Sent inside the
     * encrypted `reply_info` block of every outbound message so contacts
     * can show it instead of the bare fingerprint. Defaults to "Anonymous"
     * if the user skipped the optional onboarding prompt.
     */
    val displayName: String = "Anonymous",
)

/**
 * Stored contact = wire QR + a learned display name + acceptance
 * status. Display name lives outside the QR (the QR JSON is unchanged)
 * because we learn names from the encrypted `reply_info` field, not
 * from the public QR code. Status drives the contact-acceptance gate:
 *
 *   - [ContactStatus.ACCEPTED] — contact you initiated (scanned QR /
 *     pasted link), or one whose first message you've already
 *     approved. Shown in the main contact list.
 *   - [ContactStatus.PENDING] — someone who sent you an X3DH initial
 *     without prior approval. Messages received and stored, but the
 *     contact only surfaces in the "Requests" UI; the push service
 *     suppresses notification banners until acceptance.
 */
internal data class ContactInfo(
    val qr: QrPayload,
    val displayName: String,
    val status: ContactStatus = ContactStatus.ACCEPTED,
    /**
     * JSON-encoded inner payload deferred from a previous receive
     * while this contact was still pending — typically a
     * group_invite. Processed and cleared on accept. Null in the
     * common case.
     */
    val pendingPayload: String? = null,
)

internal enum class ContactStatus {
    ACCEPTED,
    PENDING;

    fun wireValue(): String = when (this) {
        ACCEPTED -> "accepted"
        PENDING -> "pending"
    }

    companion object {
        fun fromWire(s: String): ContactStatus = when (s) {
            "pending" -> PENDING
            else -> ACCEPTED   // accept anything we don't recognise — safer than wedging
        }
    }
}

internal data class SignedPreKeyRecord(
    val keyId: Int,
    val publicKey: ByteArray,
    val secretKey: ByteArray,
)

internal data class SessionRecord(
    val contactFingerprint: String,
    val inboundMailbox: String,
    val inboundBearerToken: String,
    val outboundMailbox: String,
    val outboundRelayServer: String,
    val associatedData: ByteArray,
    val ratchetState: RatchetState,
    val lastFetchedSequence: Long,
    val updatedAt: String,
)

internal enum class MessageDirection { SENT, RECEIVED }

internal data class StoredMessage(
    val id: Long,
    val direction: MessageDirection,
    val body: String,
    val timestamp: String,
    val storedAt: String,
    val messageUuid: String? = null,
    val edited: Boolean = false,
)

/**
 * Slim projection returned by [Persistence.findMessageByUuid]: just
 * enough info for the orchestrator to do sender-identity verification
 * (contactFingerprint + direction) without round-tripping the body.
 */
internal data class MessageLookup(
    val id: Long,
    val contactFingerprint: String,
    val direction: MessageDirection,
    val messageUuid: String,
)

internal data class KeyServerTokenRecord(
    val token: String,
    val expiresAt: String,
)

// ── Group records (ADR 023) ──────────────────────────────────────────────────

internal data class GroupRecord(
    val groupId: String,
    val groupName: String,
    val createdByFingerprint: String,
    val isAdmin: Boolean,
    val createdAt: String,
)

internal data class GroupMemberRecord(
    val fingerprint: String,
    val displayName: String,
)

internal data class GroupMessageRecord(
    val id: Long,
    val senderFingerprint: String,
    val senderDisplayName: String,
    val body: String,
    val timestamp: String,
    val direction: MessageDirection,
    val storedAt: String,
    val messageUuid: String? = null,
    val edited: Boolean = false,
)

/**
 * Group equivalent of [MessageLookup]: orchestrator uses
 * senderFingerprint to verify the edit originated from the same
 * member who sent the original message.
 */
internal data class GroupMessageLookup(
    val id: Long,
    val groupId: String,
    val senderFingerprint: String,
    val messageUuid: String,
)
