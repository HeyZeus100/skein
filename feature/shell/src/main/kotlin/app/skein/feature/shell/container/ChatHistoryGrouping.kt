// skein-xtov.24.6 (AL-07, UX Wave 3): pure date-bucket grouping for the
// drawer/rail-pane chat history (`CHAT_UX_SPEC.md` §12.2). No Compose, no
// Android — testable on the plain JVM, mirroring
// `feature/timeline/.../TimelineFormatting.kt`'s rule: [nowMillis]/[zone]
// are parameters, never read from the wall clock, so the bucket boundary
// is deterministic in tests and screenshots.
package app.skein.feature.shell.container

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One drawer/rail-pane section (§12.2): a header label and its rows, newest first. */
data class ChatHistoryGroup(
    val label: String,
    val items: List<ChatHistoryItem>,
)

/**
 * Buckets [items] into Today / Yesterday / Previous 7 days / Previous 30
 * days / one section per month (§12.2), sections in that order and rows
 * newest-first within each. Empty sections are omitted by construction.
 *
 * Relies on the same trick as `TimelineFormatting.groupByDay`: sorting
 * newest-first first means every bucket boundary is crossed at most once
 * while walking the list, so a plain insertion-ordered `groupBy` already
 * yields sections in display order — no separate sort-the-sections step.
 */
fun groupChatHistory(
    items: List<ChatHistoryItem>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ChatHistoryGroup> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val sorted = items.sortedByDescending { it.lastMessageAtMillis }
    val buckets = linkedMapOf<String, MutableList<ChatHistoryItem>>()
    for (item in sorted) {
        val day = Instant.ofEpochMilli(item.lastMessageAtMillis).atZone(zone).toLocalDate()
        val label = chatHistoryGroupLabel(day, today, locale)
        buckets.getOrPut(label) { mutableListOf() }.add(item)
    }
    return buckets.map { (label, group) -> ChatHistoryGroup(label, group) }
}

/**
 * "Today" / "Yesterday" / "Previous 7 days" (2–7 days ago) / "Previous 30
 * days" (8–30 days ago) / the month name ("August"; "July 2025" once the
 * month falls in an earlier year than [today]). A [day] after [today]
 * (clock skew, a restored backup) clamps to "Today" rather than going
 * negative.
 */
internal fun chatHistoryGroupLabel(
    day: LocalDate,
    today: LocalDate,
    locale: Locale,
): String {
    val age = ChronoUnit.DAYS.between(day, today)
    return when {
        age <= 0 -> "Today"
        age == 1L -> "Yesterday"
        age <= 7 -> "Previous 7 days"
        age <= 30 -> "Previous 30 days"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMMM", locale))
        else -> day.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
    }
}
