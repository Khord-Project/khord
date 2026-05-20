package org.khord.android.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.khord.android.AppContainer
import org.khord.android.ServerUrls
import org.khord.android.util.UpdateChecker
import org.khord.shared.crypto.Crypto
import java.io.File

sealed interface SplashState {
    data object Working : SplashState
    /**
     * No identity persisted — go to onboarding.
     *
     * [previouslySetUp] is true when bootstrap found ANY leftover state
     * (the database file, the Keystore-backed prefs blob, OR a
     * just-regenerated Keystore key) but couldn't load an identity. On
     * MIUI / Xiaomi this is the "Keystore invalidated / battery saver
     * cleared our data" path; the splash screen uses it to offer a
     * diagnostic report before sending the user to Welcome.
     *
     * Note that [keystoreRegenerated] is a separate signal because the
     * orphan-cleanup path in DbPersistenceFactory deletes the DB AND
     * the prefs blob is already cleared by the keystore catch — so by
     * the time we check, both file-existence heuristics may read false
     * even though state loss DID happen. Without this third flag the
     * dialog would silently not fire on exactly the case we built it
     * to catch.
     */
    data class NeedsOnboarding(
        val previouslySetUp: Boolean = false,
        val dbFileExists: Boolean = false,
        val prefsHaveBlob: Boolean = false,
        val keystoreRegenerated: Boolean = false,
    ) : SplashState
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
                val application: Application = getApplication()

                // Snapshot the on-disk indicators BEFORE bootstrap runs.
                // Bootstrap will create both files on first launch (the
                // keystore blob during getOrCreateDatabasePassphrase, the
                // DB file when SQLCipher opens it), so checking after
                // bootstrap would always return true and the state-loss
                // dialog would mis-fire for every brand-new install.
                // See issue #11 — confirmed via the DiagnosticLog ring
                // shipped in alpha.5.
                val dbFileExists = File(
                    application.getDatabasePath(ServerUrls.DB_NAME).absolutePath,
                ).exists()
                val prefsHaveBlob = application
                    .getSharedPreferences("khord_keystore_blob", Context.MODE_PRIVATE)
                    .getString("iv", null) != null

                val loaded = AppContainer.bootstrap(application)
                _state.value = if (loaded) {
                    SplashState.Loaded(
                        needsServerRegistration =
                            AppContainer.messaging?.needsServerRegistration ?: false,
                    )
                } else {
                    val keystoreRegenerated = AppContainer.bootstrapRegeneratedKeystore
                    SplashState.NeedsOnboarding(
                        previouslySetUp = dbFileExists || prefsHaveBlob || keystoreRegenerated,
                        dbFileExists = dbFileExists,
                        prefsHaveBlob = prefsHaveBlob,
                        keystoreRegenerated = keystoreRegenerated,
                    )
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
