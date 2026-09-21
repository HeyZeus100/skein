// `MarkdownBlockScanner` (bd skein-92u, E5.I5, spec §7.1 step 2). A small,
// offset-exact top-level block scanner over raw `bodyMd` text, purpose-built
// for `Chunker`.
//
// Why not walk `:core:markdown`'s `SkeinDocument` AST (`SkeinNode.kt`)
// instead? That AST is deliberately position-free — `Heading`/`Paragraph`/
// etc. carry no source offsets, because its only consumers so far
// (`MarkdownRenderer`, `MarkdownFlattener`, `DocxWriter`) re-render content
// rather than pointing back into the original string. The chunker's whole
// job is producing *stable byte-anchored locators* into `bodyMd`
// (`docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3's `Locator`), so it needs
// exact offsets more than it needs full inline-formatting fidelity. This
// mirrors the precedent already in the codebase: `WikilinkExtractor` and
// `TagExtractor` (`:core:vault`) both work directly off `bodyMd` with a
// fence-aware line scan for exactly the same reason (see that module's
// `MarkdownLines.kt` header) rather than through the AST. Adding an offset
// map to `:core:markdown` itself is out of scope here (non-negotiable: no
// edits to `:core:markdown`).
//
// Simplifications versus full CommonMark (acceptable for a chunk-boundary
// scanner, not a renderer): only the three-backtick fence form is
// recognized (matching `MarkdownLines.kt`'s own note); list/quote "loose"
// continuation uses a simpler indentation/marker heuristic instead of the
// full CommonMark list-item algorithm; reference-style links, tables and
// HTML blocks are not special-cased (they just fall out as paragraph text,
// which is fine — this scanner only needs to agree on *block* boundaries).

package app.skein.core.rag.chunk

/** Coarse top-level block kinds `Chunker` packs around. */
internal enum class BlockKind {
    HEADING,
    PARAGRAPH,
    LIST,
    QUOTE,
    FENCE,
    THEMATIC_BREAK,
}

/**
 * One top-level block. [start]/[end] are `[start, end)` char offsets into
 * the scanned source. After [MarkdownBlockScanner.scan] returns, blocks
 * *partition* the source exactly — `blocks[0].start == 0`,
 * `blocks[i].end == blocks[i + 1].start`, and `blocks.last().end ==
 * source.length` — so concatenating every block's raw span reconstructs the
 * source verbatim (gaps such as the blank line between two paragraphs are
 * folded into the end of the preceding block).
 */
internal data class MdBlock(
    val kind: BlockKind,
    val start: Int,
    val end: Int,
    val headingLevel: Int = 0,
    val headingText: String = "",
)

internal object MarkdownBlockScanner {
    private val ATX_RE = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*))?$""")

    fun scan(source: String): List<MdBlock> {
        if (source.isBlank()) return emptyList()
        val lines = LineIndex(source)
        val tight = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.count) {
            val t = lines.text(i)
            if (t.isBlank()) {
                i++
                continue
            }
            i =
                when {
                    isFenceLine(t) -> scanFence(lines, i, tight)
                    atxHeading(t) != null -> scanAtxHeading(lines, i, tight)
                    isThematicBreak(t) -> scanThematicBreak(lines, i, tight)
                    isListStart(t) -> scanList(lines, i, tight)
                    isQuoteStart(t) -> scanQuote(lines, i, tight)
                    else -> scanParagraph(lines, i, tight)
                }
        }
        if (tight.isEmpty()) return emptyList()
        return tight.mapIndexed { idx, b ->
            val newStart = if (idx == 0) 0 else b.start
            val newEnd = if (idx == tight.lastIndex) source.length else tight[idx + 1].start
            b.copy(start = newStart, end = newEnd)
        }
    }

    // ------------------------------------------------------------ block scans

    private fun scanFence(
        lines: LineIndex,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        var j = start + 1
        while (j < lines.count && !isFenceLine(lines.text(j))) j++
        val endLine = if (j < lines.count) j else lines.count - 1
        out += MdBlock(BlockKind.FENCE, lines.start(start), lines.end(endLine))
        return endLine + 1
    }

    private fun scanAtxHeading(
        lines: LineIndex,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        val (level, text) = atxHeading(lines.text(start))!!
        out += MdBlock(BlockKind.HEADING, lines.start(start), lines.end(start), level, text)
        return start + 1
    }

    private fun scanThematicBreak(
        lines: LineIndex,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        out += MdBlock(BlockKind.THEMATIC_BREAK, lines.start(start), lines.end(start))
        return start + 1
    }

    private fun scanList(
        lines: LineIndex,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        var last = start
        var i = start + 1
        while (i < lines.count) {
            val t = lines.text(i)
            if (t.isNotBlank()) {
                if (interruptsList(t)) break
                last = i
                i++
            } else {
                val j =
                    nextNonBlank(lines, i) ?: run {
                        i = lines.count
                        -1
                    }
                if (j < 0) break
                val nt = lines.text(j)
                if (isListStart(nt) || isIndentedContinuation(nt)) {
                    last = j
                    i = j + 1
                } else {
                    break
                }
            }
        }
        out += MdBlock(BlockKind.LIST, lines.start(start), lines.end(last))
        return i
    }

    private fun scanQuote(
        lines: LineIndex,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        var last = start
        var i = start + 1
        while (i < lines.count) {
            val t = lines.text(i)
            if (t.isNotBlank()) {
                if (interruptsQuote(t)) break
                last = i
                i++
            } else {
                val j =
                    nextNonBlank(lines, i) ?: run {
                        i = lines.count
                        -1
                    }
                if (j < 0) break
                if (isQuoteStart(lines.text(j))) {
                    last = j
                    i = j + 1
                } else {
                    break
                }
            }
        }
        out += MdBlock(BlockKind.QUOTE, lines.start(start), lines.end(last))
        return i
    }

    private fun scanParagraph(
        lines: LineIndex,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        var last = start
        var i = start + 1
        while (i < lines.count) {
            val t = lines.text(i)
            if (t.isBlank()) break
            val setext = setextLevel(t)
            if (setext != null) {
                val headingText = paragraphText(lines, start, last)
                out += MdBlock(BlockKind.HEADING, lines.start(start), lines.end(i), setext, headingText)
                return i + 1
            }
            if (startsNewTopLevelBlock(t)) break
            last = i
            i++
        }
        out += MdBlock(BlockKind.PARAGRAPH, lines.start(start), lines.end(last))
        return i
    }

    // ------------------------------------------------------------ predicates

    private fun interruptsList(t: String): Boolean =
        atxHeading(t) != null || isFenceLine(t) || isThematicBreak(t) || isQuoteStart(t)

    private fun interruptsQuote(t: String): Boolean =
        atxHeading(t) != null || isFenceLine(t) || isThematicBreak(t) || isListStart(t)

    private fun startsNewTopLevelBlock(t: String): Boolean =
        atxHeading(t) != null || isFenceLine(t) || isThematicBreak(t) || isListStart(t) || isQuoteStart(t)

    private fun isFenceLine(t: String): Boolean = t.trimStart().startsWith("```")

    private fun atxHeading(t: String): Pair<Int, String>? {
        val m = ATX_RE.find(t) ?: return null
        val level = m.groupValues[1].length
        val text = (m.groupValues.getOrNull(2) ?: "").trim().trimEnd('#').trim()
        return level to text
    }

    private fun isThematicBreak(t: String): Boolean {
        val s = t.trim()
        if (s.length < 3) return false
        val c = s[0]
        if (c != '-' && c != '_' && c != '*') return false
        if (!s.all { it == c || it == ' ' || it == '\t' }) return false
        return s.count { it == c } >= 3
    }

    private fun setextLevel(t: String): Int? {
        val s = t.trim()
        if (s.isEmpty()) return null
        return when {
            s.all { it == '=' } -> 1
            s.all { it == '-' } -> 2
            else -> null
        }
    }

    private fun isListStart(t: String): Boolean {
        var idx = 0
        var spaces = 0
        while (idx < t.length && t[idx] == ' ' && spaces < 3) {
            idx++
            spaces++
        }
        if (idx >= t.length) return false
        val c = t[idx]
        if (c == '-' || c == '+' || c == '*') {
            val rest = idx + 1
            return rest == t.length || t[rest] == ' ' || t[rest] == '\t'
        }
        if (c.isDigit()) {
            var j = idx
            var digits = 0
            while (j < t.length && t[j].isDigit() && digits < 9) {
                j++
                digits++
            }
            if (j < t.length && (t[j] == '.' || t[j] == ')')) {
                val rest = j + 1
                return rest == t.length || t[rest] == ' ' || t[rest] == '\t'
            }
        }
        return false
    }

    private fun isQuoteStart(t: String): Boolean {
        var idx = 0
        var spaces = 0
        while (idx < t.length && t[idx] == ' ' && spaces < 3) {
            idx++
            spaces++
        }
        return idx < t.length && t[idx] == '>'
    }

    private fun isIndentedContinuation(t: String): Boolean = t.isNotBlank() && (t.startsWith(" ") || t.startsWith("\t"))

    private fun nextNonBlank(
        lines: LineIndex,
        from: Int,
    ): Int? {
        var j = from
        while (j < lines.count && lines.text(j).isBlank()) j++
        return if (j < lines.count) j else null
    }

    private fun paragraphText(
        lines: LineIndex,
        start: Int,
        last: Int,
    ): String = (start..last).joinToString(" ") { lines.text(it).trim() }.trim()

    /** Physical-line index over [source]: `[start, end)` char offsets per line, newline excluded. */
    private class LineIndex(
        private val source: String,
    ) {
        private val starts: IntArray =
            run {
                val list = ArrayList<Int>(source.length / 40 + 4)
                list.add(0)
                for (i in source.indices) if (source[i] == '\n') list.add(i + 1)
                list.toIntArray()
            }

        val count: Int get() = starts.size

        fun start(i: Int): Int = starts[i]

        fun end(i: Int): Int = if (i + 1 < starts.size) starts[i + 1] - 1 else source.length

        fun text(i: Int): String = source.substring(start(i), end(i))
    }
}
