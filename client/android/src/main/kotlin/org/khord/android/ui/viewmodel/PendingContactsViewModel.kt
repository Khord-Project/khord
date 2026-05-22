package org.khord.android.ui.viewmodel

import android.content.Context
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
import org.khord.android.push.PushServiceController
import org.khord.shared.storage.PlatformContextProvider

/**
 * Backs PendingContactsScreen. Holds the list of contacts the user
 * hasn't yet accepted — see Messaging.pendingContacts() — plus their
 * first stored message as a preview so the user can decide.
 *
 * Acceptance and decline are both terminal: after either, the row
 * disappears from this screen. Accept promotes the contact to
 * ContactStatus.ACCEPTED (the regular contact list will show them on
 * its next refresh). Decline is a local delete with no notification
 * to the sender.
 */
class PendingContactsViewModel : ViewModel() {

    /**
     * One pending contact row. The preview is the first message they
     * sent us (per spec — the X3DH initial's decrypted payload is
     * already in the message history).
     */
    data class Row(
        val fingerprint: String,
        val displayLabel: String,
        val firstMessagePreview: String?,
    )

    data class UiState(
        val rows: List<Row> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val messaging = AppContainer.messaging ?: error("messaging not initialised")
                    val rows = messaging.pendingContacts().map { pc ->
                        // First message preview — the X3DH initial's
                        // decrypted text was saved into the message
                        // history by receiveInitialBlobInternal.
                        val first = messaging.messageHistory(pc.fingerprint).firstOrNull()
                        val label = pc.displayName.takeIf { it.isNotEmpty() }
                            ?: (pc.fingerprint.take(8) + "…" + pc.fingerprint.takeLast(8))
                        Row(
                            fingerprint = pc.fingerprint,
                            displayLabel = label,
                            firstMessagePreview = first?.body,
                        )
                    }
                    _state.update { it.copy(rows = rows, loading = false) }
                } catch (e: Throwable) {
                    _state.update {
                        it.copy(loading = false, error = e.message ?: e::class.simpleName)
                    }
                }
            }
        }
    }

    /**
     * Promote pending → accepted. After the call returns, the next
     * ContactListViewModel refresh will surface the contact in the
     * regular list, and the push service will start posting
     * notification banners for their messages.
     *
     * [onAccepted] is invoked on the Main dispatcher exactly once if
     * the acceptance call succeeds. The screen uses this to navigate
     * straight into the newly-accepted contact's chat, since the
     * user has clearly just decided they want to talk to them.
     * Default no-op so callers that don't care don't have to pass
     * a lambda.
     */
    fun accept(fingerprint: String, onAccepted: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            mutex.withLock {
                try {
                    val messaging = AppContainer.messaging ?: error("messaging not initialised")
                    messaging.acceptContact(fingerprint)
                    success = true
                } catch (e: Throwable) {
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                    return@withLock
                }
            }
            if (success) {
                // Refresh fires its own coroutine; navigation can run
                // immediately on Main and tear down the screen — any
                // pending refresh update lands on a disposed VM and
                // is harmless.
                refresh()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onAccepted()
                }
            }
        }
    }

    /**
     * Local-only delete — drops the contact row, session, and every
     * stored message. Reuses Messaging.deleteContact (which is also
     * what the regular long-press-delete flow on ContactList uses).
     * No notification is sent to the counterparty — they only learn
     * we're gone when their next outbound bounces off the relay 404.
     */
    fun decline(fingerprint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val messaging = AppContainer.messaging ?: error("messaging not initialised")
                    messaging.deleteContact(fingerprint)
                    (PlatformContextProvider.get() as? Context)?.let { ctx ->
                        runCatching { PushServiceController.refresh(ctx) }
                    }
                } catch (e: Throwable) {
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                    return@withLock
                }
            }
            refresh()
        }
    }
}
