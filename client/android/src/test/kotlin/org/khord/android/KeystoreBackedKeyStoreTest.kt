package org.khord.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.khord.shared.storage.KeystoreBackedKeyStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Robolectric tests for [KeystoreBackedKeyStore].
 *
 * Status: **disabled under Robolectric.** The JVM running these tests
 * doesn't ship the `AndroidKeyStore` security provider, so any
 * `KeyStore.getInstance("AndroidKeyStore")` call throws
 * `NoSuchAlgorithmException`. Recent Robolectric releases have a
 * `ShadowAndroidKeyStore` reachable through `@Config(shadows = [...])`
 * but it's incomplete for AES-GCM as of 4.16.x.
 *
 * The investigation already deferred SQLCipher round-trip tests to
 * on-device manual testing for the same reason (no native `.so` under
 * Robolectric). Same fate for Keystore: shipping unit-test stubs the
 * runtime can't honour would just be noise. The actual code path IS
 * exercised by the manual onboarding flow on an emulator/device.
 *
 * Re-enable when either:
 *   - we add an instrumented (`androidTest`) suite on a real device, or
 *   - Robolectric's KeyStore shadow gains AES-GCM support.
 */
@org.junit.Ignore("AndroidKeyStore provider not available under Robolectric — see class doc")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreBackedKeyStoreTest {

    private lateinit var ctx: Context

    @BeforeTest
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @AfterTest
    fun tearDown() {
        // Best-effort cleanup so test order doesn't leak Keystore state.
        runTest {
            KeystoreBackedKeyStore(ctx).clear()
        }
    }

    @Test
    fun first_call_generates_a_32_byte_passphrase() = runTest {
        val ks = KeystoreBackedKeyStore(ctx)
        val passphrase = ks.getOrCreateDatabasePassphrase()
        assertEquals(32, passphrase.size)
    }

    @Test
    fun subsequent_calls_return_the_same_passphrase() = runTest {
        val ks = KeystoreBackedKeyStore(ctx)
        val first = ks.getOrCreateDatabasePassphrase()
        val second = ks.getOrCreateDatabasePassphrase()
        assertContentEquals(first, second)
    }

    @Test
    fun a_fresh_instance_decrypts_the_existing_blob() = runTest {
        val a = KeystoreBackedKeyStore(ctx).getOrCreateDatabasePassphrase()
        val b = KeystoreBackedKeyStore(ctx).getOrCreateDatabasePassphrase()
        assertContentEquals(a, b, "second instance must decrypt to the same passphrase")
    }

    @Test
    fun stale_blob_without_keystore_key_triggers_regenerate() = runTest {
        // Simulates the panic-race: panic + Process.killProcess fired
        // between the Keystore alias delete and the SharedPreferences
        // commit, leaving the (iv, ct) blob orphaned.
        val ks = KeystoreBackedKeyStore(ctx)
        val original = ks.getOrCreateDatabasePassphrase()
        val prefs = ctx.getSharedPreferences("khord_keystore_blob", Context.MODE_PRIVATE)
        val savedIv = prefs.getString("iv", null)!!
        val savedCt = prefs.getString("ct", null)!!

        ks.clear()
        // Reinstate the encrypted blob; the Keystore alias is now gone.
        prefs.edit().putString("iv", savedIv).putString("ct", savedCt).apply()

        // Defense-in-depth: stale blob without its Keystore key must
        // wipe-and-regenerate, NOT throw a SecretKey cast NPE on the
        // next launch. The new passphrase MUST differ from the original.
        val fresh = KeystoreBackedKeyStore(ctx).getOrCreateDatabasePassphrase()
        assertEquals(32, fresh.size)
        assertFalse(
            fresh.contentEquals(original),
            "regenerated passphrase must differ from the pre-clear original",
        )
    }
}
