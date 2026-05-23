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

    // ── Group state (ADR 023) ───────────────────────────────────────────────
    private val groups = mutableMapOf<String, GroupRecord>()
    private val groupMembers = mutableMapOf<String, MutableMap<String, String>>() // groupId → fingerprint → displayName
    private val groupMessages = mutableListOf<Pair<String, GroupMessageRecord>>()
    private var nextGroupMessageId = 1L

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
    override suspend fun deleteAllOneTimePreKeys() {
        // Zero each secret before removing the map entry — the in-memory
        // variant doesn't have to keep ByteArray contents in-process any
        // longer than necessary.
        for (secret in opks.values) secret.fill(0)
        opks.clear()
    }

    override suspend fun saveContact(
        qr: QrPayload,
        displayName: String,
        status: ContactStatus,
    ) {
        contacts[qr.fingerprint] = ContactInfo(qr, displayName, status)
    }
    override suspend fun loadContact(fingerprint: String): ContactInfo? = contacts[fingerprint]
    override suspend fun loadAllContacts(): List<ContactInfo> = contacts.values.toList()
    override suspend fun loadPendingContacts(): List<ContactInfo> =
        contacts.values.filter { it.status == ContactStatus.PENDING }
    override suspend fun updateContactDisplayName(fingerprint: String, displayName: String) {
        contacts[fingerprint]?.let { contacts[fingerprint] = it.copy(displayName = displayName) }
    }
    override suspend fun setContactStatus(fingerprint: String, status: ContactStatus) {
        contacts[fingerprint]?.let { contacts[fingerprint] = it.copy(status = status) }
    }

    override suspend fun deleteContact(fingerprint: String) {
        // Mirror the SQLDelight CASCADE behaviour by removing every
        // sibling map entry keyed off this fingerprint. Messages live
        // in a list-of-pairs keyed by fingerprint; everything else is
        // a direct map lookup.
        contacts.remove(fingerprint)
        sessions.remove(fingerprint)
        messages.removeAll { it.first == fingerprint }
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
        messageUuid: String?,
    ) {
        val msg = StoredMessage(
            id = nextMessageId++,
            direction = direction,
            body = body,
            timestamp = timestamp,
            storedAt = Clock.System.now().toString(),
            messageUuid = messageUuid,
            edited = false,
        )
        messages += contactFingerprint to msg
    }

    override suspend fun loadMessages(contactFingerprint: String): List<StoredMessage> =
        messages.filter { it.first == contactFingerprint }.map { it.second }.sortedBy { it.id }

    override suspend fun findMessageByUuid(messageUuid: String): MessageLookup? {
        if (messageUuid.isEmpty()) return null
        for ((fp, msg) in messages) {
            if (msg.messageUuid == messageUuid) {
                return MessageLookup(
                    id = msg.id,
                    contactFingerprint = fp,
                    direction = msg.direction,
                    messageUuid = messageUuid,
                )
            }
        }
        return null
    }

    override suspend fun updateMessageBodyByUuid(messageUuid: String, newBody: String) {
        for (i in messages.indices) {
            val (fp, msg) = messages[i]
            if (msg.messageUuid == messageUuid) {
                messages[i] = fp to msg.copy(body = newBody, edited = true)
                return
            }
        }
    }

    // ── Groups (ADR 023) ────────────────────────────────────────────────────

    override suspend fun saveGroup(
        groupId: String,
        groupName: String,
        createdByFingerprint: String,
        isAdmin: Boolean,
    ) {
        val existing = groups[groupId]
        groups[groupId] = GroupRecord(
            groupId = groupId,
            groupName = groupName,
            createdByFingerprint = createdByFingerprint,
            isAdmin = isAdmin,
            createdAt = existing?.createdAt ?: Clock.System.now().toString(),
        )
    }

    override suspend fun loadGroups(): List<GroupRecord> =
        groups.values.sortedBy { it.createdAt }

    override suspend fun loadGroup(groupId: String): GroupRecord? = groups[groupId]

    override suspend fun updateGroupName(groupId: String, newName: String) {
        groups[groupId]?.let { groups[groupId] = it.copy(groupName = newName) }
    }

    override suspend fun deleteGroup(groupId: String) {
        groups.remove(groupId)
        groupMembers.remove(groupId)
        groupMessages.removeAll { it.first == groupId }
    }

    override suspend fun addGroupMember(
        groupId: String,
        fingerprint: String,
        displayName: String,
    ) {
        groupMembers.getOrPut(groupId) { mutableMapOf() }[fingerprint] = displayName
    }

    override suspend fun removeGroupMember(groupId: String, fingerprint: String) {
        groupMembers[groupId]?.remove(fingerprint)
    }

    override suspend fun loadGroupMembers(groupId: String): List<GroupMemberRecord> =
        groupMembers[groupId].orEmpty().entries
            .map { GroupMemberRecord(it.key, it.value) }
            .sortedBy { it.fingerprint }

    override suspend fun saveGroupMessage(
        groupId: String,
        senderFingerprint: String,
        senderDisplayName: String,
        body: String,
        timestamp: String,
        direction: MessageDirection,
        messageUuid: String?,
    ) {
        val msg = GroupMessageRecord(
            id = nextGroupMessageId++,
            senderFingerprint = senderFingerprint,
            senderDisplayName = senderDisplayName,
            body = body,
            timestamp = timestamp,
            direction = direction,
            storedAt = Clock.System.now().toString(),
            messageUuid = messageUuid,
            edited = false,
        )
        groupMessages += groupId to msg
    }

    override suspend fun loadGroupMessages(groupId: String): List<GroupMessageRecord> =
        groupMessages.filter { it.first == groupId }.map { it.second }.sortedBy { it.id }

    override suspend fun findGroupMessageByUuid(messageUuid: String): GroupMessageLookup? {
        if (messageUuid.isEmpty()) return null
        for ((gid, msg) in groupMessages) {
            if (msg.messageUuid == messageUuid) {
                return GroupMessageLookup(
                    id = msg.id,
                    groupId = gid,
                    senderFingerprint = msg.senderFingerprint,
                    messageUuid = messageUuid,
                )
            }
        }
        return null
    }

    override suspend fun updateGroupMessageBodyByUuid(messageUuid: String, newBody: String) {
        for (i in groupMessages.indices) {
            val (gid, msg) = groupMessages[i]
            if (msg.messageUuid == messageUuid) {
                groupMessages[i] = gid to msg.copy(body = newBody, edited = true)
                return
            }
        }
    }

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
        groups.clear()
        groupMembers.clear()
        groupMessages.clear()
        nextGroupMessageId = 1L
    }

    override suspend fun close() {
        // No-op for the in-memory variant.
    }
}
