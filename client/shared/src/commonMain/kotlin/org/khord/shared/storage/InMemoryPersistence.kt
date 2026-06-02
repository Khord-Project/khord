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

    // ── Outbox (ADR 030) ────────────────────────────────────────────────────
    private val outbox = mutableListOf<OutboxItem>()
    private var nextOutboxId = 1L

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
        // Preserve pending_payload + verified + local_display_name
        // across re-upserts — their dedicated setters are the only
        // paths allowed to mutate those fields directly.
        val existing = contacts[qr.fingerprint]
        contacts[qr.fingerprint] = ContactInfo(
            qr = qr,
            displayName = displayName,
            status = status,
            pendingPayload = existing?.pendingPayload,
            verified = existing?.verified ?: false,
            localDisplayName = existing?.localDisplayName,
            blocked = existing?.blocked ?: false,
            muted = existing?.muted ?: false,
            imagesAccepted = existing?.imagesAccepted ?: true,
        )
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

    override suspend fun setContactPendingPayload(fingerprint: String, payload: String?) {
        contacts[fingerprint]?.let {
            contacts[fingerprint] = it.copy(pendingPayload = payload)
        }
    }

    override suspend fun setContactVerified(fingerprint: String, verified: Boolean) {
        contacts[fingerprint]?.let {
            contacts[fingerprint] = it.copy(verified = verified)
        }
    }

    override suspend fun isContactVerified(fingerprint: String): Boolean =
        contacts[fingerprint]?.verified == true

    override suspend fun setContactLocalName(fingerprint: String, localName: String?) {
        // Normalise blank → null so "cleared the field" resets to the
        // contact's self-reported name.
        val normalised = localName?.takeIf { it.isNotBlank() }?.trim()
        contacts[fingerprint]?.let {
            contacts[fingerprint] = it.copy(localDisplayName = normalised)
        }
    }

    override suspend fun setContactBlocked(fingerprint: String, blocked: Boolean) {
        contacts[fingerprint]?.let { contacts[fingerprint] = it.copy(blocked = blocked) }
    }

    override suspend fun setContactMuted(fingerprint: String, muted: Boolean) {
        contacts[fingerprint]?.let { contacts[fingerprint] = it.copy(muted = muted) }
    }

    override suspend fun setContactImagesAccepted(fingerprint: String, accepted: Boolean) {
        contacts[fingerprint]?.let { contacts[fingerprint] = it.copy(imagesAccepted = accepted) }
    }

    override suspend fun loadBlockedContacts(): List<ContactInfo> =
        contacts.values.filter { it.blocked }

    override suspend fun deleteContact(fingerprint: String) {
        // Mirror the SQLDelight CASCADE behaviour by removing every
        // sibling map entry keyed off this fingerprint. Messages live
        // in a list-of-pairs keyed by fingerprint; everything else is
        // a direct map lookup.
        contacts.remove(fingerprint)
        sessions.remove(fingerprint)
        messages.removeAll { it.first == fingerprint }
        outbox.removeAll { it.contactFingerprint == fingerprint }
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
        replyToUuid: String?,
        media: MediaAttachment?,
        deliveryStatus: String?,
    ) {
        val msg = StoredMessage(
            id = nextMessageId++,
            direction = direction,
            body = body,
            timestamp = timestamp,
            storedAt = Clock.System.now().toString(),
            messageUuid = messageUuid,
            edited = false,
            replyToUuid = replyToUuid,
            media = media,
            deliveryStatus = deliveryStatus,
        )
        messages += contactFingerprint to msg
    }

    override suspend fun loadMessages(contactFingerprint: String): List<StoredMessage> =
        messages.filter { it.first == contactFingerprint }.map { it.second }.sortedBy { it.id }

    override suspend fun setMessageMediaCachedPath(messageId: Long, cachedPath: String) {
        for (i in messages.indices) {
            val (fp, msg) = messages[i]
            if (msg.id == messageId && msg.media != null) {
                messages[i] = fp to msg.copy(media = msg.media.copy(cachedPath = cachedPath))
                return
            }
        }
    }

    override suspend fun updateMessageDeliveryStatus(messageUuid: String, status: String) {
        for (i in messages.indices) {
            val (fp, msg) = messages[i]
            if (msg.messageUuid == messageUuid) {
                messages[i] = fp to msg.copy(deliveryStatus = status)
                return
            }
        }
    }

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
        outbox.removeAll { it.groupId == groupId }
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
        replyToUuid: String?,
        media: MediaAttachment?,
        deliveryStatus: String?,
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
            replyToUuid = replyToUuid,
            media = media,
            deliveryStatus = deliveryStatus,
        )
        groupMessages += groupId to msg
    }

    override suspend fun loadGroupMessages(groupId: String): List<GroupMessageRecord> =
        groupMessages.filter { it.first == groupId }.map { it.second }.sortedBy { it.id }

    override suspend fun setGroupMessageMediaCachedPath(messageId: Long, cachedPath: String) {
        for (i in groupMessages.indices) {
            val (gid, msg) = groupMessages[i]
            if (msg.id == messageId && msg.media != null) {
                groupMessages[i] = gid to msg.copy(media = msg.media.copy(cachedPath = cachedPath))
                return
            }
        }
    }

    override suspend fun updateGroupMessageDeliveryStatus(messageUuid: String, status: String) {
        for (i in groupMessages.indices) {
            val (gid, msg) = groupMessages[i]
            if (msg.messageUuid == messageUuid) {
                groupMessages[i] = gid to msg.copy(deliveryStatus = status)
                return
            }
        }
    }

    // ── Outbox (ADR 030) ────────────────────────────────────────────────────

    override suspend fun insertOutbox(item: OutboxItem) {
        outbox += item.copy(id = nextOutboxId++)
    }

    override suspend fun loadOutbox(): List<OutboxItem> =
        outbox.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun updateOutboxStatus(id: Long, status: String, error: String?) {
        for (i in outbox.indices) {
            if (outbox[i].id == id) {
                outbox[i] = outbox[i].copy(status = status, lastError = error)
                return
            }
        }
    }

    override suspend fun incrementOutboxAttempts(id: Long) {
        for (i in outbox.indices) {
            if (outbox[i].id == id) {
                outbox[i] = outbox[i].copy(attempts = outbox[i].attempts + 1)
                return
            }
        }
    }

    override suspend fun deleteOutboxItem(id: Long) {
        outbox.removeAll { it.id == id }
    }

    override suspend fun deleteOutboxForContact(contactFingerprint: String) {
        outbox.removeAll { it.contactFingerprint == contactFingerprint }
    }

    override suspend fun deleteOutboxForGroup(groupId: String) {
        outbox.removeAll { it.groupId == groupId }
    }

    override suspend fun resetOutboxByUuid(messageUuid: String) {
        for (i in outbox.indices) {
            if (outbox[i].messageUuid == messageUuid) {
                outbox[i] = outbox[i].copy(attempts = 0, status = "pending", lastError = null)
            }
        }
    }

    override suspend fun deleteOutboxByUuid(messageUuid: String) {
        outbox.removeAll { it.messageUuid == messageUuid }
    }

    override suspend fun deleteMessageByUuid(messageUuid: String) {
        messages.removeAll { it.second.messageUuid == messageUuid }
    }

    override suspend fun deleteGroupMessageByUuid(messageUuid: String) {
        groupMessages.removeAll { it.second.messageUuid == messageUuid }
    }

    override suspend fun clearOutbox() {
        outbox.clear()
    }

    override suspend fun searchMessages(query: String): List<SearchResult> {
        // No FTS engine in the in-memory store — fall back to a simple
        // case-insensitive substring scan. Sufficient for tests; the
        // DbPersistence path uses real FTS5.
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()
        val results = mutableListOf<SearchResult>()
        for ((fp, msg) in messages) {
            if (msg.body.contains("🔄 Session reset")) continue
            if (msg.body.startsWith("Security verification has been reset.")) continue
            if (msg.body.lowercase().contains(needle)) {
                val c = contacts[fp]
                val label = c?.localDisplayName?.takeIf { it.isNotBlank() }
                    ?: c?.displayName?.takeIf { it.isNotEmpty() }
                    ?: (fp.take(8) + "…" + fp.takeLast(8))
                results += SearchResult(fp, label, isGroup = false, body = msg.body,
                    timestamp = msg.timestamp)
            }
        }
        for ((gid, msg) in groupMessages) {
            if (msg.body.lowercase().contains(needle)) {
                val label = groups[gid]?.groupName ?: gid
                results += SearchResult(gid, label, isGroup = true, body = msg.body,
                    timestamp = msg.timestamp)
            }
        }
        return results.sortedByDescending { it.timestamp }
    }

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
        outbox.clear()
        nextOutboxId = 1L
    }

    override suspend fun close() {
        // No-op for the in-memory variant.
    }
}
