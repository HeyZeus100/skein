package app.skein.feature.editor

/**
 * One physical line of the source with its `[start, end)` offsets into the
 * original raw text ([start] is the first char of the line; [end] is the
 * offset of the terminating `\n` or the string length for the final line
 * when it is unterminated). [insideFence] is true when this line falls
 * *inside* a fenced code block (`\`\`\``): the fence markers themselves
 * are [FenceKind.OPEN]/[FenceKind.CLOSE]; content between them is
 * [FenceKind.INSIDE]; everything else is [FenceKind.NONE].
 *
 * The distinction is what lets the live-preview transformer skip
 * inline-syntax matching for the interior of a fenced code block —
 * `[[Note]]` typed inside a code fence must render as literal text, not as
 * a wikilink (bd skein-03f acceptance criterion "Wikilink inside a code
 * block does NOT parse as a link").
 */
internal data class Line(
    val start: Int,
    val end: Int,
    val fence: FenceKind,
) {
    /** True when the caret offset [c] falls anywhere on this line (`[start, end]`). */
    fun containsCursor(c: Int): Boolean = c in start..end

    /** The line's textual content (excludes the trailing `\n`, if any). */
    fun text(source: String): String = source.substring(start, end)
}

internal enum class FenceKind { NONE, OPEN, INSIDE, CLOSE }

/**
 * Splits [source] into [Line]s and marks each with its fenced-code role.
 * Called once per parse pass — cheap, single pass. Recognizes only the
 * three-backtick form of a code fence (```) with optional info string; the
 * tilde-fence alt-form is not v1 syntax.
 */
internal fun buildLines(source: String): List<Line> {
    if (source.isEmpty()) return listOf(Line(0, 0, FenceKind.NONE))
    val out = ArrayList<Line>()
    var i = 0
    var openFence = false
    while (i <= source.length) {
        val nl = source.indexOf('\n', i).let { if (it < 0) source.length else it }
        val lineText = source.substring(i, nl)
        val isFenceMarker = lineText.trimStart().startsWith("```")
        val kind =
            when {
                isFenceMarker && !openFence -> FenceKind.OPEN
                isFenceMarker && openFence -> FenceKind.CLOSE
                openFence -> FenceKind.INSIDE
                else -> FenceKind.NONE
            }
        out += Line(start = i, end = nl, fence = kind)
        if (isFenceMarker) openFence = !openFence
        if (nl == source.length) break
        i = nl + 1
    }
    return out
}
