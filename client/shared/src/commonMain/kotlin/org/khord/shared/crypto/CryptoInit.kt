package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer

/**
 * One-time initialization of the underlying libsodium runtime.
 *
 * The ionspin/kotlin-multiplatform-libsodium binding requires
 * [LibsodiumInitializer.initialize] to be called once per process before any
 * primitive is used. All Khord crypto entry points should call
 * [Crypto.ensureInitialized] up-front; subsequent calls are no-ops.
 *
 * See ADR 006 and the ionspin README.
 */
object Crypto {
    @Volatile
    private var initialized: Boolean = false

    suspend fun ensureInitialized() {
        if (initialized) return
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initialize()
        }
        initialized = true
    }
}
