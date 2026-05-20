package org.khord.shared.diagnostic

import android.util.Log

/**
 * Process-singleton ring buffer mirroring every Khord diagnostic line
 * to both `android.util.Log` (so adb logcat still works during dev) and
 * an in-memory window readable by [BugReporter].
 *
 * Replaces the old logcat-shelling capture path. On Xiaomi / MIUI builds
 * — and increasingly on other OEM ROMs — the `logcat` binary either
 * isn't reachable from a non-system UID or filters every line by the
 * time the bug-report dialog runs. The field reports from the Xiaomi
 * M2101K6G testers (issues #4–#6) all showed "Startup diagnostic path:
 * not available" for exactly this reason.
 *
 * The ring is fixed-size [MAX_ENTRIES] so the buffer can't grow without
 * bound on a long-running process; the oldest entry is dropped when a
 * new one comes in over the cap.
 *
 * Thread-safety: every public entry point is `@Synchronized`. The
 * buffer is hit from arbitrary background threads (bootstrap workers,
 * the push service, the registration coroutine) and from the dump
 * site (UI thread when the bug dialog opens), so unsynchronised reads
 * would race the writer.
 */
object DiagnosticLog {

    private const val MAX_ENTRIES = 100

    private val buffer = ArrayDeque<String>(MAX_ENTRIES)

    /**
     * Record one diagnostic line. Always mirrors to [Log.w] under the
     * supplied [tag] so the line still appears in adb logcat during
     * development.
     */
    @Synchronized
    fun log(tag: String, message: String) {
        Log.w(tag, message)
        val entry = "${System.currentTimeMillis()} $tag: $message"
        if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
        buffer.addLast(entry)
    }

    /**
     * Snapshot of the current ring contents, oldest line first, one
     * entry per line. Returned as a single newline-joined string —
     * matches the shape the old capture produced so the BugReporter
     * "Startup diagnostic path" field stays consistent.
     */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    /**
     * Drop every captured entry. Currently only invoked from tests,
     * but exposed in case panic ever wants to scrub the buffer before
     * the process is killed.
     */
    @Synchronized
    internal fun clear() {
        buffer.clear()
    }
}
