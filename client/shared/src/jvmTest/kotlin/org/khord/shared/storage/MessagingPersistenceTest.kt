package org.khord.shared.storage

import io.ktor.client.engine.java.Java
import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.protocol.khordHttpClient
import org.khord.shared.protocol.orchestrator.Messaging
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Persistence-level tests for the Messaging orchestrator.
 *
 * These tests do NOT require the Docker stack — they verify the
 * orchestrator's save/load behaviour with a DbPersistence wrapping a
 * SQLite file in a tempdir. The tests that DO need the live stack are
 * in [org.khord.shared.protocol.EndToEndIntegrationTest].
 */
class MessagingPersistenceTest {

    private val tempDir = createTempDirectory("khord-messaging-persist").toFile().apply {
        deleteOnExit()
    }

    @AfterTest
    fun cleanup() { tempDir.deleteRecursively() }

    @Test
    fun panic_marks_orchestrator_dead_and_wipes_db() = runTest {
        Crypto.ensureInitialized()
        val dbPath = Path(tempDir.absolutePath, "panic-${System.nanoTime()}.db")
            .absolutePathString()
        val ks = InMemoryKeyStore()
        val persistence = openDbPersistence(dbPath, ks)
        val identity = IdentityKey.fromSeedPhrase("panic-test ${System.nanoTime()}")

        val m = Messaging.createWithPersistence(
            identity = identity,
            keyServerUrl = "http://localhost:0",
            relayServerUrl = "http://localhost:0",
            http = khordHttpClient(Java),
            persistence = persistence,
        )

        // Save just enough state to make panic non-trivial: identity row only.
        // (Calling register() would hit the network — out of scope here.)
        persistence.saveIdentity(IdentityRecord(
            identity = identity,
            keyServerUrl = "http://localhost:0",
            relayServerUrl = "http://localhost:0",
            createdAt = "2026-05-05T00:00:00Z",
        ))
        assertNotNull(persistence.loadIdentity())

        m.panic()

        // After panic the database file is gone.
        assertFalse(Path(dbPath).exists(), "panic must delete the database file")

        // After panic the orchestrator throws on every method.
        assertFailsWith<IllegalStateException> { m.myQrPayload() }
    }

    @Test
    fun load_returns_null_when_no_identity_persisted() = runTest {
        Crypto.ensureInitialized()
        val dbPath = Path(tempDir.absolutePath, "empty-${System.nanoTime()}.db")
            .absolutePathString()
        val persistence = openDbPersistence(dbPath, InMemoryKeyStore())
        try {
            val result = Messaging.load(khordHttpClient(Java), persistence)
            assertNull(result, "load() must return null on empty store")
        } finally {
            persistence.close()
        }
    }

    @Test
    fun opk_consumption_is_durable_across_reload() = runTest {
        Crypto.ensureInitialized()
        val dbPath = Path(tempDir.absolutePath, "opk-${System.nanoTime()}.db")
            .absolutePathString()
        val ks = InMemoryKeyStore()

        // Phase 1: save 5 OPKs, consume one, close.
        run {
            val p = openDbPersistence(dbPath, ks)
            p.saveOpkBatch((1..5).associateWith { ByteArray(32) { _ -> it.toByte() } })
            p.deleteOneTimePreKey(3)
            assertEquals(4, p.loadAllOpkSecrets().size)
            p.close()
        }

        // Phase 2: reopen. The deleted OPK stays gone.
        run {
            val p = openDbPersistence(dbPath, ks)
            try {
                val remaining = p.loadAllOpkSecrets()
                assertEquals(4, remaining.size)
                assertFalse(3 in remaining, "consumed OPK must remain consumed after reload")
            } finally {
                p.close()
            }
        }
    }
}
