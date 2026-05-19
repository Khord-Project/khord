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
import org.khord.android.util.UpdateChecker
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
                // Bootstrap finished — AppContainer.http is now set.
                // Fire the GitHub Releases check once per cold start.
                // Launched on applicationScope so it survives splash-
                // screen disposal (the check can outlive the brief
                // splash window without being cancelled mid-request).
                // Failure is silent: UpdateChecker.checkOnce returns
                // null for every error path.
                AppContainer.applicationScope.launch(Dispatchers.IO) {
                    val http = AppContainer.http ?: return@launch
                    val info = UpdateChecker.checkOnce(http) ?: return@launch
                    AppContainer.availableUpdate.value = info
                }
            } catch (e: Throwable) {
                _state.value = SplashState.Failed(e.message ?: e::class.simpleName ?: "init failed")
            }
        }
    }
}
