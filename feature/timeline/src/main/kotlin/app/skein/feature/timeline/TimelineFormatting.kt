// skein-2qv (E6.I7): pure formatting helpers behind the timeline rows and
// day sections. No Compose, no Android — `TimelineFormattingTest` covers
// them on the JVM. Every function here is a function of its arguments
// only (clock and locale are passed in), so nothing in this file reads
// wall-clock time or device locale on its own.

package app.skein.feature.timeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plan `E6.I7`: rows show "the first 80 chars of body (Markdown stripped)". */
internal const val PREVIEW_MAX_CHARS: Int = 80

/**
 * One-line body preview with Markdown syntax stripped: fence lines, block
 * prefixes (headings, bullets, ordered lists, quotes, rules), images and
 * links (keeping their text), wikilinks (keeping the alias or title),
 * inline code, emphasis, strikethrough, and HTML tags. Whitespace collapses
 * to single spaces and the result is capped at [maxChars].
 *
 * Deliberately regex-based rather than a `:core:markdown` parse: a list row
 * is not a rendering surface, and running the AST on every visible row is
 * work the scroll frame does not need.
 */
internal fun previewOf(
    bodyMd: String?,
    maxChars: Int = PREVIEW_MAX_CHARS,
): String {
    if (bodyMd.isNullOrBlank()) return ""
    val text =
        bodyMd
            .replace(FENCE_LINE, "")
            .replace(IMAGE, "$1")
            .replace(WIKILINK_ALIASED, "$2")
            .replace(WIKILINK, "$1")
            .replace(LINK, "$1")
            .replace(BLOCK_PREFIX, "")
            .replace(INLINE_CODE, "$1")
            .replace(STRONG, "$2")
            .replace(EM_STAR, "$1")
            .replace(EM_UNDERSCORE, "$1")
            .replace(STRIKE, "$1")
            .replace(HTML_TAG, "")
            .replace(WHITESPACE, " ")
            .trim()
    return if (text.length > maxChars) text.substring(0, maxChars).trimEnd() else text
}

private val FENCE_LINE = Regex("""^[ \t]*(?:`{3,}|~{3,})[^\n]*\n?""", RegexOption.MULTILINE)
private val IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
private val WIKILINK_ALIASED = Regex("""\[\[([^\]|]+)\|([^\]]+)\]\]""")
private val WIKILINK = Regex("""\[\[([^\]]+)\]\]""")
private val LINK = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val BLOCK_PREFIX =
    Regex("""^[ \t]*(?:#{1,6}[ \t]+|>[ \t]?|[-*+][ \t]+|\d+[.)][ \t]+|[-*_]{3,}[ \t]*$)""", RegexOption.MULTILINE)
private val INLINE_CODE = Regex("""`([^`]*)`""")
private val STRONG = Regex("""(\*\*|__)(.+?)\1""")
private val EM_STAR = Regex("""\*(.+?)\*""")
private val EM_UNDERSCORE = Regex("""(?<!\w)_(.+?)_(?!\w)""")
private val STRIKE = Regex("""~~(.+?)~~""")
private val HTML_TAG = Regex("""<[^>]+>""")
private val WHITESPACE = Regex("""\s+""")

// ---------------------------------------------------------------------------
// Day sections
// ---------------------------------------------------------------------------

/** One sticky-header section of the list: every entry updated on [day]. */
internal data class DaySection(
    val day: LocalDate,
    val documents: List<Document>,
)

/**
 * Groups [entries] by local calendar day of `updatedAt`, preserving the
 * newest-first order `observeTimeline` already established. Because the
 * input is sorted, each day is contiguous, so a plain `groupBy` (insertion
 * ordered) is exactly the section list.
 */
internal fun groupByDay(
    entries: List<Document>,
    zone: ZoneId,
): List<DaySection> = entries.groupBy { localDay(it.updatedAt, zone) }.map { (day, docs) -> DaySection(day, docs) }

internal fun localDay(
    epochMillis: Long,
    zone: ZoneId,
): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

/** "Today" / "Yesterday" / "Wednesday, Sep 2" (this year) / "Fri, Dec 12, 2025" (older). */
internal fun dayLabel(
    day: LocalDate,
    today: LocalDate,
    locale: Locale,
): String =
    when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale))
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale))
    }

// ---------------------------------------------------------------------------
// Relative time
// ---------------------------------------------------------------------------

private const val MINUTE_MILLIS: Long = 60_000L
private const val HOUR_MILLIS: Long = 60 * MINUTE_MILLIS
private const val DAY_MILLIS: Long = 24 * HOUR_MILLIS
private const val WEEK_MILLIS: Long = 7 * DAY_MILLIS

/**
 * Compact relative timestamp for a row: "just now", "5m", "3h", "2d", then a
 * short date ("Sep 1", or "Sep 20, 2025" across a year boundary). A
 * timestamp ahead of [nowMillis] (clock skew, restored backup) reads as
 * "just now" rather than a negative number.
 */
internal fun relativeTime(
    thenMillis: Long,
    nowMillis: Long,
    zone: ZoneId,
    locale: Locale,
): String {
    val elapsed = nowMillis - thenMillis
    return when {
        elapsed < MINUTE_MILLIS -> "just now"
        elapsed < HOUR_MILLIS -> "${elapsed / MINUTE_MILLIS}m"
        elapsed < DAY_MILLIS -> "${elapsed / HOUR_MILLIS}h"
        elapsed < WEEK_MILLIS -> "${elapsed / DAY_MILLIS}d"
        else -> {
            val then = Instant.ofEpochMilli(thenMillis).atZone(zone)
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            val pattern = if (then.year == now.year) "MMM d" else "MMM d, yyyy"
            then.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}

// ---------------------------------------------------------------------------
// Tags and kinds
// ---------------------------------------------------------------------------

/**
 * Tags declared in the document's frontmatter `tags:` array
 * (`FrontmatterKeys.TAGS`). This is the only tag source visible on a
 * `Document` itself; the repository's own tag filter resolves through TAG
 * edges, which the plan's `VaultRepository.listTags()` extension would
 * aggregate once it lands. Blank entries and a malformed value both yield
 * nothing rather than throwing.
 */
internal fun frontmatterTags(document: Document): List<String> {
    val array = document.frontmatter[FrontmatterKeys.TAGS] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        (element as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

/** Row / rail glyph per kind. Note, chat and attachment match the tab glyphs in spec §8.3. */
internal fun kindGlyph(kind: DocumentKind): String =
    when (kind) {
        DocumentKind.NOTE -> "📄"
        DocumentKind.CHAT -> "💬"
        DocumentKind.ATTACHMENT -> "📎"
        DocumentKind.AIOUT -> "✧"
    }

/** Accessibility label read in place of the glyph. */
internal fun kindLabel(kind: DocumentKind): String =
    when (kind) {
        DocumentKind.NOTE -> "Note"
        DocumentKind.CHAT -> "Chat"
        DocumentKind.ATTACHMENT -> "Attachment"
        DocumentKind.AIOUT -> "AI output"
    }

/** Filter-chip label per kind. */
internal fun kindChipLabel(kind: DocumentKind): String =
    when (kind) {
        DocumentKind.NOTE -> "Notes"
        DocumentKind.CHAT -> "Chats"
        DocumentKind.ATTACHMENT -> "Files"
        DocumentKind.AIOUT -> "AI"
    }
