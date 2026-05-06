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
     */
    fun panic() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                AppContainer.messaging?.panic()
                AppContainer.keyStore?.clear()
                AppContainer.reset()
                _state.update { it.copy(panicked = true) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }
}

