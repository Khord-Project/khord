package org.khord.shared.storage

/**
 * Per-target factory entry point.
 *
 * The deletion step inside [Persistence.panic] needs platform-specific file
 * I/O (`java.io.File` on JVM, `Context.deleteDatabase` on Android, etc.).
 * The cleanest KMP shape is an `expect` factory paired with a per-target
 * deleter implementation.
 */
internal expect suspend fun openDbPersistence(
    databasePath: String,
    keyStore: KeyStore,
): Persistence
