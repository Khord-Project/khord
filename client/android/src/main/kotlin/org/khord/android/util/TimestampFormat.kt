package org.khord.android.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Timestamp formatting helpers for the chat / contact-list UI.
 *
 * All inputs are ISO 8601 strings as written by `Clock.System.now().toString()`
 * in the orchestrator. Output strings are user-locale formatted via
 * [DateTimeFormatter] using a locale-sensitive medium pattern where it matters.
 *
 * Java time APIs are used (rather than kotlinx-datetime) because they're
 * already on the Android classpath via core library desugaring (enabled in
 * :android build.gradle.kts) and they ship the locale-aware day/month
 * formatters out of the box.
 */
object TimestampFormat {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private val monthDayFormatter = DateTimeFormatter.ofPattern("MMM d")
    private val monthDayYearFormatter = DateTimeFormatter.ofPattern("MMM d yyyy")
    private val fullMonthDayFormatter = DateTimeFormatter.ofPattern("MMMM d")

    /**
     * Per-message footer label below each chat bubble:
     *   - same day        →  "10:42 AM"
     *   - yesterday       →  "Yesterday, 10:42 AM"
     *   - this year       →  "Mar 15, 10:42 AM"
     *   - older           →  "Mar 15 2025, 10:42 AM"
     */
    fun formatMessageTime(iso: String): String {
        val instant = parseOrNull(iso) ?: return ""
        val zoned = instant.atZone(zone)
        val msgDate = zoned.toLocalDate()
        val today = LocalDate.now(zone)
        val time = zoned.toLocalTime().format(timeFormatter)
        return when {
            msgDate == today -> time
            msgDate == today.minusDays(1) -> "Yesterday, $time"
            msgDate.year == today.year -> "${msgDate.format(monthDayFormatter)}, $time"
            else -> "${msgDate.format(monthDayYearFormatter)}, $time"
        }
    }

    /**
     * Centered date separator inserted between messages from different days:
     *   - today         →  "Today"
     *   - yesterday     →  "Yesterday"
     *   - older         →  "March 15"
     */
    fun dateHeaderLabel(iso: String): String {
        val instant = parseOrNull(iso) ?: return ""
        val msgDate = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            msgDate == today -> "Today"
            msgDate == today.minusDays(1) -> "Yesterday"
            else -> msgDate.format(fullMonthDayFormatter)
        }
    }

    /**
     * Compact relative timestamp for the contact-list "Recent Chats" rows:
     *   - <60s         →  "now"
     *   - <60m         →  "Nm ago"
     *   - <24h         →  "Nh ago"
     *   - <48h         →  "Yesterday"
     *   - <7d          →  "Nd ago"
     *   - this year    →  "Mar 15"
     *   - older        →  "Mar 15 2025"
     */
    fun formatRelativeShort(iso: String?): String {
        if (iso == null) return ""
        val instant = parseOrNull(iso) ?: return ""
        val now = Instant.now()
        val seconds = ChronoUnit.SECONDS.between(instant, now).coerceAtLeast(0)
        return when {
            seconds < 60 -> "now"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            seconds < 86_400 * 2 -> "Yesterday"
            seconds < 86_400 * 7 -> "${seconds / 86_400}d ago"
            else -> {
                val msgDate = instant.atZone(zone).toLocalDate()
                val today = LocalDate.now(zone)
                if (msgDate.year == today.year) msgDate.format(monthDayFormatter)
                else msgDate.format(monthDayYearFormatter)
            }
        }
    }

    /** True iff the two ISO timestamps fall on the same calendar day in the user's zone. */
    fun sameDay(a: String, b: String): Boolean {
        val ai = parseOrNull(a) ?: return false
        val bi = parseOrNull(b) ?: return false
        return ai.atZone(zone).toLocalDate() == bi.atZone(zone).toLocalDate()
    }

    private fun parseOrNull(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: Throwable) {
        null
    }
}
