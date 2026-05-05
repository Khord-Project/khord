package org.khord.shared.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.khord.shared.storage.db.KhordDatabase
import java.io.File
import java.util.Properties

actual class DriverFactory actual constructor() {
    actual fun createDriver(databasePath: String, passphrase: ByteArray): SqlDriver {
        val url = if (databasePath == ":memory:") {
            JdbcSqliteDriver.IN_MEMORY
        } else {
            // Ensure the parent directory exists so opening doesn't fail on
            // a fresh test temp dir.
            File(databasePath).parentFile?.mkdirs()
            "jdbc:sqlite:$databasePath"
        }
        val driver = JdbcSqliteDriver(url, Properties())
        // Foreign-key enforcement is off by default in SQLite — turn it on
        // so our ON DELETE CASCADE clauses actually fire.
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        // Initialise the schema on a fresh database; safely a no-op on an
        // existing one because SQLDelight tracks schema versions via PRAGMA
        // user_version internally.
        if (currentSchemaVersion(driver) == 0L) {
            KhordDatabase.Schema.create(driver)
            setSchemaVersion(driver, KhordDatabase.Schema.version)
        }
        return driver
    }

    private fun currentSchemaVersion(driver: SqlDriver): Long {
        var version = 0L
        driver.executeQuery(null, "PRAGMA user_version;", { cursor ->
            if (cursor.next().value) version = cursor.getLong(0) ?: 0
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        return version
    }

    private fun setSchemaVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version=$version;", 0)
    }
}
