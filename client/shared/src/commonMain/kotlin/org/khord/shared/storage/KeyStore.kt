package org.khord.shared.storage

import org.khord.shared.crypto.Random
import org.khord.shared.crypto.wipe

/**
 * Database-passphrase storage.
 *
 * On Android (UI phase), the actual implementation will:
 *   1. Generate a Keystore-bound AES-GCM key on first launch.
 *   2. Use that AES key to encrypt a fresh 32-byte database passphrase.
 *   3. Persist the encrypted blob in app-private storage.
 *   4. On subsequent launches, ask Keystore to decrypt the blob.
 *
 * For the PoC and JVM tests we just need a passphrase — encryption-at-rest
 * lands when SQLCipher is wired in.
 *
 * @see InMemoryKeyStore for a process-local default suitable for tests.
 */
interface KeyStore {
    suspend fun getOrCreateDatabasePassphrase(): ByteArray
    suspend fun clear()
}

/**
 * Process-local KeyStore — passphrase regenerated on every construction.
 * Suitable for tests and as the default until Android Keystore is wired in.
 */
class InMemoryKeyStore : KeyStore {
    @Volatile
    private var passphrase: ByteArray? = null

    override suspend fun getOrCreateDatabasePassphrase(): ByteArray {
        passphrase?.let { return it }
        val fresh = Random.bytes(32)
        passphrase = fresh
        return fresh
    }

    override suspend fun clear() {
        passphrase?.wipe()
        passphrase = null
    }
}
