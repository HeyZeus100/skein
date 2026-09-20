package app.skein.core.markdown

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * bd skein-ujn acceptance criterion #1: 30+ Markdown fixtures -> `RenderTree`
 * (the Skein AST) matches a committed JSON snapshot, including nested lists,
 * task items, fenced code with a language hint, wikilinks with alias, and
 * an image referencing `attachment:<uuid>`.
 *
 * Also proves the v1 guardrail by example: the `table-unsupported` and
 * `html-block-unsupported` fixtures show tables/raw-HTML degrading to
 * `UnsupportedBlock` with source preserved, rather than being parsed as
 * first-class nodes or silently dropped.
 */
class GoldenFixtureTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun fixtureCorpusHasAtLeastThirty() {
        assertEquals(true, GoldenFixtures.entries.size >= 30)
    }

    @Test
    fun fixtureNamesAreUnique() {
        val names = GoldenFixtures.entries.map { it.first }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun parsedFixturesMatchCommittedSnapshot() {
        val actual =
            GoldenFixtures.entries.joinToString("\n\n") { (name, markdown) ->
                "// $name\n" + json.encodeToString(MarkdownAst.parse(markdown))
            }
        val resource =
            javaClass.getResourceAsStream("/golden/markdown-ast-snapshots.txt")
                ?: error("Missing golden fixture resource: golden/markdown-ast-snapshots.txt")
        val expected = resource.bufferedReader().readText()
        assertEquals(expected.trim(), actual.trim())
    }
}
