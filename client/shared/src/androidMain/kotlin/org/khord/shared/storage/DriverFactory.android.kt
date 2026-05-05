package org.khord.shared.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Placeholder Android driver implementation.
 *
 * The real Android driver wiring happens in the UI phase: it pairs an
 * `AndroidSqliteDriver` with a SQLCipher passphrase that is itself
 * derived from a Keystore-bound AES key (see persistence investigation
 * Q3). This file exists now only so the KMP `expect class DriverFactory`
 * has an `actual` on every active target.
 */
actual class DriverFactory actual constructor() {
    actual fun createDriver(databasePath: String, passphrase: ByteArray): SqlDriver {
        throw NotImplementedError(
            "Android DriverFactory not yet wired — see persistence investigation Q3 / UI phase."
        )
    }
}
