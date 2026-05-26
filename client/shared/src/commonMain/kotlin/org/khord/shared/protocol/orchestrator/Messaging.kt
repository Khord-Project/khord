@file:OptIn(ExperimentalUnsignedTypes::class)

package org.khord.shared.protocol.orchestrator

import io.ktor.client.HttpClient
import kotlinx.datetime.Clock
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.crypto.PreKeyBundle
import org.khord.shared.crypto.PreKeys
import org.khord.shared.crypto.Session
import org.khord.shared.crypto.SignedPreKey
import org.khord.shared.crypto.X25519KeyPair
import org.khord.shared.crypto.X3dh
import org.khord.shared.crypto.OneTimePreKey as CryptoOneTimePreKey
import org.khord.shared.crypto.fromHex
import org.khord.shared.crypto.wipe
import org.khord.shared.protocol.Base64Std
import org.khord.shared.protocol.KhordJson
import org.khord.shared.protocol.ProtocolError
import org.khord.shared.protocol.client.KeyServerClient
import org.khord.shared.protocol.client.Mailboxes
import org.khord.shared.protocol.client.PowMiner
import org.khord.shared.protocol.client.RelayServerClient
import org.khord.shared.protocol.wire.BundleUploadRequest
import org.khord.shared.protocol.wire.GroupMember as WireGroupMember
import org.khord.shared.protocol.wire.InnerPayload
import org.khord.shared.protocol.wire.OneTimePreKeyDto
import org.khord.shared.protocol.wire.QrPayload
import org.khord.shared.protocol.wire.ReplyInfo
import org.khord.shared.protocol.wire.SignedPreKeyDto
import org.khord.shared.protocol.wire.WireEnvelope
import org.khord.shared.storage.ContactInfo
import org.khord.shared.storage.GroupMemberRecord
import org.khord.shared.storage.GroupMessageRecord
import org.khord.shared.storage.GroupRecord

/**
 * High-level Khord messaging — what app code interacts with.
 *
 * Holds (in memory) the per-user identity + the per-contact ratchet
 * sessions. Drives the Key Server (challenge / verify / bundle upload)
 * and the Relay Server (mailbox CRUD + send + fetch + ack) under the
 * hood. Persistence is the next phase — for now, all state lives only
 * in this object.
 *
 * Mailbox-as-context invariant: once a contact is bound to an inbound
 * mailbox (either Alice's at initiation time, or Bob's at first receive),
 * every subsequent blob arriving on that mailbox is from THAT contact.
 * The orchestrator NEVER inspects fingerprints inside ongoing-session
 * blobs — they're encrypted, and the relay server cannot use sender
 * metadata to disambiguate (PROTOCOL.md §5.2 — sending is unauthenticated).
 *
 * Concurrency: single-threaded. Calling [sendMessage] or [receiveMessages]
 * concurrently on the same [ContactSession] races on the underlying
 * mutable ratchet state. Locking is the next phase's concern.
 */
class Messaging internal constructor(
    private val identity: IdentityKey,
    private val keyServerUrl: String,
    private val relayServerUrl: String,
    private val http: HttpClient,
    private val persistence: org.khord.shared.storage.Persistence,
    /**
     * User's chosen display name. Embedded in `reply_info` on every
     * outbound message so contacts learn it without out-of-band exchange.
     * Default "Anonymous" matches the schema default — used when the user
     * skipped the optional onboarding prompt.
     */
    private var displayName: String = "Anonymous",
) {

    private val ksClient = KeyServerClient(http, keyServerUrl)
    private val rsClient = RelayServerClient(http, relayServerUrl)

    /**
     * Set to true after [panic]. Every public method throws [IllegalStateException]
     * after this, so the caller is forced to construct a fresh instance.
     */
    @Volatile
    private var panicked: Boolean = false

    private fun checkAlive() {
        check(!panicked) { "Messaging instance is dead — panic() was called; construct a new one" }
    }

    /**
     * Stored contacts keyed by fingerprint. Holds the QR coordinates plus a
     * (possibly empty) display name learned from the contact's encrypted
     * `reply_info`. Updated when:
     *   - the user explicitly scans a QR via [storeContact]
     *   - the orchestrator auto-creates an entry when an unknown party's
     *     X3DH initial lands and reply_info is parseable (replaces the
     *     legacy bidirectional-QR requirement)
     *   - a subsequent inbound payload's reply_info reports a different
     *     display name (self-healing rename)
     */
    private val contactsByFingerprint = mutableMapOf<String, ContactInfo>()

    /** Per-contact running session, keyed by the inbound mailbox hosting it. */
    private val sessionsByInboundMailbox = mutableMapOf<String, ContactSession>()

    /**
     * Mailboxes I've minted via [myQrPayload] but no contact has written
     * to yet. Mailbox ID → bearer token. Once an X3dhInitial arrives on
     * one of these and is processed, the entry moves to
     * [sessionsByInboundMailbox] and is removed from here.
     */
    private val pendingInboundMailboxes = mutableMapOf<String, String>()

    /** My signed pre-key — generated at [register] time and reused for X3DH. */
    private var spkKeyId: Int = -1
    private var spkSecret: ByteArray? = null
    private var spkPublic: ByteArray? = null

    /** My OPK secrets keyed by ID. Entries are wiped+removed when an X3DH consumes one. */
    private val opkSecretByKeyId = mutableMapOf<Int, ByteArray>()

    /**
     * True if the local identity exists but the Key Server hasn't received
     * (or hasn't acknowledged) our pre-key bundle yet. Set to false after
     * a successful uploadBundle in [register]. UI should re-run [register]
     * whenever this is true.
     */
    @Volatile
    var needsServerRegistration: Boolean = true
        private set

    /** Test-only: how many OPK secrets are still resident. */
    internal val opkSecretCount: Int get() = opkSecretByKeyId.size

    /** Test-only: is a particular OPK id still in the secret map? */
    internal fun hasOpkSecret(keyId: Int): Boolean = opkSecretByKeyId.containsKey(keyId)

    /** Cached Key Server bearer token — minted lazily, refreshed on 401. */
    private var keyServerToken: String? = null

    /**
     * Generate keys + upload bundle to my Key Server. Idempotent only if
     * called once per [Messaging] instance — calling twice generates fresh
     * SPK/OPKs and replaces the server bundle.
     */
    suspend fun register(opkBatchSize: Int = 50) {
        checkAlive()
        require(opkBatchSize in 1..255) { "opkBatchSize out of range" }

        val spkGen = PreKeys.generateSignedPreKey(identity, keyId = 1)
        val opkGens = PreKeys.generateOneTimePreKeys(1..opkBatchSize)

        // Cache the secrets locally for X3DH respond use.
        spkKeyId = spkGen.signedPreKey.keyId
        spkSecret = spkGen.secretKey.copyOf()
        spkPublic = spkGen.signedPreKey.publicKey.copyOf()
        opkSecretByKeyId.clear()
        for (gen in opkGens) {
            opkSecretByKeyId[gen.oneTimePreKey.keyId] = gen.secretKey.copyOf()
        }

        // Persist identity + SPK + OPKs locally.
        //
        // ── Registration-retry safety ────────────────────────────────────
        // Wipe any previously-persisted OPKs from the local store BEFORE
        // re-inserting. The first call to register() persists OPKs with
        // key_ids 1..opkBatchSize. If that call later fails (network
        // error during uploadBundle, app crash mid-flight, etc.) and the
        // user retries — either explicitly via the UI or implicitly via
        // Messaging.load's needsServerRegistration recovery path — the
        // new OPKs use the SAME key_ids (PreKeys.generateOneTimePreKeys
        // is deterministic on the range it's given). Without this wipe
        // the second saveOpkBatch hits a UNIQUE constraint violation on
        // one_time_pre_key.key_id and the retry crashes the same way as
        // the first attempt. A fresh sweep on every register call makes
        // retries idempotent; the Key Server upload below overwrites
        // whatever the server had too, so the local + remote state
        // stays consistent.
        //
        // SPK uses INSERT OR REPLACE in its .sq query so it's already
        // retry-safe — no wipe needed for that one.
        persistence.deleteAllOneTimePreKeys()
        persistence.saveIdentity(
            org.khord.shared.storage.IdentityRecord(
                identity = identity,
                keyServerUrl = keyServerUrl,
                relayServerUrl = relayServerUrl,
                createdAt = kotlinx.datetime.Clock.System.now().toString(),
                displayName = displayName,
            )
        )
        persistence.saveSignedPreKey(
            org.khord.shared.storage.SignedPreKeyRecord(
                keyId = spkGen.signedPreKey.keyId,
                publicKey = spkGen.signedPreKey.publicKey,
                secretKey = spkGen.secretKey,
            )
        )
        persistence.saveOpkBatch(
            opkGens.associate { it.oneTimePreKey.keyId to it.secretKey }
        )

        val token = obtainKeyServerToken()
        ksClient.uploadBundle(
            fingerprint = identity.fingerprint,
            bundle = BundleUploadRequest(
                identityKey = Base64Std.encode(identity.ed25519PublicKey),
                signedPreKey = SignedPreKeyDto(
                    keyId = spkGen.signedPreKey.keyId,
                    publicKey = Base64Std.encode(spkGen.signedPreKey.publicKey),
                    signature = Base64Std.encode(spkGen.signedPreKey.signature),
                ),
                oneTimePreKeys = opkGens.map {
                    OneTimePreKeyDto(
                        keyId = it.oneTimePreKey.keyId,
                        publicKey = Base64Std.encode(it.oneTimePreKey.publicKey),
                    )
                },
            ),
            token = token,
        )
        // Mark the identity as fully registered ONLY on a successful
        // uploadBundle return — that's the partial-registration recovery
        // boundary (investigation Q7).
        persistence.markRegisteredAtServer()
        needsServerRegistration = false
    }

    /**
     * Mint a fresh per-contact inbound mailbox on my Relay Server and
     * return the QR payload to share with the next contact who'll scan it.
     *
     * The minted mailbox stays "pending" — bound to no contact yet — until
     * the contact actually writes to it. At that point [receiveInitialMessage]
     * binds it to a [ContactSession].
     */
    suspend fun myQrPayload(): QrPayload {
        checkAlive()
        val (mailboxId, token) = createInboundMailbox()
        pendingInboundMailboxes[mailboxId] = token
        persistence.savePendingMailbox(mailboxId, token)
        return QrPayload(
            identityKey = Base64Std.encode(identity.ed25519PublicKey),
            fingerprint = identity.fingerprint,
            keyServer = keyServerUrl,
            relayServer = relayServerUrl,
            relayMailbox = mailboxId,
        )
    }

    /**
     * Drain every pending inbound mailbox (those minted via [myQrPayload]
     * that have not yet received their first contact). For each X3dhInitial
     * blob found, run X3DH respond, bind the mailbox to a [ContactSession],
     * and return the decrypted first plaintext alongside the new session.
     *
     * Pending mailboxes that turn out to contain a non-x3dh_initial blob
     * (shouldn't happen in normal use) are skipped — the orchestrator does
     * not silently drop them, but stays in the pending state.
     */
    suspend fun pollPendingMailboxes(): List<NewContact> {
        checkAlive()
        val results = mutableListOf<NewContact>()
        val toClear = mutableListOf<String>()
        for ((mailboxId, token) in pendingInboundMailboxes.toMap()) {
            val fetched = rsClient.fetchMessages(mailboxId, token, afterSequence = 0)
            if (fetched.isEmpty()) continue

            for (m in fetched) {
                val envelope = decodeEnvelope(Base64Std.decode(m.blob))
                if (envelope !is WireEnvelope.X3dhInitial) continue
                val initial = receiveInitialBlobInternal(
                    myInboundMailbox = mailboxId,
                    bearerTokenForMailbox = token,
                    envelope = envelope,
                )
                val session = initial.session
                val text = initial.text
                results += NewContact(session, text)
                toClear += mailboxId
                // Ack the initial so subsequent receiveMessages on this contact
                // won't see it again. We ack only the first message — any
                // later messages on this mailbox arrived after the X3dhInitial
                // and belong to the now-bound contact.
                rsClient.acknowledge(mailboxId, token, m.sequence)
                session.lastFetchedSequence = m.sequence
                persistence.updateLastFetchedSequence(session.contactFingerprint, m.sequence)
                persistence.saveMessage(
                    contactFingerprint = session.contactFingerprint,
                    direction = org.khord.shared.storage.MessageDirection.RECEIVED,
                    body = text,
                    timestamp = kotlinx.datetime.Clock.System.now().toString(),
                    messageUuid = initial.messageUuid,
                )
                break
            }
        }
        for (mailboxId in toClear) {
            pendingInboundMailboxes.remove(mailboxId)
            persistence.deletePendingMailbox(mailboxId)
        }
        return results
    }

    /**
     * The result of [pollPendingMailboxes] for a single mailbox: a fresh
     * [ContactSession] and the first decrypted message from that contact.
     */
    data class NewContact(val session: ContactSession, val firstMessage: String)

    /**
     * Persist a contact's QR (called after scanning the QR out-of-band).
     * Display name is empty here — the QR doesn't carry it. We learn the
     * display name later from the contact's encrypted reply_info on first
     * message receipt.
     */
    suspend fun storeContact(contactQr: QrPayload) {
        checkAlive()
        val existing = contactsByFingerprint[contactQr.fingerprint]
        // User-initiated → always ACCEPTED. If the contact already
        // existed as PENDING (we got their X3DH initial before they
        // shared their QR with us out-of-band), this storeContact
        // call effectively accepts them.
        val info = ContactInfo(
            qr = contactQr,
            displayName = existing?.displayName ?: "",
            status = org.khord.shared.storage.ContactStatus.ACCEPTED,
        )
        contactsByFingerprint[contactQr.fingerprint] = info
        persistence.saveContact(contactQr, info.displayName, info.status)
    }

    // ── Acceptance gate ─────────────────────────────────────────────────────
    //
    // See ROADMAP "Contact acceptance gate" + the receiveInitialBlobInternal /
    // applyX3dhInitialReset paths that mint PENDING contacts.

    /**
     * One pending contact's user-facing surface — fingerprint + display
     * name. The chat-screen layer composes message history on top.
     */
    data class PendingContact(
        val fingerprint: String,
        val displayName: String,
    )

    /**
     * Contacts whose first X3DH initial we received but the local user
     * hasn't accepted yet. Source-of-truth is the in-memory
     * [contactsByFingerprint] map (mirrored to persistence on every
     * write). Empty list is the normal case.
     */
    fun pendingContacts(): List<PendingContact> {
        checkAlive()
        return contactsByFingerprint.values
            .filter { it.status == org.khord.shared.storage.ContactStatus.PENDING }
            .map {
                // Honour a local nickname override here too, falling
                // back to the contact's self-reported name.
                val name = it.localDisplayName?.takeIf { n -> n.isNotBlank() }
                    ?: it.displayName
                PendingContact(it.qr.fingerprint, name)
            }
    }

    /** True iff the fingerprint is known AND its status is ACCEPTED. */
    fun isContactAccepted(fingerprint: String): Boolean =
        contactsByFingerprint[fingerprint]?.status ==
            org.khord.shared.storage.ContactStatus.ACCEPTED

    /**
     * Promote a pending contact to accepted. No-op if the fingerprint
     * is unknown OR already accepted. After this returns the contact
     * appears in the regular UI list and the push service stops
     * suppressing their notification banners.
     *
     * "Decline" is just [deleteContact] — same effect locally, no
     * notification to the counterparty either way.
     */
    suspend fun acceptContact(fingerprint: String) {
        checkAlive()
        val info = contactsByFingerprint[fingerprint] ?: return
        if (info.status == org.khord.shared.storage.ContactStatus.ACCEPTED) return
        contactsByFingerprint[fingerprint] =
            info.copy(status = org.khord.shared.storage.ContactStatus.ACCEPTED)
        persistence.setContactStatus(
            fingerprint, org.khord.shared.storage.ContactStatus.ACCEPTED,
        )
        // Process any payload deferred while this contact was pending.
        // Classic case: a group_invite that landed before the user
        // accepted. Without replay the group would never be created
        // locally even though we now trust the sender. Read fresh
        // from persistence (in-memory ContactInfo's pendingPayload
        // can be stale), decode, apply, then clear both copies.
        val freshInfo = persistence.loadContact(fingerprint)
        val pending = freshInfo?.pendingPayload
        if (pending != null) {
            runCatching {
                val payload = KhordJson.decodeFromString(
                    InnerPayload.serializer(), pending,
                )
                if (payload.type == "group_invite") {
                    applyGroupInvite(fingerprint, payload)
                }
                // Other payload types could be deferred in future; if
                // we ever stored an unrecognised type we still clear
                // it below rather than re-process indefinitely.
            }
            persistence.setContactPendingPayload(fingerprint, null)
            contactsByFingerprint[fingerprint] = contactsByFingerprint[fingerprint]
                ?.copy(pendingPayload = null)
                ?: contactsByFingerprint[fingerprint]!!
        }
    }

    /**
     * Edit a previously-sent message by UUID. Looks up the message
     * locally to determine whether it was 1:1 or group, verifies it
     * was sent by us, fans out a "message_edit" payload through the
     * appropriate pairwise channel(s), and updates the local copy in
     * place. Receivers persist the new body and flag the row as
     * edited so the UI can show "(edited)".
     *
     * Returns true on success, false if the UUID didn't match
     * anything OR the matched message wasn't sent by us (anti-spoof:
     * we don't fan out edits for messages we didn't author).
     *
     * Best-effort: a recipient who is offline at edit time will see
     * the original body until the next time they poll/push and the
     * edit envelope is delivered. ADR 026 documents the trade-off.
     */
    suspend fun editMessage(messageUuid: String, newBody: String): Boolean {
        checkAlive()
        require(messageUuid.isNotBlank()) { "messageUuid must be non-blank" }
        require(newBody.isNotBlank()) { "newBody must be non-blank" }

        // Try 1:1 path first — most common.
        val oneToOne = persistence.findMessageByUuid(messageUuid)
        if (oneToOne != null) {
            if (oneToOne.direction != org.khord.shared.storage.MessageDirection.SENT) {
                return false   // anti-spoof: can only edit messages we sent
            }
            val contact = sessionForFingerprint(oneToOne.contactFingerprint)
                ?: return false
            val payload = InnerPayload(
                type = "message_edit",
                timestamp = Clock.System.now().toString(),
                messageUuid = messageUuid,
                newBody = newBody,
                replyInfo = ReplyInfo(
                    mailbox = contact.inboundMailboxId,
                    relayServer = relayServerUrl,
                    keyServer = keyServerUrl,
                    fingerprint = identity.fingerprint,
                    displayName = displayName,
                ),
            )
            sendRatchetPayload(contact, payload)
            persistence.updateMessageBodyByUuid(messageUuid, newBody)
            return true
        }

        // Fall through: group message?
        val group = persistence.findGroupMessageByUuid(messageUuid) ?: return false
        if (group.senderFingerprint != identity.fingerprint) return false
        val members = persistence.loadGroupMembers(group.groupId)
            .filter { it.fingerprint != identity.fingerprint }
        val timestamp = Clock.System.now().toString()
        for (m in members) {
            val c = sessionForFingerprint(m.fingerprint) ?: continue
            sendGroupInnerPayload(
                contact = c,
                payload = InnerPayload(
                    type = "message_edit",
                    timestamp = timestamp,
                    messageUuid = messageUuid,
                    newBody = newBody,
                    replyInfo = myReplyInfo(c.inboundMailboxId),
                    groupId = group.groupId,
                ),
            )
        }
        persistence.updateGroupMessageBodyByUuid(messageUuid, newBody)
        return true
    }

    /**
     * Inbound side of [editMessage]. The pairwise channel already
     * proved sender authenticity (Double Ratchet AEAD); we still
     * verify the edit's claimed UUID belongs to a message we
     * remember as having come from THIS sender. Edits for messages
     * we sent (i.e. arriving on the "wrong" direction) are
     * silently ignored; same for unknown UUIDs.
     */
    private suspend fun handleMessageEdit(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        val uuid = payload.messageUuid ?: return
        val newBody = payload.newBody ?: return

        val oneToOne = persistence.findMessageByUuid(uuid)
        if (oneToOne != null) {
            // Must be a RECEIVED message (we got it from the sender),
            // and the contact whose session delivered the edit must
            // be the same contact we received the original from.
            if (oneToOne.direction != org.khord.shared.storage.MessageDirection.RECEIVED) {
                return
            }
            if (oneToOne.contactFingerprint != contact.contactFingerprint) {
                return
            }
            persistence.updateMessageBodyByUuid(uuid, newBody)
            return
        }

        val group = persistence.findGroupMessageByUuid(uuid) ?: return
        // Sender of the edit must match the sender of the original
        // group message — otherwise it's an attempt to edit someone
        // else's group message, which we never honour.
        if (group.senderFingerprint != contact.contactFingerprint) return
        persistence.updateGroupMessageBodyByUuid(uuid, newBody)
    }

    /**
     * Shared body-of-sendMessage used by [editMessage]: encrypt one
     * InnerPayload through the contact's ratchet and POST to their
     * relay. No local persistence — the caller decides what (if
     * anything) to record locally for this payload type.
     */
    private suspend fun sendRatchetPayload(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        val plaintextBytes = KhordJson.encodeToString(InnerPayload.serializer(), payload)
            .encodeToByteArray()
        val encrypted = contact.session.encrypt(plaintextBytes)
        val envelope = WireEnvelope.Ratchet(
            header = Base64Std.encode(encrypted.headerBytes),
            ciphertext = Base64Std.encode(encrypted.ciphertext),
        )
        val envelopeBytes = KhordJson
            .encodeToString(WireEnvelope.serializer(), envelope as WireEnvelope)
            .encodeToByteArray()
        val rs = if (contact.outboundRelayServer == relayServerUrl) {
            rsClient
        } else {
            RelayServerClient(http, contact.outboundRelayServer)
        }
        rs.sendMessage(contact.outboundMailboxId, envelopeBytes)
        persistSession(contact)
    }

    /** Update my own display name (persisted; used in subsequent reply_info). */
    suspend fun updateMyDisplayName(name: String) {
        checkAlive()
        displayName = name
        persistence.updateMyDisplayName(name)
    }

    /** This user's chosen display name (or "Anonymous"). */
    val myDisplayName: String get() = displayName

    /**
     * Effective display name for a contact: the user's local nickname
     * override if set, else the contact's self-reported display name,
     * else null (callers fall back to a fingerprint prefix). This is
     * the single resolution point for the
     * `local_display_name ?? display_name ?? fingerprint` precedence —
     * every screen that shows a contact name routes through here.
     */
    fun contactDisplayName(fingerprint: String): String? {
        val info = contactsByFingerprint[fingerprint] ?: return null
        return info.localDisplayName?.takeIf { it.isNotBlank() }
            ?: info.displayName.takeIf { it.isNotEmpty() }
    }

    /** Raw local nickname override (null if none), for the rename dialog. */
    fun contactLocalName(fingerprint: String): String? =
        contactsByFingerprint[fingerprint]?.localDisplayName

    /**
     * Set (blank/null clears) the local nickname override for a
     * contact. Local-only, never transmitted; takes precedence over
     * the contact's self-reported name everywhere via
     * [contactDisplayName].
     */
    suspend fun setContactLocalName(fingerprint: String, localName: String?) {
        checkAlive()
        val normalised = localName?.takeIf { it.isNotBlank() }?.trim()
        val info = contactsByFingerprint[fingerprint] ?: return
        contactsByFingerprint[fingerprint] = info.copy(localDisplayName = normalised)
        persistence.setContactLocalName(fingerprint, normalised)
    }

    /**
     * Mark / unmark a contact as fingerprint-verified. Local trust
     * decision — never transmitted. The [applyX3dhInitialReset] hook
     * unconditionally clears verified on any key change so a key
     * rotation / seed-phrase recovery / impersonation drops the
     * badge until the user re-verifies in person.
     */
    suspend fun setContactVerified(fingerprint: String, verified: Boolean) {
        checkAlive()
        val info = contactsByFingerprint[fingerprint] ?: return
        contactsByFingerprint[fingerprint] = info.copy(verified = verified)
        persistence.setContactVerified(fingerprint, verified)
    }

    /** True if the user has fingerprint-verified this contact. */
    fun isContactVerified(fingerprint: String): Boolean =
        contactsByFingerprint[fingerprint]?.verified == true

    // ── Blocking + muting (#27) ───────────────────────────────────────────────

    /**
     * Block / unblock a contact. Local-only, never transmitted. When
     * blocked: inbound messages are dropped on receive (still drained
     * + acked off the relay so the mailbox doesn't fill, but not
     * stored or surfaced), they're hidden from the contact list (the
     * UI filters by [isContactBlocked]), and [sendMessage] refuses to
     * send. Their group messages are dropped locally too.
     *
     * We deliberately keep their push subscription alive (contacts()
     * still returns them) so their relay mailbox keeps draining — the
     * drop happens at the persist step, not the fetch step.
     */
    suspend fun setContactBlocked(fingerprint: String, blocked: Boolean) {
        checkAlive()
        val info = contactsByFingerprint[fingerprint] ?: return
        contactsByFingerprint[fingerprint] = info.copy(blocked = blocked)
        persistence.setContactBlocked(fingerprint, blocked)
    }

    fun isContactBlocked(fingerprint: String): Boolean =
        contactsByFingerprint[fingerprint]?.blocked == true

    /**
     * Mute / unmute a contact. Local-only. Muted contacts' messages
     * are received + stored normally and they stay in the list (with
     * a muted indicator), but the push service suppresses their
     * notifications.
     */
    suspend fun setContactMuted(fingerprint: String, muted: Boolean) {
        checkAlive()
        val info = contactsByFingerprint[fingerprint] ?: return
        contactsByFingerprint[fingerprint] = info.copy(muted = muted)
        persistence.setContactMuted(fingerprint, muted)
    }

    fun isContactMuted(fingerprint: String): Boolean =
        contactsByFingerprint[fingerprint]?.muted == true

    /** Blocked contacts, for the Settings → Blocked list. */
    fun blockedContacts(): List<PendingContact> {
        checkAlive()
        return contactsByFingerprint.values
            .filter { it.blocked }
            .map {
                val name = it.localDisplayName?.takeIf { n -> n.isNotBlank() }
                    ?: it.displayName
                PendingContact(it.qr.fingerprint, name)
            }
    }

    /**
     * Forget a contact entirely. Drops:
     *   - the in-memory [ContactSession] (so [contacts] and
     *     [pushSubscriptions] both stop returning this fingerprint
     *     immediately — the platform push service refreshing its
     *     listener after this returns will tear down the WebSocket
     *     for the contact's inbound mailbox)
     *   - the in-memory [ContactInfo] cache
     *   - the persisted contact row, session row, and every persisted
     *     message (via [persistence.deleteContact], which transactions
     *     all three tables together)
     *
     * Local-only. No Key Server or Relay Server call is made, no
     * "I'm removing you" payload is sent to the contact — matches
     * Khord's no-server-trace stance on contact lifecycle. The
     * counterparty keeps their copy of the conversation; they only
     * notice when their next outbound message bounces off the relay
     * 404 (already surfaced as "Unavailable" in chat).
     *
     * No-op if the fingerprint is unknown.
     */
    suspend fun deleteContact(fingerprint: String) {
        checkAlive()
        val session = sessionForFingerprint(fingerprint)
        if (session != null) {
            sessionsByInboundMailbox.remove(session.inboundMailboxId)
        }
        contactsByFingerprint.remove(fingerprint)
        persistence.deleteContact(fingerprint)
    }

    /**
     * Initiate an X3DH session with `contactFingerprint` and send the first
     * encrypted message.
     *
     * `myInboundMailboxId` MUST be a mailbox-ID I previously minted via
     * [myQrPayload] AND shared with this contact via the QR exchange. It
     * becomes the session's `inboundMailboxId` — where the contact's reply
     * will land. The caller is the only party who knows which of their QRs
     * went to which contact, so the binding is passed in explicitly here
     * rather than guessed.
     *
     * Caller must also have called [storeContact] first with the contact's QR.
     */
    suspend fun initiateContact(
        contactFingerprint: String,
        myInboundMailboxId: String,
        firstMessage: String,
    ): ContactSession {
        checkAlive()
        val myInboundToken = pendingInboundMailboxes[myInboundMailboxId]
            ?: throw IllegalStateException(
                "myInboundMailboxId is not a pending mailbox — call myQrPayload() " +
                "first and pass the same mailbox you shared with this contact"
            )

        val contactInfo = contactsByFingerprint[contactFingerprint]
            ?: throw IllegalStateException("contact not stored: $contactFingerprint")
        val contactQr = contactInfo.qr

        // Sanity: the QR's identity key really hashes to the claimed fingerprint.
        val contactIdEd = Base64Std.decode(contactQr.identityKey)
        require(org.khord.shared.crypto.IdentityKey.fromBytes_internalCheck(contactIdEd, contactQr.fingerprint)) {
            "QR fingerprint does not match identity_key"
        }

        // Fetch the contact's bundle from THEIR key server (X3DH §3.2 / ADR 002).
        val theirKsClient = KeyServerClient(http, contactQr.keyServer)
        val bundleDto = theirKsClient.fetchBundle(contactFingerprint)

        // Bind to crypto-layer types and verify SPK signature.
        val theirIdEd = Base64Std.decode(bundleDto.identityKey)
        require(theirIdEd.contentEquals(contactIdEd)) {
            "bundle identity_key does not match QR identity_key"
        }
        val cryptoBundle = PreKeyBundle(
            identityKeyEd25519 = theirIdEd,
            signedPreKey = SignedPreKey(
                keyId = bundleDto.signedPreKey.keyId,
                publicKey = Base64Std.decode(bundleDto.signedPreKey.publicKey),
                signature = Base64Std.decode(bundleDto.signedPreKey.signature),
            ),
            oneTimePreKey = bundleDto.oneTimePreKey?.let {
                CryptoOneTimePreKey(
                    keyId = it.keyId,
                    publicKey = Base64Std.decode(it.publicKey),
                )
            },
        )

        // Run X3DH (verifies SPK signature internally).
        val initOut = try {
            X3dh.initiate(identity, cryptoBundle)
        } catch (e: IllegalArgumentException) {
            throw ProtocolError.BadSignedPreKey()
        }

        // Init my ratchet, encrypt the first inner payload (X3DH initial).
        // reply_info is REQUIRED on this message — without it, Bob can't
        // auto-create the Alice-contact entry and the legacy "received from
        // unknown fingerprint" error would force a bidirectional QR scan.
        val session = Session.fromInitiator(initOut, cryptoBundle.signedPreKey.publicKey)
        val firstMessageTimestamp = Clock.System.now().toString()
        val firstMessageUuid = newMessageUuid()
        val payload = InnerPayload(
            type = "text",
            timestamp = firstMessageTimestamp,
            body = firstMessage,
            messageUuid = firstMessageUuid,
            replyInfo = ReplyInfo(
                mailbox = myInboundMailboxId,
                relayServer = relayServerUrl,
                keyServer = keyServerUrl,
                fingerprint = identity.fingerprint,
                displayName = displayName,
            ),
        )
        val plaintextBytes = KhordJson.encodeToString(InnerPayload.serializer(), payload)
            .encodeToByteArray()
        val encrypted = session.encrypt(plaintextBytes)

        // Build the X3dhInitial envelope.
        val envelope = WireEnvelope.X3dhInitial(
            ikA = Base64Std.encode(initOut.identityKeyEd25519),
            ekA = Base64Std.encode(initOut.ephemeralPublicKey),
            spkId = initOut.signedPreKeyId,
            opkId = initOut.oneTimePreKeyId,
            header = Base64Std.encode(encrypted.headerBytes),
            ciphertext = Base64Std.encode(encrypted.ciphertext),
        )
        val envelopeBytes = KhordJson
            .encodeToString(WireEnvelope.serializer(), envelope as WireEnvelope)
            .encodeToByteArray()

        // Send the X3dhInitial to the contact's relay mailbox.
        val theirRsClient = RelayServerClient(http, contactQr.relayServer)
        theirRsClient.sendMessage(contactQr.relayMailbox, envelopeBytes)

        // Bind the existing pending mailbox (the one I shared in my QR) to
        // this session — that's where the contact's replies will land.
        pendingInboundMailboxes.remove(myInboundMailboxId)
        persistence.deletePendingMailbox(myInboundMailboxId)

        val contactSession = ContactSession(
            contactIdentityKey = contactIdEd,
            contactFingerprint = contactQr.fingerprint,
            outboundMailboxId = contactQr.relayMailbox,
            outboundRelayServer = contactQr.relayServer,
            inboundMailboxId = myInboundMailboxId,
            inboundBearerToken = myInboundToken,
            session = session,
        )
        sessionsByInboundMailbox[myInboundMailboxId] = contactSession
        persistSession(contactSession)
        // Persist the first sent message so it shows up in local history.
        persistence.saveMessage(
            contactFingerprint = contactSession.contactFingerprint,
            direction = org.khord.shared.storage.MessageDirection.SENT,
            body = firstMessage,
            timestamp = firstMessageTimestamp,
            messageUuid = firstMessageUuid,
        )
        return contactSession
    }

    /**
     * Bob's side: process an X3dhInitial blob from `myInboundMailbox`.
     * Called from [pollPendingMailboxes] after the envelope decode.
     *
     * **OPK forward secrecy invariant** (X3DH §3.4): the OPK secret consumed
     * by this X3DH is wiped and removed from the local store before this
     * function returns successfully. Tested.
     */
    private suspend fun receiveInitialBlobInternal(
        myInboundMailbox: String,
        bearerTokenForMailbox: String,
        envelope: WireEnvelope.X3dhInitial,
    ): InitialReceiveResult {
        val ikA = Base64Std.decode(envelope.ikA)
        val ekA = Base64Std.decode(envelope.ekA)
        val initiatorFp = identityFingerprint(ikA)

        // Look up this side's secrets by ID.
        val mySpkSecret = spkSecret
            ?: throw IllegalStateException("not registered — call register() first")
        require(envelope.spkId == spkKeyId) { "unknown SPK id: ${envelope.spkId}" }
        val opkSecret = envelope.opkId?.let {
            opkSecretByKeyId[it]
                ?: throw IllegalStateException("unknown OPK id: $it")
        }

        val sk = X3dh.respond(
            X3dh.ResponderInput(
                initiatorIdentityKeyEd25519 = ikA,
                initiatorEphemeralPublicKey = ekA,
                responderIdentity = identity,
                signedPreKeySecret = mySpkSecret,
                oneTimePreKeySecret = opkSecret,
            )
        )

        // OPK forward secrecy: wipe + remove from the local store + DB.
        if (envelope.opkId != null) {
            val secret = opkSecretByKeyId.remove(envelope.opkId)
            secret?.wipe()
            persistence.deleteOneTimePreKey(envelope.opkId)
        }

        val ad = X3dh.associatedDataFor(ikA, identity)
        val session = Session.fromResponder(
            sk = sk,
            bobSignedPreKeyPair = X25519KeyPair(spkPublic!!, mySpkSecret),
            associatedData = ad,
        )

        val plaintextBytes = session.decrypt(
            headerBytes = Base64Std.decode(envelope.header),
            ciphertext = Base64Std.decode(envelope.ciphertext),
        )
        val payload = KhordJson.decodeFromString(
            InnerPayload.serializer(),
            plaintextBytes.decodeToString(),
        )
        val text = decodeInnerPayloadText(payload)

        // Auto-create the contact from reply_info — replaces the legacy
        // "caller must storeContact() before first receive" prerequisite.
        // This is the unidirectional-flow fix: one QR scan suffices.
        val replyInfo = payload.replyInfo
            ?: throw ProtocolError.WireFormatError(
                "X3DH initial from $initiatorFp missing reply_info — " +
                "older sender (pre-display-name protocol)? legacy bidirectional " +
                "QR exchange required as fallback"
            )
        require(replyInfo.fingerprint == initiatorFp) {
            "reply_info.fingerprint ${replyInfo.fingerprint} does not match " +
            "envelope.ik_a hash $initiatorFp"
        }
        val autoCreatedQr = QrPayload(
            identityKey = envelope.ikA,
            fingerprint = initiatorFp,
            keyServer = replyInfo.keyServer,
            relayServer = replyInfo.relayServer,
            relayMailbox = replyInfo.mailbox,
        )
        // Acceptance gate: a new fingerprint reaching us via X3DH
        // initial defaults to PENDING. If we ALREADY know this
        // fingerprint (e.g. seed-phrase recovery re-binding through
        // Case B, or the user manually stored their QR earlier),
        // preserve the existing status — don't downgrade an accepted
        // contact to pending on session re-bind.
        val existingStatus = contactsByFingerprint[initiatorFp]?.status
            ?: org.khord.shared.storage.ContactStatus.PENDING
        contactsByFingerprint[initiatorFp] =
            ContactInfo(autoCreatedQr, replyInfo.displayName, existingStatus)
        persistence.saveContact(autoCreatedQr, replyInfo.displayName, existingStatus)

        val contactSession = ContactSession(
            contactIdentityKey = ikA,
            contactFingerprint = initiatorFp,
            outboundMailboxId = autoCreatedQr.relayMailbox,
            outboundRelayServer = autoCreatedQr.relayServer,
            inboundMailboxId = myInboundMailbox,
            inboundBearerToken = bearerTokenForMailbox,
            session = session,
        )
        // Case B (seed-phrase recovery / reinstall, ADR 025): if a
        // session for this fingerprint already exists under a
        // DIFFERENT inbound mailbox, drop the stale in-memory entry
        // before binding the new one. The persisted session row is
        // keyed by contact_fingerprint (PK) so [persistSession] below
        // will UPSERT-overwrite it; the in-memory map is keyed by
        // inbound mailbox so without this cleanup the user would see
        // the contact listed twice until the next app restart and
        // the dead WebSocket subscription would keep running.
        //
        // Message history is preserved — only the session metadata
        // is replaced. A reset marker is inserted so the chat reads
        // continuously across the recovery boundary.
        val existingSession = sessionForFingerprint(initiatorFp)
        if (existingSession != null &&
            existingSession.inboundMailboxId != myInboundMailbox) {
            sessionsByInboundMailbox.remove(existingSession.inboundMailboxId)
            saveSessionResetMarker(initiatorFp)
        }
        sessionsByInboundMailbox[myInboundMailbox] = contactSession
        persistSession(contactSession)
        return InitialReceiveResult(contactSession, text, payload.messageUuid)
    }

    /** Send a text message to the contact this session is bound to. */
    suspend fun sendMessage(
        contact: ContactSession,
        text: String,
        replyToUuid: String? = null,
    ): Long {
        checkAlive()
        // Refuse to send to a blocked contact. The UI hides blocked
        // contacts so this shouldn't be reachable, but guard anyway.
        check(!isContactBlocked(contact.contactFingerprint)) {
            "cannot send to a blocked contact"
        }
        val timestamp = Clock.System.now().toString()
        // Stamp every outgoing message with a fresh UUID so the
        // recipient can persist the same identifier and a later
        // "message_edit" payload can target this specific message
        // by reference (rather than fragile timestamp+body matching).
        val messageUuid = newMessageUuid()
        // Always include reply_info on outbound ratchet messages too — costs
        // ~150 bytes per message and gives us self-healing display-name and
        // server-URL updates without adding a separate "I-renamed-myself"
        // message type. Receivers no-op when nothing changed.
        val payload = InnerPayload(
            type = "text",
            timestamp = timestamp,
            body = text,
            messageUuid = messageUuid,
            replyToUuid = replyToUuid,
            replyInfo = ReplyInfo(
                mailbox = contact.inboundMailboxId,
                relayServer = relayServerUrl,
                keyServer = keyServerUrl,
                fingerprint = identity.fingerprint,
                displayName = displayName,
            ),
        )
        val plaintextBytes = KhordJson.encodeToString(InnerPayload.serializer(), payload)
            .encodeToByteArray()
        val encrypted = contact.session.encrypt(plaintextBytes)

        val envelope = WireEnvelope.Ratchet(
            header = Base64Std.encode(encrypted.headerBytes),
            ciphertext = Base64Std.encode(encrypted.ciphertext),
        )
        val envelopeBytes = KhordJson
            .encodeToString(WireEnvelope.serializer(), envelope as WireEnvelope)
            .encodeToByteArray()

        val rs = if (contact.outboundRelayServer == relayServerUrl) {
            rsClient
        } else {
            RelayServerClient(http, contact.outboundRelayServer)
        }
        val sequence = rs.sendMessage(contact.outboundMailboxId, envelopeBytes)

        // Persist message + advanced ratchet state.
        persistence.saveMessage(
            contactFingerprint = contact.contactFingerprint,
            direction = org.khord.shared.storage.MessageDirection.SENT,
            body = text,
            timestamp = timestamp,
            messageUuid = messageUuid,
            replyToUuid = replyToUuid,
        )
        persistSession(contact)

        return sequence
    }

    /**
     * Poll my inbound mailbox for messages from this contact. Returns the
     * decrypted plaintexts of TEXT messages in sequence order — group
     * messages and group-management payloads (ADR 023) are processed via
     * their own side-effect paths (group_messages table, in-memory
     * group state) and are NOT included in the returned list. The
     * highest sequence is acked regardless of payload type.
     */
    suspend fun receiveMessages(contact: ContactSession): List<String> {
        checkAlive()
        val fetched = rsClient.fetchMessages(
            mailboxId = contact.inboundMailboxId,
            bearerToken = contact.inboundBearerToken,
            afterSequence = contact.lastFetchedSequence,
        )
        if (fetched.isEmpty()) return emptyList()

        // The session this method drives can change mid-batch if an
        // inbound X3DH initial triggers a reset (seed-phrase recovery
        // path — see [applyX3dhInitialReset] and ADR 025). Use a local
        // var so subsequent ratchet envelopes in the same fetch batch
        // decrypt under the post-reset session.
        var currentContact = contact
        val plaintexts = mutableListOf<String>()
        for (m in fetched) {
            val envelope = decodeEnvelope(Base64Std.decode(m.blob))
            if (envelope is WireEnvelope.X3dhInitial) {
                // Case A: an X3DH initial landed on an already-bound
                // mailbox. This means the counterparty lost their state
                // (reinstall / seed-phrase recovery) and started a fresh
                // X3DH against our existing mailbox. Reset the session
                // in-place; the helper persists the new state, drops a
                // visible "Session reset" marker into the chat log, and
                // saves the decrypted first message. On fingerprint
                // mismatch the helper throws, which we catch + log so a
                // hostile / malformed envelope can't take down the whole
                // poll.
                try {
                    val (resetContact, text) = applyX3dhInitialReset(
                        currentContact,
                        envelope,
                    )
                    sessionsByInboundMailbox[currentContact.inboundMailboxId] =
                        resetContact
                    currentContact = resetContact
                    plaintexts += text
                } catch (e: Throwable) {
                    // Don't fail the whole poll — log diagnostically
                    // and skip this envelope. The wider receive loop
                    // still acks all envelopes up to the highest
                    // sequence (below), so a malformed initial doesn't
                    // wedge the mailbox.
                    org.khord.shared.diagnostic.commonDiagnosticLog(
                        "Khord",
                        "receiveMessages: dropped X3DH initial on " +
                            "bound mailbox ${currentContact.inboundMailboxId}: " +
                            "${e::class.simpleName}: ${e.message}",
                    )
                }
                continue
            }
            // Ratchet envelope — the normal hot path.
            val ratchet = envelope as WireEnvelope.Ratchet
            val plaintextBytes = currentContact.session.decrypt(
                Base64Std.decode(ratchet.header),
                Base64Std.decode(ratchet.ciphertext),
            )
            val payload = KhordJson.decodeFromString(
                InnerPayload.serializer(),
                plaintextBytes.decodeToString(),
            )

            // Self-healing display-name update applies to every payload
            // type — runs before the dispatch so group_* payloads also
            // refresh the sender's name.
            payload.replyInfo?.let { ri ->
                val current = contactsByFingerprint[currentContact.contactFingerprint]
                if (current != null && current.displayName != ri.displayName) {
                    contactsByFingerprint[currentContact.contactFingerprint] =
                        current.copy(displayName = ri.displayName)
                    persistence.updateContactDisplayName(
                        currentContact.contactFingerprint,
                        ri.displayName,
                    )
                }
            }

            // Dispatch by payload type. Group payloads (ADR 023) take the
            // group path; everything else falls through to the legacy
            // text path (where unknown types still raise
            // UnsupportedPayloadType, preserving the original contract).
            when (payload.type) {
                "group_invite" -> handleGroupInvite(currentContact, payload)
                "group_message" -> handleGroupMessage(currentContact, payload)
                "group_member_added" -> handleGroupMemberAdded(currentContact, payload)
                "group_member_left" -> handleGroupMemberLeft(currentContact, payload)
                "group_name_changed" -> handleGroupNameChanged(currentContact, payload)
                "message_edit" -> handleMessageEdit(currentContact, payload)
                else -> {
                    // Legacy one-to-one text path. decodeInnerPayloadText
                    // throws on unknown types, which is the documented
                    // forward-compat behaviour.
                    val text = decodeInnerPayloadText(payload)
                    // Blocked-contact drop: we've already fetched the
                    // message off the relay (it'll be acked at the loop
                    // end, clearing the server mailbox), but a blocked
                    // sender's message is neither stored nor surfaced —
                    // and crucially not added to `plaintexts`, so
                    // handlePush sees nothing to notify about.
                    if (contactsByFingerprint[currentContact.contactFingerprint]?.blocked == true) {
                        // dropped
                    } else {
                        plaintexts += text
                        persistence.saveMessage(
                            contactFingerprint = currentContact.contactFingerprint,
                            direction = org.khord.shared.storage.MessageDirection.RECEIVED,
                            body = text,
                            timestamp = payload.timestamp,
                            messageUuid = payload.messageUuid,
                            replyToUuid = payload.replyToUuid,
                        )
                    }
                }
            }
        }

        val highestSequence = fetched.last().sequence
        rsClient.acknowledge(
            mailboxId = currentContact.inboundMailboxId,
            bearerToken = currentContact.inboundBearerToken,
            throughSequence = highestSequence,
        )
        currentContact.lastFetchedSequence = highestSequence
        persistence.updateLastFetchedSequence(
            currentContact.contactFingerprint, highestSequence,
        )
        persistSession(currentContact)
        return plaintexts
    }

    // ── Groups (ADR 023) ────────────────────────────────────────────────────

    /**
     * Create a new group on this device and fan out invitations to every
     * member via their existing pairwise channels. Returns the generated
     * `group_id` (32-char hex).
     *
     * The caller (Alice) becomes the admin. Each member's QR must have
     * been scanned beforehand (so a [ContactSession] exists in
     * [sessionsByInboundMailbox]); invitations to members without an
     * active session are silently skipped — the group will exist on
     * Alice's device with the missing member listed, but messages will
     * never reach them. See ADR 023 for the cross-introduction note.
     */
    suspend fun createGroup(
        groupName: String,
        memberFingerprints: List<String>,
    ): String {
        checkAlive()
        require(groupName.isNotBlank()) { "groupName must be non-blank" }
        val distinct = memberFingerprints
            .distinct()
            .filter { it != identity.fingerprint } // self is added separately

        val groupId = newGroupId()

        // Persist locally first — admin's view exists even if fan-out
        // partially fails.
        persistence.saveGroup(
            groupId = groupId,
            groupName = groupName,
            createdByFingerprint = identity.fingerprint,
            isAdmin = true,
        )
        persistence.addGroupMember(groupId, identity.fingerprint, displayName)
        for (fp in distinct) {
            val theirName = contactsByFingerprint[fp]?.displayName ?: ""
            persistence.addGroupMember(groupId, fp, theirName)
        }

        // Build the full membership list (including self) once — every
        // invite recipient gets the same view.
        val fullMembers = buildList {
            add(WireGroupMember(identity.fingerprint, displayName))
            for (fp in distinct) {
                add(WireGroupMember(
                    fingerprint = fp,
                    displayName = contactsByFingerprint[fp]?.displayName ?: "",
                ))
            }
        }

        // Fan out the invites via each member's pairwise channel.
        for (fp in distinct) {
            val contact = sessionForFingerprint(fp) ?: continue
            sendGroupInnerPayload(
                contact = contact,
                payload = InnerPayload(
                    type = "group_invite",
                    timestamp = Clock.System.now().toString(),
                    replyInfo = myReplyInfo(contact.inboundMailboxId),
                    groupId = groupId,
                    groupName = groupName,
                    members = fullMembers,
                ),
            )
        }

        return groupId
    }

    /**
     * Send a text message to every member of [groupId] via their pairwise
     * channels. The message is also saved locally as a `sent` group
     * message so it appears in the sender's own group chat history.
     *
     * Fan-out cost: O(N) ratchet encryptions + O(N) relay POSTs, one per
     * member with an active [ContactSession]. Members without a session
     * are silently skipped (per ADR 023).
     */
    suspend fun sendGroupMessage(
        groupId: String,
        text: String,
        replyToUuid: String? = null,
    ) {
        checkAlive()
        require(text.isNotBlank()) { "text must be non-blank" }
        val group = persistence.loadGroup(groupId)
            ?: throw IllegalStateException("unknown groupId: $groupId")
        val members = persistence.loadGroupMembers(groupId)
            .filter { it.fingerprint != identity.fingerprint }

        val timestamp = Clock.System.now().toString()
        // Single UUID stamped on every fan-out copy — receiving members
        // store the same identifier, so a later message_edit fanout
        // (referencing this UUID) can be applied uniformly across all
        // recipients.
        val messageUuid = newMessageUuid()
        for (m in members) {
            val contact = sessionForFingerprint(m.fingerprint) ?: continue
            sendGroupInnerPayload(
                contact = contact,
                payload = InnerPayload(
                    type = "group_message",
                    timestamp = timestamp,
                    body = text,
                    messageUuid = messageUuid,
                    replyToUuid = replyToUuid,
                    replyInfo = myReplyInfo(contact.inboundMailboxId),
                    groupId = groupId,
                ),
            )
        }

        persistence.saveGroupMessage(
            groupId = groupId,
            senderFingerprint = identity.fingerprint,
            senderDisplayName = displayName,
            body = text,
            timestamp = timestamp,
            direction = org.khord.shared.storage.MessageDirection.SENT,
            messageUuid = messageUuid,
            replyToUuid = replyToUuid,
        )
        // Read-mod-write: groupName access not needed here, but keep
        // `group` local in case future logic on this branch needs it.
        check(group.groupId == groupId)
    }

    /**
     * Admin-only: add a new member to an existing group. Sends
     * `group_invite` to the newcomer (their first view of the group)
     * AND `group_member_added` to every existing member so they update
     * their local list. Non-admin callers throw.
     */
    suspend fun addGroupMember(groupId: String, fingerprint: String) {
        checkAlive()
        require(fingerprint != identity.fingerprint) { "cannot add self" }
        val group = persistence.loadGroup(groupId)
            ?: throw IllegalStateException("unknown groupId: $groupId")
        check(group.isAdmin) { "only the admin can add members" }

        val existingMembers = persistence.loadGroupMembers(groupId)
        if (existingMembers.any { it.fingerprint == fingerprint }) {
            // Already a member — no-op.
            return
        }
        val newName = contactsByFingerprint[fingerprint]?.displayName ?: ""
        persistence.addGroupMember(groupId, fingerprint, newName)

        // Build full membership including the new arrival for the invite.
        val fullMembers = (existingMembers +
            GroupMemberRecord(fingerprint, newName))
            .map { WireGroupMember(it.fingerprint, it.displayName) }

        // Send invite to the new member.
        sessionForFingerprint(fingerprint)?.let { contact ->
            sendGroupInnerPayload(
                contact = contact,
                payload = InnerPayload(
                    type = "group_invite",
                    timestamp = Clock.System.now().toString(),
                    replyInfo = myReplyInfo(contact.inboundMailboxId),
                    groupId = groupId,
                    groupName = group.groupName,
                    members = fullMembers,
                ),
            )
        }

        // Notify existing members (excluding self and the newcomer).
        val notifyTimestamp = Clock.System.now().toString()
        for (m in existingMembers) {
            if (m.fingerprint == identity.fingerprint) continue
            val contact = sessionForFingerprint(m.fingerprint) ?: continue
            sendGroupInnerPayload(
                contact = contact,
                payload = InnerPayload(
                    type = "group_member_added",
                    timestamp = notifyTimestamp,
                    replyInfo = myReplyInfo(contact.inboundMailboxId),
                    groupId = groupId,
                    added = WireGroupMember(fingerprint, newName),
                ),
            )
        }
    }

    /**
     * Rename a group. Admin-only (matches the inbound auth gate in
     * [handleGroupNameChanged], which ignores rename payloads from
     * anyone but the group's creator). Updates the local name, then
     * fans a `group_name_changed` payload out to every member with an
     * active session — same pairwise-channel pattern as
     * [sendGroupMessage]. Members without a live session are silently
     * skipped (ADR 023); they'll see the old name until a future
     * message re-syncs, which is acceptable for a cosmetic field.
     */
    suspend fun renameGroup(groupId: String, newName: String) {
        checkAlive()
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "group name must be non-blank" }
        val group = persistence.loadGroup(groupId)
            ?: throw IllegalStateException("unknown groupId: $groupId")
        check(group.isAdmin) { "only the admin can rename the group" }

        persistence.updateGroupName(groupId, trimmed)

        val members = persistence.loadGroupMembers(groupId)
            .filter { it.fingerprint != identity.fingerprint }
        val timestamp = Clock.System.now().toString()
        for (m in members) {
            val contact = sessionForFingerprint(m.fingerprint) ?: continue
            sendGroupInnerPayload(
                contact = contact,
                payload = InnerPayload(
                    type = "group_name_changed",
                    timestamp = timestamp,
                    replyInfo = myReplyInfo(contact.inboundMailboxId),
                    groupId = groupId,
                    groupName = trimmed,
                ),
            )
        }
    }

    /**
     * Leave a group. Sends `group_member_left` (with own fingerprint)
     * to every member, then deletes the group locally. Available to
     * any member, admin or not — when the admin leaves, the group
     * continues to exist on members' devices but has no admin
     * (subsequent add/rename operations from anyone will be ignored
     * by every other member's auth gate).
     */
    suspend fun leaveGroup(groupId: String) {
        checkAlive()
        val group = persistence.loadGroup(groupId)
            ?: throw IllegalStateException("unknown groupId: $groupId")
        val members = persistence.loadGroupMembers(groupId)
            .filter { it.fingerprint != identity.fingerprint }

        val timestamp = Clock.System.now().toString()
        for (m in members) {
            val contact = sessionForFingerprint(m.fingerprint) ?: continue
            sendGroupInnerPayload(
                contact = contact,
                payload = InnerPayload(
                    type = "group_member_left",
                    timestamp = timestamp,
                    replyInfo = myReplyInfo(contact.inboundMailboxId),
                    groupId = groupId,
                    groupMemberFingerprint = identity.fingerprint,
                ),
            )
        }
        persistence.deleteGroup(groupId)
        check(group.groupId == groupId) // touch group to silence unused var
    }

    /** Snapshot the local view of a group (returns null if unknown). */
    suspend fun groupSnapshot(groupId: String): GroupEntry? =
        persistence.loadGroup(groupId)?.toEntry()

    /** Members of a group, as the local device sees them. */
    suspend fun groupMembers(groupId: String): List<GroupMemberEntry> =
        persistence.loadGroupMembers(groupId).map { it.toEntry() }

    /** Full message history for a group. */
    suspend fun groupMessageHistory(groupId: String): List<GroupMessageEntry> =
        persistence.loadGroupMessages(groupId).map { it.toEntry() }

    /** Every group known to this device. */
    suspend fun allGroups(): List<GroupEntry> =
        persistence.loadGroups().map { it.toEntry() }

    // ── Group internals ─────────────────────────────────────────────────────

    /**
     * Encrypt + send an [InnerPayload] of any group_* type over the
     * existing pairwise Double Ratchet channel for [contact]. Mirrors
     * the encrypt/envelope/relay path of [sendMessage] but does NOT
     * touch the per-contact `message` table — group payloads are stored
     * separately (see [saveGroupMessage]) or live as in-memory
     * state-machine events (member-add, leave, rename).
     */
    private suspend fun sendGroupInnerPayload(
        contact: ContactSession,
        payload: InnerPayload,
    ): Long {
        val plaintextBytes = KhordJson.encodeToString(InnerPayload.serializer(), payload)
            .encodeToByteArray()
        val encrypted = contact.session.encrypt(plaintextBytes)
        val envelope = WireEnvelope.Ratchet(
            header = Base64Std.encode(encrypted.headerBytes),
            ciphertext = Base64Std.encode(encrypted.ciphertext),
        )
        val envelopeBytes = KhordJson
            .encodeToString(WireEnvelope.serializer(), envelope as WireEnvelope)
            .encodeToByteArray()
        val rs = if (contact.outboundRelayServer == relayServerUrl) {
            rsClient
        } else {
            RelayServerClient(http, contact.outboundRelayServer)
        }
        val sequence = rs.sendMessage(contact.outboundMailboxId, envelopeBytes)
        persistSession(contact)
        return sequence
    }

    /**
     * `reply_info` block carrying this user's coordinates + display name
     * — every outbound payload includes it (one-to-one or group).
     */
    private fun myReplyInfo(inboundMailbox: String): ReplyInfo =
        ReplyInfo(
            mailbox = inboundMailbox,
            relayServer = relayServerUrl,
            keyServer = keyServerUrl,
            fingerprint = identity.fingerprint,
            displayName = displayName,
        )

    /** Find an active ContactSession by fingerprint, or null if not friends. */
    private fun sessionForFingerprint(fp: String): ContactSession? =
        sessionsByInboundMailbox.values.firstOrNull { it.contactFingerprint == fp }

    /**
     * Group payload dispatch helpers — invoked from [receiveMessages]
     * when the decoded payload's `type` matches a group_* discriminator.
     * Each handler is responsible for applying the side effect; the
     * outer loop still acks the message regardless.
     */
    private suspend fun handleGroupInvite(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        // Acceptance gate: if the sender is still PENDING, we DON'T
        // create the group yet — the user hasn't decided whether to
        // talk to this person. Stash the invite on the contact row;
        // [acceptContact] re-runs this handler after promoting the
        // contact to ACCEPTED. Without this gate, a stranger could
        // populate the local group list (and trigger group-message
        // notifications via the eventual group-notif feature)
        // without ever passing the accept screen.
        val info = contactsByFingerprint[contact.contactFingerprint]
        if (info != null && info.status == org.khord.shared.storage.ContactStatus.PENDING) {
            // NOTE: only the most recent deferred payload is kept — if a
            // pending contact sends multiple group_invites before being
            // accepted, only the latest survives. The user spec didn't
            // call for queueing; if it becomes a real problem a separate
            // pending_payloads table is the natural next step.
            val asJson = KhordJson.encodeToString(InnerPayload.serializer(), payload)
            persistence.setContactPendingPayload(contact.contactFingerprint, asJson)
            contactsByFingerprint[contact.contactFingerprint] =
                info.copy(pendingPayload = asJson)
            return
        }
        applyGroupInvite(contact.contactFingerprint, payload)
    }

    /**
     * The actual group-creation side effect of a group_invite. Factored
     * out so [acceptContact] can replay a deferred invite without going
     * through the sender-status gate a second time (and without
     * needing to find a live ContactSession just to read the
     * fingerprint).
     */
    private suspend fun applyGroupInvite(senderFingerprint: String, payload: InnerPayload) {
        val groupId = payload.groupId ?: return
        val groupName = payload.groupName ?: return
        val membersList = payload.members ?: return
        persistence.saveGroup(
            groupId = groupId,
            groupName = groupName,
            createdByFingerprint = senderFingerprint,
            isAdmin = false,
        )
        for (m in membersList) {
            persistence.addGroupMember(groupId, m.fingerprint, m.displayName)
        }
        // Make sure self is on the list — defensive; the inviter SHOULD
        // include us already.
        persistence.addGroupMember(groupId, identity.fingerprint, displayName)
    }

    private suspend fun handleGroupMessage(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        val groupId = payload.groupId ?: return
        val body = payload.body ?: return
        // Blocked group member: hide their messages locally. We still
        // acked the envelope off the relay; we just don't store this
        // into the group log. Other members are unaffected.
        if (contactsByFingerprint[contact.contactFingerprint]?.blocked == true) return
        // Drop messages for groups we don't know about — likely the
        // invite arrived out of order or never reached us.
        val group = persistence.loadGroup(groupId) ?: return
        check(group.groupId == groupId)
        val senderName = payload.replyInfo?.displayName
            ?: contactsByFingerprint[contact.contactFingerprint]?.displayName
            ?: ""
        persistence.saveGroupMessage(
            groupId = groupId,
            senderFingerprint = contact.contactFingerprint,
            senderDisplayName = senderName,
            body = body,
            timestamp = payload.timestamp,
            direction = org.khord.shared.storage.MessageDirection.RECEIVED,
            messageUuid = payload.messageUuid,
            replyToUuid = payload.replyToUuid,
        )
        // Also keep the per-member display_name in sync — fresh info.
        if (senderName.isNotEmpty()) {
            persistence.addGroupMember(
                groupId = groupId,
                fingerprint = contact.contactFingerprint,
                displayName = senderName,
            )
        }
    }

    private suspend fun handleGroupMemberAdded(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        val groupId = payload.groupId ?: return
        val added = payload.added ?: return
        val group = persistence.loadGroup(groupId) ?: return
        // Admin-auth gate: ignore unless the sender is the group's admin.
        if (contact.contactFingerprint != group.createdByFingerprint) return
        persistence.addGroupMember(groupId, added.fingerprint, added.displayName)
    }

    private suspend fun handleGroupMemberLeft(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        val groupId = payload.groupId ?: return
        val leaverFp = payload.groupMemberFingerprint ?: return
        val group = persistence.loadGroup(groupId) ?: return
        // Two legit cases: leaver is leaving themselves, OR admin is
        // removing a member. Otherwise ignore.
        val isSelfLeave = leaverFp == contact.contactFingerprint
        val isAdminRemove = contact.contactFingerprint == group.createdByFingerprint
        if (!isSelfLeave && !isAdminRemove) return
        persistence.removeGroupMember(groupId, leaverFp)
    }

    private suspend fun handleGroupNameChanged(
        contact: ContactSession,
        payload: InnerPayload,
    ) {
        val groupId = payload.groupId ?: return
        val newName = payload.groupName ?: return
        val group = persistence.loadGroup(groupId) ?: return
        if (contact.contactFingerprint != group.createdByFingerprint) return
        persistence.updateGroupName(groupId, newName)
    }

    /**
     * Generate a fresh group id: 16 random bytes hex-encoded → 32 chars.
     * Doesn't need to be cryptographically secret (group_id is shared
     * with members) but should be globally unique with overwhelming
     * probability. `Random.nextBytes` is good enough — collisions across
     * 16 bytes are astronomically unlikely.
     */
    /**
     * Return type of [receiveInitialBlobInternal] — the new session,
     * the decoded first text, and the sender-issued UUID of that
     * first message (null when the sender is pre-alpha.14).
     */
    private data class InitialReceiveResult(
        val session: ContactSession,
        val text: String,
        val messageUuid: String?,
    )

    private fun newGroupId(): String {
        val bytes = kotlin.random.Random.nextBytes(16)
        return bytes.joinToString("") { ((it.toInt() and 0xff)).toString(16).padStart(2, '0') }
    }

    /**
     * Per-message UUID stamped on every outgoing alpha.14+ text and
     * group_message. 128 bits of random — collision-resistant under
     * any realistic load. Hex-encoded with dashes (RFC 4122-ish but
     * without the version + variant bits since we don't depend on
     * that semantics anywhere). Same generator on Android and JVM.
     */
    private fun newMessageUuid(): String {
        val bytes = kotlin.random.Random.nextBytes(16)
        val hex = bytes.joinToString("") {
            ((it.toInt() and 0xff)).toString(16).padStart(2, '0')
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
            "${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    // ─── internals ────────────────────────────────────────────────────────

    /**
     * Cached lazy challenge-response token mint.
     * Persists the token so a fresh process doesn't have to re-mint after
     * a restart unless the previous token has expired.
     */
    private suspend fun obtainKeyServerToken(): String {
        keyServerToken?.let { return it }
        val challenge = ksClient.requestChallenge(identity.fingerprint)
        val signature = com.ionspin.kotlin.crypto.signature.Signature
            .detached(challenge.toUByteArray(), identity.ed25519SecretKey.toUByteArray())
            .toByteArray()
        val token = ksClient.verifyChallenge(
            fingerprint = identity.fingerprint,
            challenge = challenge,
            signature = signature,
            identityPublicKey = identity.ed25519PublicKey,
        )
        keyServerToken = token
        // Stateless tokens have a known TTL (15 min) but we don't know the
        // server's clock; record the current-time + a conservative 14 min
        // window so callers can expire on their own clock if they care.
        persistence.saveKeyServerToken(
            token = token,
            expiresAt = (kotlinx.datetime.Clock.System.now() +
                kotlin.time.Duration.parse("PT14M")).toString(),
        )
        return token
    }

    /** Mint a fresh inbound mailbox on MY relay server. */
    private suspend fun createInboundMailbox(): Pair<String, String> {
        val params = rsClient.powParams()
        val mailboxId = Mailboxes.newId()
        val nonce = PowMiner.mine(mailboxId, params.difficultyBits)
        val token = rsClient.createMailbox(mailboxId, nonce)
        return mailboxId to token
    }

    /** Decode a `blob` that came from the relay (or was already raw bytes). */
    private fun decodeEnvelope(blob: ByteArray): WireEnvelope =
        try {
            KhordJson.decodeFromString(WireEnvelope.serializer(), blob.decodeToString())
        } catch (e: Exception) {
            throw ProtocolError.WireFormatError("envelope decode failed", e)
        }

    private fun decodeInnerPayloadText(payload: InnerPayload): String {
        if (payload.type != "text") throw ProtocolError.UnsupportedPayloadType(payload.type)
        return payload.body ?: throw ProtocolError.WireFormatError("text payload missing body")
    }

    /**
     * Save a "session was reset" marker into the local message log for
     * [fingerprint]. Direction is RECEIVED so it appears on the
     * counterparty's bubble side — semantically the reset was driven
     * by something the OTHER party did (re-install, recovery), and the
     * local user is the passive observer. The body uses a stable
     * prefix that the chat layer can detect later if we want to
     * upgrade rendering without a schema change.
     */
    private suspend fun saveSessionResetMarker(fingerprint: String) {
        persistence.saveMessage(
            contactFingerprint = fingerprint,
            direction = org.khord.shared.storage.MessageDirection.RECEIVED,
            body = SESSION_RESET_MARKER_BODY,
            timestamp = kotlinx.datetime.Clock.System.now().toString(),
        )
    }

    /**
     * Process an X3DH initial that landed on an ALREADY-bound inbound
     * mailbox — the seed-phrase-recovery / app-reinstall path.
     *
     * Verifies that the new initial's identity hashes to the same
     * fingerprint we already know for [oldContact] (anti-impersonation
     * gate — without this, anyone who knows the contact's public
     * identity key could force a session reset and DoS the channel).
     *
     * On verification success: runs [X3dh.respond] to derive a fresh
     * shared secret, bootstraps a new ratchet via
     * [Session.fromResponder], decrypts the first message, and returns
     * a new [ContactSession] keyed by the SAME inbound mailbox (we
     * still own that mailbox — only the counterparty's state was
     * lost). The replaced session is also written through to
     * persistence (UPSERT on contact_fingerprint PK overwrites the
     * old session row).
     *
     * Caller is responsible for inserting the result into
     * [sessionsByInboundMailbox] in place of [oldContact].
     *
     * Inserts a "session reset" marker into the message log BEFORE
     * the decrypted first message so the chat reads naturally on the
     * user's next visit.
     *
     * On fingerprint mismatch, throws [ProtocolError.WireFormatError]
     * — the caller should log and skip the envelope (no reset).
     *
     * See ADR 025 for design rationale.
     */
    private suspend fun applyX3dhInitialReset(
        oldContact: ContactSession,
        envelope: WireEnvelope.X3dhInitial,
    ): Pair<ContactSession, String> {
        val ikA = Base64Std.decode(envelope.ikA)
        val ekA = Base64Std.decode(envelope.ekA)
        val initiatorFp = identityFingerprint(ikA)
        if (initiatorFp != oldContact.contactFingerprint) {
            throw ProtocolError.WireFormatError(
                "X3DH initial fingerprint $initiatorFp does not match " +
                "bound mailbox owner ${oldContact.contactFingerprint} — refusing reset",
            )
        }

        val mySpkSecret = spkSecret
            ?: throw IllegalStateException("not registered — call register() first")
        require(envelope.spkId == spkKeyId) { "unknown SPK id: ${envelope.spkId}" }
        val opkSecret = envelope.opkId?.let {
            opkSecretByKeyId[it]
                ?: throw IllegalStateException("unknown OPK id: $it")
        }

        val sk = X3dh.respond(
            X3dh.ResponderInput(
                initiatorIdentityKeyEd25519 = ikA,
                initiatorEphemeralPublicKey = ekA,
                responderIdentity = identity,
                signedPreKeySecret = mySpkSecret,
                oneTimePreKeySecret = opkSecret,
            )
        )

        // OPK forward secrecy: wipe + remove the consumed OPK from
        // local + persisted state. Matches the original respond path.
        if (envelope.opkId != null) {
            val secret = opkSecretByKeyId.remove(envelope.opkId)
            secret?.wipe()
            persistence.deleteOneTimePreKey(envelope.opkId)
        }

        val ad = X3dh.associatedDataFor(ikA, identity)
        val newSession = Session.fromResponder(
            sk = sk,
            bobSignedPreKeyPair = X25519KeyPair(spkPublic!!, mySpkSecret),
            associatedData = ad,
        )

        val plaintextBytes = newSession.decrypt(
            headerBytes = Base64Std.decode(envelope.header),
            ciphertext = Base64Std.decode(envelope.ciphertext),
        )
        val payload = KhordJson.decodeFromString(
            InnerPayload.serializer(),
            plaintextBytes.decodeToString(),
        )
        val replyInfo = payload.replyInfo
            ?: throw ProtocolError.WireFormatError(
                "X3DH initial from $initiatorFp missing reply_info on reset path",
            )
        require(replyInfo.fingerprint == initiatorFp) {
            "reply_info.fingerprint ${replyInfo.fingerprint} != envelope.ik_a hash $initiatorFp"
        }
        val text = decodeInnerPayloadText(payload)

        // Update outbound coordinates from the new reply_info — the
        // counterparty almost certainly minted a fresh inbound mailbox
        // on their side as part of the recovery flow, so the old
        // outboundMailboxId on `oldContact` is stale.
        val updatedQr = QrPayload(
            identityKey = envelope.ikA,
            fingerprint = initiatorFp,
            keyServer = replyInfo.keyServer,
            relayServer = replyInfo.relayServer,
            relayMailbox = replyInfo.mailbox,
        )
        // Preserve acceptance status across the reset. The reset path
        // only fires for an already-known contact (we just verified
        // the fingerprint matches `oldContact.contactFingerprint`),
        // so the status MUST already exist — but fall back to PENDING
        // defensively if the in-memory map is somehow out of sync.
        val existingInfo = contactsByFingerprint[initiatorFp]
        val existingStatus = existingInfo?.status
            ?: org.khord.shared.storage.ContactStatus.PENDING
        // Capture verified status BEFORE the upsert so we know whether
        // to surface the "your prior verification just got dropped"
        // banner. The upsert preserves verified=true (per the
        // pending_payload + verified preservation pattern in
        // DbPersistence.saveContact), but we want to FORCE it to false
        // on key change — that's the whole point of the reset hook.
        val wasVerified = existingInfo?.verified == true
        contactsByFingerprint[initiatorFp] =
            ContactInfo(updatedQr, replyInfo.displayName, existingStatus)
        persistence.saveContact(updatedQr, replyInfo.displayName, existingStatus)
        // Force-clear verified after the upsert. Done unconditionally;
        // a no-op when wasVerified=false, plus defensive against the
        // upsert preserving a stale verified=true if the in-memory
        // copy was somehow out of sync with persistence.
        persistence.setContactVerified(initiatorFp, false)
        contactsByFingerprint[initiatorFp] =
            contactsByFingerprint[initiatorFp]?.copy(verified = false)
                ?: contactsByFingerprint[initiatorFp]!!

        val newContact = ContactSession(
            contactIdentityKey = ikA,
            contactFingerprint = initiatorFp,
            outboundMailboxId = replyInfo.mailbox,
            outboundRelayServer = replyInfo.relayServer,
            // OUR mailbox + bearer stay the same — we still own this
            // mailbox; only the counterparty's state was lost.
            inboundMailboxId = oldContact.inboundMailboxId,
            inboundBearerToken = oldContact.inboundBearerToken,
            session = newSession,
            lastFetchedSequence = oldContact.lastFetchedSequence,
        )
        // Save the marker BEFORE the decrypted text so the chat order
        // reads naturally: ... old messages ... [reset marker] ...
        // new first message ...
        saveSessionResetMarker(initiatorFp)
        // If the contact had been fingerprint-verified before this
        // reset, surface a SECOND system message that calls out the
        // verification drop specifically. The existing session-reset
        // marker is more generic; the verification-specific text
        // makes clear "the trust decision you made earlier no longer
        // applies — someone may be impersonating, or your contact
        // recovered from seed phrase." Logged via DiagnosticLog so
        // the field bug-report trail captures the event.
        if (wasVerified) {
            val displayLabel = replyInfo.displayName.ifEmpty {
                initiatorFp.take(8) + "…" + initiatorFp.takeLast(8)
            }
            persistence.saveMessage(
                contactFingerprint = initiatorFp,
                direction = org.khord.shared.storage.MessageDirection.RECEIVED,
                body = "Security verification has been reset. " +
                    "$displayLabel's identity key has changed.",
                timestamp = kotlinx.datetime.Clock.System.now().toString(),
            )
            org.khord.shared.diagnostic.commonDiagnosticLog(
                "Khord",
                "Verified flag cleared on session reset for $initiatorFp " +
                    "(key change / seed recovery / impersonation).",
            )
        }
        persistence.saveMessage(
            contactFingerprint = initiatorFp,
            direction = org.khord.shared.storage.MessageDirection.RECEIVED,
            body = text,
            timestamp = payload.timestamp,
            messageUuid = payload.messageUuid,
        )
        persistSession(newContact)
        return newContact to text
    }

    private fun identityFingerprint(ed25519Pub: ByteArray): String =
        com.ionspin.kotlin.crypto.hash.Hash
            .sha256(ed25519Pub.toUByteArray()).toByteArray()
            .joinToString("") { ((it.toInt() and 0xff)).toString(16).padStart(2, '0') }

    /** Mirror the live ratchet state of `contact` to the persistence layer. */
    private suspend fun persistSession(contact: ContactSession) {
        persistence.saveSession(
            org.khord.shared.storage.SessionRecord(
                contactFingerprint = contact.contactFingerprint,
                inboundMailbox = contact.inboundMailboxId,
                inboundBearerToken = contact.inboundBearerToken,
                outboundMailbox = contact.outboundMailboxId,
                outboundRelayServer = contact.outboundRelayServer,
                associatedData = contact.session.associatedData,
                ratchetState = contact.session.ratchetStateForPersistence(),
                lastFetchedSequence = contact.lastFetchedSequence,
                updatedAt = kotlinx.datetime.Clock.System.now().toString(),
            )
        )
    }

    /**
     * Local message history for a contact — read straight from the DB so the
     * UI doesn't need to re-implement loading.
     */
    suspend fun messageHistory(contactFingerprint: String): List<MessageEntry> {
        checkAlive()
        return persistence.loadMessages(contactFingerprint).map {
            MessageEntry(
                id = it.id,
                direction = if (it.direction == org.khord.shared.storage.MessageDirection.SENT)
                    MessageEntry.Direction.SENT else MessageEntry.Direction.RECEIVED,
                body = it.body,
                timestamp = it.timestamp,
                messageUuid = it.messageUuid,
                edited = it.edited,
                replyToUuid = it.replyToUuid,
            )
        }
    }

    /**
     * Wipe absolutely everything: persistence is told to wipe and close,
     * in-memory secrets are zeroed, and the orchestrator becomes inert.
     * Subsequent calls on this instance throw — construct a new one.
     */
    suspend fun panic() {
        if (panicked) return
        panicked = true
        // 1. Wipe persisted state + delete DB file (DbPersistence) / clear caches.
        try { persistence.panic() } catch (_: Throwable) { /* still wipe RAM */ }
        // 2. Wipe in-memory secrets.
        spkSecret?.wipe(); spkSecret = null
        spkPublic?.fill(0); spkPublic = null
        spkKeyId = -1
        for (secret in opkSecretByKeyId.values) secret.wipe()
        opkSecretByKeyId.clear()
        contactsByFingerprint.clear()
        sessionsByInboundMailbox.clear()
        pendingInboundMailboxes.clear()
        keyServerToken = null
    }

    /** Public read of the current contact session list (live, in-memory). */
    fun contacts(): List<ContactSession> = sessionsByInboundMailbox.values.toList()

    /**
     * Snapshot the (mailbox, token, fingerprint, relay-URL) tuples needed
     * to drive [org.khord.shared.protocol.client.PushSignalListener] —
     * one per active contact session. Computed off the same map as
     * [contacts]; safe to call from any thread that already has access
     * to this [Messaging] instance.
     *
     * Exposed as a typed list rather than letting the caller reach into
     * each [ContactSession] because [ContactSession.inboundBearerToken]
     * is module-internal.
     */
    fun pushSubscriptions(): List<org.khord.shared.protocol.client.PushSignalListener.Subscription> =
        sessionsByInboundMailbox.values.map {
            org.khord.shared.protocol.client.PushSignalListener.Subscription(
                contactFingerprint = it.contactFingerprint,
                mailboxId = it.inboundMailboxId,
                bearerToken = it.inboundBearerToken,
                relayServerUrl = relayServerUrl,
            )
        }

    /** This user's own identity fingerprint. Stable across app launches. */
    val myFingerprint: String get() = identity.fingerprint

    /** Key Server URL this identity registered against. Stable for life of identity. */
    val myKeyServerUrl: String get() = keyServerUrl

    /** Relay Server URL this identity registered against. Stable for life of identity. */
    val myRelayServerUrl: String get() = relayServerUrl

    /** Test-only: lookup a session by inbound mailbox. */
    internal fun contactByInboundMailbox(mailboxId: String): ContactSession? =
        sessionsByInboundMailbox[mailboxId]

    companion object {
        /**
         * Body inserted into the local message history when an inbound
         * X3DH initial forces a session reset (seed-phrase recovery or
         * app reinstall on the counterparty's side). Surfaced as a
         * regular RECEIVED message so it shows up inline in the chat
         * — see [saveSessionResetMarker]. Stable string so chat-layer
         * code can pattern-match the prefix to apply special styling
         * later without a schema migration.
         */
        const val SESSION_RESET_MARKER_BODY =
            "🔄 Session reset — the other party may have re-installed " +
            "Khord or recovered from a seed phrase. Earlier messages " +
            "stay in this chat but cannot be replied to under the old " +
            "session."

        /**
         * Public constructor — fresh in-memory orchestrator (no persistence).
         * Callers that want durable state should use the internal
         * [createWithPersistence] / [load] factories from within the shared
         * module (the persistence layer is not part of Khord's public API yet).
         */
        fun create(
            identity: IdentityKey,
            keyServerUrl: String,
            relayServerUrl: String,
            http: HttpClient,
            displayName: String = "Anonymous",
        ): Messaging = Messaging(
            identity, keyServerUrl, relayServerUrl, http,
            persistence = org.khord.shared.storage.InMemoryPersistence(),
            displayName = displayName,
        )

        /** Internal constructor with explicit persistence (e.g. DbPersistence). */
        internal fun createWithPersistence(
            identity: IdentityKey,
            keyServerUrl: String,
            relayServerUrl: String,
            http: HttpClient,
            persistence: org.khord.shared.storage.Persistence,
            displayName: String = "Anonymous",
        ): Messaging = Messaging(
            identity, keyServerUrl, relayServerUrl, http, persistence, displayName,
        )

        /**
         * Reconstruct a previously-registered Messaging instance from
         * `persistence`. Returns null if no identity has been saved yet
         * (caller should fall back to fresh-registration flow).
         *
         * Loads identity, SPK + OPK secrets, contacts, pending mailboxes,
         * sessions, and the cached key-server token from the store.
         * In-memory state mirrors the loaded data; subsequent state changes
         * re-persist as usual.
         */
        internal suspend fun load(
            http: HttpClient,
            persistence: org.khord.shared.storage.Persistence,
        ): Messaging? {
            val record = persistence.loadIdentity() ?: return null
            val m = Messaging(
                identity = record.identity,
                keyServerUrl = record.keyServerUrl,
                relayServerUrl = record.relayServerUrl,
                http = http,
                persistence = persistence,
                displayName = record.displayName,
            )
            m.needsServerRegistration = !record.registeredAtServer
            persistence.loadSignedPreKey()?.let { spk ->
                m.spkKeyId = spk.keyId
                m.spkPublic = spk.publicKey.copyOf()
                m.spkSecret = spk.secretKey.copyOf()
            }
            for ((id, secret) in persistence.loadAllOpkSecrets()) {
                m.opkSecretByKeyId[id] = secret
            }
            for (info in persistence.loadAllContacts()) {
                m.contactsByFingerprint[info.qr.fingerprint] = info
            }
            for ((mb, tok) in persistence.loadPendingMailboxes()) {
                m.pendingInboundMailboxes[mb] = tok
            }
            for (session in persistence.loadAllSessions()) {
                val contactIdEd = m.contactsByFingerprint[session.contactFingerprint]
                    ?.let { Base64Std.decode(it.qr.identityKey) }
                    ?: continue   // Orphan session — skip; should not happen.
                val cs = ContactSession(
                    contactIdentityKey = contactIdEd,
                    contactFingerprint = session.contactFingerprint,
                    outboundMailboxId = session.outboundMailbox,
                    outboundRelayServer = session.outboundRelayServer,
                    inboundMailboxId = session.inboundMailbox,
                    inboundBearerToken = session.inboundBearerToken,
                    session = Session.fromExistingRatchet(
                        ratchetState = session.ratchetState,
                        associatedData = session.associatedData,
                    ),
                    lastFetchedSequence = session.lastFetchedSequence,
                )
                m.sessionsByInboundMailbox[session.inboundMailbox] = cs
            }
            persistence.loadKeyServerToken()?.let { m.keyServerToken = it.token }
            return m
        }
    }
}

/**
 * A locally-stored message exposed to the UI — read-only view of one
 * row in the messages table.
 */
data class MessageEntry(
    val id: Long,
    val direction: Direction,
    val body: String,
    val timestamp: String,
    /**
     * Sender-issued UUID for this message. Null on pre-alpha.14
     * messages (those existed before the column was added and remain
     * un-editable). Required to invoke [Messaging.editMessage].
     */
    val messageUuid: String? = null,
    /**
     * True if this message has been edited since it was originally
     * sent. UI surfaces a small "(edited)" badge below the bubble
     * when true.
     */
    val edited: Boolean = false,
    /**
     * If this message is a quote-reply, the [messageUuid] of the
     * message it replies to. The UI resolves the quoted text by
     * finding that UUID in the loaded history. Null = not a reply.
     */
    val replyToUuid: String? = null,
) {
    enum class Direction { SENT, RECEIVED }
}

// ── Group public DTOs (ADR 023) ──────────────────────────────────────────────
// Same pattern as MessageEntry: the storage layer's records are internal;
// the orchestrator re-maps to these public types so the Android module
// can read them without breaking module-visibility rules.

data class GroupEntry(
    val groupId: String,
    val groupName: String,
    val createdByFingerprint: String,
    val isAdmin: Boolean,
    val createdAt: String,
)

data class GroupMemberEntry(
    val fingerprint: String,
    val displayName: String,
)

data class GroupMessageEntry(
    val id: Long,
    val senderFingerprint: String,
    val senderDisplayName: String,
    val body: String,
    val timestamp: String,
    val direction: MessageEntry.Direction,
    val messageUuid: String? = null,
    val edited: Boolean = false,
    val replyToUuid: String? = null,
)

internal fun org.khord.shared.storage.GroupRecord.toEntry(): GroupEntry =
    GroupEntry(
        groupId = groupId,
        groupName = groupName,
        createdByFingerprint = createdByFingerprint,
        isAdmin = isAdmin,
        createdAt = createdAt,
    )

internal fun org.khord.shared.storage.GroupMemberRecord.toEntry(): GroupMemberEntry =
    GroupMemberEntry(fingerprint = fingerprint, displayName = displayName)

internal fun org.khord.shared.storage.GroupMessageRecord.toEntry(): GroupMessageEntry =
    GroupMessageEntry(
        id = id,
        senderFingerprint = senderFingerprint,
        senderDisplayName = senderDisplayName,
        body = body,
        timestamp = timestamp,
        direction = if (direction == org.khord.shared.storage.MessageDirection.SENT)
            MessageEntry.Direction.SENT else MessageEntry.Direction.RECEIVED,
        messageUuid = messageUuid,
        edited = edited,
        replyToUuid = replyToUuid,
    )

// Tiny helper: exposed only so Messaging can validate QR payload bindings without
// re-implementing the SHA-256-and-hex pipeline. Using fromHex() here would defeat
// the purpose; we want the actual hash of the bytes.
internal fun org.khord.shared.crypto.IdentityKey.Companion.fromBytes_internalCheck(
    ed25519Pub: ByteArray, claimedFingerprint: String,
): Boolean {
    val computed = com.ionspin.kotlin.crypto.hash.Hash
        .sha256(ed25519Pub.toUByteArray()).toByteArray()
    return computed.contentEquals(claimedFingerprint.fromHex())
}
