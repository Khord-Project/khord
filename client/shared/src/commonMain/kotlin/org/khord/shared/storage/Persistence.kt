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
     * Set / clear the local-only verified flag on a contact. Local
     * trust decision — never transmitted, per-device. Cleared
     * unconditionally by [Messaging.applyX3dhInitialReset] on any
     * key change.
     */
    suspend fun setContactVerified(fingerprint: String, verified: Boolean)

    /**
     * Read the verified flag. False for unknown fingerprints.
     */
    suspend fun isContactVerified(fingerprint: String): Boolean

    /**
     * Set (or clear, when null/blank) the local nickname override on
     * a contact. No-op for unknown fingerprints.
     */
    suspend fun setContactLocalName(fingerprint: String, localName: String?)

    /** Set/clear the block flag (#27). No-op for unknown fingerprints. */
    suspend fun setContactBlocked(fingerprint: String, blocked: Boolean)

    /** Set/clear the mute flag (#27). No-op for unknown fingerprints. */
    suspend fun setContactMuted(fingerprint: String, muted: Boolean)

    /**
     * Set the contact's image-acceptance hint from a received
     * `capability_notice` (feat/capability-notice). Local-only; never sent
     * back. No-op for unknown fingerprints.
     */
    suspend fun setContactImagesAccepted(fingerprint: String, accepted: Boolean)

    /** All blocked contacts — backs the Settings → Blocked list. */
    suspend fun loadBlockedContacts(): List<ContactInfo>

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
        replyToUuid: String? = null,
        media: MediaAttachment? = null,
        deliveryStatus: String? = null,
    )
    suspend fun loadMessages(contactFingerprint: String): List<StoredMessage>

    /** Set the local cache path on a 1:1 message after its full image is fetched (ADR 029). */
    suspend fun setMessageMediaCachedPath(messageId: Long, cachedPath: String)

    /**
     * Update the outbound delivery state ('pending'/'sent'/'failed') of a
     * 1:1 message by its UUID (ADR 030). No-op if the UUID is unknown.
     */
    suspend fun updateMessageDeliveryStatus(messageUuid: String, status: String)

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
        replyToUuid: String? = null,
        media: MediaAttachment? = null,
        deliveryStatus: String? = null,
    )

    suspend fun loadGroupMessages(groupId: String): List<GroupMessageRecord>

    /** Group equivalent of [setMessageMediaCachedPath] (ADR 029). */
    suspend fun setGroupMessageMediaCachedPath(messageId: Long, cachedPath: String)

    /** Group equivalent of [updateMessageDeliveryStatus] (ADR 030). */
    suspend fun updateGroupMessageDeliveryStatus(messageUuid: String, status: String)

    // ── Outbox / offline queue (ADR 030, #59) ───────────────────────────────

    /** Enqueue a message that couldn't be delivered, for later retry. */
    suspend fun insertOutbox(item: OutboxItem)

    /** All queued items, oldest first (created_at ASC) — drain order. */
    suspend fun loadOutbox(): List<OutboxItem>

    /** Set an item's status ('pending'/'sending'/'failed') + last error. */
    suspend fun updateOutboxStatus(id: Long, status: String, error: String?)

    /** Bump the retry counter after a failed attempt. */
    suspend fun incrementOutboxAttempts(id: Long)

    /** Remove a delivered item from the queue. */
    suspend fun deleteOutboxItem(id: Long)

    /** Drop every queued item for a contact (called on contact deletion). */
    suspend fun deleteOutboxForContact(contactFingerprint: String)

    /** Drop every queued item for a group (called on group deletion). */
    suspend fun deleteOutboxForGroup(groupId: String)

    /** Reset a failed item (attempts=0, status=pending) so a drain retries it. */
    suspend fun resetOutboxByUuid(messageUuid: String)

    /** Drop a queued item by its message UUID (manual "Delete"). */
    suspend fun deleteOutboxByUuid(messageUuid: String)

    /** Delete a 1:1 message row by UUID (manual delete of a failed message). */
    suspend fun deleteMessageByUuid(messageUuid: String)

    /** Delete a group message row by UUID (manual delete of a failed message). */
    suspend fun deleteGroupMessageByUuid(messageUuid: String)

    /** Wipe the whole queue — part of [panic]. */
    suspend fun clearOutbox()

    /**
     * Full-text search (FTS5) across all 1:1 + group message bodies
     * (#60). [query] is a raw user string; the implementation wraps
     * it into an FTS5 prefix MATCH. System messages are excluded.
     * Returns newest-first, capped. Local-only — never hits a server.
     */
    suspend fun searchMessages(query: String): List<SearchResult>

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
    /**
     * Local-only fingerprint verification flag. See Contact.sq's
     * `verified` column for full semantics.
     */
    val verified: Boolean = false,
    /**
     * User-set nickname overriding [displayName] in the UI. Null
     * means "use displayName". Local-only. See Contact.sq's
     * `local_display_name` column.
     */
    val localDisplayName: String? = null,
    /** Local-only block flag (#27). See Contact.sq. */
    val blocked: Boolean = false,
    /** Local-only mute flag (#27). See Contact.sq. */
    val muted: Boolean = false,
    /**
     * Whether this contact accepts image attachments (feat/capability-notice).
     * Learned from their `capability_notice`; local hint, defaults true.
     */
    val imagesAccepted: Boolean = true,
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

/**
 * Image-attachment coordinates persisted alongside a message (ADR 029).
 * Bundled so the many message APIs don't sprout six parameters each.
 *
 * - [mediaKey] / [mediaNonce]: the one-time XChaCha20-Poly1305 key + nonce
 *   (raw bytes). The thumbnail nonce is derived from [mediaNonce].
 * - [thumbnail]: ENCRYPTED thumbnail bytes (ct||tag), decrypted at render.
 * - [cachedPath]: filesystem path to the decrypted full image once fetched,
 *   or null until the recipient taps to download (the sender sets it after
 *   upload, since the relay's one-time read won't let it re-fetch its own).
 *
 * The full image bytes never live in the DB — only here, by reference.
 */
internal data class MediaAttachment(
    val mediaId: String,
    val mediaKey: ByteArray,
    val mediaNonce: ByteArray,
    val mediaRelay: String,
    val thumbnail: ByteArray,
    val cachedPath: String? = null,
)

/**
 * One full-text search hit (#60). [conversationId] is a contact
 * fingerprint for 1:1 hits or a group_id for group hits;
 * [isGroup] disambiguates so the UI can route the tap to the right
 * chat screen. [conversationLabel] is the contact/group display
 * name (caller may further apply nickname overrides).
 */
internal data class SearchResult(
    val conversationId: String,
    val conversationLabel: String,
    val isGroup: Boolean,
    val body: String,
    val timestamp: String,
)

internal data class StoredMessage(
    val id: Long,
    val direction: MessageDirection,
    val body: String,
    val timestamp: String,
    val storedAt: String,
    val messageUuid: String? = null,
    val edited: Boolean = false,
    val replyToUuid: String? = null,
    /** Image attachment (ADR 029), or null for a plain text message. */
    val media: MediaAttachment? = null,
    /** Outbound delivery state 'pending'/'sent'/'failed' (ADR 030); null on received. */
    val deliveryStatus: String? = null,
)

/**
 * A queued outbound message awaiting (re)delivery (ADR 030, #59). Stores
 * PLAINTEXT — encryption happens at delivery time in [drainOutbox] so the
 * ratchet advances in real send order. Exactly one of [contactFingerprint]
 * (1:1) / [groupId] (group) is non-null. Image fields are null for text;
 * [fullImage] + [thumbnail] are the already-processed bytes and [mediaKey]
 * / [mediaNonce] the per-image one-time key reused across retries.
 */
internal data class OutboxItem(
    val id: Long = 0,
    val contactFingerprint: String? = null,
    val groupId: String? = null,
    val body: String,
    val fullImage: ByteArray? = null,
    val thumbnail: ByteArray? = null,
    val mediaKey: ByteArray? = null,
    val mediaNonce: ByteArray? = null,
    val replyToUuid: String? = null,
    val messageUuid: String,
    val createdAt: String,
    val attempts: Long = 0,
    val lastError: String? = null,
    val status: String = "pending",
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
    val replyToUuid: String? = null,
    /** Image attachment (ADR 029), or null for a plain text message. */
    val media: MediaAttachment? = null,
    /** Outbound delivery state 'pending'/'sent'/'failed' (ADR 030); null on received. */
    val deliveryStatus: String? = null,
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
