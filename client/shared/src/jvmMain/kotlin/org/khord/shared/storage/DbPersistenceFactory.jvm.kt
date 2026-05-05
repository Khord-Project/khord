package org.khord.shared.storage

import java.io.File

internal actual suspend fun openDbPersistence(
    databasePath: String,
    keyStore: KeyStore,
): Persistence {
    val passphrase = keyStore.getOrCreateDatabasePassphrase()
    val driver = DriverFactory().createDriver(databasePath, passphrase)
    return DbPersistence(
        driver = driver,
        databasePath = databasePath,
        deleteFile = { path ->
            // Companion files SQLite may have created.
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                File(path + suffix).takeIf { it.exists() }?.delete()
            }
        },
    )
}
