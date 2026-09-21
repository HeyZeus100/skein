// E2.I11 (bd skein-80m): flattens the `:core:markdown` AST (`SkeinDocument`,
// `E7.I2`) into the linear `PrintBlock` model `Paginator` (`:core:export`)
// consumes. Kept separate from `app.skein.core.markdown.render.MarkdownRenderer`
// (which targets Compose `AnnotatedString` for on-screen surfaces) because
// the PDF/DOCX paths need per-block granularity (a decision point per
// heading/paragraph/code block/list item) rather than one flat run of
// styled text for a whole document.
//
// E2.I12 (bd skein-jq8): moved into `:core:markdown` alongside `PrintBlock`
// (see that file's header) so `DocxWriter` (`:core:vault`) can reuse this
// same flattening step instead of re-walking the AST. Deliberately
// Android-free — see the header comment on `PrintBlock.kt`.

package app.skein.core.markdown.layout

import app.skein.core.markdown.ast.BlockNode
import app.skein.core.markdown.ast.BulletList
import app.skein.core.markdown.ast.Code
import app.skein.core.markdown.ast.CodeBlock
import app.skein.core.markdown.ast.Emph
import app.skein.core.markdown.ast.HardBreak
import app.skein.core.markdown.ast.Heading
import app.skein.core.markdown.ast.Image
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
 * Flattens a [SkeinDocument] into an ordered [PrintBlock] list. Block
 * quotes and list items (which nest [BlockNode]s in the AST) are joined
 * into a single [StyledText] per [PrintBlock.Quote]/[PrintBlock.ListEntry],
 * with a blank-line separator between nested blocks — matching how
 * [app.skein.core.markdown.render.MarkdownRenderer] joins top-level blocks.
 */
public object MarkdownFlattener {
    private const val BULLET_MARKER = "• " // "• "
    private const val CHECKED_MARKER = "☑ " // "☑ "
    private const val UNCHECKED_MARKER = "☐ " // "☐ "
    private const val IMAGE_PLACEHOLDER_PREFIX = "[image] "

    public fun flatten(document: SkeinDocument): List<PrintBlock> =
        document.blocks.flatMap { flattenBlock(it, depth = 0) }

    private fun flattenBlock(
        block: BlockNode,
        depth: Int,
    ): List<PrintBlock> =
        when (block) {
            is Heading -> listOf(PrintBlock.Heading(block.level, flattenInlines(block.inlines)))
            is Paragraph -> listOf(PrintBlock.Paragraph(flattenInlines(block.inlines)))
            is CodeBlock -> listOf(PrintBlock.Code(block.language, block.text.split("\n")))
            is Quote -> listOf(PrintBlock.Quote(joinBlocks(block.blocks)))
            is BulletList -> block.items.map { listEntry(it, BULLET_MARKER, depth) }
            is OrderedList -> {
                var number = block.startNumber
                block.items.map { listEntry(it, "${number++}. ", depth) }
            }
            ThematicBreak -> listOf(PrintBlock.ThematicBreak)
            is UnsupportedBlock -> listOf(PrintBlock.Paragraph(StyledText.of(block.raw)))
        }

    private fun listEntry(
        item: ListItem,
        defaultMarker: String,
        depth: Int,
    ): PrintBlock.ListEntry {
        val marker =
            when (item.checked) {
                true -> CHECKED_MARKER
                false -> UNCHECKED_MARKER
                null -> defaultMarker
            }
        return PrintBlock.ListEntry(marker, joinBlocks(item.blocks), depth)
    }

    /** Joins nested blocks (quote body, list item body) into one [StyledText], blank-line separated — mirrors `MarkdownRenderer`'s top-level block join. */
    private fun joinBlocks(blocks: List<BlockNode>): StyledText {
        val spans = mutableListOf<TextSpan>()
        blocks.forEachIndexed { index, child ->
            if (index > 0) spans += TextSpan("\n\n")
            spans += textOnly(child).spans
        }
        return StyledText(spans)
    }

    /** Reduces a nested block to plain styled inline text (used only for quote/list-item bodies, which in practice are paragraphs/other quotes/lists — never headings/code inside Skein v1 Markdown). */
    private fun textOnly(block: BlockNode): StyledText =
        when (block) {
            is Heading -> flattenInlines(block.inlines)
            is Paragraph -> flattenInlines(block.inlines)
            is CodeBlock -> StyledText(listOf(TextSpan(block.text, monospace = true)))
            is Quote -> joinBlocks(block.blocks)
            is BulletList -> StyledText(block.items.flatMap { joinBlocks(it.blocks).spans + TextSpan("\n") })
            is OrderedList -> StyledText(block.items.flatMap { joinBlocks(it.blocks).spans + TextSpan("\n") })
            ThematicBreak -> StyledText.of("")
            is UnsupportedBlock -> StyledText.of(block.raw)
        }

    private fun flattenInlines(inlines: List<InlineNode>): StyledText {
        val spans = mutableListOf<TextSpan>()
        for (inline in inlines) spans += flattenInline(inline)
        return StyledText(spans)
    }

    private fun flattenInline(
        inline: InlineNode,
        bold: Boolean = false,
        italic: Boolean = false,
        strikethrough: Boolean = false,
    ): List<TextSpan> =
        when (inline) {
            is Text -> listOf(TextSpan(inline.value, bold = bold, italic = italic, strikethrough = strikethrough))
            is Emph -> inline.inlines.flatMap { flattenInline(it, bold, italic = true, strikethrough) }
            is Strong -> inline.inlines.flatMap { flattenInline(it, bold = true, italic, strikethrough) }
            is Strikethrough -> inline.inlines.flatMap { flattenInline(it, bold, italic, strikethrough = true) }
            is Code -> listOf(TextSpan(inline.value, bold = bold, italic = italic, monospace = true))
            is Link ->
                inline.inlines
                    .flatMap { flattenInline(it, bold, italic, strikethrough) }
                    .map { it.copy(underline = true, href = inline.destination) }
                    .ifEmpty { listOf(TextSpan(inline.destination, underline = true, href = inline.destination)) }
            is WikiLink ->
                listOf(
                    TextSpan(inline.alias ?: inline.target, bold = bold, italic = italic, underline = true),
                )
            is Image -> listOf(TextSpan("$IMAGE_PLACEHOLDER_PREFIX${inline.alt.ifEmpty { "image" }}", italic = true))
            SoftBreak -> listOf(TextSpan(" "))
            HardBreak -> listOf(TextSpan("\n"))
            is UnsupportedInline ->
                listOf(
                    TextSpan(inline.raw, bold = bold, italic = italic, strikethrough = strikethrough),
                )
        }
}
