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
import kotlinx.coroutines.flow.update
import org.khord.android.ui.theme.KhordThemeChoice
import org.khord.android.ui.theme.loadThemeChoice
import org.khord.android.ui.theme.saveThemeChoice
import org.khord.android.util.UpdateInfo
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
     * Per-contact consecutive `receiveMessages()` failure count.
     * Incremented by [recordReceiveFailure] from callers that drive
     * receive (ChatViewModel poll loop, KhordPushService push handler);
     * reset to 0 by [recordReceiveSuccess] on any successful drain.
     *
     * ContactListViewModel observes this to surface a muted indicator
     * once the count crosses [DEAD_CONTACT_THRESHOLD] (currently 3) —
     * the user sees "this contact appears to be unavailable" without
     * having to open the chat.
     *
     * Ephemeral: lives only in the running process. A panic or app
     * restart resets every counter to 0, which is fine — the same
     * receive failures will recur if the contact really is gone.
     */
    val contactReceiveFailures: MutableStateFlow<Map<String, Int>> =
        MutableStateFlow(emptyMap())

    const val DEAD_CONTACT_THRESHOLD = 3

    /**
     * Set by [org.khord.android.util.UpdateChecker] once-per-cold-start
     * if a newer GitHub release exists. ContactListScreen observes this
     * and shows a subtle banner; tapping "Dismiss" clears it for the
     * remainder of the session via [dismissAvailableUpdate].
     *
     * Reset to null on panic so a fresh process re-checks on next
     * launch.
     */
    val availableUpdate: MutableStateFlow<UpdateInfo?> = MutableStateFlow(null)

    fun dismissAvailableUpdate() {
        availableUpdate.value = null
    }

    /**
     * One-shot signal: did the most recent [bootstrap] hit the
     * Keystore-invalidation recovery path? Set to true when the
     * keystore had to regenerate the DB passphrase AND the orphaned
     * `khord.db` file existed on disk (the Xiaomi / MIUI symptom).
     *
     * Read by SplashViewModel — the state-loss dialog needs to fire
     * even after the orphan was deleted, by which point the on-disk
     * heuristics (db file exists, prefs blob exists) are no longer
     * true. The flag is in-memory only; a fresh cold start clears it.
     */
    @Volatile
    var bootstrapRegeneratedKeystore: Boolean = false
        private set

    fun recordReceiveFailure(fingerprint: String) {
        contactReceiveFailures.update { current ->
            current + (fingerprint to ((current[fingerprint] ?: 0) + 1))
        }
    }

    fun recordReceiveSuccess(fingerprint: String) {
        // Drop the entry entirely rather than write `0` — the map then
        // doubles as a "which contacts are currently failing" set for
        // any future observer that prefers `containsKey` semantics.
        contactReceiveFailures.update { current ->
            if (fingerprint in current) current - fingerprint else current
        }
    }

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

        // Diagnostic snapshot of the databases directory BEFORE any
        // file or keystore work runs. Repeated Xiaomi / OnePlus reports
        // (issues #4 #5 #6 #11 #13 #14) show state_loss with no clear
        // cause; recording the on-disk shape lets us tell apart:
        //   - the databases dir itself is missing (Android wiped a
        //     whole sandbox subtree)
        //   - dir exists, file is gone (something deleted just the
        //     main .db)
        //   - dir exists with WAL/SHM/journal but no main .db (mid-
        //     write corruption)
        //   - the path is inconsistent between launches (MIUI dual-
        //     app / second profile cloning)
        // The lines land in the DiagnosticLog ring, so they're picked
        // up by every subsequent bug-report submission.
        val dbDir = applicationContext.getDatabasePath(dbName).parentFile
        org.khord.shared.diagnostic.DiagnosticLog.log(
            "Khord",
            "Bootstrap: databases dir=${dbDir?.absolutePath} " +
                "exists=${dbDir?.exists()}",
        )
        if (dbDir?.exists() == true) {
            org.khord.shared.diagnostic.DiagnosticLog.log(
                "Khord",
                "Bootstrap: databases dir contents=" +
                    "${dbDir.listFiles()?.map { it.name }}",
            )
        }
        org.khord.shared.diagnostic.DiagnosticLog.log(
            "Khord",
            "Bootstrap: full db path=" +
                applicationContext.getDatabasePath(dbName).absolutePath,
        )

        val httpClient = khordHttpClient(OkHttp)
        val ks = KeystoreBackedKeyStore(applicationContext)
        // Wrap Khord.open in try/catch so any crash inside Messaging.load
        // (DB corruption, keystore alias gone, schema mismatch, ratchet
        // deserialisation failure, etc.) is logged with the "Khord" tag
        // and surfaces to the caller as "no identity loaded" rather than
        // a full app crash. The user can panic + re-onboard from there.
        // commonMain can't use android.util.Log, hence the wrapper lives
        // here at the nearest android-visible call site.
        val b = try {
            Khord.open(httpClient, dbName, ks)
        } catch (e: Throwable) {
            org.khord.shared.diagnostic.DiagnosticLog.log(
                "Khord", "Messaging.load failed: ${e.message}",
            )
            android.util.Log.e("Khord", "Messaging.load failed", e)
            http = httpClient
            keyStore = ks
            bootstrapRegeneratedKeystore = ks.lastLoadRegeneratedKey
            return false
        }

        http = httpClient
        keyStore = ks
        bootstrap = b
        messaging = b.messaging
        bootstrapRegeneratedKeystore = ks.lastLoadRegeneratedKey
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
        contactReceiveFailures.value = emptyMap()
        availableUpdate.value = null
        bootstrapRegeneratedKeystore = false
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
 * The defaults come from per-flavor BuildConfig fields:
 *   - `dev` flavor → http://10.0.2.2:8001 / :8002 (emulator host loopback)
 *   - `prod` flavor → https://keys.khord.org / https://relay.khord.org
 *
 * See `productFlavors` in `client/android/build.gradle.kts`. `const val`
 * isn't possible here because Kotlin doesn't treat AGP's generated
 * BuildConfig fields as compile-time constants — `val` is the right shape.
 */
object ServerUrls {
    val DEFAULT_KEY_SERVER: String = BuildConfig.DEFAULT_KEY_SERVER
    val DEFAULT_RELAY_SERVER: String = BuildConfig.DEFAULT_RELAY_SERVER
    const val DB_NAME = "khord.db"
}
