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

        // SQLCipher 4.x (net.zetetic.database.sqlcipher) loads its native
        // library lazily on first SupportFactory use — no explicit init
        // needed (the 3.x `SQLiteDatabase.loadLibs(context)` API is gone).
        //
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
