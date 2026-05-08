package org.khord.android.ui.viewmodel

import android.os.Process
import android.util.Log
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
        val keyServerUrl: String? = null,
        val relayServerUrl: String? = null,
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
            _state.update {
                it.copy(
                    fingerprint = m.myFingerprint,
                    keyServerUrl = m.myKeyServerUrl,
                    relayServerUrl = m.myRelayServerUrl,
                )
            }
        }
    }

    /**
     * Wipe everything: orchestrator panic + persistence panic + Keystore key
     * deletion. The hosting process is killed in `finally` so the next
     * launch is a guaranteed cold boot — no Activity, ViewModelStore, or
     * NavBackStackEntry survives to reference the now-deleted persistence.
     *
     * The `wiping` flag is set synchronously so the UI can show a spinner
     * before the destructive work even starts. Idempotent — repeated taps
     * while wiping is already in progress are no-ops.
     *
     * Why kill the process instead of Activity.recreate()? Two earlier
     * attempts (commits 851fe40, 9ad407c) used Activity.recreate(), which:
     *
     *   1. Preserves NavBackStackEntries pointing at the destroyed
     *      persistence layer, leading to "AppContainer not bootstrapped"
     *      errors after the next bootstrap.
     *
     *   2. Couldn't reliably resolve the Activity reference through
     *      Compose's LocalContext (which is sometimes a ContextWrapper),
     *      stranding the user on the "Wiping…" spinner.
     *
     * killProcess(myPid()) sidesteps both. Android's launcher cold-starts
     * a fresh process when the user re-taps the icon — same approach
     * Signal uses for "Delete all data". Ungraceful by design: there is
     * zero state worth preserving after a panic wipe.
     */
    fun panic() {
        if (_state.value.wiping) return
        _state.update { it.copy(wiping = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppContainer.messaging?.panic()
                AppContainer.keyStore?.clear()
                AppContainer.reset()
            } catch (e: Throwable) {
                // Non-fatal — we kill the process below regardless. Logged
                // so a future "panic left the device half-wiped" report
                // has something to chase in logcat.
                Log.w("Khord", "panic cleanup threw; killing process anyway", e)
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }
}

