package org.khord.shared.storage

import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.Clock
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.crypto.ratchet.RatchetState
import org.khord.shared.protocol.wire.QrPayload
import org.khord.shared.storage.db.KhordDatabase

/**
 * SQLDelight-backed persistence. Construction:
 *
 *   val driver = DriverFactory().createDriver(path, passphrase)
 *   val persistence = DbPersistence(driver, path)
 *
 * The `path` is captured so [panic] can delete the file. Pass `":memory:"`
 * for an ephemeral database (tests). Pass an absolute file path for the
 * production case.
 *
 * A note on consistency: the in-memory state in [Messaging] is the source
 * of truth at runtime; we mirror writes here on every state change. If a
 * write throws, the caller surfaces the error and the in-memory state
 * stays mutated — on restart, the rebuilt in-memory state will be missing
 * the failed write. Acceptable for the PoC; production wants
 * write-ahead. (See investigation Q7 issue 8.)
 */
internal class DbPersistence(
    private val driver: SqlDriver,
    private val databasePath: String,
    private val deleteFile: (String) -> Unit,
) : Persistence {

    private val db: KhordDatabase = KhordDatabase(driver)

    private fun now(): String = Clock.System.now().toString()

    // ── Identity ────────────────────────────────────────────────────────────

    override suspend fun loadIdentity(): IdentityRecord? {
        val row = db.identityQueries.selectIdentity().executeAsOneOrNull() ?: return null
        return IdentityRecord(
            identity = IdentityKey(
                ed25519PublicKey = row.ed25519_public,
                ed25519SecretKey = row.ed25519_secret,
                x25519PublicKey = row.x25519_public,
                x25519SecretKey = row.x25519_secret,
                fingerprint = row.fingerprint,
            ),
            keyServerUrl = row.key_server_url,
            relayServerUrl = row.relay_server_url,
            createdAt = row.created_at,
            registeredAtServer = row.registered_at_server != 0L,
            displayName = row.display_name,
        )
    }

    override suspend fun saveIdentity(record: IdentityRecord) {
        db.identityQueries.upsertIdentity(
            ed25519_public = record.identity.ed25519PublicKey,
            ed25519_secret = record.identity.ed25519SecretKey,
            x25519_public = record.identity.x25519PublicKey,
            x25519_secret = record.identity.x25519SecretKey,
            fingerprint = record.identity.fingerprint,
            key_server_url = record.keyServerUrl,
            relay_server_url = record.relayServerUrl,
            created_at = record.createdAt,
            registered_at_server = if (record.registeredAtServer) 1L else 0L,
            display_name = record.displayName,
        )
    }

    override suspend fun markRegisteredAtServer() {
        db.identityQueries.markRegisteredAtServer()
    }

    override suspend fun updateMyDisplayName(displayName: String) {
        db.identityQueries.updateDisplayName(displayName)
    }

    // ── Pre-keys ────────────────────────────────────────────────────────────

    override suspend fun saveSignedPreKey(record: SignedPreKeyRecord) {
        db.preKeysQueries.upsertSignedPreKey(
            key_id = record.keyId.toLong(),
            public_key = record.publicKey,
            secret_key = record.secretKey,
        )
    }

    override suspend fun loadSignedPreKey(): SignedPreKeyRecord? {
        val row = db.preKeysQueries.selectSignedPreKey().executeAsOneOrNull() ?: return null
        return SignedPreKeyRecord(
            keyId = row.key_id.toInt(),
            publicKey = row.public_key,
            secretKey = row.secret_key,
        )
    }

    override suspend fun saveOpkBatch(secretsByKeyId: Map<Int, ByteArray>) {
        db.transaction {
            for ((id, secret) in secretsByKeyId) {
                db.preKeysQueries.insertOpkSecret(id.toLong(), secret)
            }
        }
    }

    override suspend fun loadAllOpkSecrets(): Map<Int, ByteArray> =
        db.preKeysQueries.selectAllOpkSecrets().executeAsList()
            .associate { it.key_id.toInt() to it.secret_key }

    override suspend fun deleteOneTimePreKey(keyId: Int) {
        db.preKeysQueries.deleteOpkSecret(keyId.toLong())
    }

    override suspend fun deleteAllOneTimePreKeys() {
        // The SQL query already existed; this just exposes it through
        // the Persistence interface for the registration-retry fix.
        db.preKeysQueries.deleteAllOpkSecrets()
    }

    // ── Contacts ────────────────────────────────────────────────────────────

    override suspend fun saveContact(
        qr: QrPayload,
        displayName: String,
        status: ContactStatus,
    ) {
        // Preserve any pending_payload + verified across re-upserts.
        // INSERT OR REPLACE nulls every column, so read the current
        // values first and pass them back in. Only the dedicated
        // [setContactPendingPayload] / [setContactVerified] setters
        // are allowed to mutate those fields directly.
        db.transaction {
            val existing = db.contactQueries
                .selectContactByFingerprint(qr.fingerprint)
                .executeAsOneOrNull()
            db.contactQueries.upsertContact(
                fingerprint = qr.fingerprint,
                ed25519_public = identityKeyBase64ToBytes(qr.identityKey),
                key_server_url = qr.keyServer,
                relay_server_url = qr.relayServer,
                contact_mailbox = qr.relayMailbox,
                stored_at = now(),
                display_name = displayName,
                status = status.wireValue(),
                pending_payload = existing?.pending_payload,
                verified = existing?.verified ?: 0L,
                local_display_name = existing?.local_display_name,
                blocked = existing?.blocked ?: 0L,
                muted = existing?.muted ?: 0L,
                // Preserve the learned image preference across re-upserts;
                // only the capability-notice receive path mutates it via
                // [setContactImagesAccepted]. Default 1 (accepts) for new rows.
                images_accepted = existing?.images_accepted ?: 1L,
            )
        }
    }

    private fun rowToContactInfo(
        fingerprint: String, ed25519Public: ByteArray, keyServerUrl: String,
        relayServerUrl: String, contactMailbox: String, displayNameCol: String,
        statusCol: String, pendingPayload: String?, verifiedCol: Long,
        localDisplayName: String?, blockedCol: Long, mutedCol: Long,
        imagesAcceptedCol: Long,
    ): ContactInfo = ContactInfo(
        qr = rowToQr(fingerprint, ed25519Public, keyServerUrl, relayServerUrl, contactMailbox),
        displayName = displayNameCol,
        status = ContactStatus.fromWire(statusCol),
        pendingPayload = pendingPayload,
        verified = verifiedCol != 0L,
        localDisplayName = localDisplayName,
        blocked = blockedCol != 0L,
        muted = mutedCol != 0L,
        imagesAccepted = imagesAcceptedCol != 0L,
    )

    override suspend fun loadContact(fingerprint: String): ContactInfo? {
        val row = db.contactQueries
            .selectContactByFingerprint(fingerprint).executeAsOneOrNull() ?: return null
        return rowToContactInfo(
            row.fingerprint, row.ed25519_public, row.key_server_url,
            row.relay_server_url, row.contact_mailbox, row.display_name,
            row.status, row.pending_payload, row.verified,
            row.local_display_name, row.blocked, row.muted, row.images_accepted,
        )
    }

    override suspend fun loadAllContacts(): List<ContactInfo> =
        db.contactQueries.selectAllContacts().executeAsList().map {
            rowToContactInfo(
                it.fingerprint, it.ed25519_public, it.key_server_url,
                it.relay_server_url, it.contact_mailbox, it.display_name,
                it.status, it.pending_payload, it.verified,
                it.local_display_name, it.blocked, it.muted, it.images_accepted,
            )
        }

    override suspend fun loadPendingContacts(): List<ContactInfo> =
        db.contactQueries.selectPendingContacts().executeAsList().map {
            rowToContactInfo(
                it.fingerprint, it.ed25519_public, it.key_server_url,
                it.relay_server_url, it.contact_mailbox, it.display_name,
                it.status, it.pending_payload, it.verified,
                it.local_display_name, it.blocked, it.muted, it.images_accepted,
            )
        }

    override suspend fun loadBlockedContacts(): List<ContactInfo> =
        db.contactQueries.selectBlockedContacts().executeAsList().map {
            rowToContactInfo(
                it.fingerprint, it.ed25519_public, it.key_server_url,
                it.relay_server_url, it.contact_mailbox, it.display_name,
                it.status, it.pending_payload, it.verified,
                it.local_display_name, it.blocked, it.muted, it.images_accepted,
            )
        }

    override suspend fun updateContactDisplayName(fingerprint: String, displayName: String) {
        db.contactQueries.updateContactDisplayName(displayName, fingerprint)
    }

    override suspend fun setContactStatus(fingerprint: String, status: ContactStatus) {
        db.contactQueries.setContactStatus(status.wireValue(), fingerprint)
    }

    override suspend fun setContactPendingPayload(fingerprint: String, payload: String?) {
        db.contactQueries.setContactPendingPayload(payload, fingerprint)
    }

    override suspend fun setContactVerified(fingerprint: String, verified: Boolean) {
        db.contactQueries.setContactVerified(if (verified) 1L else 0L, fingerprint)
    }

    override suspend fun isContactVerified(fingerprint: String): Boolean =
        (db.contactQueries.selectContactByFingerprint(fingerprint)
            .executeAsOneOrNull()?.verified ?: 0L) != 0L

    override suspend fun setContactLocalName(fingerprint: String, localName: String?) {
        val normalised = localName?.takeIf { it.isNotBlank() }?.trim()
        db.contactQueries.setContactLocalName(normalised, fingerprint)
    }

    override suspend fun setContactBlocked(fingerprint: String, blocked: Boolean) {
        db.contactQueries.setContactBlocked(if (blocked) 1L else 0L, fingerprint)
    }

    override suspend fun setContactMuted(fingerprint: String, muted: Boolean) {
        db.contactQueries.setContactMuted(if (muted) 1L else 0L, fingerprint)
    }

    override suspend fun setContactImagesAccepted(fingerprint: String, accepted: Boolean) {
        db.contactQueries.setContactImagesAccepted(if (accepted) 1L else 0L, fingerprint)
    }

    override suspend fun deleteContact(fingerprint: String) {
        // Explicit transactional delete of messages + session + the
        // contact row. The schema declares ON DELETE CASCADE on the
        // session and message FKs, but the Android SQLCipher driver
        // doesn't enable `PRAGMA foreign_keys=ON` (only the JVM
        // driver does), so CASCADE silently no-ops there. Doing the
        // deletes explicitly in a transaction matches the
        // [deleteGroup] convention and makes the contract independent
        // of FK pragma state.
        db.transaction {
            db.messageQueries.deleteMessagesForContact(fingerprint)
            // Drop any queued (undelivered) messages for this contact too —
            // the outbox has no FK to contact, and retrying into a
            // torn-down session would fail forever (ADR 030).
            db.outboxQueries.deleteOutboxForContact(fingerprint)
            db.sessionQueries.deleteSession(fingerprint)
            db.contactQueries.deleteContact(fingerprint)
        }
    }

    private fun rowToQr(
        fingerprint: String, ed25519: ByteArray, ks: String, rs: String, mailbox: String,
    ): QrPayload = QrPayload(
        identityKey = bytesToBase64(ed25519),
        fingerprint = fingerprint,
        keyServer = ks,
        relayServer = rs,
        relayMailbox = mailbox,
    )

    // ── Pending mailboxes ───────────────────────────────────────────────────

    override suspend fun savePendingMailbox(mailboxId: String, bearerToken: String) {
        db.pendingMailboxQueries.upsertPendingMailbox(mailboxId, bearerToken, now())
    }

    override suspend fun loadPendingMailboxes(): Map<String, String> =
        db.pendingMailboxQueries.selectAllPendingMailboxes().executeAsList()
            .associate { it.mailbox_id to it.bearer_token }

    override suspend fun deletePendingMailbox(mailboxId: String) {
        db.pendingMailboxQueries.deletePendingMailbox(mailboxId)
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    override suspend fun saveSession(record: SessionRecord) {
        db.sessionQueries.upsertSession(
            contact_fingerprint = record.contactFingerprint,
            inbound_mailbox = record.inboundMailbox,
            inbound_bearer_token = record.inboundBearerToken,
            outbound_mailbox = record.outboundMailbox,
            outbound_relay_server = record.outboundRelayServer,
            associated_data = record.associatedData,
            ratchet_state = RatchetStateSerializer.serialize(record.ratchetState),
            last_fetched_sequence = record.lastFetchedSequence,
            updated_at = record.updatedAt,
        )
    }

    override suspend fun loadSession(contactFingerprint: String): SessionRecord? {
        val row = db.sessionQueries
            .selectSessionByFingerprint(contactFingerprint).executeAsOneOrNull() ?: return null
        return rowToSessionRecord(row)
    }

    override suspend fun loadAllSessions(): List<SessionRecord> =
        db.sessionQueries.selectAllSessions().executeAsList().map(::rowToSessionRecord)

    private fun rowToSessionRecord(row: org.khord.shared.storage.db.Session): SessionRecord =
        SessionRecord(
            contactFingerprint = row.contact_fingerprint,
            inboundMailbox = row.inbound_mailbox,
            inboundBearerToken = row.inbound_bearer_token,
            outboundMailbox = row.outbound_mailbox,
            outboundRelayServer = row.outbound_relay_server,
            associatedData = row.associated_data,
            ratchetState = RatchetStateSerializer.deserialize(row.ratchet_state),
            lastFetchedSequence = row.last_fetched_sequence,
            updatedAt = row.updated_at,
        )

    override suspend fun updateRatchetState(contactFingerprint: String, state: RatchetState) {
        db.sessionQueries.updateRatchetState(
            ratchet_state = RatchetStateSerializer.serialize(state),
            updated_at = now(),
            contact_fingerprint = contactFingerprint,
        )
    }

    override suspend fun updateLastFetchedSequence(contactFingerprint: String, sequence: Long) {
        db.sessionQueries.updateLastFetchedSequence(
            last_fetched_sequence = sequence,
            updated_at = now(),
            contact_fingerprint = contactFingerprint,
        )
    }

    // ── Messages ────────────────────────────────────────────────────────────

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
        db.messageQueries.insertMessage(
            contact_fingerprint = contactFingerprint,
            direction = direction.wire,
            body = body,
            timestamp = timestamp,
            stored_at = now(),
            message_uuid = messageUuid,
            reply_to_uuid = replyToUuid,
            media_id = media?.mediaId,
            media_key = media?.mediaKey,
            media_nonce = media?.mediaNonce,
            media_relay = media?.mediaRelay,
            thumbnail = media?.thumbnail,
            media_cached_path = media?.cachedPath,
            delivery_status = deliveryStatus,
        )
    }

    override suspend fun loadMessages(contactFingerprint: String): List<StoredMessage> =
        db.messageQueries.selectMessagesByContact(contactFingerprint).executeAsList().map {
            StoredMessage(
                id = it.id,
                direction = directionFromWire(it.direction),
                body = it.body,
                timestamp = it.timestamp,
                storedAt = it.stored_at,
                messageUuid = it.message_uuid,
                edited = it.edited != 0L,
                replyToUuid = it.reply_to_uuid,
                media = mediaFrom(
                    it.media_id, it.media_key, it.media_nonce,
                    it.media_relay, it.thumbnail, it.media_cached_path,
                ),
                deliveryStatus = it.delivery_status,
            )
        }

    override suspend fun setMessageMediaCachedPath(messageId: Long, cachedPath: String) {
        db.messageQueries.updateMessageMediaCachedPath(cachedPath, messageId)
    }

    override suspend fun updateMessageDeliveryStatus(messageUuid: String, status: String) {
        db.messageQueries.updateMessageDeliveryStatus(status, messageUuid)
    }

    override suspend fun findMessageByUuid(messageUuid: String): MessageLookup? {
        if (messageUuid.isEmpty()) return null
        val row = db.messageQueries.selectMessageByUuid(messageUuid).executeAsOneOrNull()
            ?: return null
        val storedUuid = row.message_uuid ?: return null
        return MessageLookup(
            id = row.id,
            contactFingerprint = row.contact_fingerprint,
            direction = directionFromWire(row.direction),
            messageUuid = storedUuid,
        )
    }

    override suspend fun updateMessageBodyByUuid(messageUuid: String, newBody: String) {
        db.messageQueries.updateMessageBodyByUuid(newBody, messageUuid)
    }

    // ── Groups (ADR 023) ────────────────────────────────────────────────────

    override suspend fun saveGroup(
        groupId: String,
        groupName: String,
        createdByFingerprint: String,
        isAdmin: Boolean,
    ) {
        // Preserve created_at on upsert by reading the existing row first.
        val existing = db.groupQueries.selectGroup(groupId).executeAsOneOrNull()
        db.groupQueries.upsertGroup(
            group_id = groupId,
            group_name = groupName,
            created_by_fingerprint = createdByFingerprint,
            is_admin = if (isAdmin) 1L else 0L,
            created_at = existing?.created_at ?: now(),
        )
    }

    override suspend fun loadGroups(): List<GroupRecord> =
        db.groupQueries.selectAllGroups().executeAsList().map {
            GroupRecord(
                groupId = it.group_id,
                groupName = it.group_name,
                createdByFingerprint = it.created_by_fingerprint,
                isAdmin = it.is_admin != 0L,
                createdAt = it.created_at,
            )
        }

    override suspend fun loadGroup(groupId: String): GroupRecord? =
        db.groupQueries.selectGroup(groupId).executeAsOneOrNull()?.let {
            GroupRecord(
                groupId = it.group_id,
                groupName = it.group_name,
                createdByFingerprint = it.created_by_fingerprint,
                isAdmin = it.is_admin != 0L,
                createdAt = it.created_at,
            )
        }

    override suspend fun updateGroupName(groupId: String, newName: String) {
        db.groupQueries.updateGroupName(newName, groupId)
    }

    override suspend fun deleteGroup(groupId: String) {
        // Explicit transactional delete of messages + members + the group
        // row. The schema declares ON DELETE CASCADE on the FKs, but
        // SQLite enforces those only when `PRAGMA foreign_keys=ON` is
        // active on the connection running the parent delete — and
        // JDBC connection-pool behaviour means we can't fully rely on
        // the pragma sticking across statements. Doing the deletes
        // explicitly in a transaction makes the contract independent
        // of FK pragma state.
        db.transaction {
            db.groupMessageQueries.deleteGroupMessages(groupId)
            db.groupMemberQueries.deleteAllGroupMembers(groupId)
            // Drop queued (undelivered) group messages too — no FK, and
            // retaining their plaintext/image BLOBs / phantom-delivering
            // them after the group is gone is wrong (ADR 030).
            db.outboxQueries.deleteOutboxForGroup(groupId)
            db.groupQueries.deleteGroup(groupId)
        }
    }

    override suspend fun addGroupMember(
        groupId: String,
        fingerprint: String,
        displayName: String,
    ) {
        db.groupMemberQueries.upsertGroupMember(groupId, fingerprint, displayName)
    }

    override suspend fun removeGroupMember(groupId: String, fingerprint: String) {
        db.groupMemberQueries.deleteGroupMember(groupId, fingerprint)
    }

    override suspend fun loadGroupMembers(groupId: String): List<GroupMemberRecord> =
        db.groupMemberQueries.selectGroupMembers(groupId).executeAsList().map {
            GroupMemberRecord(it.fingerprint, it.display_name)
        }

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
        db.groupMessageQueries.insertGroupMessage(
            group_id = groupId,
            sender_fingerprint = senderFingerprint,
            sender_display_name = senderDisplayName,
            body = body,
            timestamp = timestamp,
            direction = direction.wire,
            stored_at = now(),
            message_uuid = messageUuid,
            reply_to_uuid = replyToUuid,
            media_id = media?.mediaId,
            media_key = media?.mediaKey,
            media_nonce = media?.mediaNonce,
            media_relay = media?.mediaRelay,
            thumbnail = media?.thumbnail,
            media_cached_path = media?.cachedPath,
            delivery_status = deliveryStatus,
        )
    }

    override suspend fun setGroupMessageMediaCachedPath(messageId: Long, cachedPath: String) {
        db.groupMessageQueries.updateGroupMessageMediaCachedPath(cachedPath, messageId)
    }

    override suspend fun updateGroupMessageDeliveryStatus(messageUuid: String, status: String) {
        db.groupMessageQueries.updateGroupMessageDeliveryStatus(status, messageUuid)
    }

    // ── Outbox (ADR 030) ─────────────────────────────────────────────────────

    override suspend fun insertOutbox(item: OutboxItem) {
        db.outboxQueries.insertOutbox(
            contact_fingerprint = item.contactFingerprint,
            group_id = item.groupId,
            body = item.body,
            full_image = item.fullImage,
            thumbnail = item.thumbnail,
            media_key = item.mediaKey,
            media_nonce = item.mediaNonce,
            reply_to_uuid = item.replyToUuid,
            message_uuid = item.messageUuid,
            created_at = item.createdAt,
            attempts = item.attempts,
            last_error = item.lastError,
            status = item.status,
        )
    }

    override suspend fun loadOutbox(): List<OutboxItem> =
        db.outboxQueries.selectOutbox().executeAsList().map {
            OutboxItem(
                id = it.id,
                contactFingerprint = it.contact_fingerprint,
                groupId = it.group_id,
                body = it.body,
                fullImage = it.full_image,
                thumbnail = it.thumbnail,
                mediaKey = it.media_key,
                mediaNonce = it.media_nonce,
                replyToUuid = it.reply_to_uuid,
                messageUuid = it.message_uuid,
                createdAt = it.created_at,
                attempts = it.attempts,
                lastError = it.last_error,
                status = it.status,
            )
        }

    override suspend fun updateOutboxStatus(id: Long, status: String, error: String?) {
        db.outboxQueries.updateOutboxStatus(status, error, id)
    }

    override suspend fun incrementOutboxAttempts(id: Long) {
        db.outboxQueries.incrementOutboxAttempts(id)
    }

    override suspend fun deleteOutboxItem(id: Long) {
        db.outboxQueries.deleteOutboxItem(id)
    }

    override suspend fun deleteOutboxForContact(contactFingerprint: String) {
        db.outboxQueries.deleteOutboxForContact(contactFingerprint)
    }

    override suspend fun deleteOutboxForGroup(groupId: String) {
        db.outboxQueries.deleteOutboxForGroup(groupId)
    }

    override suspend fun resetOutboxByUuid(messageUuid: String) {
        db.outboxQueries.resetOutboxByUuid(messageUuid)
    }

    override suspend fun deleteOutboxByUuid(messageUuid: String) {
        db.outboxQueries.deleteOutboxByUuid(messageUuid)
    }

    override suspend fun deleteMessageByUuid(messageUuid: String) {
        db.messageQueries.deleteMessageByUuid(messageUuid)
    }

    override suspend fun deleteGroupMessageByUuid(messageUuid: String) {
        db.groupMessageQueries.deleteGroupMessageByUuid(messageUuid)
    }

    override suspend fun clearOutbox() {
        db.outboxQueries.clearOutbox()
    }

    /**
     * Reassemble a [MediaAttachment] from a row's media columns. Returns
     * null unless the required fields are all present (a plain text row has
     * them all null; cachedPath alone may be null on an attachment that
     * hasn't been downloaded yet).
     */
    private fun mediaFrom(
        mediaId: String?,
        mediaKey: ByteArray?,
        mediaNonce: ByteArray?,
        mediaRelay: String?,
        thumbnail: ByteArray?,
        cachedPath: String?,
    ): MediaAttachment? =
        if (mediaId != null && mediaKey != null && mediaNonce != null &&
            mediaRelay != null && thumbnail != null
        ) {
            MediaAttachment(mediaId, mediaKey, mediaNonce, mediaRelay, thumbnail, cachedPath)
        } else {
            null
        }

    override suspend fun findGroupMessageByUuid(messageUuid: String): GroupMessageLookup? {
        if (messageUuid.isEmpty()) return null
        val row = db.groupMessageQueries.selectGroupMessageByUuid(messageUuid)
            .executeAsOneOrNull() ?: return null
        val storedUuid = row.message_uuid ?: return null
        return GroupMessageLookup(
            id = row.id,
            groupId = row.group_id,
            senderFingerprint = row.sender_fingerprint,
            messageUuid = storedUuid,
        )
    }

    override suspend fun updateGroupMessageBodyByUuid(messageUuid: String, newBody: String) {
        db.groupMessageQueries.updateGroupMessageBodyByUuid(newBody, messageUuid)
    }

    override suspend fun loadGroupMessages(groupId: String): List<GroupMessageRecord> =
        db.groupMessageQueries.selectGroupMessages(groupId).executeAsList().map {
            GroupMessageRecord(
                id = it.id,
                senderFingerprint = it.sender_fingerprint,
                senderDisplayName = it.sender_display_name,
                body = it.body,
                timestamp = it.timestamp,
                direction = directionFromWire(it.direction),
                storedAt = it.stored_at,
                messageUuid = it.message_uuid,
                edited = it.edited != 0L,
                replyToUuid = it.reply_to_uuid,
                media = mediaFrom(
                    it.media_id, it.media_key, it.media_nonce,
                    it.media_relay, it.thumbnail, it.media_cached_path,
                ),
                deliveryStatus = it.delivery_status,
            )
        }

    override suspend fun searchMessages(query: String): List<SearchResult> {
        val fts = toFtsPrefixQuery(query) ?: return emptyList()
        val oneToOne = db.messageSearchQueries.searchMessages(fts).executeAsList().map {
            val label = it.local_display_name?.takeIf { n -> n.isNotBlank() }
                ?: it.display_name.takeIf { n -> n.isNotEmpty() }
                ?: (it.fingerprint.take(8) + "…" + it.fingerprint.takeLast(8))
            SearchResult(
                conversationId = it.fingerprint,
                conversationLabel = label,
                isGroup = false,
                body = it.body,
                timestamp = it.timestamp,
            )
        }
        val group = db.messageSearchQueries.searchGroupMessages(fts).executeAsList().map {
            SearchResult(
                conversationId = it.group_id,
                conversationLabel = it.group_name,
                isGroup = true,
                body = it.body,
                timestamp = it.timestamp,
            )
        }
        return (oneToOne + group).sortedByDescending { it.timestamp }.take(100)
    }

    /**
     * Turn a raw user search string into an FTS5 MATCH expression:
     * each whitespace token becomes a double-quoted prefix term
     * (`"foo"* "bar"*`). Quoting neutralises FTS5 operator characters
     * in user input; the trailing `*` gives search-as-you-type prefix
     * matching. Returns null for an all-whitespace query.
     */
    private fun toFtsPrefixQuery(query: String): String? {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }

    // ── Token cache ─────────────────────────────────────────────────────────

    override suspend fun saveKeyServerToken(token: String, expiresAt: String) {
        db.keyServerTokenQueries.upsertToken(token, expiresAt)
    }

    override suspend fun loadKeyServerToken(): KeyServerTokenRecord? =
        db.keyServerTokenQueries.selectToken().executeAsOneOrNull()?.let {
            KeyServerTokenRecord(it.token, it.expires_at)
        }

    override suspend fun clearKeyServerToken() {
        db.keyServerTokenQueries.deleteToken()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override suspend fun panic() {
        // Close-first, delete-second. Two reasons:
        //
        //  1. Robustness against in-flight DB activity. A background poller
        //     (ChatViewModel, AddContactViewModel) might be mid-write when
        //     the user taps panic — the previous "scrub-then-close" pattern
        //     would queue panic's transaction behind the in-flight one,
        //     waiting for the network call to return + the poll's writes
        //     to commit. Several seconds of UI freeze in the worst case.
        //     `driver.close()` releases the SQLite handle synchronously;
        //     any in-flight transaction in another coroutine then fails
        //     with "database closed", which is fine — we're destroying
        //     everything anyway.
        //
        //  2. Forensic-recovery trade-off. The previous per-table scrub
        //     was meant to leave nothing readable in the WAL/journal even
        //     if the file delete failed. For the PoC with a sub-millisecond
        //     unlink right after, the trade-off favours panic latency over
        //     forensic guarantees — an attacker with file-system access
        //     bypasses Khord's protections regardless.
        try { driver.close() } catch (_: Throwable) { /* keep going */ }
        if (databasePath != ":memory:") {
            // Delete the main DB file PLUS every SQLite companion file.
            // SQLite writes these alongside the main file depending on
            // journal mode:
            //   - <db>-wal      Write-Ahead Log (WAL mode)
            //   - <db>-shm      Shared Memory file (WAL mode)
            //   - <db>-journal  Rollback journal (legacy / DELETE mode)
            // Leaving any of them behind lets a re-opened database recover
            // partial state from the journal — observed in the wild as
            // "ghost contacts" reappearing after panic. deleteFile is a
            // no-op on missing paths so we don't need an existence check.
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                try { deleteFile(databasePath + suffix) } catch (_: Throwable) { /* keep going */ }
            }
        }
    }

    override suspend fun close() {
        driver.close()
    }
}

// ─── small helpers ──────────────────────────────────────────────────────────

private val MessageDirection.wire: String get() = when (this) {
    MessageDirection.SENT -> "sent"
    MessageDirection.RECEIVED -> "received"
}

private fun directionFromWire(s: String): MessageDirection = when (s) {
    "sent" -> MessageDirection.SENT
    "received" -> MessageDirection.RECEIVED
    else -> error("invalid direction in DB: $s")
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun identityKeyBase64ToBytes(b64: String): ByteArray =
    kotlin.io.encoding.Base64.decode(b64)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun bytesToBase64(bytes: ByteArray): String =
    kotlin.io.encoding.Base64.encode(bytes)
