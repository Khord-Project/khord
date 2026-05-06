package org.khord.android

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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

    /** Drop process references after panic so onboarding starts clean. */
    fun reset() {
        messaging = null
        bootstrap = null
        keyStore = null
        http = null
    }
}

/** Hardcoded server URLs for the PoC; emulator-loopback by default. */
object ServerUrls {
    /**
     * Android emulator loopback to the Docker compose stack on the host.
     * Real-device testing on a LAN replaces this with `http://<host-LAN>:8001`
     * etc. (and a different network_security_config entry).
     */
    const val KEY_SERVER = "http://10.0.2.2:8001"
    const val RELAY_SERVER = "http://10.0.2.2:8002"
    const val DB_NAME = "khord.db"
}
