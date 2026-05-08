package org.khord.shared.storage

import kotlinx.datetime.Clock
import org.khord.shared.crypto.ratchet.RatchetState
import org.khord.shared.protocol.wire.QrPayload

/**
 * Process-local persistence — used by tests that don't need DB durability,
 * and as the default when no DB is configured. Behaves like a faithful
 * but ephemeral version of [DbPersistence].
 */
internal class InMemoryPersistence : Persistence {

    private var identity: IdentityRecord? = null
    private var spk: SignedPreKeyRecord? = null
    private val opks = mutableMapOf<Int, ByteArray>()
    private val contacts = mutableMapOf<String, ContactInfo>()
    private val pendingMailboxes = mutableMapOf<String, String>()
    private val sessions = mutableMapOf<String, SessionRecord>()
    private val messages = mutableListOf<Pair<String, StoredMessage>>()
    private var nextMessageId = 1L
    private var keyServerToken: KeyServerTokenRecord? = null

    override suspend fun loadIdentity(): IdentityRecord? = identity
    override suspend fun saveIdentity(record: IdentityRecord) { identity = record }
    override suspend fun markRegisteredAtServer() {
        identity = identity?.copy(registeredAtServer = true)
    }
    override suspend fun updateMyDisplayName(displayName: String) {
        identity = identity?.copy(displayName = displayName)
    }

    override suspend fun saveSignedPreKey(record: SignedPreKeyRecord) { spk = record }
    override suspend fun loadSignedPreKey(): SignedPreKeyRecord? = spk

    override suspend fun saveOpkBatch(secretsByKeyId: Map<Int, ByteArray>) {
        for ((id, secret) in secretsByKeyId) opks[id] = secret.copyOf()
    }
    override suspend fun loadAllOpkSecrets(): Map<Int, ByteArray> =
        opks.mapValues { it.value.copyOf() }
    override suspend fun deleteOneTimePreKey(keyId: Int) { opks.remove(keyId) }

    override suspend fun saveContact(qr: QrPayload, displayName: String) {
        contacts[qr.fingerprint] = ContactInfo(qr, displayName)
    }
    override suspend fun loadContact(fingerprint: String): ContactInfo? = contacts[fingerprint]
    override suspend fun loadAllContacts(): List<ContactInfo> = contacts.values.toList()
    override suspend fun updateContactDisplayName(fingerprint: String, displayName: String) {
        contacts[fingerprint]?.let { contacts[fingerprint] = it.copy(displayName = displayName) }
    }

    override suspend fun savePendingMailbox(mailboxId: String, bearerToken: String) {
        pendingMailboxes[mailboxId] = bearerToken
    }
    override suspend fun loadPendingMailboxes(): Map<String, String> = pendingMailboxes.toMap()
    override suspend fun deletePendingMailbox(mailboxId: String) {
        pendingMailboxes.remove(mailboxId)
    }

    override suspend fun saveSession(record: SessionRecord) {
        sessions[record.contactFingerprint] = record
    }
    override suspend fun loadSession(contactFingerprint: String): SessionRecord? =
        sessions[contactFingerprint]
    override suspend fun loadAllSessions(): List<SessionRecord> = sessions.values.toList()

    override suspend fun updateRatchetState(contactFingerprint: String, state: RatchetState) {
        sessions[contactFingerprint]?.let {
            sessions[contactFingerprint] = it.copy(
                ratchetState = state,
                updatedAt = Clock.System.now().toString(),
            )
        }
    }

    override suspend fun updateLastFetchedSequence(contactFingerprint: String, sequence: Long) {
        sessions[contactFingerprint]?.let {
            sessions[contactFingerprint] = it.copy(
                lastFetchedSequence = sequence,
                updatedAt = Clock.System.now().toString(),
            )
        }
    }

    override suspend fun saveMessage(
        contactFingerprint: String,
        direction: MessageDirection,
        body: String,
        timestamp: String,
    ) {
        val msg = StoredMessage(
            id = nextMessageId++,
            direction = direction,
            body = body,
            timestamp = timestamp,
            storedAt = Clock.System.now().toString(),
        )
        messages += contactFingerprint to msg
    }

    override suspend fun loadMessages(contactFingerprint: String): List<StoredMessage> =
        messages.filter { it.first == contactFingerprint }.map { it.second }.sortedBy { it.id }

    override suspend fun saveKeyServerToken(token: String, expiresAt: String) {
        keyServerToken = KeyServerTokenRecord(token, expiresAt)
    }
    override suspend fun loadKeyServerToken(): KeyServerTokenRecord? = keyServerToken
    override suspend fun clearKeyServerToken() { keyServerToken = null }

    override suspend fun panic() {
        identity = null
        spk = null
        opks.clear()
        contacts.clear()
        pendingMailboxes.clear()
        sessions.clear()
        messages.clear()
        nextMessageId = 1L
        keyServerToken = null
    }

    override suspend fun close() {
        // No-op for the in-memory variant.
    }
}
