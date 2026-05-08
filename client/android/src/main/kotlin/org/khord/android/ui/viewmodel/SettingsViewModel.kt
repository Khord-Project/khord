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

class SettingsViewModel : ViewModel() {

    data class UiState(
        val fingerprint: String? = null,
        /**
         * Flipped to true the instant the user confirms panic, BEFORE the
         * destructive coroutine launches. The screen swaps to a "Wiping…"
         * spinner immediately so there's no perceived freeze while panic
         * grinds through Keystore + DB cleanup (which can take seconds if
         * a background poller was mid-DB-write when panic fired).
         */
        val wiping: Boolean = false,
        val panicked: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        AppContainer.messaging?.let { m ->
            _state.update { it.copy(fingerprint = m.myFingerprint) }
        }
    }

    /**
     * Wipe everything: orchestrator panic + persistence panic + Keystore key
     * deletion. After this completes the process is in onboarding-state.
     *
     * The `wiping` flag is set synchronously so the UI can show a spinner
     * before the destructive work even starts. Idempotent — repeated taps
     * while wiping is already in progress are no-ops.
     */
    fun panic() {
        if (_state.value.wiping) return
        _state.update { it.copy(wiping = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                AppContainer.messaging?.panic()
                AppContainer.keyStore?.clear()
                AppContainer.reset()
                _state.update { it.copy(panicked = true) }
            }.onFailure { e ->
                _state.update {
                    it.copy(wiping = false, error = e.message ?: e::class.simpleName)
                }
            }
        }
    }
}

