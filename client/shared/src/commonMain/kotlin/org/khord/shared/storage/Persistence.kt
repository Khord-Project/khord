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

    suspend fun saveContact(qr: QrPayload, displayName: String = "")
    suspend fun loadContact(fingerprint: String): ContactInfo?
    suspend fun loadAllContacts(): List<ContactInfo>
    suspend fun updateContactDisplayName(fingerprint: String, displayName: String)

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
    )
    suspend fun loadMessages(contactFingerprint: String): List<StoredMessage>

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
    )

    suspend fun loadGroupMessages(groupId: String): List<GroupMessageRecord>

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
 * Stored contact = wire QR + a learned display name. Display name lives
 * outside the QR (the QR JSON is unchanged) because we learn names from
 * the encrypted `reply_info` field, not from the public QR code.
 */
internal data class ContactInfo(
    val qr: QrPayload,
    val displayName: String,
)

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
)
