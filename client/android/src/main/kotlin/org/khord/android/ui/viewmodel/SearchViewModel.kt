package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.khord.android.AppContainer
import org.khord.shared.protocol.orchestrator.MessageSearchResult

/**
 * Backs [org.khord.android.ui.screens.SearchScreen]. The query text
 * feeds a 300 ms-debounced flow so we don't run an FTS query on every
 * keystroke; each settled query runs [org.khord.shared.protocol.orchestrator.Messaging.searchMessages]
 * (local FTS5) off the main thread. Local-only — no network.
 */
@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<MessageSearchResult> = emptyList(),
        /** True once the user has typed something and a search has run. */
        val searched: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { q ->
                    val trimmed = q.trim()
                    if (trimmed.isEmpty()) {
                        _state.update { it.copy(results = emptyList(), searched = false) }
                    } else {
                        val results = AppContainer.messaging?.searchMessages(trimmed)
                            ?: emptyList()
                        _state.update { it.copy(results = results, searched = true) }
                    }
                }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        queryFlow.value = q
    }
}
