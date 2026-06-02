package org.khord.android.ui.viewmodel

import android.content.Context
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.khord.android.AppContainer
import org.khord.android.push.PushServiceController
import org.khord.shared.diagnostic.DiagnosticLog
import org.khord.shared.storage.PlatformContextProvider

class SettingsViewModel : ViewModel() {

    /** Outcome of a single health-endpoint probe. */
    enum class ServerHealth {
        /** Probe in flight or not yet started — UI shows neutral "Checking…". */
        Checking,
        /** HTTP 2xx within the timeout — UI shows green dot + "Operational". */
        Operational,
        /** Non-2xx, exception, or timeout — UI shows red dot + "Unreachable". */
        Unreachable,
    }

    data class UiState(
        val fingerprint: String? = null,
        val keyServerUrl: String? = null,
        val relayServerUrl: String? = null,
        /** Most recent probe outcome for the Key Server's /v1/health. */
        val keyServerHealth: ServerHealth = ServerHealth.Checking,
        /** Most recent probe outcome for the Relay Server's /v1/health. */
        val relayServerHealth: ServerHealth = ServerHealth.Checking,
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
            // Fire the two health probes in parallel on screen open.
            // Snapshot-only — no auto-refresh, no retry; the user can
            // back out + re-enter Settings to get a fresh reading.
            checkServerHealth(m.myKeyServerUrl, m.myRelayServerUrl)
        }
    }

    /**
     * Concurrent health-probe of both configured servers. Each is a
     * GET to `${url}/v1/health` with a 5-second timeout. The two probes
     * run in parallel so the slower one doesn't gate the faster.
     *
     * Non-blocking, fire-and-forget: state starts as [ServerHealth.Checking]
     * (the UiState default) and flips to [Operational] or [Unreachable]
     * per server as each probe resolves. The UI binds directly to the
     * StateFlow so partial results render the moment they arrive.
     */
    private fun checkServerHealth(keyServerUrl: String, relayServerUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val http = AppContainer.http ?: run {
                // No HTTP client yet — mark both Unreachable. Shouldn't
                // happen on a registered identity, but defensive against
                // a Settings re-open after panic.
                _state.update {
                    it.copy(
                        keyServerHealth = ServerHealth.Unreachable,
                        relayServerHealth = ServerHealth.Unreachable,
                    )
                }
                return@launch
            }
            // Two parallel probes, each independently mapped into the
            // state flow as soon as it resolves.
            val keyJob = async {
                probeHealth(http, "$keyServerUrl/v1/health").also { result ->
                    _state.update { it.copy(keyServerHealth = result) }
                }
            }
            val relayJob = async {
                probeHealth(http, "$relayServerUrl/v1/health").also { result ->
                    _state.update { it.copy(relayServerHealth = result) }
                }
            }
            awaitAll(keyJob, relayJob)
        }
    }

    private suspend fun probeHealth(
        http: io.ktor.client.HttpClient,
        url: String,
    ): ServerHealth = withTimeoutOrNull(5_000) {
        runCatching { http.get(url).status }.getOrNull()?.let {
            if (it.isSuccess()) ServerHealth.Operational else ServerHealth.Unreachable
        } ?: ServerHealth.Unreachable
    } ?: ServerHealth.Unreachable

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
                // Stop the push service first so the persistent notification
                // disappears before the process kill and there's no chance
                // of a final WS push firing receiveMessages() against an
                // already-wiped persistence layer.
                (PlatformContextProvider.get() as? Context)?.let {
                    runCatching { PushServiceController.stop(it) }
                    // Wipe the decrypted-image cache (plaintext JPEGs in
                    // files/media) — the DB wipe below removes the keys +
                    // path refs, but the panic guarantee must also delete
                    // the actual decrypted bytes on disk.
                    runCatching { org.khord.android.media.MediaCache.clear(it) }
                }
                AppContainer.messaging?.panic()
                AppContainer.keyStore?.clear()
                AppContainer.reset()
            } catch (e: Throwable) {
                // Non-fatal — we kill the process below regardless. Logged
                // so a future "panic left the device half-wiped" report
                // has something to chase in logcat.
                DiagnosticLog.log(
                    "Khord",
                    "panic cleanup threw; killing process anyway: ${e.message}",
                )
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }
}

