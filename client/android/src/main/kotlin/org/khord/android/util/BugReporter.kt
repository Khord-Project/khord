package org.khord.android.util

import android.os.Build
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.khord.android.AppContainer
import org.khord.android.BuildConfig
import org.khord.shared.diagnostic.DiagnosticLog

/**
 * In-app bug reporting (commit context for v0.1.0-alpha.3).
 *
 * Collects a small structured Report — never contains fingerprints,
 * keys, message contents, contact lists, or server URLs (those last
 * are scrubbed in [scrubSensitive] before the report leaves the
 * device). Submits to a `/api/report` endpoint on the landing-page
 * host. The endpoint may not exist on day one; submission failure
 * is non-fatal and falls back to a "copy to clipboard" affordance
 * in the dialog.
 *
 * The data we DO send:
 *   - app version (from BuildConfig.VERSION_NAME)
 *   - android version and API level
 *   - device manufacturer + model
 *   - error message (scrubbed)
 *   - truncated stack trace (scrubbed)
 *   - last [DiagnosticLog.MAX_ENTRIES] in-process diagnostic lines
 *     (scrubbed). Replaces the old shell-out to `logcat` which Xiaomi /
 *     MIUI builds block for non-system UIDs — see field reports #4–#6.
 *   - user-typed additional context (consent-gated by the dialog)
 *
 * The data we DO NOT send (explicit non-goals per the feature spec):
 *   - identity fingerprints
 *   - any cryptographic key material
 *   - message body text
 *   - contact names or fingerprints
 *   - server URLs (keys.khord.org / relay.khord.org / etc.)
 *
 * Reports are only ever submitted with user consent — the dialog
 * shows a full preview of what's about to be sent.
 */
object BugReporter {

    private const val REPORT_URL = "https://khord.org/api/report"
    private const val STACK_TRACE_LIMIT = 5_000
    private const val DIAGNOSTIC_CHAR_LIMIT = 3_000

    /**
     * One captured bug report. The fields are designed so that no
     * single one carries sensitive material — see the scrubbing
     * pipeline in [collect] and [scrubSensitive].
     */
    @Serializable
    data class Report(
        val appVersion: String,
        val androidVersion: String,
        val deviceModel: String,
        val errorMessage: String,
        val stackTrace: String? = null,
        val diagnosticPath: String? = null,
        val additionalContext: String? = null,
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Build a [Report] from a Throwable + optional user-supplied
     * additional-context string. Every text field is run through
     * [scrubSensitive] before being included.
     */
    fun collect(error: Throwable, additionalContext: String? = null): Report {
        val rawMessage = error.message ?: error.toString()
        val rawStackTrace = error.stackTraceToString().take(STACK_TRACE_LIMIT)
        return Report(
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            errorMessage = scrubSensitive(rawMessage),
            stackTrace = scrubSensitive(rawStackTrace),
            diagnosticPath = collectDiagnosticPath()?.let(::scrubSensitive),
            additionalContext = additionalContext?.let(::scrubSensitive),
        )
    }

    /**
     * Pretty-printed JSON representation of a report — used by the
     * dialog's "See what will be sent" expansion AND as the clipboard
     * fallback when submission fails.
     */
    fun toJsonString(report: Report): String = json.encodeToString(report)

    /**
     * POST the report to the landing-page endpoint. Returns
     * `Result.success(message)` on HTTP 201, `Result.failure(...)`
     * otherwise. Never throws.
     */
    suspend fun submit(report: Report): Result<String> {
        val http = AppContainer.http
            ?: return Result.failure(IllegalStateException("HTTP client not initialised"))
        return try {
            val response: HttpResponse = http.post(REPORT_URL) {
                contentType(ContentType.Application.Json)
                setBody(toJsonString(report))
            }
            if (response.status.value == 201) {
                Result.success("Report submitted")
            } else {
                Result.failure(
                    IllegalStateException("Server returned HTTP ${response.status.value}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Snapshot of the in-process [DiagnosticLog] ring buffer, capped
     * at [DIAGNOSTIC_CHAR_LIMIT] so the bug-report JSON stays bounded
     * even on a chatty session. Returns null when the ring is empty
     * (e.g. a crash that fired before any DiagnosticLog.log call).
     *
     * Replaces the previous shell-out to `logcat`, which Xiaomi / MIUI
     * builds reliably blocked for non-system UIDs — every field report
     * from the M2101K6G testers (issues #4–#6) showed "not available"
     * for this field.
     */
    private fun collectDiagnosticPath(): String? {
        val dump = DiagnosticLog.dump()
        if (dump.isEmpty()) return null
        // Keep the most-recent slice if the buffer overflows the cap —
        // tail is more useful than head when triaging a crash.
        return if (dump.length <= DIAGNOSTIC_CHAR_LIMIT) dump
        else dump.takeLast(DIAGNOSTIC_CHAR_LIMIT)
    }

    /**
     * Strip patterns that are KNOWN to leak Khord-sensitive data:
     *
     *   1. URLs of any scheme → `<url>` (covers server URLs +
     *      anything else pointing at infra).
     *   2. 64-char hex strings → `<fingerprint>` (sha256-style
     *      identity hashes).
     *   3. Run-of-base64 16+ chars adjacent to a `=` → `<b64>` (a
     *      conservative heuristic catching ciphertexts that landed
     *      in exception messages). False positives on long
     *      stack-trace symbols are acceptable; over-scrubbing is
     *      cheaper than leaking.
     *
     * Order matters: do URLs before hex so the host portion of a
     * URL doesn't get partially redacted as a fingerprint.
     */
    internal fun scrubSensitive(input: String): String {
        var out = input
        out = URL_REGEX.replace(out, "<url>")
        out = HEX_FINGERPRINT_REGEX.replace(out, "<fingerprint>")
        out = BASE64_BLOB_REGEX.replace(out, "<b64>")
        return out
    }

    private val URL_REGEX = Regex("""https?://[^\s"<>'\\]+""")
    private val HEX_FINGERPRINT_REGEX = Regex("""\b[0-9a-fA-F]{64}\b""")
    private val BASE64_BLOB_REGEX = Regex("""[A-Za-z0-9+/]{16,}=+""")
}
