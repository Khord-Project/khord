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

    // ── Pre-keys ────────────────────────────────────────────────────────────

    suspend fun saveSignedPreKey(record: SignedPreKeyRecord)
    suspend fun loadSignedPreKey(): SignedPreKeyRecord?

    /** Add a batch of OPK secrets at registration. */
    suspend fun saveOpkBatch(secretsByKeyId: Map<Int, ByteArray>)
    suspend fun loadAllOpkSecrets(): Map<Int, ByteArray>
    /** Remove an OPK secret after a successful X3DH respond consumed it. */
    suspend fun deleteOneTimePreKey(keyId: Int)

    // ── Contacts ────────────────────────────────────────────────────────────

    suspend fun saveContact(qr: QrPayload)
    suspend fun loadContact(fingerprint: String): QrPayload?
    suspend fun loadAllContacts(): List<QrPayload>

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
