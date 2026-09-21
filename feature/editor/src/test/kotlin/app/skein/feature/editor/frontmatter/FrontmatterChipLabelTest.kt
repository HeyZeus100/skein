package app.skein.feature.editor.frontmatter

import app.skein.feature.editor.buildLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** bd `skein-6rr`: the collapsed chip reads `— id 0192… · 3 tags ▸` (plan `E7.I3` description, verbatim example). */
class FrontmatterChipLabelTest {
    private fun label(source: String): String {
        val lines = buildLines(source)
        val end = FrontmatterBlock.endLineIndex(lines, source)
        return FrontmatterChipLabel.build(source, lines, end)
    }

    @Test
    fun `shortens a long id and counts a flow-style tags list`() {
        val source = "---\nid: 0192abcd6c3a7c3e8f2a6b1e2d3c4a5b\ntags: [example, fixture, third]\n---\nbody"

        assertEquals("— id 0192abcd… · 3 tags ▸", label(source))
    }

    @Test
    fun `does not truncate a short id`() {
        val source = "---\nid: short\ntags: [a]\n---\nbody"

        assertEquals("— id short · 1 tags ▸", label(source))
    }

    @Test
    fun `omits the tag count when there is no tags key`() {
        val source = "---\nid: abc\n---\nbody"

        assertEquals("— id abc ▸", label(source))
    }

    @Test
    fun `an empty flow list counts as zero tags`() {
        val source = "---\nid: abc\ntags: []\n---\nbody"

        assertEquals("— id abc · 0 tags ▸", label(source))
    }
}
