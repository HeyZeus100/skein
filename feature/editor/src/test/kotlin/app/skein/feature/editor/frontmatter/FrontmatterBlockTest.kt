package app.skein.feature.editor.frontmatter

import app.skein.feature.editor.buildLines
import org.junit.Assert.assertEquals
import org.junit.Test

class FrontmatterBlockTest {
    @Test
    fun `finds the closing delimiter of a well-formed block`() {
        val source = "---\nid: 1\ntags: [a]\n---\nbody"
        val lines = buildLines(source)

        assertEquals(3, FrontmatterBlock.endLineIndex(lines, source))
    }

    @Test
    fun `returns -1 when the source has no leading delimiter`() {
        val source = "no frontmatter here\n---\nstill not one"
        val lines = buildLines(source)

        assertEquals(-1, FrontmatterBlock.endLineIndex(lines, source))
    }

    @Test
    fun `returns -1 for an unterminated block`() {
        val source = "---\nid: 1\nno closing delimiter"
        val lines = buildLines(source)

        assertEquals(-1, FrontmatterBlock.endLineIndex(lines, source))
    }

    @Test
    fun `returns -1 for an empty document`() {
        assertEquals(-1, FrontmatterBlock.endLineIndex(buildLines(""), ""))
    }

    @Test
    fun `a body-only fenced code block does not get mistaken for frontmatter`() {
        val source = "---\nid: 1\n---\n```\ncode\n```\nafter"
        val lines = buildLines(source)

        assertEquals(2, FrontmatterBlock.endLineIndex(lines, source))
    }
}
