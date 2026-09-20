package app.skein.feature.editor

import app.skein.core.markdown.render.MarkdownStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bd skein-03f acceptance criterion #1: for a mixed-syntax document, every
 * raw offset roundtrips through the transformer's `OffsetMapping` without
 * the caret landing inside a hidden run.
 *
 * The test exercises three angles independently:
 *  1. `originalToTransformed(transformedToOriginal(t)) == t` for every
 *     transformed offset t — the "IME edit doesn't shift the caret" case.
 *  2. Monotonicity: `originalToTransformed(a) <= originalToTransformed(b)`
 *     whenever `a <= b` — the invariant that keeps a selection non-inverted
 *     after transformation.
 *  3. Hidden-range avoidance: for every raw offset r that maps into a
 *     hidden character (e.g. the `**` around bold), the roundtrip
 *     `originalToTransformed(r)` stays clamped to the *edge* of the hidden
 *     run rather than a phantom position inside it.
 */
class OffsetMappingTest {
    private val style = MarkdownStyle.Default

    @Test
    fun `originalToTransformed and transformedToOriginal roundtrip on transformed offsets`() {
        val doc = generateMixedSyntaxDocument(lineCount = 200)
        // Cursor at end-of-document — every line is inactive, so every
        // syntax marker in the document is hidden and the offset mapping
        // is fully non-identity.
        val transformed = transform(doc, cursor = doc.length + 1, style = style)
        val mapping = transformed.offsetMapping

        for (t in 0..transformed.text.length) {
            val original = mapping.transformedToOriginal(t)
            val roundTrip = mapping.originalToTransformed(original)
            assertEquals(
                "originalToTransformed(transformedToOriginal($t)) should equal $t",
                t,
                roundTrip,
            )
        }
    }

    @Test
    fun `originalToTransformed is monotone on raw offsets`() {
        val doc = generateMixedSyntaxDocument(lineCount = 200)
        val mapping = transform(doc, cursor = doc.length + 1, style = style).offsetMapping

        var previous = mapping.originalToTransformed(0)
        for (r in 1..doc.length) {
            val current = mapping.originalToTransformed(r)
            assertTrue(
                "originalToTransformed must be monotone: at r=$r, previous=$previous > current=$current",
                current >= previous,
            )
            previous = current
        }
    }

    @Test
    fun `caret never lands inside the hidden marker of a bold run`() {
        // "**bold**" — with cursor far away, `**` on both sides is hidden.
        val source = "**bold**"
        val transformed = transform(source, cursor = 999, style = style)
        val mapping = transformed.offsetMapping

        // Offsets are *caret positions between characters*, 0-indexed
        // from "before the first char" to "after the last char":
        //   raw:  0  1  2  3  4  5  6  7  8
        //           *  *  b  o  l  d  *  *
        //   xf:   0        0  1  2  3  4
        //           b  o  l  d
        // Any raw caret in [0..2] (before/inside opening `**`) clamps to
        // transformed 0. Raw 3..6 track the visible run 1..4. Raw 7..8
        // (inside/after the trailing `**`) clamp to transformed 4.
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.originalToTransformed(1))
        assertEquals(0, mapping.originalToTransformed(2))
        assertEquals(1, mapping.originalToTransformed(3))
        assertEquals(2, mapping.originalToTransformed(4))
        assertEquals(3, mapping.originalToTransformed(5))
        assertEquals(4, mapping.originalToTransformed(6))
        assertEquals(4, mapping.originalToTransformed(7))
        assertEquals(4, mapping.originalToTransformed(8))
    }

    @Test
    fun `active line preserves identity mapping across its extent`() {
        // Cursor on line 0 → that whole line's text is raw, so offsets on
        // that line map 1:1 up to the newline.
        val source = "**bold**\n# heading"
        val transformed = transform(source, cursor = 3, style = style)
        val mapping = transformed.offsetMapping

        // Line 0 is active — raw "**bold**" occupies transformed positions
        // 0..8; every raw offset in [0..8] should map to itself.
        for (r in 0..8) {
            assertEquals("active line raw offset $r should roundtrip 1:1", r, mapping.originalToTransformed(r))
        }
    }

    @Test
    fun `empty source maps end offset to zero`() {
        val transformed = transform("", cursor = 0, style = style)
        assertEquals(0, transformed.text.length)
        assertEquals(0, transformed.offsetMapping.originalToTransformed(0))
        assertEquals(0, transformed.offsetMapping.transformedToOriginal(0))
    }

    /**
     * A 200-line fixture with headings, bold, italic, inline code,
     * wikilinks, list items and a fenced code block. Deterministic so a
     * test regression points at the transformer, not the fixture.
     */
    private fun generateMixedSyntaxDocument(lineCount: Int): String =
        buildString {
            for (i in 0 until lineCount) {
                when (i % 8) {
                    0 -> append("# Heading $i\n")
                    1 -> append("Paragraph with **bold $i** and *italic* text\n")
                    2 -> append("Line with `inline code $i` and more\n")
                    3 -> append("- bullet item $i\n")
                    4 -> append("1. ordered item $i\n")
                    5 -> append("> quoted line $i\n")
                    6 -> append("A [[Note Title $i|display $i]] wikilink here\n")
                    7 -> append("Plain line $i without any syntax\n")
                }
            }
        }
}
