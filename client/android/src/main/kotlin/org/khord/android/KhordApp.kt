package org.khord.android

import android.app.Application
import android.util.Log
import org.khord.android.util.BugReporter
import org.khord.android.util.CrashReportStore
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
        // Seed the theme StateFlow from SharedPreferences so the very first
        // composition shows the user's saved theme (no flash of default).
        AppContainer.loadInitialTheme(this)
        // Ensure the notification channel exists before anything tries to
        // post to it. Cheap, idempotent.
        org.khord.android.push.KhordNotifications.ensureChannel(this)
        // Install the uncaught-exception handler so any unrecovered
        // crash gets a Report saved to SharedPreferences. The next
        // cold start sees the saved report and surfaces
        // [BugReportDialog] from ContactListScreen with the user's
        // consent flow.
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Best-effort capture. Anything that throws inside here
            // is swallowed — we MUST chain to the previous handler
            // (Android's default = crash + kill) so the system keeps
            // its normal "stack-trace to logcat, kill the process"
            // behaviour. The Report is the diagnostic we'd otherwise
            // lose.
            try {
                val report = BugReporter.collect(throwable)
                CrashReportStore.save(this, report)
                Log.e("Khord", "Uncaught exception captured for next-launch dialog", throwable)
            } catch (_: Throwable) {
                // Nothing more we can do here.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
