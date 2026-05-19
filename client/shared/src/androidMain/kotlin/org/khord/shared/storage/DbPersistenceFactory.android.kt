package org.khord.shared.storage

import android.content.Context
import android.util.Log
import java.io.File

internal actual suspend fun openDbPersistence(
    databasePath: String,
    keyStore: KeyStore,
): Persistence {
    val context = (PlatformContextProvider.get() as? Context)
        ?: error(
            "Android Context not initialised — KhordApp must call " +
            "PlatformContextProvider.set() before openDbPersistence()."
        )

    // The DB lives under context.getDatabasePath(name); panic deletes that
    // file (SQLCipher's open helper also creates -journal/-wal/-shm).
    val dbName = databasePath.substringAfterLast('/')
    val canonicalPath = context.getDatabasePath(dbName).absolutePath
    val canonicalFile = File(canonicalPath)
    // Diagnostic only — no sensitive data. Helps tell "first launch" from
    // "DB exists but won't open" in user-submitted bug reports.
    Log.w("Khord", "DbPersistence: file exists=${canonicalFile.exists()} at $canonicalPath")

    val passphrase = keyStore.getOrCreateDatabasePassphrase()
    val driver = DriverFactory().createDriver(databasePath, passphrase)

    return DbPersistence(
        driver = driver,
        databasePath = canonicalPath,
        deleteFile = { path ->
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                File(path + suffix).takeIf { it.exists() }?.delete()
            }
        },
    )
}
