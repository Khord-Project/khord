package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.khord.android.AppContainer

/**
 * Backs [org.khord.android.ui.screens.BlockedContactsScreen]. Lists
 * blocked contacts and unblocks them on demand. Snapshot-on-open +
 * refresh-after-unblock; no live observation needed for this rarely-
 * visited screen.
 */
class BlockedContactsViewModel : ViewModel() {

    data class Row(val fingerprint: String, val displayLabel: String)

    data class UiState(val rows: List<Row> = emptyList())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            val rows = messaging.blockedContacts().map {
                Row(
                    it.fingerprint,
                    it.displayName.ifEmpty {
                        it.fingerprint.take(8) + "…" + it.fingerprint.takeLast(8)
                    },
                )
            }
            _state.update { it.copy(rows = rows) }
        }
    }

    fun unblock(fingerprint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AppContainer.messaging?.setContactBlocked(fingerprint, false)
            refresh()
        }
    }
}
