package app.skein.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Line-span accounting and fenced-code state — the two invariants that
 * every downstream `LivePreviewTransformer` pass depends on. Kept in a
 * dedicated test so a fence-detection regression is legible on its own,
 * without wading through 200-line offset-mapping cases.
 */
class LineModelTest {
    @Test
    fun `single line without newline has one entry from zero to length`() {
        val lines = buildLines("hello")
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].start)
        assertEquals(5, lines[0].end)
        assertEquals(FenceKind.NONE, lines[0].fence)
    }

    @Test
    fun `trailing newline yields a final empty line`() {
        val lines = buildLines("a\nb\n")
        assertEquals(3, lines.size)
        assertEquals(Line(0, 1, FenceKind.NONE), lines[0])
        assertEquals(Line(2, 3, FenceKind.NONE), lines[1])
        assertEquals(Line(4, 4, FenceKind.NONE), lines[2])
    }

    @Test
    fun `fenced block flags open, inside, and close lines`() {
        val source =
            """
            para
            ```kotlin
            val x = 1
            val y = 2
            ```
            after
            """.trimIndent()
        val lines = buildLines(source)
        // 0: "para" (NONE), 1: "```kotlin" (OPEN),
        // 2: "val x = 1" (INSIDE), 3: "val y = 2" (INSIDE),
        // 4: "```" (CLOSE), 5: "after" (NONE)
        assertEquals(FenceKind.NONE, lines[0].fence)
        assertEquals(FenceKind.OPEN, lines[1].fence)
        assertEquals(FenceKind.INSIDE, lines[2].fence)
        assertEquals(FenceKind.INSIDE, lines[3].fence)
        assertEquals(FenceKind.CLOSE, lines[4].fence)
        assertEquals(FenceKind.NONE, lines[5].fence)
    }

    @Test
    fun `containsCursor is inclusive on both endpoints`() {
        val line = Line(start = 3, end = 8, fence = FenceKind.NONE)
        assertEquals(true, line.containsCursor(3))
        assertEquals(true, line.containsCursor(8))
        assertEquals(true, line.containsCursor(5))
        assertEquals(false, line.containsCursor(2))
        assertEquals(false, line.containsCursor(9))
    }
}
