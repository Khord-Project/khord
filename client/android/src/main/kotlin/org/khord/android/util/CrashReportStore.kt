package org.khord.android.util

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed holding area for a single pending crash
 * Report between the crash and the next cold start.
 *
 * Lifecycle:
 *   1. Uncaught-exception handler in KhordApp builds a Report,
 *      calls [save].
 *   2. App dies (the previous default handler kills the process).
 *   3. User relaunches the app.
 *   4. ContactListScreen reads via [load] on first composition.
 *      If non-null: surface BugReportDialog. The dialog's submit
 *      or dismiss handler calls [clear] so the same report isn't
 *      shown twice.
 *
 * One report at a time. If multiple crashes happen between
 * launches (a crash-loop), only the most-recent is kept — sufficient
 * for the user's immediate "report what went wrong" need, and avoids
 * the failure mode where the prefs file balloons during a crash loop.
 */
object CrashReportStore {

    private const val PREFS_NAME = "khord_crash_report"
    private const val KEY_REPORT_JSON = "pending_report_v1"

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    fun save(context: Context, report: BugReporter.Report) {
        // commit() — not apply() — so the write hits disk synchronously
        // before the process dies. apply() is async and the buffered
        // write may be dropped on hard kill.
        try {
            val payload = json.encodeToString(report)
            prefs(context).edit()
                .putString(KEY_REPORT_JSON, payload)
                .commit()
        } catch (e: Throwable) {
            Log.e("Khord", "CrashReportStore.save failed", e)
        }
    }

    fun load(context: Context): BugReporter.Report? {
        val raw = prefs(context).getString(KEY_REPORT_JSON, null) ?: return null
        return try {
            json.decodeFromString<BugReporter.Report>(raw)
        } catch (e: Throwable) {
            Log.w("Khord", "CrashReportStore.load: invalid stored payload, clearing", e)
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_REPORT_JSON).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
