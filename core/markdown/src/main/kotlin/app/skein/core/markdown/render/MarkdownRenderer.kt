package app.skein.core.markdown.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import app.skein.core.markdown.ast.BlockNode
import app.skein.core.markdown.ast.BulletList
import app.skein.core.markdown.ast.Code
import app.skein.core.markdown.ast.CodeBlock
import app.skein.core.markdown.ast.Emph
import app.skein.core.markdown.ast.HardBreak
import app.skein.core.markdown.ast.Heading
import app.skein.core.markdown.ast.Image
import app.skein.core.markdown.ast.ImageTarget
import app.skein.core.markdown.ast.InlineNode
import app.skein.core.markdown.ast.Link
import app.skein.core.markdown.ast.ListItem
import app.skein.core.markdown.ast.OrderedList
import app.skein.core.markdown.ast.Paragraph
import app.skein.core.markdown.ast.Quote
import app.skein.core.markdown.ast.SkeinDocument
import app.skein.core.markdown.ast.SoftBreak
import app.skein.core.markdown.ast.Strikethrough
import app.skein.core.markdown.ast.Strong
import app.skein.core.markdown.ast.Text
import app.skein.core.markdown.ast.ThematicBreak
import app.skein.core.markdown.ast.UnsupportedBlock
import app.skein.core.markdown.ast.UnsupportedInline
import app.skein.core.markdown.ast.WikiLink

/**
 * Renders the Skein Markdown AST to a Compose [AnnotatedString], for the
 * live-preview editor's inactive lines, chat message bubbles, and anywhere
 * else that needs styled (not raw) Markdown text. Used together with
 * [plainText] (timeline previews, search snippets, anywhere raw syntax would
 * be noise).
 *
 * Wikilinks and citations are exposed as [AnnotatedString.getStringAnnotations]
 * ranges so a caller's `ClickableText`/`LinkAnnotation` handling can react to
 * taps without re-parsing Markdown:
 *  - [TAG_WIKILINK] — annotation is the target (`"target#heading"` when the
 *    link has a heading fragment, else just `"target"`).
 *  - [TAG_LINK] — annotation is the link destination.
 *  - [TAG_IMAGE] — annotation is `"attachment:<uuid>"` or the external URL.
 *  - [TAG_CITATION] — only added when [toAnnotatedString] is given a non-null
 *    `citations` list; annotation is the 1-based citation number found in a
 *    `[n]` marker in the *rendered* text (chat assembles these into the
 *    assistant's answer text; they are not Markdown syntax).
 */
object MarkdownRenderer {
    const val TAG_WIKILINK = "wikilink"
    const val TAG_LINK = "link"
    const val TAG_IMAGE = "image"
    const val TAG_CITATION = "citation"

    private val citationMarker = Regex("\\[(\\d+)]")

    /**
     * @param citations when non-null, `[n]` markers in rendered text (1-based
     *   index into this list) are tagged with [TAG_CITATION]; `n` must be a
     *   valid index (`1..citations.size`) to be treated as a citation rather
     *   than literal text — see spec §8.4 / bd skein-ujn acceptance criteria.
     */
    fun toAnnotatedString(
        document: SkeinDocument,
        style: MarkdownStyle = MarkdownStyle.Default,
        citations: List<String>? = null,
    ): AnnotatedString =
        buildAnnotatedString {
            document.blocks.forEachIndexed { index, block ->
                if (index > 0) append("\n\n")
                appendBlock(block, style, citations, depth = 0)
            }
        }

    /** Strips all Markdown syntax to plain readable text (timeline previews, search snippets). */
    fun plainText(document: SkeinDocument): String = document.blocks.joinToString("\n\n") { plainTextBlock(it) }

    // ------------------------------------------------------------------
    // AnnotatedString rendering
    // ------------------------------------------------------------------

    private fun AnnotatedString.Builder.appendBlock(
        block: BlockNode,
        style: MarkdownStyle,
        citations: List<String>?,
        depth: Int,
    ) {
        when (block) {
            is Heading -> withStyle(style.headingStyle(block.level)) { appendInlines(block.inlines, style, citations) }
            is Paragraph -> appendInlines(block.inlines, style, citations)
            is CodeBlock -> withStyle(style.codeStyle) { append(block.text) }
            is Quote -> appendQuote(block, style, citations)
            is BulletList -> appendListItems(block.items, style, citations, depth) { style.bulletMarker }
            is OrderedList -> {
                var number = block.startNumber
                appendListItems(block.items, style, citations, depth) { "${number++}. " }
            }
            ThematicBreak -> withStyle(style.unsupportedStyle) { append(style.thematicBreak) }
            is UnsupportedBlock -> withStyle(style.unsupportedStyle) { append(block.raw) }
        }
    }

    /**
     * A single leading [MarkdownStyle.quotePrefix] plus the muted/italic
     * [MarkdownStyle.quoteStyle] span over the whole quote conveys "this is
     * quoted" without re-inserting the prefix at every wrapped *visual*
     * line, which plain text can't do correctly (wrapping happens at layout
     * time, well after this string is built). A prefix bar redrawn per
     * visual line belongs to the live-preview editor's per-line decoration
     * layer (E7.I1), which has layout information this function doesn't.
     */
    private fun AnnotatedString.Builder.appendQuote(
        quote: Quote,
        style: MarkdownStyle,
        citations: List<String>?,
    ) {
        withStyle(style.quoteStyle) {
            append(style.quotePrefix)
            quote.blocks.forEachIndexed { index, child ->
                if (index > 0) append("\n\n")
                appendBlock(child, style, citations, depth = 0)
            }
        }
    }

    private inline fun AnnotatedString.Builder.appendListItems(
        items: List<ListItem>,
        style: MarkdownStyle,
        citations: List<String>?,
        depth: Int,
        marker: () -> String,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) append("\n")
            append("  ".repeat(depth))
            when (item.checked) {
                true -> append(style.checkedMarker)
                false -> append(style.uncheckedMarker)
                null -> append(marker())
            }
            item.blocks.forEachIndexed { blockIndex, block ->
                if (blockIndex > 0) append("\n")
                appendBlock(block, style, citations, depth = depth + 1)
            }
        }
    }

    private fun AnnotatedString.Builder.appendInlines(
        inlines: List<InlineNode>,
        style: MarkdownStyle,
        citations: List<String>?,
    ) {
        for (inline in inlines) appendInline(inline, style, citations)
    }

    private fun AnnotatedString.Builder.appendInline(
        inline: InlineNode,
        style: MarkdownStyle,
        citations: List<String>?,
    ) {
        when (inline) {
            is Text -> appendTextWithCitations(inline.value, style, citations)
            is Emph -> withStyle(style.emphStyle) { appendInlines(inline.inlines, style, citations) }
            is Strong -> withStyle(style.strongStyle) { appendInlines(inline.inlines, style, citations) }
            is Strikethrough -> withStyle(style.strikethroughStyle) { appendInlines(inline.inlines, style, citations) }
            is Code -> withStyle(style.codeStyle) { append(inline.value) }
            is Link ->
                withAnnotation(TAG_LINK, inline.destination) {
                    withStyle(style.linkStyle) { appendInlines(inline.inlines, style, citations) }
                }
            is WikiLink -> {
                val annotation = if (inline.heading != null) "${inline.target}#${inline.heading}" else inline.target
                withAnnotation(TAG_WIKILINK, annotation) {
                    withStyle(style.wikilinkStyle) { append(inline.alias ?: inline.target) }
                }
            }
            is Image ->
                withAnnotation(TAG_IMAGE, imageAnnotation(inline.target)) {
                    withStyle(style.imagePlaceholderStyle) {
                        append(style.imagePlaceholderPrefix)
                        append(inline.alt.ifEmpty { "image" })
                    }
                }
            SoftBreak -> append("\n")
            HardBreak -> append("\n")
            // A `[1]`-shaped chat citation marker parses as an (unresolved)
            // short-reference-link, landing here rather than as plain Text —
            // still scan it for citation markers so citation tagging doesn't
            // depend on that parser-internal distinction.
            is UnsupportedInline ->
                withStyle(
                    style.unsupportedStyle,
                ) { appendTextWithCitations(inline.raw, style, citations) }
        }
    }

    private fun AnnotatedString.Builder.appendTextWithCitations(
        value: String,
        style: MarkdownStyle,
        citations: List<String>?,
    ) {
        if (citations == null) {
            append(value)
            return
        }
        var last = 0
        for (match in citationMarker.findAll(value)) {
            val n = match.groupValues[1].toIntOrNull()
            if (n == null || n < 1 || n > citations.size) continue
            append(value.substring(last, match.range.first))
            withAnnotation(TAG_CITATION, n.toString()) {
                withStyle(style.citationStyle) { append(match.value) }
            }
            last = match.range.last + 1
        }
        append(value.substring(last))
    }

    private fun imageAnnotation(target: ImageTarget): String =
        when (target) {
            is ImageTarget.AttachmentRef -> "attachment:${target.uuid}"
            is ImageTarget.ExternalUrl -> target.url
        }

    // ------------------------------------------------------------------
    // plainText
    // ------------------------------------------------------------------

    private fun plainTextBlock(block: BlockNode): String =
        when (block) {
            is Heading -> plainTextInlines(block.inlines)
            is Paragraph -> plainTextInlines(block.inlines)
            is CodeBlock -> block.text
            is Quote -> block.blocks.joinToString("\n") { plainTextBlock(it) }
            is BulletList -> block.items.joinToString("\n") { plainTextListItem(it) }
            is OrderedList -> block.items.joinToString("\n") { plainTextListItem(it) }
            ThematicBreak -> ""
            is UnsupportedBlock -> block.raw
        }

    private fun plainTextListItem(item: ListItem): String = item.blocks.joinToString(" ") { plainTextBlock(it) }

    private fun plainTextInlines(inlines: List<InlineNode>): String =
        buildString {
            for (inline in inlines) append(plainTextInline(inline))
        }

    private fun plainTextInline(inline: InlineNode): String =
        when (inline) {
            is Text -> inline.value
            is Emph -> plainTextInlines(inline.inlines)
            is Strong -> plainTextInlines(inline.inlines)
            is Strikethrough -> plainTextInlines(inline.inlines)
            is Code -> inline.value
            is Link -> plainTextInlines(inline.inlines)
            is WikiLink -> inline.alias ?: inline.target
            is Image -> inline.alt
            SoftBreak -> "\n"
            HardBreak -> "\n"
            is UnsupportedInline -> ""
        }
}
