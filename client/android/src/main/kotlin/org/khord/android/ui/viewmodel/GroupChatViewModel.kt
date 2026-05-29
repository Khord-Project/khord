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
import org.khord.shared.protocol.orchestrator.GroupEntry
import org.khord.shared.protocol.orchestrator.GroupMessageEntry

/**
 * Backs [org.khord.android.ui.screens.GroupChatScreen]. Reads the
 * group's snapshot, member list, and message history from persistence;
 * polls every 2 s for newly-arrived messages from peer pairwise channels
 * (the actual network fetch happens via [KhordPushService] + individual
 * [ChatViewModel]s, so this poll just re-reads the local DB).
 *
 * Sending a message fans the encrypted payload out to every member
 * with an active [org.khord.shared.protocol.orchestrator.ContactSession]
 * — non-friend members are silently skipped per ADR 023.
 */
class GroupChatViewModel(
    private val groupId: String,
) : ViewModel() {

    data class UiState(
        val group: GroupEntry? = null,
        val memberCount: Int = 0,
        val messages: List<GroupMessageEntry> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null,
        /**
         * The Throwable behind [error]. ChatScreen-parity: when this
         * is non-null the screen surfaces a "Report" button that opens
         * a BugReportDialog pre-populated with the stack trace. Cleared
         * by [clearError] when the dialog dismisses.
         */
        val errorCause: Throwable? = null,
        /** media_ids whose full image is currently being fetched/decrypted (ADR 029). */
        val downloadingMediaIds: Set<String> = emptySet(),
    )

    /** Called by GroupChatScreen after the user dismisses the bug-report dialog. */
    fun clearError() {
        _state.update { it.copy(error = null, errorCause = null) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var pollJob: Job? = null

    init {
        reload()
        // Observe per-contact incoming-message ticks. The push service
        // increments these after every successful drain that produced
        // ≥1 plaintext payload. Group messages currently never appear
        // in the "plaintext" set (they route through handleGroupMessage,
        // not the legacy text path), so the tick fires whenever a
        // group MEMBER sent a *direct* message — but reloading on any
        // tick is cheap (just a local DB re-read) and gives us the
        // same fast-refresh behaviour ChatScreen enjoys for the cases
        // that DO bump the tick. Reloading on every tick across all
        // fingerprints is also fine — coarse but correct, and the
        // group-membership filter would require an extra DB read.
        viewModelScope.launch {
            AppContainer.incomingMessageTick
                .map { it.values.sum() }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload() }
        }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                reloadLocked()
                delay(2_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Edit a previously-sent group message in place. Fans the edit
     * out to every member with an active session — recipients
     * offline at the time of edit see the original until their next
     * poll/push delivery.
     */
    fun edit(messageUuid: String, newBody: String) {
        val trimmed = newBody.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.editMessage(messageUuid, trimmed)
                    reloadLockedInternal()
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    fun send(text: String, replyToUuid: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(sending = true, error = null) }
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.sendGroupMessage(groupId, trimmed, replyToUuid)
                    reloadLockedInternal()
                }.onFailure { e ->
                    _state.update {
                        it.copy(
                            error = e.message ?: e::class.simpleName,
                            errorCause = e,
                        )
                    }
                }
                _state.update { it.copy(sending = false) }
            }
        }
    }

    /** Send an image to the group (ADR 029). See [ChatViewModel.sendImage]. */
    fun sendImage(context: Context, uri: Uri, caption: String) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(sending = true, error = null) }
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    val processed = ImageProcessor.process(appContext, uri)
                    val mediaId = messaging.sendGroupImageMessage(
                        groupId = groupId,
                        fullImageBytes = processed.full,
                        thumbnailBytes = processed.thumbnail,
                        caption = caption.trim(),
                    )
                    val path = MediaCache.write(appContext, mediaId, processed.full)
                    messaging.groupMessageHistory(groupId)
                        .firstOrNull { it.media?.mediaId == mediaId }
                        ?.let { messaging.setGroupMessageImageCached(it.id, path) }
                    reloadLockedInternal()
                }.onFailure { e ->
                    _state.update {
                        it.copy(error = e.message ?: e::class.simpleName, errorCause = e)
                    }
                }
                _state.update { it.copy(sending = false) }
            }
        }
    }

    /** Download + decrypt a received group image. See [ChatViewModel.downloadImage]. */
    fun downloadImage(context: Context, messageId: Long, mediaId: String) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (mediaId in _state.value.downloadingMediaIds) return@withLock
                _state.update { it.copy(downloadingMediaIds = it.downloadingMediaIds + mediaId) }
            }
            runCatching {
                val messaging = AppContainer.messaging ?: error("not initialised")
                val media = messaging.groupMessageHistory(groupId)
                    .firstOrNull { it.id == messageId }?.media
                    ?: error("attachment not found")
                val bytes = messaging.fetchImage(media)
                val path = MediaCache.write(appContext, mediaId, bytes)
                messaging.setGroupMessageImageCached(messageId, path)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e::class.simpleName, errorCause = e) }
            }
            mutex.withLock {
                _state.update { it.copy(downloadingMediaIds = it.downloadingMediaIds - mediaId) }
                reloadLockedInternal()
            }
        }
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock { reloadLockedInternal() }
        }
    }

    private suspend fun reloadLocked() {
        mutex.withLock { reloadLockedInternal() }
    }

    private suspend fun reloadLockedInternal() {
        val messaging = AppContainer.messaging ?: return
        val group = messaging.groupSnapshot(groupId)
        val members = messaging.groupMembers(groupId)
        val msgs = messaging.groupMessageHistory(groupId).map { m ->
            // Apply the local nickname override to sender labels:
            // local_display_name ?? stored sender_display_name. Falls
            // back to the stored name for senders who aren't direct
            // contacts (you can share a group with someone you haven't
            // added 1:1, per ADR 023 — contactDisplayName returns null
            // for them and we keep the group-carried name).
            val override = messaging.contactDisplayName(m.senderFingerprint)
            if (override != null && override != m.senderDisplayName) {
                m.copy(senderDisplayName = override)
            } else {
                m
            }
        }
        _state.update {
            it.copy(group = group, memberCount = members.size, messages = msgs)
        }
    }
}
