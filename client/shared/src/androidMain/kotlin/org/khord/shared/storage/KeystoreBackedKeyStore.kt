package org.khord.shared.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore as JavaKeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.khord.shared.crypto.Random
import org.khord.shared.diagnostic.DiagnosticLog

/**
 * Android Keystore-backed [KeyStore].
 *
 * Pattern (per persistence investigation Q3):
 *   1. On first launch, generate an AES-256-GCM key in Android Keystore
 *      (non-extractable, hardware-backed where available).
 *   2. Generate a fresh 32-byte database passphrase.
 *   3. Encrypt the passphrase with the Keystore key (AES/GCM/NoPadding).
 *   4. Persist (iv, ciphertext) in plain SharedPreferences. Plain because
 *      the ciphertext is meaningless without the Keystore-bound key, and
 *      EncryptedSharedPreferences would be belt-on-suspenders for our use.
 *   5. On subsequent launches, decrypt the blob and return the passphrase.
 *
 * [clear] deletes the Keystore alias — the encrypted blob in
 * SharedPreferences is then mathematically unrecoverable. This IS the
 * panic button's kill-switch for the database passphrase.
 */
class KeystoreBackedKeyStore(private val context: Context) : KeyStore {

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "khord_db_passphrase_v1"
        const val PREFS_NAME = "khord_keystore_blob"
        const val PREF_IV = "iv"
        const val PREF_CIPHERTEXT = "ct"
        const val GCM_TAG_LEN_BITS = 128
        const val PASSPHRASE_LEN = 32
        const val LOG_TAG = "Khord"
    }

    /**
     * One-shot signal: was the previous call to [getOrCreateDatabasePassphrase]
     * forced to regenerate the passphrase because the persisted blob
     * couldn't be decrypted? Set only when iv+ct were both present (so
     * we're sure it's a real key-invalidation, not a first launch).
     *
     * The Android factory at [org.khord.shared.storage.openDbPersistence]
     * reads this immediately after the call to decide whether the
     * existing `khord.db` file is now an orphan that needs deleting —
     * SQLCipher won't be able to decrypt it with the freshly-minted
     * passphrase, so leaving it on disk would loop forever between
     * bootstrap and the state-loss dialog.
     *
     * Resets to false the moment a fresh call to
     * [getOrCreateDatabasePassphrase] returns successfully without
     * needing regeneration.
     */
    var lastLoadRegeneratedKey: Boolean = false
        private set

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun getOrCreateDatabasePassphrase(): ByteArray {
        val ivB64 = prefs.getString(PREF_IV, null)
        val ctB64 = prefs.getString(PREF_CIPHERTEXT, null)
        if (ivB64 != null && ctB64 != null) {
            // Normal case: decrypt the stored blob with the Keystore key.
            try {
                val pass = decryptExisting(
                    Base64.decode(ivB64, Base64.NO_WRAP),
                    Base64.decode(ctB64, Base64.NO_WRAP),
                )
                lastLoadRegeneratedKey = false
                DiagnosticLog.log(LOG_TAG, "Keystore: decrypted existing passphrase successfully")
                return pass
            } catch (e: Exception) {
                // Two distinct triggers funnel through here:
                //   1. Panic + Process.killProcess firing between the
                //      synchronous Keystore alias delete and the prefs
                //      write — the blob points at a key that no longer
                //      exists. [clear] uses commit() to avoid this, but
                //      an OS-killed-mid-flush (low-memory kill,
                //      force-stop) can still strand the next launch.
                //   2. Xiaomi / MIUI Keystore invalidation. The Keystore
                //      key just disappears (lock-screen change, system
                //      update, "clear app data" on a parent app
                //      template). Field reports #4–#6 are this path:
                //      iv+ct are both still in prefs, but no key.
                //
                // Either way we wipe the orphaned prefs entry and fall
                // through to a fresh passphrase. The
                // [lastLoadRegeneratedKey] flag tells the Android
                // factory to also delete the orphaned `khord.db` so
                // SQLCipher doesn't keep failing on a file no key can
                // open.
                DiagnosticLog.log(
                    LOG_TAG,
                    "Keystore: decrypt failed, regenerating — ${e.message}",
                )
                prefs.edit().clear().commit()
                lastLoadRegeneratedKey = true
            }
        } else {
            DiagnosticLog.log(LOG_TAG, "Keystore: no existing blob, generating fresh")
            lastLoadRegeneratedKey = false
        }
        return generateAndStore()
    }

    override suspend fun clear() {
        // Step 1: kill the Keystore key — encrypted blob now unrecoverable.
        val ks = JavaKeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        // Step 2: drop the SharedPreferences entry SYNCHRONOUSLY. apply()
        // is async — when the panic flow follows clear() with
        // Process.killProcess(), the SharedPreferences write may not flush
        // before the process dies, leaving an orphaned (iv, ct) blob
        // pointing at a Keystore key that no longer exists. On next
        // launch, decryptExisting() would then throw a SecretKey cast
        // NullPointerException. commit() blocks until the write hits disk.
        prefs.edit().clear().commit()
    }

    private fun ensureKeystoreKey(): SecretKey {
        val ks = JavaKeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        ks.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)  // forces a fresh IV per encrypt
                .setUserAuthenticationRequired(false)   // PoC: no biometric gate
                .build()
        )
        return gen.generateKey()
    }

    private fun loadKeystoreKey(): SecretKey {
        val ks = JavaKeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        // getKey returns null when the alias was deleted (e.g., after panic).
        // Throw a typed exception rather than letting `as SecretKey` produce
        // a cryptic NullPointerException — the catch in
        // getOrCreateDatabasePassphrase converts this into a wipe-and-
        // regenerate.
        return (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)
            ?: throw IllegalStateException(
                "AndroidKeyStore alias $KEYSTORE_ALIAS not found — was clear() called?"
            )
    }

    private suspend fun generateAndStore(): ByteArray {
        val key = ensureKeystoreKey()
        // Use libsodium's CSPRNG (consistent with the rest of Khord's RNG).
        org.khord.shared.crypto.Crypto.ensureInitialized()
        val passphrase = Random.bytes(PASSPHRASE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(passphrase)
        prefs.edit()
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    private fun decryptExisting(iv: ByteArray, ct: ByteArray): ByteArray {
        val key = loadKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
        }
        return cipher.doFinal(ct)
    }
}
