package org.khord.android

import android.app.Application
import org.khord.shared.storage.PlatformContextProvider

/**
 * Khord's Application subclass.
 *
 * The only thing it owns at process-start is publishing the application
 * context to [PlatformContextProvider] so that the SQLDelight Android
 * driver can resolve `Context.getDatabasePath()` at DB-open time. Real
 * initialization (libsodium, persistence open, Messaging.load) happens
 * on the splash screen, so a slow startup never blocks the UI thread.
 */
class KhordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlatformContextProvider.set(this)
    }
}
