package app.skein.feature.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.skein.core.markdown.render.MarkdownStyle
import app.skein.feature.editor.frontmatter.FrontmatterBlock

/**
 * Obsidian-style live preview: raw Markdown is the source of truth (never
 * rewritten), the visible text on any line the caret is NOT on has its
 * syntax markers *hidden* and the surrounding text carries the styled
 * span (bold, italic, code, larger heading, wikilink underline). The line
 * the caret IS on always shows the raw source unchanged so editing feels
 * like a plain textarea — see bd `skein-03f` acceptance criteria (§8.5 of
 * `docs/superpowers/specs/2026-09-19-skein-design.md`).
 *
 * Cursor-inside-a-fenced-code-block: the whole block is monospace; the
 * fence lines (```…```) *become* raw text only on the line the caret is
 * on; on inactive lines they stay hidden. Wikilinks and inline emphasis
 * inside a fenced block are treated as literal text (no matching) — this
 * satisfies bd `skein-03f`'s "wikilink inside a code block does NOT parse
 * as a link" acceptance criterion.
 *
 * ## Offset mapping
 *
 * Hiding characters means the visible text is shorter than the source, so
 * every insertion/deletion by the IME must round-trip through
 * [OffsetMapping]. This transformer builds a strictly monotone offset
 * table `visibleOffsets` where `visibleOffsets[i]` is the raw source
 * offset of the transformed character at position `i` (plus one sentinel
 * for end-of-text). `originalToTransformed(raw)` binary-searches for the
 * first `i` with `visibleOffsets[i] >= raw`; `transformedToOriginal(t)`
 * reads `visibleOffsets[t]`. `OffsetMappingTest` asserts the roundtrip
 * invariant for a 200-line mixed-syntax fixture.
 *
 * The transformer does not consume the full :core:markdown AST — for a
 * per-line, per-keystroke transformation we need a shallower line-scoped
 * tokenizer that never crosses paragraph boundaries. `MarkdownStyle` from
 * :core:markdown still supplies the shared color/typography tokens.
 */
internal class LivePreviewTransformer(
    private val style: MarkdownStyle,
    private val cursor: Int,
    private val knownWikilinks: Set<String>? = null,
    private val frontmatterExpanded: Boolean = false,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        transform(text.text, cursor, style, knownWikilinks, frontmatterExpanded)
}

/** Marker tag on styled wikilink runs; instrumented tests use it to click. */
internal const val TAG_WIKILINK = "wikilink"

/**
 * Bare-function entry point used by both [LivePreviewTransformer] and the
 * JVM unit tests (which can't spin up a full Compose runtime just to
 * observe an [OffsetMapping]). Exposed as internal so `OffsetMappingTest`
 * can call it directly with a source + cursor and inspect the output.
 */
internal fun transform(
    source: String,
    cursor: Int,
    style: MarkdownStyle,
    knownWikilinks: Set<String>? = null,
    frontmatterExpanded: Boolean = false,
): TransformedText {
    val lines = buildLines(source)
    val builder = AnnotatedString.Builder()
    // visibleOffsets[i] = raw source offset for transformed char i.
    // Extra sentinel slot at the end so `transformedToOriginal(len)` maps
    // to source.length (a valid past-the-end cursor position).
    val visibleOffsets = IntArray(source.length + 2)
    var visibleLen = 0

    val appendRaw: (Int) -> Unit = { rawIndex ->
        visibleOffsets[visibleLen++] = rawIndex
        builder.append(source[rawIndex])
    }

    // bd skein-6rr (E7.I3): while collapsed, the leading frontmatter block
    // (`---\n…\n---`) contributes zero characters to the transformed
    // text — the same "just skip appendRaw for the hidden run" technique
    // `renderLine` already uses for an ATX heading's `# ` prefix. The
    // one-line chip summarizing it is a separate composable
    // (`app.skein.feature.editor.frontmatter.FrontmatterChip`) the caller
    // places above the field, not synthesized text here, so there is one
    // source of truth for what's "in" the transformed string. A document
    // with no frontmatter block is completely unaffected regardless of
    // [frontmatterExpanded] — `endLineIndex` is -1 and every line renders
    // exactly as it did before this parameter existed.
    val frontmatterEndLineIndex = if (frontmatterExpanded) -1 else FrontmatterBlock.endLineIndex(lines, source)

    for ((index, line) in lines.withIndex()) {
        if (frontmatterEndLineIndex >= 0 && index <= frontmatterEndLineIndex) continue
        val isActive = line.containsCursor(cursor)
        renderLine(
            builder = builder,
            source = source,
            line = line,
            style = style,
            isActive = isActive,
            knownWikilinks = knownWikilinks,
            appendRaw = appendRaw,
        )
        if (line.end < source.length) {
            visibleOffsets[visibleLen++] = line.end
            builder.append('\n')
        }
    }
    visibleOffsets[visibleLen] = source.length

    val effectiveVisibleLen = visibleLen
    val transformedText = builder.toAnnotatedString()
    val offsets = visibleOffsets

    return TransformedText(
        text = transformedText,
        offsetMapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    if (offset <= 0) return 0
                    if (offset >= source.length) return effectiveVisibleLen
                    var lo = 0
                    var hi = effectiveVisibleLen
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (offsets[mid] < offset) lo = mid + 1 else hi = mid
                    }
                    return lo
                }

                override fun transformedToOriginal(offset: Int): Int {
                    if (offset <= 0) return 0
                    if (offset >= effectiveVisibleLen) return source.length
                    return offsets[offset]
                }
            },
    )
}

/**
 * Renders a single line: appends its transformed characters into [builder]
 * with style spans, calling [appendRaw] for every character that survives
 * into the transformed output.
 *
 * The tokenizer is deliberately line-scoped so no state crosses paragraph
 * boundaries — an unclosed `*` never eats the rest of the document.
 * Fenced code blocks are the one whole-block construct: their `INSIDE`
 * lines render as monospace verbatim.
 */
@Suppress("LongParameterList")
private fun renderLine(
    builder: AnnotatedString.Builder,
    source: String,
    line: Line,
    style: MarkdownStyle,
    isActive: Boolean,
    knownWikilinks: Set<String>?,
    appendRaw: (Int) -> Unit,
) {
    val lineText = line.text(source)
    // Fenced-code lines.
    when (line.fence) {
        FenceKind.OPEN, FenceKind.CLOSE -> {
            if (isActive) {
                withSpan(builder, style.codeStyle) {
                    for (k in line.start until line.end) appendRaw(k)
                }
            }
            return
        }
        FenceKind.INSIDE -> {
            withSpan(builder, style.codeStyle) {
                for (k in line.start until line.end) appendRaw(k)
            }
            return
        }
        FenceKind.NONE -> Unit
    }

    // Active (non-fence) line: raw source (never rewritten).
    if (isActive) {
        for (k in line.start until line.end) appendRaw(k)
        return
    }

    val n = lineText.length

    // ATX heading: leading `#`s + a required space are hidden.
    val hashCount = countLeading(lineText, '#').coerceAtMost(6)
    if (hashCount in 1..6 && hashCount < n && lineText[hashCount] == ' ') {
        withSpan(builder, style.headingStyle(hashCount)) {
            renderInlines(
                source = source,
                lineStart = line.start,
                lineText = lineText,
                fromIndex = hashCount + 1,
                style = style,
                builder = builder,
                appendRaw = appendRaw,
                knownWikilinks = knownWikilinks,
            )
        }
        return
    }

    // Blockquote `> `.
    if (n >= 2 && lineText[0] == '>' && lineText[1] == ' ') {
        withSpan(builder, style.quoteStyle) {
            for (k in line.start until line.start + 2) appendRaw(k)
            renderInlines(
                source = source,
                lineStart = line.start,
                lineText = lineText,
                fromIndex = 2,
                style = style,
                builder = builder,
                appendRaw = appendRaw,
                knownWikilinks = knownWikilinks,
            )
        }
        return
    }

    // Bullet or ordered list marker: kept visible (offset-preserving),
    // inline body scanned normally. The friendlier bullet glyph is a
    // followup (bd skein-6sd/E7.I3 territory) so this path stays 1:1
    // with the raw source for now.
    val markerLen = matchBulletPrefix(lineText).takeIf { it > 0 } ?: matchOrderedPrefix(lineText)
    if (markerLen > 0) {
        for (k in line.start until (line.start + markerLen)) appendRaw(k)
        renderInlines(
            source = source,
            lineStart = line.start,
            lineText = lineText,
            fromIndex = markerLen,
            style = style,
            builder = builder,
            appendRaw = appendRaw,
            knownWikilinks = knownWikilinks,
        )
        return
    }

    // Plain paragraph line.
    renderInlines(
        source = source,
        lineStart = line.start,
        lineText = lineText,
        fromIndex = 0,
        style = style,
        builder = builder,
        appendRaw = appendRaw,
        knownWikilinks = knownWikilinks,
    )
}

/**
 * Scans `lineText.substring(fromIndex)` and emits its inline tokens —
 * `**bold**`, `*italic*`, `` `code` ``, `[[wikilink]]` (with `|alias` and
 * `#heading` forms). Unmatched or unclosed markers fall through as
 * literal text so a half-typed `**foo` never eats the rest of the line.
 *
 * Every character that survives into the transformed output goes through
 * [appendRaw] so the offset table stays in step with the visible text.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
private fun renderInlines(
    source: String,
    lineStart: Int,
    lineText: String,
    fromIndex: Int,
    style: MarkdownStyle,
    builder: AnnotatedString.Builder,
    appendRaw: (Int) -> Unit,
    knownWikilinks: Set<String>?,
) {
    var i = fromIndex
    val n = lineText.length
    while (i < n) {
        val c = lineText[i]
        // Strong `**text**` — check first so it out-priorities single `*`.
        if (c == '*' && i + 1 < n && lineText[i + 1] == '*') {
            val closeIdx = findClose(lineText, i + 2, "**")
            if (closeIdx in (i + 3)..(n - 2)) {
                withSpan(builder, style.strongStyle) {
                    for (k in (i + 2) until closeIdx) appendRaw(lineStart + k)
                }
                i = closeIdx + 2
                continue
            }
        }
        if (c == '*') {
            val closeIdx = findClose(lineText, i + 1, "*")
            if (closeIdx in (i + 2)..(n - 1)) {
                withSpan(builder, style.emphStyle) {
                    for (k in (i + 1) until closeIdx) appendRaw(lineStart + k)
                }
                i = closeIdx + 1
                continue
            }
        }
        if (c == '`') {
            val closeIdx = findClose(lineText, i + 1, "`")
            if (closeIdx in (i + 2)..(n - 1)) {
                withSpan(builder, style.codeStyle) {
                    for (k in (i + 1) until closeIdx) appendRaw(lineStart + k)
                }
                i = closeIdx + 1
                continue
            }
        }
        if (c == '[' && i + 1 < n && lineText[i + 1] == '[') {
            val closeIdx = findClose(lineText, i + 2, "]]")
            if (closeIdx in (i + 3)..(n - 2)) {
                val inner = lineText.substring(i + 2, closeIdx)
                val pipe = inner.indexOf('|')
                val title =
                    (if (pipe >= 0) inner.substring(0, pipe) else inner).substringBefore('#').trim()
                val displayRun =
                    if (pipe >= 0) inner.substring(pipe + 1).trim().ifEmpty { title } else title
                val broken = knownWikilinks != null && title.isNotEmpty() && title !in knownWikilinks
                val linkSpan =
                    if (broken) style.wikilinkStyle.copy(color = style.mutedColor) else style.wikilinkStyle
                val runStart = if (pipe >= 0) i + 2 + pipe + 1 else i + 2
                val runEnd = if (pipe >= 0) closeIdx else closeIdx
                val rawSlice = lineText.substring(runStart, runEnd)
                builder.pushStringAnnotation(TAG_WIKILINK, title)
                withSpan(builder, linkSpan) {
                    if (rawSlice == displayRun) {
                        for (k in runStart until runEnd) appendRaw(lineStart + k)
                    } else {
                        // Trim shortened the display; map every displayed
                        // char to `runStart` — still a valid caret target
                        // inside the wikilink. `originalToTransformed`
                        // stays monotone because that shared raw index is
                        // >= any earlier raw index emitted for this line.
                        for (k in displayRun.indices) appendRaw(lineStart + runStart)
                    }
                }
                builder.pop()
                i = closeIdx + 2
                continue
            }
        }
        appendRaw(lineStart + i)
        i++
    }
}

private inline fun withSpan(
    builder: AnnotatedString.Builder,
    span: SpanStyle,
    block: () -> Unit,
) {
    val id = builder.pushStyle(span)
    try {
        block()
    } finally {
        builder.pop(id)
    }
}

private fun findClose(
    text: String,
    from: Int,
    marker: String,
): Int {
    var i = from
    val stop = text.length - marker.length + 1
    while (i < stop) {
        if (text.regionMatches(i, marker, 0, marker.length)) return i
        i++
    }
    return -1
}

private fun countLeading(
    text: String,
    ch: Char,
): Int {
    var i = 0
    while (i < text.length && text[i] == ch) i++
    return i
}

private fun matchBulletPrefix(line: String): Int {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') i++
    if (i >= line.length) return 0
    val marker = line[i]
    if (marker !in listOf('-', '*', '+')) return 0
    if (i + 1 >= line.length || line[i + 1] != ' ') return 0
    // GFM task-list `- [ ]` / `- [x]` — checkbox is part of the marker.
    if (i + 5 < line.length &&
        line[i + 2] == '[' &&
        line[i + 4] == ']' &&
        line[i + 5] == ' '
    ) {
        return i + 6
    }
    return i + 2
}

private fun matchOrderedPrefix(line: String): Int {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') i++
    val digitsStart = i
    while (i < line.length && line[i].isDigit()) i++
    if (i == digitsStart) return 0
    if (i - digitsStart > 9) return 0
    if (i >= line.length) return 0
    if (line[i] != '.' && line[i] != ')') return 0
    if (i + 1 >= line.length || line[i + 1] != ' ') return 0
    return i + 2
}
