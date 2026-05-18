package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khord.android.AppContainer
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var pollJob: Job? = null

    init { reload() }

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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(sending = true, error = null) }
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.sendGroupMessage(groupId, trimmed)
                    reloadLockedInternal()
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
                _state.update { it.copy(sending = false) }
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
        val msgs = messaging.groupMessageHistory(groupId)
        _state.update {
            it.copy(group = group, memberCount = members.size, messages = msgs)
        }
    }
}
