package org.khord.android.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * deletion. After this completes the process is in onboarding-state, and
     * [onComplete] fires from the Main thread — wired by the screen to
     * Activity.recreate() so the app restarts cleanly.
     *
     * The `wiping` flag is set synchronously so the UI can show a spinner
     * before the destructive work even starts. Idempotent — repeated taps
     * while wiping is already in progress are no-ops.
     *
     * The recreate trigger lives in a try/finally rather than a state-flow-
     * observing LaunchedEffect for two reasons:
     *
     *  1. Robustness against partial failure. If any cleanup step throws,
     *     we still want to restart the Activity — a partially wiped device
     *     is recoverable, but a stuck "Wiping…" spinner with no way out is
     *     a complete dead-end (the only escape is force-stop from Settings).
     *
     *  2. Robustness against the Activity reference resolving to null. The
     *     state-flow / LaunchedEffect path called `activity?.recreate()`,
     *     where `activity` came from `LocalContext.current as? Activity`.
     *     Compose sometimes wraps the Activity in a ContextWrapper (theme,
     *     configuration), and the plain `as? Activity` cast silently returns
     *     null — so the LaunchedEffect would fire, the cast would yield null,
     *     and the recreate would be a no-op. The screen now resolves the
     *     Activity by walking the ContextWrapper chain (see findActivity()
     *     in SettingsScreen) and passes a guaranteed-non-null callback in.
     */
    fun panic(onComplete: () -> Unit) {
        if (_state.value.wiping) return
        _state.update { it.copy(wiping = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppContainer.messaging?.panic()
                AppContainer.keyStore?.clear()
                AppContainer.reset()
            } catch (e: Throwable) {
                // Logged so a future "panic left the device half-wiped"
                // report has something to chase. We still proceed to the
                // recreate step in finally — see kdoc above.
                Log.w("Khord", "panic cleanup threw; restarting anyway", e)
            } finally {
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}

