package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.khord.android.AppContainer

/**
 * Drives the [org.khord.android.ui.screens.ServerSetupScreen] step.
 *
 * Validates the user's chosen pair of URLs at /v1/health BEFORE proceeding
 * to seed-phrase generation — a typo or down server here is much easier to
 * recover from than discovering the problem at registration time.
 *
 * On success, writes the URLs onto AppContainer.onboardingViewModel
 * (creating it if needed so the seed-phrase screen finds the right one)
 * and flips status to [Status.Success]. The screen's LaunchedEffect then
 * navigates to SEED_DISPLAY.
 */
class ServerSetupViewModel : ViewModel() {

    sealed interface Status {
        data object Idle : Status
        data object Validating : Status
        data class Failed(val message: String) : Status
        data object Success : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Reset back to Idle (called when the user edits a field after a failure). */
    fun clearError() {
        if (_status.value is Status.Failed) _status.value = Status.Idle
    }

    fun validateAndContinue(rawKeyServer: String, rawRelayServer: String) {
        if (_status.value is Status.Validating) return
        val ks = rawKeyServer.trim().trimEnd('/')
        val rs = rawRelayServer.trim().trimEnd('/')

        if (!isValidUrl(ks)) {
            _status.value = Status.Failed("Key server URL must start with http:// or https://")
            return
        }
        if (!isValidUrl(rs)) {
            _status.value = Status.Failed("Relay server URL must start with http:// or https://")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _status.value = Status.Validating
            val http = AppContainer.http
            if (http == null) {
                _status.value = Status.Failed(
                    "App not bootstrapped — restart and try again.",
                )
                return@launch
            }

            val ksOk = ping(http, ks)
            val rsOk = ping(http, rs)
            val msg = when {
                !ksOk && !rsOk ->
                    "Couldn't reach either server. Check the URLs and your network."
                !ksOk -> "Couldn't reach key server at $ks"
                !rsOk -> "Couldn't reach relay server at $rs"
                else -> null
            }
            if (msg != null) {
                _status.value = Status.Failed(msg)
                return@launch
            }

            // Stage the URLs on the OnboardingViewModel so the rest of the
            // onboarding flow uses them. Created here if it doesn't exist
            // yet — typical case since we run BEFORE SeedDisplayScreen.
            val ovm = AppContainer.onboardingViewModel
                ?: OnboardingViewModel().also { AppContainer.onboardingViewModel = it }
            ovm.setServerUrls(ks, rs)
            _status.value = Status.Success
        }
    }
}

/**
 * Loose URL shape check — just enough to reject obvious typos before
 * making a network request. The /v1/health probe is the real validator.
 */
private fun isValidUrl(s: String): Boolean {
    if (s.isEmpty()) return false
    return try {
        val uri = java.net.URI(s)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()
    } catch (_: Throwable) {
        false
    }
}

/**
 * GET /v1/health on [baseUrl] with a short timeout. Returns true iff we
 * get a 2xx response. Any other outcome (timeout, IO error, non-2xx) is
 * treated as failure — the user gets a clear "couldn't reach" message
 * rather than a confusing stack trace.
 */
private suspend fun ping(http: HttpClient, baseUrl: String): Boolean = try {
    val response: HttpResponse? = withTimeoutOrNull(timeMillis = 5_000) {
        http.get("$baseUrl/v1/health")
    }
    response?.status?.isSuccess() == true
} catch (_: Throwable) {
    false
}
