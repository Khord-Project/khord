package org.khord.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.khord.android.AppContainer
import org.khord.shared.crypto.Crypto

sealed interface SplashState {
    data object Working : SplashState
    /** No identity persisted — go to onboarding. */
    data object NeedsOnboarding : SplashState
    /** Identity loaded; routing target depends on registeredAtServer. */
    data class Loaded(val needsServerRegistration: Boolean) : SplashState
    data class Failed(val message: String) : SplashState
}

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<SplashState>(SplashState.Working)
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Crypto.ensureInitialized()
                val loaded = AppContainer.bootstrap(getApplication())
                _state.value = if (loaded) {
                    SplashState.Loaded(
                        needsServerRegistration =
                            AppContainer.messaging?.needsServerRegistration ?: false,
                    )
                } else {
                    SplashState.NeedsOnboarding
                }
            } catch (e: Throwable) {
                _state.value = SplashState.Failed(e.message ?: e::class.simpleName ?: "init failed")
            }
        }
    }
}
