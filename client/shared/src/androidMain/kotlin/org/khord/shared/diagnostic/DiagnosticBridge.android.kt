package org.khord.shared.diagnostic

actual fun commonDiagnosticLog(tag: String, message: String) {
    DiagnosticLog.log(tag, message)
}
