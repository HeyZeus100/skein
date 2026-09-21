package app.skein.feature.timeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Pure-function coverage for the row/section formatting helpers in `TimelineFormatting.kt`. */
class TimelineFormattingTest {
    // ---- previewOf -------------------------------------------------------

    @Test
    fun `previewOf strips block and inline markdown`() {
        val body = "# Heading\n\n- **bold** item with `code` and [[Note 1|alias]] and [link](http://x)\n> quote"

        assertEquals("Heading bold item with code and alias and link quote", previewOf(body))
    }

    @Test
    fun `previewOf drops fence lines but keeps the code`() {
        val body = "```kotlin\nval x = 1\n```\nAfter"

        assertEquals("val x = 1 After", previewOf(body))
    }

    @Test
    fun `previewOf unwraps a bare wikilink`() {
        assertEquals("See Note 2.", previewOf("See [[Note 2]]."))
    }

    @Test
    fun `previewOf leaves snake_case identifiers alone`() {
        assertEquals("use foo_bar_baz here", previewOf("use foo_bar_baz here"))
    }

    @Test
    fun `previewOf caps the result at 80 characters`() {
        assertEquals(80, previewOf("a".repeat(200)).length)
    }

    @Test
    fun `previewOf of a null or blank body is empty`() {
        assertEquals("", previewOf(null))
        assertEquals("", previewOf("   \n"))
    }

    // ---- groupByDay ------------------------------------------------------

    @Test
    fun `groupByDay groups consecutive entries by local calendar day preserving order`() {
        val day1 =
            LocalDate
                .of(2026, 9, 20)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val day0 =
            LocalDate
                .of(2026, 9, 19)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val entries =
            listOf(
                doc("c", updatedAt = day1 + 5_000),
                doc("b", updatedAt = day1 + 1_000),
                doc("a", updatedAt = day0 + 1_000),
            )

        val sections = groupByDay(entries, ZoneOffset.UTC)

        assertEquals(listOf(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 19)), sections.map { it.day })
        assertEquals(listOf("c", "b"), sections[0].documents.map { it.id })
        assertEquals(listOf("a"), sections[1].documents.map { it.id })
    }

    @Test
    fun `groupByDay of nothing is nothing`() {
        assertTrue(groupByDay(emptyList(), ZoneOffset.UTC).isEmpty())
    }

    // ---- dayLabel --------------------------------------------------------

    @Test
    fun `dayLabel names today and yesterday and formats older days`() {
        val today = LocalDate.of(2026, 9, 20)

        assertEquals("Today", dayLabel(today, today, Locale.ENGLISH))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today, Locale.ENGLISH))
        assertEquals("Wednesday, Sep 2", dayLabel(LocalDate.of(2026, 9, 2), today, Locale.ENGLISH))
        assertEquals("Fri, Dec 12, 2025", dayLabel(LocalDate.of(2025, 12, 12), today, Locale.ENGLISH))
    }

    // ---- relativeTime ----------------------------------------------------

    @Test
    fun `relativeTime buckets minutes hours and days then falls back to a date`() {
        val now =
            LocalDate
                .of(2026, 9, 20)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli() + 12 * HOUR

        assertEquals("just now", relativeTime(now - 30_000, now, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("5m", relativeTime(now - 5 * MINUTE, now, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("3h", relativeTime(now - 3 * HOUR, now, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("2d", relativeTime(now - 2 * DAY, now, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("Sep 1", relativeTime(now - 19 * DAY, now, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("Sep 20, 2025", relativeTime(now - 365 * DAY, now, ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test
    fun `relativeTime treats a future timestamp as just now`() {
        assertEquals("just now", relativeTime(10_000, 0, ZoneOffset.UTC, Locale.ENGLISH))
    }

    // ---- frontmatterTags -------------------------------------------------

    @Test
    fun `frontmatterTags reads the tags array and skips blanks`() {
        val frontmatter =
            buildJsonObject {
                put(
                    FrontmatterKeys.TAGS,
                    JsonArray(listOf(JsonPrimitive("project"), JsonPrimitive(" "), JsonPrimitive("idea"))),
                )
            }

        assertEquals(listOf("project", "idea"), frontmatterTags(doc("x", frontmatter = frontmatter)))
    }

    @Test
    fun `frontmatterTags is empty when the key is missing or malformed`() {
        val malformed = buildJsonObject { put(FrontmatterKeys.TAGS, JsonPrimitive("not-an-array")) }

        assertTrue(frontmatterTags(doc("x")).isEmpty())
        assertTrue(frontmatterTags(doc("x", frontmatter = malformed)).isEmpty())
    }

    // ---- glyphs ----------------------------------------------------------

    @Test
    fun `every document kind has its own glyph`() {
        val glyphs = DocumentKind.entries.map(::kindGlyph)

        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    // ------------------------------------------------------------------

    private fun doc(
        id: String,
        updatedAt: Long = 0L,
        frontmatter: JsonObject = JsonObject(emptyMap()),
    ): Document =
        Document(
            id = id,
            kind = DocumentKind.NOTE,
            title = "Doc $id",
            bodyMd = null,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            personaId = null,
            frontmatter = frontmatter,
            contentHash = null,
        )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
