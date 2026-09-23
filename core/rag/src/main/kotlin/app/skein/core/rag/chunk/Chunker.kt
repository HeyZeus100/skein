// `Chunker` (bd skein-92u, E5.I5, spec §7.1 step 2). Splits a document body
// into ~512-token, paragraph-boundary chunks with a 64-token overlap for
// embedding/retrieval. Builds on `MarkdownBlockScanner` (this package, for
// offset-exact block boundaries) and the real tokenizer landed by skein-bpt
// (`app.skein.core.rag.tokenizers.Tokenizer`) so packing budgets against the
// exact same token count the embedder will see.
//
// Packing algorithm, in order:
//  1. Strip a leading `---`-delimited frontmatter block, if present (bd:
//     "frontmatter is stripped first"). `bodyMd` is normally already
//     frontmatter-free (`app.skein.core.model.Document.bodyMd` is a
//     separate column from `frontmatter`) — this is a defensive no-op for
//     that normal case, and what makes the "body with only frontmatter"
//     acceptance criterion (zero chunks) hold for a caller that passes a
//     whole raw file anyway.
//  2. Scan top-level blocks (`MarkdownBlockScanner`). An oversized single
//     `PARAGRAPH` block (alone over target) is split at sentence, then
//     word, then character boundaries — greedily, always making forward
//     progress. `FENCE`/`LIST`/`QUOTE`/`HEADING`/`THEMATIC_BREAK` blocks are
//     never split, matching bd's explicit "never split inside a fence" rule
//     (extended here to every indivisible block kind).
//  3. Walk blocks in order, tracking a heading stack (by level) to compute
//     the breadcrumb active at each point, and merge every `HEADING` block
//     with the very next block into one atomic "unit" so a chunk boundary
//     can never land between a heading and its first paragraph.
//  4. Greedily pack units into chunks against `targetTokens` (counted on
//     the breadcrumb + body text together, since that is what gets
//     embedded), seeding each new chunk with the trailing `overlapTokens`
//     tokens of the previous chunk's own text, cut at a sentence boundary
//     when one is available (bd's literal wording) rather than only at
//     whole-unit boundaries — the latter alone would produce zero overlap
//     whenever a chunk happens to be exactly one large unit.
package app.skein.core.rag.chunk

import app.skein.core.rag.tokenizers.Tokenizer

/**
 * Paragraph-boundary semantic chunker (bd skein-92u).
 *
 * @param tokenizer counts tokens the same way the embedder will — packing
 *   budgets against [Tokenizer.countTokens], never an approximation.
 * @param targetTokens soft budget per chunk (spec default 512). A single
 *   indivisible unit (an oversized fence, or a heading glued to its first
 *   paragraph) may still exceed this — see the class header.
 * @param overlapTokens minimum trailing overlap seeded into the next chunk
 *   (spec default 64), cut at a sentence boundary within the previous
 *   chunk's own text when one is available (falling back to a word
 *   boundary otherwise).
 */
public class Chunker(
    private val tokenizer: Tokenizer,
    private val targetTokens: Int = DEFAULT_TARGET_TOKENS,
    private val overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
) {
    init {
        require(targetTokens > 0) { "targetTokens must be positive, was $targetTokens" }
        require(overlapTokens >= 0) { "overlapTokens must not be negative, was $overlapTokens" }
        require(overlapTokens < targetTokens) {
            "overlapTokens ($overlapTokens) must be less than targetTokens ($targetTokens)"
        }
    }

    /**
     * Chunks [bodyMd]. Returns `emptyList()` for a blank body or a body that
     * is only frontmatter. Chunk `ord` is sequential from 0 and every
     * `chunk.text` is exactly `bodyMd.substring(chunk.start, chunk.end)`.
     */
    public fun chunk(bodyMd: String): List<Chunk> {
        val body = stripFrontmatter(bodyMd)
        if (body.isBlank()) return emptyList()

        val units = buildUnits(body)
        if (units.isEmpty()) return emptyList()

        return pack(body, units)
    }

    // ------------------------------------------------------------- units

    private fun buildUnits(body: String): List<UnitSpan> {
        val blocks = MarkdownBlockScanner.scan(body)
        if (blocks.isEmpty()) return emptyList()
        val expanded = expandOversizedParagraphs(body, blocks)

        val stack = ArrayList<Pair<Int, String>>()
        val units = ArrayList<UnitSpan>()
        var idx = 0
        while (idx < expanded.size) {
            val b = expanded[idx]
            if (b.kind == BlockKind.HEADING) {
                while (stack.isNotEmpty() && stack.last().first >= b.headingLevel) stack.removeAt(stack.size - 1)
                stack.add(b.headingLevel to b.headingText)
                val breadcrumb = formatBreadcrumb(stack)
                val next = expanded.getOrNull(idx + 1)
                if (next != null && next.kind != BlockKind.HEADING) {
                    units += UnitSpan(b.start, next.end, breadcrumb)
                    idx += 2
                } else {
                    units += UnitSpan(b.start, b.end, breadcrumb)
                    idx += 1
                }
            } else {
                units += UnitSpan(b.start, b.end, formatBreadcrumb(stack))
                idx += 1
            }
        }
        return units
    }

    private fun formatBreadcrumb(stack: List<Pair<Int, String>>): String? =
        if (stack.isEmpty()) {
            null
        } else {
            stack.joinToString(" › ") { (level, text) -> "#".repeat(level) + " " + text }
        }

    // ------------------------------------------------------ oversize split

    private fun expandOversizedParagraphs(
        body: String,
        blocks: List<MdBlock>,
    ): List<MdBlock> =
        blocks.flatMap { b ->
            if (b.kind == BlockKind.PARAGRAPH) {
                splitOversizedRange(body, b.start until b.end, targetTokens).map { r ->
                    MdBlock(BlockKind.PARAGRAPH, r.first, r.last + 1)
                }
            } else {
                listOf(b)
            }
        }

    private fun splitOversizedRange(
        fullText: String,
        range: IntRange,
        maxTokens: Int,
    ): List<IntRange> {
        if (tokenizer.countTokens(fullText.substring(range.first, range.last + 1)) <= maxTokens) {
            return listOf(range)
        }
        val bySentence = greedyPack(fullText, range, sentenceBreakPoints(fullText, range), maxTokens)
        return bySentence.flatMap { r ->
            if (tokenizer.countTokens(fullText.substring(r.first, r.last + 1)) <= maxTokens) {
                listOf(r)
            } else {
                val byWord = greedyPack(fullText, r, wordBreakPoints(fullText, r), maxTokens)
                byWord.flatMap { r2 ->
                    if (tokenizer.countTokens(fullText.substring(r2.first, r2.last + 1)) <= maxTokens) {
                        listOf(r2)
                    } else {
                        greedyPack(fullText, r2, (r2.first + 1..r2.last + 1).toList(), maxTokens)
                    }
                }
            }
        }
    }

    /**
     * Greedily unions the atomic spans delimited by [breakPoints] (ascending
     * absolute offsets within `(range.first, range.last + 1]`, always ending
     * with `range.last + 1`) into groups of at most [maxTokens] tokens. An
     * atomic span that alone exceeds [maxTokens] is still emitted whole —
     * the caller is responsible for recursing at a finer granularity.
     */
    private fun greedyPack(
        fullText: String,
        range: IntRange,
        breakPoints: List<Int>,
        maxTokens: Int,
    ): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var groupStart = range.first
        var lastGoodEnd = -1
        var idx = 0
        while (idx < breakPoints.size) {
            val candidateEnd = breakPoints[idx]
            if (candidateEnd <= groupStart) {
                idx++
                continue
            }
            val tokens = tokenizer.countTokens(fullText.substring(groupStart, candidateEnd))
            when {
                tokens <= maxTokens -> {
                    lastGoodEnd = candidateEnd
                    idx++
                }
                lastGoodEnd > groupStart -> {
                    ranges += groupStart until lastGoodEnd
                    groupStart = lastGoodEnd
                    lastGoodEnd = -1
                }
                else -> {
                    ranges += groupStart until candidateEnd
                    groupStart = candidateEnd
                    lastGoodEnd = -1
                    idx++
                }
            }
        }
        val end = range.last + 1
        if (groupStart < end) ranges += groupStart until end
        return ranges
    }

    private val sentenceEnd = Regex("""[.!?]["')\]]*(?:\s+|$)""")

    private fun sentenceBreakPoints(
        fullText: String,
        range: IntRange,
    ): List<Int> {
        val points = sortedSetOf<Int>()
        val slice = fullText.substring(range.first, range.last + 1)
        for (m in sentenceEnd.findAll(slice)) points += range.first + m.range.last + 1
        points += range.last + 1
        return points.toList()
    }

    private fun wordBreakPoints(
        fullText: String,
        range: IntRange,
    ): List<Int> {
        val points = sortedSetOf<Int>()
        val slice = fullText.substring(range.first, range.last + 1)
        for (m in Regex("""\s+""").findAll(slice)) points += range.first + m.range.last + 1
        points += range.last + 1
        return points.toList()
    }

    // ------------------------------------------------------------- packing

    private fun pack(
        body: String,
        units: List<UnitSpan>,
    ): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        var chunkUnits = ArrayList<UnitSpan>()

        // The chunk's breadcrumb is its *last* unit's heading context — a chunk
        // that starts under one heading but runs into the next (because both
        // are small) is chiefly "about" wherever it ends up, which is also
        // the most useful self-description for retrieval.
        fun tokensFor(list: List<UnitSpan>): Int {
            if (list.isEmpty()) return 0
            val text = body.substring(list.first().start, list.last().end)
            return tokenizer.countTokens(embed(list.last().breadcrumb, text))
        }

        fun emit(list: List<UnitSpan>) {
            val start = list.first().start
            val end = list.last().end
            val text = body.substring(start, end)
            val breadcrumb = list.last().breadcrumb
            chunks +=
                Chunk(
                    ord = chunks.size,
                    headingBreadcrumb = breadcrumb,
                    text = text,
                    start = start,
                    end = end,
                    tokenCount = tokenizer.countTokens(embed(breadcrumb, text)),
                )
        }

        // bd: "seeded with the trailing overlap tokens of the previous one, at
        // a sentence boundary when possible". Unlike unit-granularity overlap,
        // this works even when the just-finalized chunk is a single large unit
        // (one packed paragraph, or a heading glued to its first paragraph) —
        // it walks sentence boundaries inside that unit's own text, falling
        // back to word boundaries when no sentence punctuation is present.
        fun overlapSeed(list: List<UnitSpan>): UnitSpan? {
            if (overlapTokens == 0) return null
            val start = list.first().start
            val end = list.last().end
            val cut =
                overlapCut(body, start, end, sentenceBreakPoints(body, start until end))
                    ?: overlapCut(body, start, end, wordBreakPoints(body, start until end))
            return if (cut != null && cut > start) UnitSpan(cut, end, list.last().breadcrumb) else null
        }

        var i = 0
        while (i < units.size) {
            if (chunkUnits.isEmpty()) {
                chunkUnits.add(units[i])
                i++
                continue
            }
            val candidate = ArrayList(chunkUnits).apply { add(units[i]) }
            if (tokensFor(candidate) <= targetTokens) {
                chunkUnits = candidate
                i++
            } else {
                emit(chunkUnits)
                var seed = overlapSeed(chunkUnits)
                if (seed != null && tokensFor(listOf(seed, units[i])) > targetTokens) seed = null
                chunkUnits = if (seed != null) arrayListOf(seed) else ArrayList()
            }
        }
        if (chunkUnits.isNotEmpty()) emit(chunkUnits)
        return chunks
    }

    /**
     * The boundary among [boundaries] (ascending absolute offsets strictly
     * inside `(start, end)`) closest to [end] that still covers at least
     * [overlapTokens] tokens of `[cut, end)`, or — if none reaches that
     * budget — the earliest one (maximal available overlap). `null` when
     * [boundaries] has nothing strictly between [start] and [end].
     */
    private fun overlapCut(
        body: String,
        start: Int,
        end: Int,
        boundaries: List<Int>,
    ): Int? {
        val interior = boundaries.filter { it in (start + 1) until end }
        if (interior.isEmpty()) return null
        var cut = interior.first()
        for (b in interior.asReversed()) {
            cut = b
            if (tokenizer.countTokens(body.substring(b, end)) >= overlapTokens) return cut
        }
        return cut
    }

    private fun embed(
        breadcrumb: String?,
        text: String,
    ): String = if (breadcrumb == null) text else "$breadcrumb\n\n$text"

    // --------------------------------------------------------- frontmatter

    /** Strips a leading `---`/`---` frontmatter block, if [raw] starts with one. Otherwise returns [raw] unchanged. */
    private fun stripFrontmatter(raw: String): String {
        var start = 0
        while (start < raw.length && raw[start] == ' ') start++
        if (start >= raw.length || !raw.startsWith("---", start)) return raw
        val markerEnd = start + 3
        val firstLineEnd = raw.indexOf('\n', markerEnd).let { if (it < 0) raw.length else it }
        if (raw.substring(markerEnd, firstLineEnd).isNotBlank()) return raw

        var lineStart = firstLineEnd + 1
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            if (raw.substring(lineStart, lineEnd).trim() == "---") {
                return raw.substring((lineEnd + 1).coerceAtMost(raw.length))
            }
            if (lineEnd >= raw.length) return raw
            lineStart = lineEnd + 1
        }
        return raw
    }

    public companion object {
        public const val DEFAULT_TARGET_TOKENS: Int = 512
        public const val DEFAULT_OVERLAP_TOKENS: Int = 64
    }
}

/** A packable span before chunking: [start, end) into `bodyMd`, plus the heading breadcrumb active there. */
private data class UnitSpan(
    val start: Int,
    val end: Int,
    val breadcrumb: String?,
)
