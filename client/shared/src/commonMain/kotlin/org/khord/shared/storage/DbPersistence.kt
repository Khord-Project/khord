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

    // ── Contacts ────────────────────────────────────────────────────────────

    override suspend fun saveContact(qr: QrPayload, displayName: String) {
        db.contactQueries.upsertContact(
            fingerprint = qr.fingerprint,
            ed25519_public = identityKeyBase64ToBytes(qr.identityKey),
            key_server_url = qr.keyServer,
            relay_server_url = qr.relayServer,
            contact_mailbox = qr.relayMailbox,
            stored_at = now(),
            display_name = displayName,
        )
    }

    override suspend fun loadContact(fingerprint: String): ContactInfo? {
        val row = db.contactQueries
            .selectContactByFingerprint(fingerprint).executeAsOneOrNull() ?: return null
        return ContactInfo(
            qr = rowToQr(row.fingerprint, row.ed25519_public, row.key_server_url,
                         row.relay_server_url, row.contact_mailbox),
            displayName = row.display_name,
        )
    }

    override suspend fun loadAllContacts(): List<ContactInfo> =
        db.contactQueries.selectAllContacts().executeAsList().map {
            ContactInfo(
                qr = rowToQr(it.fingerprint, it.ed25519_public, it.key_server_url,
                             it.relay_server_url, it.contact_mailbox),
                displayName = it.display_name,
            )
        }

    override suspend fun updateContactDisplayName(fingerprint: String, displayName: String) {
        db.contactQueries.updateContactDisplayName(displayName, fingerprint)
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
    ) {
        db.messageQueries.insertMessage(
            contact_fingerprint = contactFingerprint,
            direction = direction.wire,
            body = body,
            timestamp = timestamp,
            stored_at = now(),
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
            )
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
