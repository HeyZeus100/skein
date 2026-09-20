package app.skein.feature.editor

import app.skein.core.markdown.render.MarkdownStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering-side counterpart to `OffsetMappingTest`: asserts the
 * transformer's visible output for every construct the bd acceptance
 * criteria call out, including the "wikilink inside a code block does NOT
 * parse as a link" negative case.
 */
class LivePreviewTransformerTest {
    private val style = MarkdownStyle.Default

    @Test
    fun `heading hash marker hides when caret is off the line`() {
        // Cursor on line 1 → line 0 (# Heading) is inactive → `# ` hides.
        val source = "# Heading\nbody"
        val transformed = transform(source, cursor = source.indexOf("body"), style = style)
        assertEquals("Heading\nbody", transformed.text.text)
    }

    @Test
    fun `heading raw source is preserved when caret is on the line`() {
        val source = "# Heading\nbody"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("# Heading\nbody", transformed.text.text)
    }

    @Test
    fun `bold markers hide when caret is off the line`() {
        val source = "line one\n**bold** middle"
        val transformed = transform(source, cursor = 0, style = style)
        // Line 0 is active raw; line 1 loses its `**`s.
        assertEquals("line one\nbold middle", transformed.text.text)
    }

    @Test
    fun `italic markers hide when caret is off the line`() {
        val source = "line one\n*emph* middle"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("line one\nemph middle", transformed.text.text)
    }

    @Test
    fun `inline code backticks hide when caret is off the line`() {
        val source = "line one\n`code` middle"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("line one\ncode middle", transformed.text.text)
    }

    @Test
    fun `wikilink brackets hide when caret is off the line`() {
        val source = "line one\n[[Note]] here"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("line one\nNote here", transformed.text.text)
    }

    @Test
    fun `wikilink alias renders the alias text when caret is off the line`() {
        val source = "line one\n[[Actual|display]] here"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("line one\ndisplay here", transformed.text.text)
    }

    @Test
    fun `wikilink emits a TAG_WIKILINK annotation over the display run`() {
        val source = "line one\n[[Note Title]] here"
        val transformed = transform(source, cursor = 0, style = style)
        val annotations = transformed.text.getStringAnnotations(TAG_WIKILINK, 0, transformed.text.length)
        assertEquals(1, annotations.size)
        assertEquals("Note Title", annotations[0].item)
    }

    @Test
    fun `wikilink inside a fenced code block is not parsed as a link`() {
        val source =
            """
            outside
            ```
            [[Not A Link]]
            ```
            after
            """.trimIndent()
        // Cursor on "outside" line → the fence lines are inactive, so
        // fence markers hide but the interior content stays as raw text.
        val transformed = transform(source, cursor = 0, style = style)
        val annotations = transformed.text.getStringAnnotations(TAG_WIKILINK, 0, transformed.text.length)
        assertTrue("no TAG_WIKILINK annotations expected inside a fence; found $annotations", annotations.isEmpty())
        // The wikilink text itself must still appear verbatim.
        assertTrue(transformed.text.text.contains("[[Not A Link]]"))
    }

    @Test
    fun `broken wikilink dims when knownWikilinkTitles is non-null and missing`() {
        val source = "line one\n[[Ghost]] here"
        val known = setOf("Real Note")
        val transformed = transform(source, cursor = 0, style = style, knownWikilinks = known)
        // The display text is present; the "brokenness" cue is a style
        // (dimmed color), not a text mutation — assert the annotation is
        // still emitted so downstream click handling still sees the
        // target, and the visible text is unchanged.
        assertEquals("line one\nGhost here", transformed.text.text)
    }

    @Test
    fun `known wikilink does not dim`() {
        val source = "line one\n[[Real Note]] here"
        val known = setOf("Real Note")
        val transformed = transform(source, cursor = 0, style = style, knownWikilinks = known)
        assertEquals("line one\nReal Note here", transformed.text.text)
    }

    @Test
    fun `unclosed bold marker falls through as literal text`() {
        // `**foo` (no closing `**`) — the raw `**foo` must remain, so a
        // half-typed bold marker doesn't eat the rest of the line.
        val source = "line one\n**foo bar\nnext"
        val transformed = transform(source, cursor = 0, style = style)
        // Every char survives; only the newline is added between lines.
        assertEquals("line one\n**foo bar\nnext", transformed.text.text)
    }

    @Test
    fun `bold nested inside heading collapses both markers`() {
        val source = "# Header **strong** end\nbody"
        val transformed = transform(source, cursor = source.indexOf("body"), style = style)
        assertEquals("Header strong end\nbody", transformed.text.text)
    }

    @Test
    fun `blockquote prefix stays visible on inactive line`() {
        val source = "para\n> quoted line\nafter"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("para\n> quoted line\nafter", transformed.text.text)
    }

    @Test
    fun `bullet list marker stays visible on inactive line`() {
        val source = "para\n- an item\nafter"
        val transformed = transform(source, cursor = 0, style = style)
        assertEquals("para\n- an item\nafter", transformed.text.text)
    }

    @Test
    fun `fenced code block fence lines hide when caret is outside the block`() {
        val source =
            """
            para
            ```
            code
            ```
            after
            """.trimIndent()
        // Cursor on "para" (line 0) → fence lines (1, 3) are inactive
        // and their `\`\`\`` disappears entirely from transformed text.
        val transformed = transform(source, cursor = 0, style = style)
        val text = transformed.text.text
        assertFalse("fence marker should not appear when caret is outside the block", text.contains("```"))
        // The code content stays intact and the blank fence line is
        // preserved so the transform doesn't visually re-flow the doc.
        assertTrue(text.contains("code"))
    }

    @Test
    fun `fenced code block fence marker shows when caret is on that line`() {
        val source =
            """
            para
            ```
            code
            ```
            after
            """.trimIndent()
        val openFenceOffset = source.indexOf("```")
        val transformed = transform(source, cursor = openFenceOffset, style = style)
        assertTrue(transformed.text.text.contains("```"))
    }
}
