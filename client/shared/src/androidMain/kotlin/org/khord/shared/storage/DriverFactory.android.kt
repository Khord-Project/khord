package org.khord.shared.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.khord.shared.storage.db.KhordDatabase

actual class DriverFactory actual constructor() {

    actual fun createDriver(databasePath: String, passphrase: ByteArray): SqlDriver {
        val context = (PlatformContextProvider.get() as? Context)
            ?: error(
                "Android Context not initialised — KhordApp.onCreate() " +
                "must run (it sets PlatformContextProvider) before any " +
                "DriverFactory call."
            )

        // SQLCipher 4.x (net.zetetic:sqlcipher-android) does NOT self-load
        // libsqlcipher.so — there's no static initializer hook and no
        // `loadLibs()` API. The canonical load site is KhordApp.onCreate();
        // calling it again here is a no-op (System.loadLibrary is idempotent
        // per JLS) but guards against entry points that might bypass the
        // Application class (instrumented tests, ContentProviders that
        // touch the DB before onCreate, etc.).
        System.loadLibrary("sqlcipher")

        // SupportOpenHelperFactory takes the passphrase as a ByteArray —
        // bytes-based passphrases avoid char-encoding ambiguity across
        // implementations. SQLCipher zeroes the array after key derivation,
        // so callers shouldn't reuse it.
        val factory = SupportOpenHelperFactory(passphrase)

        // SQLDelight's AndroidSqliteDriver uses Context.getDatabasePath()
        // under the hood and only accepts a basename, not a full path.
        val name = databasePath.substringAfterLast('/')

        return AndroidSqliteDriver(
            schema = KhordDatabase.Schema,
            context = context,
            name = name,
            factory = factory,
        )
    }
}
