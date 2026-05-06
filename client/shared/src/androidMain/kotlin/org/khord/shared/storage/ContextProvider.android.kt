package org.khord.shared.storage

import android.content.Context

actual object PlatformContextProvider {
    @Volatile private var ctx: Context? = null
    actual fun set(context: Any) {
        require(context is Context) { "expected android.content.Context, got ${context::class.simpleName}" }
        // Always store the application context so we don't leak Activity refs.
        ctx = context.applicationContext
    }
    actual fun get(): Any? = ctx
}
