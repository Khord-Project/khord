package org.khord.android.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khord.android.AppContainer
import org.khord.android.media.ImageProcessor
import org.khord.android.media.MediaCache
import org.khord.shared.protocol.ProtocolError
import org.khord.shared.protocol.orchestrator.MessageEntry
import java.io.IOException

/**
 * Per-chat ViewModel. Owns the 5s fallback poll loop AND observes
 * [AppContainer.pushConnected] so the poll can be suppressed when the
 * push service has a healthy WebSocket for this fingerprint.
 *
 * The chat polling stays as a belt-and-braces fallback — if Android
 * kills the foreground service (rare) or the WebSocket flaps, the
 * 5s poll resumes and the user still gets messages. Pull-to-refresh
 * is independent of either path.
 *
 * Dead-contact handling: send or receive failures that look like the
 * contact's relay endpoint is gone (HTTP 404, connection refused /
 * any IOException) flip [UiState.contactStatus] to Unavailable. The
 * UI then disables the composer, shows an inline banner, and keeps
 * the message history visible (the user might want to re-read it).
 * Polling stops once Unavailable — we don't keep hammering a dead
 * endpoint every 5 s. The status resets if the user backs out and
 * re-enters the screen (fresh ViewModel).
 */

class ChatViewModel(
    private val contactFingerprint: String,
) : ViewModel() {

    enum class ContactStatus {
        /** Normal operation — sending and polling both proceed. */
        Available,

        /** Recent send or receive failure pattern-matched as "endpoint gone". */
        Unavailable,
    }

    data class UiState(
        val messages: List<MessageEntry> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null,
        /**
         * The Throwable behind [error], retained so the user can open
         * a BugReportDialog from the chat with a real stack trace.
         * Cleared in lockstep with [error] on success or dismiss.
         */
        val errorCause: Throwable? = null,
        /** Display name learned from the contact's reply_info, or null if unknown yet. */
        val contactDisplayName: String? = null,
        /** Reachability heuristic for the contact's relay endpoint. */
        val contactStatus: ContactStatus = ContactStatus.Available,
        /**
         * Locally-marked verified flag. Updated alongside history on
         * every reload — typically refreshes within 5s of a verify
         * action because the polling loop ticks it. Drives the small
         * shield-check badge in the chat header.
         */
        val verified: Boolean = false,
        /** media_ids whose full image is currently being fetched/decrypted (ADR 029). */
        val downloadingMediaIds: Set<String> = emptySet(),
    )

    /** Called by ChatScreen after the user dismisses the bug-report dialog. */
    fun clearError() {
        _state.update { it.copy(error = null, errorCause = null) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var pollJob: Job? = null

    init {
        reloadHistory()
        // Listen for push-delivered messages targeted at this
        // fingerprint. Map → distinctUntilChanged so we only react
        // when our specific contact's counter ticks; drop(1) skips
        // the initial value (the reloadHistory() above already
        // covers the snapshot at construction time). On every tick,
        // reload the history from persistence — the push service
        // has already written the new row(s) by the time we observe.
        viewModelScope.launch {
            AppContainer.incomingMessageTick
                .map { it[contactFingerprint] ?: 0L }
                .distinctUntilChanged()
                .drop(1)
                .collect { reloadHistory() }
        }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            // Always drain once on open, EVEN IF the push socket is
            // connected. The push path only fires on a live "new message"
            // signal; a message that arrived while this device's socket
            // was down (app backgrounded, just-launched, flaky network) is
            // never re-signalled on reconnect, and the steady-state poll
            // below is suppressed whenever push is alive — so without this
            // the backlog would sit unfetched until the *next* new message.
            // Opening the conversation is exactly when the user expects to
            // see everything waiting for them.
            fetchOnce()
            // Also flush any of OUR messages queued offline (ADR 030) —
            // opening the chat is a natural retry point.
            runCatching { AppContainer.messaging?.drainOutbox() }
            while (isActive) {
                // No point polling a contact we've already marked
                // unavailable — wait for the user to back out and
                // come back if they want to retry.
                if (_state.value.contactStatus == ContactStatus.Unavailable) {
                    delay(5_000)
                    continue
                }
                // Suppress the 5s poll while the push service has a
                // healthy WebSocket for this contact. If it goes
                // unhealthy (or the service isn't running), the poll
                // resumes automatically on the next loop iteration.
                val pushAlive = contactFingerprint in AppContainer.pushConnected.value
                if (!pushAlive) {
                    fetchOnce()
                }
                delay(5_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Edit a previously-sent message in place. Best-effort fan-out:
     * the local copy updates regardless of network outcome (the
     * shared Messaging.editMessage applies persistence + send under
     * the same mutex), so the user sees the edit immediately. Other
     * recipients see the original until the edit envelope reaches
     * them.
     */
    fun edit(messageUuid: String, newBody: String) {
        val trimmed = newBody.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.editMessage(messageUuid, trimmed)
                    reloadHistoryLocked()
                }.onFailure { e ->
                    _state.update {
                        it.copy(
                            error = e.message ?: e::class.simpleName,
                            errorCause = e,
                        )
                    }
                }
            }
        }
    }

    fun send(text: String, replyToUuid: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Don't even try if the contact is already known unavailable —
        // the UI should have the input disabled, but guard defensively.
        if (_state.value.contactStatus == ContactStatus.Unavailable) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(sending = true, error = null) }
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    val contact = messaging.contacts().firstOrNull {
                        it.contactFingerprint == contactFingerprint
                    } ?: error("contact session not found")
                    messaging.sendMessage(contact, trimmed, replyToUuid)
                    reloadHistoryLocked()
                }.onFailure { e ->
                    if (looksLikeDeadContact(e)) {
                        _state.update {
                            it.copy(contactStatus = ContactStatus.Unavailable, error = null)
                        }
                    } else {
                        _state.update {
                            it.copy(
                                error = e.message ?: e::class.simpleName,
                                errorCause = e,
                            )
                        }
                    }
                }
                _state.update { it.copy(sending = false) }
            }
        }
    }

    /**
     * Send an image (ADR 029). Reads the picked [uri] on the IO
     * dispatcher, strips EXIF + downscales + thumbnails via
     * [ImageProcessor], hands the bytes to [Messaging.sendImageMessage]
     * (encrypt + upload + send), then caches the plaintext full image
     * locally so the sender can re-view it (the relay's one-time read
     * won't let it re-fetch its own blob).
     */
    fun sendImage(context: Context, uri: Uri, caption: String) {
        if (_state.value.contactStatus == ContactStatus.Unavailable) return
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(sending = true, error = null) }
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    val contact = messaging.contacts().firstOrNull {
                        it.contactFingerprint == contactFingerprint
                    } ?: error("contact session not found")
                    val processed = ImageProcessor.process(appContext, uri)
                    // Returns the message UUID — stable even when the send is
                    // queued offline (no media id assigned until upload).
                    val messageUuid = messaging.sendImageMessage(
                        contact = contact,
                        fullImageBytes = processed.full,
                        thumbnailBytes = processed.thumbnail,
                        caption = caption.trim(),
                    )
                    // Cache the plaintext full image (keyed by the UUID) + record
                    // its path so the sender's own bubble shows the full image
                    // immediately, online or queued.
                    val path = MediaCache.write(appContext, messageUuid, processed.full)
                    messaging.messageHistory(contactFingerprint)
                        .firstOrNull { it.messageUuid == messageUuid }
                        ?.let { messaging.setMessageImageCached(it.id, path) }
                    reloadHistoryLocked()
                }.onFailure { e ->
                    if (looksLikeDeadContact(e)) {
                        _state.update {
                            it.copy(contactStatus = ContactStatus.Unavailable, error = null)
                        }
                    } else {
                        _state.update {
                            it.copy(error = e.message ?: e::class.simpleName, errorCause = e)
                        }
                    }
                }
                _state.update { it.copy(sending = false) }
            }
        }
    }

    /**
     * Download + decrypt the full image for a received attachment, cache
     * it to private storage, and record the path. One-time relay read, so
     * this runs at most once per attachment; a 404 (already fetched, or
     * expired) surfaces as an error.
     */
    fun downloadImage(context: Context, messageId: Long, mediaId: String) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (mediaId in _state.value.downloadingMediaIds) return@withLock
                _state.update { it.copy(downloadingMediaIds = it.downloadingMediaIds + mediaId) }
            }
            runCatching {
                val messaging = AppContainer.messaging ?: error("not initialised")
                val media = messaging.messageHistory(contactFingerprint)
                    .firstOrNull { it.id == messageId }?.media
                    ?: error("attachment not found")
                val bytes = messaging.fetchImage(media)
                val path = MediaCache.write(appContext, mediaId, bytes)
                messaging.setMessageImageCached(messageId, path)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e::class.simpleName, errorCause = e) }
            }
            mutex.withLock {
                _state.update { it.copy(downloadingMediaIds = it.downloadingMediaIds - mediaId) }
                reloadHistoryLocked()
            }
        }
    }

    /** Manual "Retry" on a failed message (ADR 030). */
    fun retryMessage(messageUuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.retryFailedMessage(messageUuid, isGroup = false)
                    reloadHistoryLocked()
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    /** Manual "Delete" on a failed message (ADR 030) — drops queue row + bubble. */
    fun deleteMessage(messageUuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.deleteFailedMessage(messageUuid, isGroup = false)
                    reloadHistoryLocked()
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    private fun reloadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock { reloadHistoryLocked() }
        }
    }

    private suspend fun reloadHistoryLocked() {
        val messaging = AppContainer.messaging ?: return
        val history = messaging.messageHistory(contactFingerprint)
        val name = messaging.contactDisplayName(contactFingerprint)
        val verified = messaging.isContactVerified(contactFingerprint)
        _state.update {
            it.copy(
                messages = history,
                contactDisplayName = name,
                verified = verified,
            )
        }
    }

    private suspend fun fetchOnce() {
        mutex.withLock {
            runCatching {
                val messaging = AppContainer.messaging ?: return@runCatching
                val contact = messaging.contacts().firstOrNull {
                    it.contactFingerprint == contactFingerprint
                } ?: return@runCatching
                messaging.receiveMessages(contact)
                // Receive succeeded — clear any dead-contact streak so
                // a recovered server stops being muted in the contact list.
                AppContainer.recordReceiveSuccess(contactFingerprint)
                reloadHistoryLocked()
            }.onFailure { e ->
                AppContainer.recordReceiveFailure(contactFingerprint)
                if (looksLikeDeadContact(e)) {
                    _state.update {
                        it.copy(contactStatus = ContactStatus.Unavailable, error = null)
                    }
                } else {
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }
}

/**
 * Heuristic — does this exception look like the contact's relay
 * endpoint is gone? Currently:
 *  - [ProtocolError.HttpError] with HTTP 404 (mailbox not found)
 *  - any [IOException] (connection refused, timeout, DNS failure —
 *    OkHttp wraps these subclasses)
 *
 * Other protocol errors (auth fail on a wrong token, signature
 * failures, etc.) deliberately do NOT trigger unavailable status —
 * those are user-actionable, not "the other side disappeared".
 */
private fun looksLikeDeadContact(e: Throwable): Boolean {
    if (e is ProtocolError.HttpError && e.status == 404) return true
    if (e is IOException) return true
    // Walk one level into cause chain — OkHttp sometimes wraps in
    // a non-IOException at the Ktor boundary.
    val cause = e.cause
    if (cause is IOException) return true
    return false
}
