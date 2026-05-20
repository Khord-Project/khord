package org.khord.shared.diagnostic

/**
 * Platform-specific diagnostic logging entry point usable from
 * commonMain. On Android, the actual implementation delegates to
 * [DiagnosticLog] (ring buffer + `android.util.Log`) so messages
 * land in submitted bug reports. On JVM (test path), the actual is
 * a no-op — tests don't need this in their output.
 *
 * Use this when you need to surface a noteworthy event from
 * commonMain crypto / protocol code where direct Android logging
 * isn't reachable. Keep volume low: this isn't a general logger,
 * it's a "future bug triager will thank you" channel.
 */
expect fun commonDiagnosticLog(tag: String, message: String)
