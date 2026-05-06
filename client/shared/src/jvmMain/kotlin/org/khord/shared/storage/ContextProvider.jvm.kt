package org.khord.shared.storage

actual object PlatformContextProvider {
    @Volatile private var ctx: Any? = null
    actual fun set(context: Any) { ctx = context }
    actual fun get(): Any? = ctx
}
