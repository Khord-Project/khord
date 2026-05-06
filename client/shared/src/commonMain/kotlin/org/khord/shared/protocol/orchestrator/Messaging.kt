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
import org.khord.shared.protocol.wire.InnerPayload
import org.khord.shared.protocol.wire.OneTimePreKeyDto
import org.khord.shared.protocol.wire.QrPayload
import org.khord.shared.protocol.wire.SignedPreKeyDto
import org.khord.shared.protocol.wire.WireEnvelope

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

    /** Contacts the user has scanned QR codes for, keyed by their fingerprint. */
    private val contactsByFingerprint = mutableMapOf<String, QrPayload>()

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
        persistence.saveIdentity(
            org.khord.shared.storage.IdentityRecord(
                identity = identity,
                keyServerUrl = keyServerUrl,
                relayServerUrl = relayServerUrl,
                createdAt = kotlinx.datetime.Clock.System.now().toString(),
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
                val (session, text) = receiveInitialBlobInternal(
                    myInboundMailbox = mailboxId,
                    bearerTokenForMailbox = token,
                    envelope = envelope,
                )
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

    /** Persist a contact's QR (called after scanning the QR out-of-band). */
    suspend fun storeContact(contactQr: QrPayload) {
        checkAlive()
        contactsByFingerprint[contactQr.fingerprint] = contactQr
        persistence.saveContact(contactQr)
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

        val contactQr = contactsByFingerprint[contactFingerprint]
            ?: throw IllegalStateException("contact not stored: $contactFingerprint")

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

        // Init my ratchet, encrypt the first inner payload.
        val session = Session.fromInitiator(initOut, cryptoBundle.signedPreKey.publicKey)
        val firstMessageTimestamp = Clock.System.now().toString()
        val payload = InnerPayload(
            type = "text",
            timestamp = firstMessageTimestamp,
            body = firstMessage,
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
    ): Pair<ContactSession, String> {
        val ikA = Base64Std.decode(envelope.ikA)
        val ekA = Base64Std.decode(envelope.ekA)
        val initiatorFp = identityFingerprint(ikA)

        val storedQr = contactsByFingerprint[initiatorFp]
            ?: throw IllegalStateException(
                "received X3DH initial from unknown fingerprint $initiatorFp — " +
                "caller must storeContact() the QR before first receive"
            )

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

        val contactSession = ContactSession(
            contactIdentityKey = ikA,
            contactFingerprint = initiatorFp,
            outboundMailboxId = storedQr.relayMailbox,
            outboundRelayServer = storedQr.relayServer,
            inboundMailboxId = myInboundMailbox,
            inboundBearerToken = bearerTokenForMailbox,
            session = session,
        )
        sessionsByInboundMailbox[myInboundMailbox] = contactSession
        persistSession(contactSession)
        return contactSession to text
    }

    /** Send a text message to the contact this session is bound to. */
    suspend fun sendMessage(contact: ContactSession, text: String): Long {
        checkAlive()
        val timestamp = Clock.System.now().toString()
        val payload = InnerPayload(type = "text", timestamp = timestamp, body = text)
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
        )
        persistSession(contact)

        return sequence
    }

    /**
     * Poll my inbound mailbox for messages from this contact. Returns the
     * decrypted plaintexts in sequence order. Acks the highest sequence.
     */
    suspend fun receiveMessages(contact: ContactSession): List<String> {
        checkAlive()
        val fetched = rsClient.fetchMessages(
            mailboxId = contact.inboundMailboxId,
            bearerToken = contact.inboundBearerToken,
            afterSequence = contact.lastFetchedSequence,
        )
        if (fetched.isEmpty()) return emptyList()

        val plaintexts = mutableListOf<String>()
        for (m in fetched) {
            val envelope = decodeEnvelope(Base64Std.decode(m.blob))
            val (header, ciphertext) = when (envelope) {
                is WireEnvelope.Ratchet -> Base64Std.decode(envelope.header) to
                        Base64Std.decode(envelope.ciphertext)
                is WireEnvelope.X3dhInitial -> Base64Std.decode(envelope.header) to
                        Base64Std.decode(envelope.ciphertext)
            }
            val plaintextBytes = contact.session.decrypt(header, ciphertext)
            val payload = KhordJson.decodeFromString(
                InnerPayload.serializer(),
                plaintextBytes.decodeToString(),
            )
            val text = decodeInnerPayloadText(payload)
            plaintexts += text
            persistence.saveMessage(
                contactFingerprint = contact.contactFingerprint,
                direction = org.khord.shared.storage.MessageDirection.RECEIVED,
                body = text,
                timestamp = payload.timestamp,
            )
        }

        val highestSequence = fetched.last().sequence
        rsClient.acknowledge(
            mailboxId = contact.inboundMailboxId,
            bearerToken = contact.inboundBearerToken,
            throughSequence = highestSequence,
        )
        contact.lastFetchedSequence = highestSequence
        persistence.updateLastFetchedSequence(contact.contactFingerprint, highestSequence)
        persistSession(contact)
        return plaintexts
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

    /** This user's own identity fingerprint. Stable across app launches. */
    val myFingerprint: String get() = identity.fingerprint

    /** Test-only: lookup a session by inbound mailbox. */
    internal fun contactByInboundMailbox(mailboxId: String): ContactSession? =
        sessionsByInboundMailbox[mailboxId]

    companion object {
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
        ): Messaging = Messaging(
            identity, keyServerUrl, relayServerUrl, http,
            persistence = org.khord.shared.storage.InMemoryPersistence(),
        )

        /** Internal constructor with explicit persistence (e.g. DbPersistence). */
        internal fun createWithPersistence(
            identity: IdentityKey,
            keyServerUrl: String,
            relayServerUrl: String,
            http: HttpClient,
            persistence: org.khord.shared.storage.Persistence,
        ): Messaging = Messaging(identity, keyServerUrl, relayServerUrl, http, persistence)

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
            for (contact in persistence.loadAllContacts()) {
                m.contactsByFingerprint[contact.fingerprint] = contact
            }
            for ((mb, tok) in persistence.loadPendingMailboxes()) {
                m.pendingInboundMailboxes[mb] = tok
            }
            for (session in persistence.loadAllSessions()) {
                val contactIdEd = m.contactsByFingerprint[session.contactFingerprint]
                    ?.let { Base64Std.decode(it.identityKey) }
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
) {
    enum class Direction { SENT, RECEIVED }
}

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
