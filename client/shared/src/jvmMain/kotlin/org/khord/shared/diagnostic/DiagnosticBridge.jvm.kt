package org.khord.shared.diagnostic

actual fun commonDiagnosticLog(tag: String, message: String) {
    // JVM build is currently test-only; silent no-op keeps test
    // output uncluttered. If a future jvmCli wants this, route it
    // to that CLI's own logger here.
}
