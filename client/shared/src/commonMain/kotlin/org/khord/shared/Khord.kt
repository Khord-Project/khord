package org.khord.shared

import io.ktor.client.HttpClient
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.protocol.orchestrator.Messaging
import org.khord.shared.storage.KeyStore
import org.khord.shared.storage.Persistence
import org.khord.shared.storage.openDbPersistence

/**
 * Public façade for embedding Khord in an application (Android UI, future
 * iOS, etc.). Hides the internal [Persistence] interface and the
 * (orchestrator-level) `createWithPersistence` / `load` factories so the
 * record types they reference (RatchetState, SessionRecord, …) can stay
 * `internal`.
 *
 * Typical lifecycle:
 *
 * ```kotlin
 * val bootstrap = Khord.open(httpClient, "khord.db", keyStore)
 * val messaging = bootstrap.messaging
 *     ?: bootstrap.registerFreshIdentity(newIdentity, ksUrl, rsUrl).also {
 *         it.register()  // first-launch: upload pre-keys
 *     }
 * // ... use messaging ...
 * bootstrap.close()
 * ```
 */
object Khord {
    /**
     * Open the local database (creating it if needed) and try to load an
     * existing identity.
     *
     * @return a [KhordBootstrap] whose `messaging` is the loaded
     *   orchestrator if an identity was already persisted, or null if
     *   this is a first launch (caller routes to the onboarding flow).
     */
    suspend fun open(
        http: HttpClient,
        dbName: String,
        keyStore: KeyStore,
    ): KhordBootstrap {
        val persistence = openDbPersistence(dbName, keyStore)
        val loaded = Messaging.load(http, persistence)
        return KhordBootstrap(persistence, loaded, http)
    }
}

/**
 * Holds the still-open persistence handle so the caller can:
 *  1. retrieve the loaded [Messaging] (post-restart path), or
 *  2. supply a fresh [IdentityKey] and obtain a Messaging that will write
 *     to the same persistence (onboarding path).
 *
 * `close()` releases DB connections cleanly. Panic goes through the
 * `Messaging` instance, which itself drives `Persistence.panic()`.
 */
class KhordBootstrap internal constructor(
    internal val persistence: Persistence,
    val messaging: Messaging?,
    private val http: HttpClient,
) {
    /** Construct a Messaging for a freshly-generated identity. */
    fun registerFreshIdentity(
        identity: IdentityKey,
        keyServerUrl: String,
        relayServerUrl: String,
    ): Messaging = Messaging.createWithPersistence(
        identity = identity,
        keyServerUrl = keyServerUrl,
        relayServerUrl = relayServerUrl,
        http = http,
        persistence = persistence,
    )

    suspend fun close() { persistence.close() }
}
