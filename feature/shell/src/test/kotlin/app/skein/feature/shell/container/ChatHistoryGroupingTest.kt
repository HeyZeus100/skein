// skein-xtov.24.6 (AL-07, UX Wave 3): pure-function coverage for
// `ChatHistoryGrouping.kt` (CHAT_UX_SPEC.md §12.2). No Compose, no
// Robolectric — matches `feature/timeline/.../TimelineFormattingTest.kt`.
package app.skein.feature.shell.container

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

private val ZONE = ZoneOffset.UTC
private val NOW = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()
private const val DAY_MILLIS = 86_400_000L

private fun item(
    id: String,
    daysAgo: Long,
    hourOfDay: Long = 0L,
): ChatHistoryItem =
    ChatHistoryItem(
        id = id,
        title = id,
        lastMessageAtMillis = NOW - daysAgo * DAY_MILLIS + hourOfDay * 3_600_000L,
        timeLabel = "",
        onOpen = {},
        onRename = {},
        onDelete = {},
    )

class ChatHistoryGroupingTest {
    @Test
    fun `buckets today, yesterday, the two windows, and a month in the current year`() {
        val items =
            listOf(
                item("today", daysAgo = 0),
                item("yesterday", daysAgo = 1),
                item("prev7-near", daysAgo = 2),
                item("prev7-far", daysAgo = 7),
                item("prev30-near", daysAgo = 8),
                item("prev30-far", daysAgo = 30),
                // 60 days before 2026-09-26 is 2026-07-28: same year as "now".
                item("older", daysAgo = 60),
            )

        val groups = groupChatHistory(items, NOW, ZONE, Locale.US)

        assertEquals(
            listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days", "July"),
            groups.map { it.label },
        )
        assertEquals(
            listOf("prev7-near", "prev7-far"),
            groups.single { it.label == "Previous 7 days" }.items.map { it.id },
        )
        assertEquals(
            listOf("prev30-near", "prev30-far"),
            groups.single { it.label == "Previous 30 days" }.items.map { it.id },
        )
    }

    @Test
    fun `a month in an earlier year carries the year`() {
        // 2025-08-22 — over a year before "now" (2026-09-26).
        val items = listOf(item("last-summer", daysAgo = 400))

        val groups = groupChatHistory(items, NOW, ZONE, Locale.US)

        assertEquals(listOf("August 2025"), groups.map { it.label })
    }

    @Test
    fun `sorts newest first within a section and omits empty sections`() {
        val items =
            listOf(
                item("today-early", daysAgo = 0, hourOfDay = -10),
                item("today-late", daysAgo = 0, hourOfDay = -1),
            )

        val groups = groupChatHistory(items, NOW, ZONE, Locale.US)

        assertEquals(listOf("Today"), groups.map { it.label })
        assertEquals(listOf("today-late", "today-early"), groups.single().items.map { it.id })
    }

    @Test
    fun `a timestamp ahead of now clamps to Today rather than a negative age`() {
        val items = listOf(item("clock-skew", daysAgo = -2))

        val groups = groupChatHistory(items, NOW, ZONE, Locale.US)

        assertEquals(listOf("Today"), groups.map { it.label })
    }
}
