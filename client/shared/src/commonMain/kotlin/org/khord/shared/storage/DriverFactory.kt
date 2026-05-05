package org.khord.shared.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Per-target SQLDelight driver constructor.
 *
 * Currently produces a plain SQLite driver. SQLCipher support lands in the
 * Android UI phase as a one-line driver swap (see ADR 015 / persistence
 * investigation Q1). The `passphrase` argument is recorded here so that
 * swap doesn't change the call site.
 *
 * Implementations:
 *   - jvmMain  → JdbcSqliteDriver (`org.xerial:sqlite-jdbc`), file or `:memory:`
 *   - androidMain → AndroidSqliteDriver (placeholder pending UI phase)
 */
expect class DriverFactory() {
    /**
     * Open (or create) a database at `databasePath`. Pass `":memory:"` to
     * get an ephemeral in-memory DB (useful for tests). The `passphrase`
     * is currently unused; it's threaded through so the SQLCipher swap is
     * a no-call-site-change patch.
     */
    fun createDriver(databasePath: String, passphrase: ByteArray): SqlDriver
}
