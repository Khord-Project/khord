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
    }
}

/**
 * Server URLs for the PoC, sourced from BuildConfig so a developer can
 * point an APK at any LAN host at build time without touching source:
 *
 *     ./gradlew :android:assembleDebug \
 *         -Pkhord.keyserver.url=http://192.168.1.42:8001 \
 *         -Pkhord.relayserver.url=http://192.168.1.42:8002
 *
 * Defaults (set in :android:build.gradle.kts) are the standard Android
 * emulator → host loopback `http://10.0.2.2:{8001,8002}`, which is the
 * right thing for local emulator testing against the Docker compose stack.
 */
object ServerUrls {
    val KEY_SERVER: String = BuildConfig.KEY_SERVER_URL
    val RELAY_SERVER: String = BuildConfig.RELAY_SERVER_URL
    const val DB_NAME = "khord.db"
}
