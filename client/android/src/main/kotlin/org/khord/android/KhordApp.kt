package org.khord.android

import android.app.Application
import org.khord.shared.storage.PlatformContextProvider

/**
 * Khord's Application subclass.
 *
 * Responsibilities at process-start:
 *  1. Load the SQLCipher native library. `net.zetetic:sqlcipher-android`
 *     4.x does NOT self-register `libsqlcipher.so` in any static
 *     initializer (the older `android-database-sqlcipher` artifact did,
 *     hence the long-standing `SQLiteDatabase.loadLibs(context)` that no
 *     longer exists). Skipping this call manifests as
 *     `UnsatisfiedLinkError: nativeOpen … is the library loaded?` on the
 *     first DB open. Doing it in onCreate() guarantees it runs before any
 *     screen or ViewModel can touch persistence.
 *  2. Publish the application Context to [PlatformContextProvider] so the
 *     SQLDelight Android driver can resolve `Context.getDatabasePath()`
 *     at DB-open time.
 *
 * Heavyweight init (libsodium, persistence open, Messaging.load) still
 * happens on the splash screen so a slow boot never blocks the UI thread.
 */
class KhordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        PlatformContextProvider.set(this)
    }
}
