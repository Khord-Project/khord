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

class ContactListViewModel : ViewModel() {

    /**
     * Unified row type — the screen renders contacts and groups in the
     * same list, distinguished by [kind]. The `id` carries either a
     * contact fingerprint (Contact) or a groupId (Group); the screen
     * routes on click accordingly.
     */
    data class Row(
        val id: String,
        val kind: Kind,
        val displayLabel: String,
        val lastMessageBody: String?,
        val lastTimestamp: String?,
        /**
         * True when this contact's `receiveMessages` has failed at
         * least [AppContainer.DEAD_CONTACT_THRESHOLD] times in a row
         * (per the AppContainer counter map). The screen renders such
         * rows muted — clickable, but visually de-emphasised so the
         * user notices something's off without having to open the chat.
         *
         * Only meaningful for [Kind.Contact]. Groups don't have a
         * single dead-side signal — they're considered available if
         * the user can see them locally.
         */
        val unavailable: Boolean = false,
    ) {
        enum class Kind { Contact, Group }

        /** Convenience: contact fingerprint, only valid for Contact rows. */
        val fingerprint: String get() = id
    }

    data class UiState(
        val rows: List<Row> = emptyList(),
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()

    init {
        refresh(pollServer = false)
        // Re-render whenever the dead-contact counter changes so the
        // muted-row indicator shows up without waiting for the next
        // pull-to-refresh or 10 s auto-poll cycle.
        viewModelScope.launch {
            AppContainer.contactReceiveFailures.collect { reapplyUnavailable(it) }
        }
    }

    /**
     * Lightweight in-place re-decoration of existing rows — flips the
     * `unavailable` flag based on the latest failure counter without
     * touching the relatively expensive history-loading path that the
     * full [refresh] runs.
     */
    private fun reapplyUnavailable(failures: Map<String, Int>) {
        _state.update { ui ->
            ui.copy(rows = ui.rows.map { row ->
                if (row.kind != Row.Kind.Contact) return@map row
                val isUnavailable = (failures[row.fingerprint] ?: 0) >=
                    AppContainer.DEAD_CONTACT_THRESHOLD
                if (row.unavailable == isUnavailable) row
                else row.copy(unavailable = isUnavailable)
            })
        }
    }

    /** Pull-to-refresh: poll pending mailboxes for new contacts, then reload list. */
    fun refresh(pollServer: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(refreshing = true, error = null) }
                try {
                    val messaging = AppContainer.messaging ?: error("messaging not initialised")
                    if (pollServer) {
                        val newOnes = messaging.pollPendingMailboxes()
                        if (newOnes.isNotEmpty()) {
                            // New inbound mailboxes are now bound to contacts —
                            // make sure the push service picks up WebSockets
                            // for them right away rather than waiting for the
                            // next process start.
                            (PlatformContextProvider.get() as? Context)?.let { ctx ->
                                runCatching { PushServiceController.refresh(ctx) }
                            }
                        }
                    }
                    val failures = AppContainer.contactReceiveFailures.value
                    val contactRows = messaging.contacts().map { contact ->
                        val history = messaging.messageHistory(contact.contactFingerprint)
                        val last = history.lastOrNull()
                        val name = messaging.contactDisplayName(contact.contactFingerprint)
                        // Display name when known; otherwise a truncated FP so the
                        // row still has something distinguishable. Names show up
                        // after the first message (carries reply_info); QR-only
                        // contacts before any messaging stay on the FP fallback.
                        val label = name ?: (contact.contactFingerprint.take(8) +
                            "…" + contact.contactFingerprint.takeLast(8))
                        val isUnavailable = (failures[contact.contactFingerprint] ?: 0) >=
                            AppContainer.DEAD_CONTACT_THRESHOLD
                        Row(
                            id = contact.contactFingerprint,
                            kind = Row.Kind.Contact,
                            displayLabel = label,
                            lastMessageBody = last?.body,
                            lastTimestamp = last?.timestamp,
                            unavailable = isUnavailable,
                        )
                    }
                    // Groups (ADR 023) — listed alongside contacts. Last
                    // message preview is "Sender: body" so it's obvious at
                    // a glance who in the group just spoke.
                    val groupRows = messaging.allGroups().map { g ->
                        val history = messaging.groupMessageHistory(g.groupId)
                        val last = history.lastOrNull()
                        val preview = last?.let { lm ->
                            val who = lm.senderDisplayName.ifEmpty { "?" }
                            "$who: ${lm.body}"
                        }
                        Row(
                            id = g.groupId,
                            kind = Row.Kind.Group,
                            displayLabel = g.groupName,
                            lastMessageBody = preview,
                            lastTimestamp = last?.timestamp,
                        )
                    }
                    val rows = contactRows + groupRows
                    // Recent-first ordering: contacts WITH a last message sorted
                    // by timestamp descending; contacts with no messages yet trail
                    // at the bottom (they're new QR-only entries waiting for the
                    // first inbound payload). String-compare on ISO 8601 is
                    // chronologically correct because the format is lex-sortable.
                    val sorted = rows.sortedWith(
                        compareByDescending<Row> { it.lastTimestamp != null }
                            .thenByDescending { it.lastTimestamp ?: "" },
                    )
                    _state.update { it.copy(rows = sorted, refreshing = false) }
                } catch (e: Throwable) {
                    _state.update {
                        it.copy(refreshing = false, error = e.message ?: e::class.simpleName)
                    }
                }
            }
        }
    }
}

