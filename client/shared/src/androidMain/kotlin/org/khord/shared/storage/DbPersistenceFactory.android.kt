package org.khord.shared.storage

import android.content.Context
import java.io.File
import org.khord.shared.diagnostic.DiagnosticLog

/**
 * Recursively delete the SQLCipher DB file and its sidecar artefacts
 * (-journal, -wal, -shm). Exposed at top-level so both the normal
 * panic path and the Keystore-invalidation orphan-cleanup path can
 * share it.
 */
private fun deleteSqlCipherDbFiles(canonicalPath: String) {
    for (suffix in listOf("", "-journal", "-wal", "-shm")) {
        File(canonicalPath + suffix).takeIf { it.exists() }?.delete()
    }
}

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
    DiagnosticLog.log(
        "Khord",
        "DbPersistence: file exists=${canonicalFile.exists()} at $canonicalPath",
    )

    val passphrase = keyStore.getOrCreateDatabasePassphrase()

    // Xiaomi / MIUI Keystore-invalidation recovery. If the Keystore
    // just regenerated the passphrase AND a `khord.db` file already
    // exists, that file is now encrypted with a passphrase nobody has.
    // Leaving it in place would loop forever — SQLCipher would fail to
    // decrypt on every cold start, bootstrap would return false, the
    // state-loss dialog would offer to send a report, and on the next
    // launch the same thing happens. Delete the orphan so we can start
    // fresh; the user re-derives their identity from the seed phrase.
    //
    // We only delete when we're SURE the Keystore was regenerated —
    // i.e. the keystore impl exposes lastLoadRegeneratedKey = true.
    // A fresh first launch leaves the flag false and the file doesn't
    // exist anyway.
    val ks = keyStore as? KeystoreBackedKeyStore
    if (ks?.lastLoadRegeneratedKey == true && canonicalFile.exists()) {
        DiagnosticLog.log(
            "Khord",
            "CRITICAL: Keystore key invalidated. Existing database at " +
                "$canonicalPath was encrypted with the now-lost " +
                "passphrase. Deleting the orphan so SQLCipher can " +
                "start fresh. User identity is unrecoverable without " +
                "the seed phrase.",
        )
        deleteSqlCipherDbFiles(canonicalPath)
    }

    val driver = DriverFactory().createDriver(databasePath, passphrase)

    return DbPersistence(
        driver = driver,
        databasePath = canonicalPath,
        deleteFile = ::deleteSqlCipherDbFiles,
    )
}
