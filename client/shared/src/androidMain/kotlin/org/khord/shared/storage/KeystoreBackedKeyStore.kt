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
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun getOrCreateDatabasePassphrase(): ByteArray {
        val ivB64 = prefs.getString(PREF_IV, null)
        val ctB64 = prefs.getString(PREF_CIPHERTEXT, null)
        return if (ivB64 != null && ctB64 != null) {
            decryptExisting(
                Base64.decode(ivB64, Base64.NO_WRAP),
                Base64.decode(ctB64, Base64.NO_WRAP),
            )
        } else {
            generateAndStore()
        }
    }

    override suspend fun clear() {
        // Step 1: kill the Keystore key — encrypted blob now unrecoverable.
        val ks = JavaKeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        // Step 2: drop the SharedPreferences entry.
        prefs.edit().clear().apply()
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
        return ks.getKey(KEYSTORE_ALIAS, null) as SecretKey
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
