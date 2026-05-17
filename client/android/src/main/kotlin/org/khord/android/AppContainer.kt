package org.khord.android

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.khord.android.ui.theme.KhordThemeChoice
import org.khord.android.ui.theme.loadThemeChoice
import org.khord.android.ui.theme.saveThemeChoice
import org.khord.shared.Khord
import org.khord.shared.KhordBootstrap
import org.khord.shared.protocol.khordHttpClient
import org.khord.shared.protocol.orchestrator.Messaging
import org.khord.shared.storage.KeyStore
import org.khord.shared.storage.KeystoreBackedKeyStore

/**
 * Process-singleton dependency container.
 *
 * Manual DI — no Hilt, no Koin. The dependency graph is small enough
 * that wiring it by hand is clearer than annotation magic, and it
 * avoids extra deps for a PoC.
 *
 * The Splash screen calls [bootstrap] once on app launch; afterwards
 * other screens read [messaging] / [bootstrap] / [http] / [keyStore]
 * via the static accessors.
 *
 * `messaging` may be null at first (no identity yet — onboarding flow
 * applies); once registration completes, the orchestrator writes itself
 * into [messaging].
 */
object AppContainer {
    @Volatile var http: HttpClient? = null
        private set
    @Volatile var keyStore: KeyStore? = null
        private set
    @Volatile var bootstrap: KhordBootstrap? = null
        private set
    @Volatile var messaging: Messaging? = null

    /**
     * Set of contact fingerprints whose mailbox WebSocket is currently
     * Connected. Updated by [org.khord.android.push.KhordPushService] as
     * connections come and go. ViewModels observe this to suppress
     * polling: when a fingerprint is in the set, push is delivering for
     * that conversation and the 5s poll is redundant.
     *
     * Empty when the push service isn't running OR none of the WS
     * connections have completed their auth handshake yet.
     */
    val pushConnected: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * Shared across the 3 onboarding screens (display → confirm → register)
     * so the user doesn't lose their phrase on back-navigation. Cleared
     * after a successful register().
     */
    @Volatile var onboardingViewModel: org.khord.android.ui.viewmodel.OnboardingViewModel? = null

    /**
     * Process-lifetime coroutine scope for fire-and-forget work that must
     * outlive the ViewModel that scheduled it — currently the seed-phrase
     * clipboard auto-clear (60s after copy, even if the user has already
     * navigated past the seed screen by then).
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Currently active visual theme. Read by [org.khord.android.ui.theme.KhordTheme]
     * via collectAsStateWithLifecycle so a write here triggers immediate
     * recomposition app-wide. KhordApp.onCreate seeds it from
     * SharedPreferences on process start; SettingsScreen writes new
     * choices via [setThemeChoice].
     */
    private val _themeChoice = MutableStateFlow(KhordThemeChoice.TEAL)
    val themeChoice: StateFlow<KhordThemeChoice> = _themeChoice.asStateFlow()

    /** Called from KhordApp.onCreate so the very first composition sees the right theme. */
    fun loadInitialTheme(context: Context) {
        _themeChoice.value = loadThemeChoice(context)
    }

    /** Called from SettingsScreen — persists the choice AND recomposes everything. */
    fun setThemeChoice(context: Context, choice: KhordThemeChoice) {
        saveThemeChoice(context, choice)
        _themeChoice.value = choice
    }

    /**
     * Open the HTTP client, KeyStore, persistence, and try to load an
     * existing identity. Idempotent — subsequent calls are no-ops once
     * bootstrap is set.
     *
     * Returns true if an existing identity was loaded; false if this is
     * a first launch (caller should run onboarding).
     */
    suspend fun bootstrap(applicationContext: Context, dbName: String = ServerUrls.DB_NAME): Boolean {
        bootstrap?.let { return it.messaging != null }
        val httpClient = khordHttpClient(OkHttp)
        val ks = KeystoreBackedKeyStore(applicationContext)
        val b = Khord.open(httpClient, dbName, ks)

        http = httpClient
        keyStore = ks
        bootstrap = b
        messaging = b.messaging
        return b.messaging != null
    }

    /**
     * Drop process references after panic so the next splash run rebuilds
     * the world from scratch. The Activity that hosts the UI is expected
     * to recreate() itself after this returns — that re-mounts the NavHost
     * at SplashScreen, which calls [bootstrap] again with the now-null
     * fields and creates a fresh KeyStore/Persistence/Messaging triple.
     *
     * Also clears [onboardingViewModel] so a leftover Status.Done (or a
     * half-finished phrase) from the previous identity can't resurface
     * during the next onboarding pass.
     */
    fun reset() {
        messaging = null
        bootstrap = null
        keyStore = null
        http = null
        onboardingViewModel = null
        pushConnected.value = emptySet()
    }
}

/**
 * Default server URLs for the "Use Khord community servers (recommended)"
 * branch of the [org.khord.android.ui.screens.ServerSetupScreen] onboarding
 * step. The user can override either with a custom URL via the same screen;
 * whichever pair the user chose is persisted on the identity row at
 * registration time, then read back via [Messaging.myKeyServerUrl] /
 * [Messaging.myRelayServerUrl] on subsequent launches.
 *
 * No build-time configuration: a developer pointing the app at a local
 * Docker stack uses the "Use custom servers" option at runtime.
 */
object ServerUrls {
    const val DEFAULT_KEY_SERVER = "https://keys.khord.org"
    const val DEFAULT_RELAY_SERVER = "https://relay.khord.org"
    const val DB_NAME = "khord.db"
}
