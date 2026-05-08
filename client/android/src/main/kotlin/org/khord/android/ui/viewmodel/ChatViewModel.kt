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
import org.khord.shared.protocol.orchestrator.MessageEntry

class ChatViewModel(
    private val contactFingerprint: String,
) : ViewModel() {

    data class UiState(
        val messages: List<MessageEntry> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null,
        /** Display name learned from the contact's reply_info, or null if unknown yet. */
        val contactDisplayName: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var pollJob: Job? = null

    init { reloadHistory() }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                fetchOnce()
                delay(5_000)
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
                    val contact = messaging.contacts().firstOrNull {
                        it.contactFingerprint == contactFingerprint
                    } ?: error("contact session not found")
                    messaging.sendMessage(contact, trimmed)
                    reloadHistoryLocked()
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
                _state.update { it.copy(sending = false) }
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
        _state.update { it.copy(messages = history, contactDisplayName = name) }
    }

    private suspend fun fetchOnce() {
        mutex.withLock {
            runCatching {
                val messaging = AppContainer.messaging ?: return@runCatching
                val contact = messaging.contacts().firstOrNull {
                    it.contactFingerprint == contactFingerprint
                } ?: return@runCatching
                messaging.receiveMessages(contact)
                reloadHistoryLocked()
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }
}
