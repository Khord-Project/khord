package org.khord.shared.storage

/**
 * Per-target hook for the platform context the persistence layer needs.
 *
 * On Android the `actual` reads `android.app.Application` (set by
 * [KhordApp.onCreate]) and exposes it as `Any` so commonMain code can
 * pass it through to the Android-side `DriverFactory.actual` cast.
 * On JVM the `actual` is a no-op.
 *
 * Keeping the type as `Any?` avoids polluting commonMain with platform
 * types — the Android `actual` of `DriverFactory` casts on use.
 */
expect object PlatformContextProvider {
    fun set(context: Any)
    fun get(): Any?
}
