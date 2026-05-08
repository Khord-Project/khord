package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khord.android.AppContainer

class ContactListViewModel : ViewModel() {

    data class Row(
        val fingerprint: String,
        val displayLabel: String,
        val lastMessageBody: String?,
        val lastTimestamp: String?,
    )

    data class UiState(
        val rows: List<Row> = emptyList(),
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()

    init { refresh(pollServer = false) }

    /** Pull-to-refresh: poll pending mailboxes for new contacts, then reload list. */
    fun refresh(pollServer: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(refreshing = true, error = null) }
                try {
                    val messaging = AppContainer.messaging ?: error("messaging not initialised")
                    if (pollServer) {
                        messaging.pollPendingMailboxes()
                    }
                    val rows = messaging.contacts().map { contact ->
                        val history = messaging.messageHistory(contact.contactFingerprint)
                        val last = history.lastOrNull()
                        val name = messaging.contactDisplayName(contact.contactFingerprint)
                        // Display name when known; otherwise a truncated FP so the
                        // row still has something distinguishable. Names show up
                        // after the first message (carries reply_info); QR-only
                        // contacts before any messaging stay on the FP fallback.
                        val label = name ?: (contact.contactFingerprint.take(8) +
                            "…" + contact.contactFingerprint.takeLast(8))
                        Row(
                            fingerprint = contact.contactFingerprint,
                            displayLabel = label,
                            lastMessageBody = last?.body,
                            lastTimestamp = last?.timestamp,
                        )
                    }
                    _state.update { it.copy(rows = rows, refreshing = false) }
                } catch (e: Throwable) {
                    _state.update {
                        it.copy(refreshing = false, error = e.message ?: e::class.simpleName)
                    }
                }
            }
        }
    }
}

