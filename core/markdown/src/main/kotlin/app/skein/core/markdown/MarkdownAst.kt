package app.skein.core.markdown

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
import app.skein.core.markdown.parser.SkeinFlavourDescriptor
import app.skein.core.markdown.parser.WikiLinkElementType
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * Entry point for `:core:markdown`: parses raw Markdown into the Skein AST
 * ([SkeinDocument]) and serializes it back to Markdown text. This is the
 * only class callers outside this module need to know about for the parse
 * side; see `MarkdownRenderer` for the `AnnotatedString` render side.
 *
 * Parser: `org.jetbrains:markdown` 0.7.14 (Apache-2.0) with the GFM flavour
 * plus a first-class `[[wikilink]]` extension (see [SkeinFlavourDescriptor]
 * / `WikiLinkParser`). No third-party AST type escapes this file.
 */
object MarkdownAst {
    private const val ATTACHMENT_PREFIX = "attachment:"

    private val parser = MarkdownParser(SkeinFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)

    fun parse(source: String): SkeinDocument {
        val root = parser.buildMarkdownTreeFromString(source as CharSequence)
        return SkeinDocument(convertBlocks(root.children, source))
    }

    fun serialize(document: SkeinDocument): String = document.blocks.joinToString("\n\n") { serializeBlock(it) }

    // ------------------------------------------------------------------
    // Parsing: org.intellij.markdown.ast.ASTNode -> Skein AST
    // ------------------------------------------------------------------

    /**
     * `>` continuation markers (and, for lazily-continued lines, the single
     * space after a bare `>`) can appear interleaved anywhere in a block
     * quote's descendant tree — as direct children of the `BLOCK_QUOTE`
     * composite for the first line, or spliced into a nested paragraph's
     * inline children for lazily-continued lines. Filtering them out here,
     * before either conversion pass, means [Quote] conversion needs no
     * special-casing and produces clean content regardless of which
     * tokenization shape the source line used — which keeps
     * parse -> serialize -> parse idempotent for multi-line quotes.
     */
    private fun filterQuoteNoise(children: List<ASTNode>): List<ASTNode> {
        var skipNextWhitespace = false
        val out = ArrayList<ASTNode>(children.size)
        for (child in children) {
            if (child.type == MarkdownTokenTypes.BLOCK_QUOTE) {
                skipNextWhitespace = true
                continue
            }
            if (skipNextWhitespace) {
                skipNextWhitespace = false
                if (child.type == MarkdownTokenTypes.WHITE_SPACE) continue
            }
            out += child
        }
        return out
    }

    private fun convertBlocks(
        children: List<ASTNode>,
        text: String,
    ): List<BlockNode> = filterQuoteNoise(children).mapNotNull { convertBlock(it, text) }

    private fun convertBlock(
        node: ASTNode,
        text: String,
    ): BlockNode? =
        when (node.type) {
            MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> null
            MarkdownElementTypes.ATX_1 -> Heading(1, headingInlines(node, text))
            MarkdownElementTypes.ATX_2 -> Heading(2, headingInlines(node, text))
            MarkdownElementTypes.ATX_3 -> Heading(3, headingInlines(node, text))
            MarkdownElementTypes.ATX_4 -> Heading(4, headingInlines(node, text))
            MarkdownElementTypes.ATX_5 -> Heading(5, headingInlines(node, text))
            MarkdownElementTypes.ATX_6 -> Heading(6, headingInlines(node, text))
            MarkdownElementTypes.SETEXT_1 -> Heading(1, setextInlines(node, text))
            MarkdownElementTypes.SETEXT_2 -> Heading(2, setextInlines(node, text))
            MarkdownElementTypes.PARAGRAPH -> Paragraph(convertInlines(node.children, text))
            MarkdownElementTypes.CODE_FENCE -> codeFence(node, text)
            MarkdownElementTypes.CODE_BLOCK -> indentedCodeBlock(node, text)
            MarkdownElementTypes.BLOCK_QUOTE -> Quote(convertBlocks(node.children, text))
            MarkdownElementTypes.UNORDERED_LIST ->
                BulletList(
                    node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.map { listItem(it, text) },
                )
            MarkdownElementTypes.ORDERED_LIST ->
                OrderedList(
                    items =
                        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.map {
                            listItem(
                                it,
                                text,
                            )
                        },
                    startNumber = orderedListStart(node, text),
                )
            MarkdownTokenTypes.HORIZONTAL_RULE -> ThematicBreak
            // Deliberately unsupported in v1 (guardrails: tables/HTML embed are
            // v2). The block-level GFM grammar still recognizes them (it can't
            // be disabled independently of the rest of GFM), so preserve the
            // original source rather than silently dropping content.
            GFMElementTypes.TABLE, MarkdownElementTypes.HTML_BLOCK ->
                UnsupportedBlock(
                    node.getTextInNode(text).toString(),
                )
            else -> UnsupportedBlock(node.getTextInNode(text).toString())
        }

    private fun headingInlines(
        node: ASTNode,
        text: String,
    ): List<InlineNode> {
        val content = node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT) ?: return emptyList()
        return convertInlines(trimEdgeWhitespace(content.children), text)
    }

    private fun setextInlines(
        node: ASTNode,
        text: String,
    ): List<InlineNode> {
        val content = node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT) ?: return emptyList()
        return convertInlines(trimEdgeWhitespace(content.children), text)
    }

    private fun trimEdgeWhitespace(children: List<ASTNode>): List<ASTNode> {
        var start = 0
        var end = children.size
        while (start < end && isEdgeWhitespace(children[start])) start++
        while (end > start && isEdgeWhitespace(children[end - 1])) end--
        return children.subList(start, end)
    }

    private fun isEdgeWhitespace(node: ASTNode) =
        node.type == MarkdownTokenTypes.WHITE_SPACE || node.type == MarkdownTokenTypes.EOL

    private fun codeFence(
        node: ASTNode,
        text: String,
    ): CodeBlock {
        val lang =
            node
                .findChildOfType(MarkdownTokenTypes.FENCE_LANG)
                ?.getTextInNode(text)
                ?.toString()
                ?.trim()
                ?.ifEmpty { null }
        val startMarker = node.findChildOfType(MarkdownTokenTypes.CODE_FENCE_START)
        val langNode = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        val endMarker = node.findChildOfType(MarkdownTokenTypes.CODE_FENCE_END)

        var contentStart = (langNode ?: startMarker)?.endOffset ?: node.startOffset
        if (contentStart < text.length && text[contentStart] == '\r') contentStart++
        if (contentStart < text.length && text[contentStart] == '\n') contentStart++

        var contentEnd = endMarker?.startOffset ?: node.endOffset
        if (contentEnd > contentStart && text[contentEnd - 1] == '\n') {
            contentEnd--
            if (contentEnd > contentStart && text[contentEnd - 1] == '\r') contentEnd--
        }

        val body = if (contentStart <= contentEnd) text.substring(contentStart, contentEnd) else ""
        return CodeBlock(language = lang, text = body)
    }

    private fun indentedCodeBlock(
        node: ASTNode,
        text: String,
    ): CodeBlock {
        val lines =
            node.children
                .filter { it.type == MarkdownTokenTypes.CODE_LINE }
                .map { trimLeadingIndent(it.getTextInNode(text).toString()) }
        return CodeBlock(language = null, text = lines.joinToString("\n"))
    }

    private fun trimLeadingIndent(line: String): String {
        var i = 0
        var col = 0
        while (i < line.length && col < 4) {
            when (line[i]) {
                ' ' -> {
                    col++
                    i++
                }
                '\t' -> {
                    col += 4
                    i++
                }
                else -> return line.substring(i)
            }
        }
        return line.substring(i)
    }

    private fun listItem(
        node: ASTNode,
        text: String,
    ): ListItem {
        val checkBox = node.findChildOfType(GFMTokenTypes.CHECK_BOX)
        val checked = checkBox?.getTextInNode(text)?.let { box -> box.contains('x') || box.contains('X') }
        val contentChildren =
            node.children.filter {
                it.type != MarkdownTokenTypes.LIST_BULLET &&
                    it.type != MarkdownTokenTypes.LIST_NUMBER &&
                    it.type != GFMTokenTypes.CHECK_BOX
            }
        return ListItem(blocks = convertBlocks(contentChildren, text), checked = checked)
    }

    private fun orderedListStart(
        node: ASTNode,
        text: String,
    ): Int {
        val firstItem = node.children.firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM } ?: return 1
        val numberText =
            firstItem.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(text)?.toString()
                ?: return 1
        return numberText.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
    }

    private fun convertInlines(
        children: List<ASTNode>,
        text: String,
    ): List<InlineNode> = filterQuoteNoise(children).mapNotNull { convertInline(it, text) }

    private fun convertInline(
        node: ASTNode,
        text: String,
    ): InlineNode? =
        when (node.type) {
            MarkdownTokenTypes.EOL -> SoftBreak
            MarkdownTokenTypes.HARD_LINE_BREAK -> HardBreak
            WikiLinkElementType -> wikiLink(node, text)
            MarkdownElementTypes.EMPH -> Emph(convertInlines(stripMarker(node.children, MarkdownTokenTypes.EMPH), text))
            MarkdownElementTypes.STRONG ->
                Strong(
                    convertInlines(stripMarker(node.children, MarkdownTokenTypes.EMPH), text),
                )
            GFMElementTypes.STRIKETHROUGH ->
                Strikethrough(convertInlines(stripMarker(node.children, GFMTokenTypes.TILDE), text))
            MarkdownElementTypes.CODE_SPAN -> Code(codeSpanText(node, text))
            MarkdownElementTypes.AUTOLINK -> {
                val content = node.getTextInNode(text).toString().removeSurrounding("<", ">")
                Link(inlines = listOf(Text(content)), destination = content)
            }
            GFMTokenTypes.GFM_AUTOLINK -> {
                val raw = node.getTextInNode(text).toString()
                Link(inlines = listOf(Text(raw)), destination = raw)
            }
            MarkdownElementTypes.IMAGE -> image(node, text)
            MarkdownElementTypes.INLINE_LINK -> inlineLink(node, text)
            // Reference-style links (`[text][ref]`, shortcut `[text]`) need a
            // document-wide link-definition table this module does not resolve
            // in v1; preserve the source rather than guessing a destination.
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            -> UnsupportedInline(node.getTextInNode(text).toString())
            MarkdownTokenTypes.HTML_TAG -> UnsupportedInline(node.getTextInNode(text).toString())
            else -> if (node.children.isEmpty()) Text(node.getTextInNode(text).toString()) else null
        }

    private fun stripMarker(
        children: List<ASTNode>,
        marker: IElementType,
    ): List<ASTNode> = children.filter { it.type != marker }

    private fun codeSpanText(
        node: ASTNode,
        text: String,
    ): String {
        val raw = node.getTextInNode(text)
        val tickLen = raw.takeWhile { it == '`' }.length
        if (tickLen == 0 || raw.length < tickLen * 2) return raw.toString()
        val inner = raw.substring(tickLen, raw.length - tickLen)
        return if (inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.any { it != ' ' }) {
            inner.substring(1, inner.length - 1)
        } else {
            inner
        }
    }

    private fun inlineLink(
        node: ASTNode,
        text: String,
    ): Link {
        val linkTextNode = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
        val inner =
            linkTextNode
                ?.children
                ?.filter {
                    it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET
                }.orEmpty()
        val inlines = convertInlines(inner, text)
        val destination =
            node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.let { destinationText(it, text) }
                ?: ""
        val title = node.findChildOfType(MarkdownElementTypes.LINK_TITLE)?.let { titleText(it, text) }
        return Link(inlines, destination, title)
    }

    private fun image(
        node: ASTNode,
        text: String,
    ): Image {
        val inner = node.findChildOfType(MarkdownElementTypes.INLINE_LINK)
        val altNode = inner?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
        val alt = altNode?.getTextInNode(text)?.toString()?.removeSurrounding("[", "]") ?: ""
        val destination =
            inner?.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.let { destinationText(it, text) }
                ?: ""
        val title = inner?.findChildOfType(MarkdownElementTypes.LINK_TITLE)?.let { titleText(it, text) }
        return Image(alt = alt, target = imageTarget(destination), title = title)
    }

    private fun imageTarget(destination: String): ImageTarget =
        if (destination.startsWith(ATTACHMENT_PREFIX)) {
            ImageTarget.AttachmentRef(destination.removePrefix(ATTACHMENT_PREFIX))
        } else {
            ImageTarget.ExternalUrl(destination)
        }

    private fun destinationText(
        node: ASTNode,
        text: String,
    ): String {
        val raw = node.getTextInNode(text).toString()
        return if (raw.length >= 2 &&
            raw.startsWith("<") &&
            raw.endsWith(">")
        ) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun titleText(
        node: ASTNode,
        text: String,
    ): String {
        val raw = node.getTextInNode(text).toString()
        return if (raw.length >= 2) raw.substring(1, raw.length - 1) else raw
    }

    private fun wikiLink(
        node: ASTNode,
        text: String,
    ): InlineNode {
        val raw = node.getTextInNode(text).toString()
        val inner = raw.removePrefix("[[").removeSuffix("]]")
        val pipeIdx = inner.indexOf('|')
        val body = if (pipeIdx >= 0) inner.substring(0, pipeIdx) else inner
        val alias = if (pipeIdx >= 0) inner.substring(pipeIdx + 1).trim().ifEmpty { null } else null
        val hashIdx = body.indexOf('#')
        val target = (if (hashIdx >= 0) body.substring(0, hashIdx) else body).trim()
        val heading = if (hashIdx >= 0) body.substring(hashIdx + 1).trim().ifEmpty { null } else null
        if (target.isEmpty()) {
            return Text(raw)
        }
        return WikiLink(target = target, alias = alias, heading = heading)
    }

    // ------------------------------------------------------------------
    // Serialization: Skein AST -> Markdown text
    // ------------------------------------------------------------------

    private fun serializeBlock(block: BlockNode): String =
        when (block) {
            is Heading -> "#".repeat(block.level) + " " + serializeInlines(block.inlines)
            is Paragraph -> serializeInlines(block.inlines)
            is CodeBlock -> {
                val runLen = Regex("`+").findAll(block.text).maxOfOrNull { it.value.length } ?: 0
                val fence = "`".repeat(maxOf(3, runLen + 1))
                fence + (block.language ?: "") + "\n" + block.text + "\n" + fence
            }
            is Quote -> prefixLines(block.blocks.joinToString("\n\n") { serializeBlock(it) }, "> ")
            is BulletList -> block.items.joinToString("\n") { serializeListItem(it, marker = "- ") }
            is OrderedList ->
                block.items
                    .mapIndexed { i, item ->
                        serializeListItem(item, marker = "${block.startNumber + i}. ")
                    }.joinToString("\n")
            ThematicBreak -> "---"
            is UnsupportedBlock -> block.raw
        }

    private fun serializeListItem(
        item: ListItem,
        marker: String,
    ): String {
        val checkPrefix =
            when (item.checked) {
                true -> "[x] "
                false -> "[ ] "
                null -> ""
            }
        val body = item.blocks.joinToString("\n\n") { serializeBlock(it) }
        // The checkbox is inline content, not a block-structure indent
        // contributor — continuation/nested-block lines only need to reach
        // the list marker's own content column (matching how the parser
        // itself treats `- [ ] text`: over-indenting past the marker width
        // reads as an indented code block instead of list continuation).
        val indent = " ".repeat(marker.length)
        return marker + checkPrefix + prefixContinuationLines(body, indent)
    }

    private fun prefixLines(
        text: String,
        prefix: String,
    ): String = text.lines().joinToString("\n") { line -> if (line.isEmpty()) prefix.trimEnd() else prefix + line }

    private fun prefixContinuationLines(
        text: String,
        indent: String,
    ): String {
        val lines = text.lines()
        if (lines.size <= 1) return text
        return lines.first() + "\n" +
            lines.drop(1).joinToString("\n") { line -> if (line.isEmpty()) line else indent + line }
    }

    private fun serializeInlines(inlines: List<InlineNode>): String =
        buildString {
            for (inline in inlines) append(serializeInline(inline))
        }

    private fun serializeInline(inline: InlineNode): String =
        when (inline) {
            is Text -> inline.value
            is Emph -> "*" + serializeInlines(inline.inlines) + "*"
            is Strong -> "**" + serializeInlines(inline.inlines) + "**"
            is Strikethrough -> "~~" + serializeInlines(inline.inlines) + "~~"
            is Code -> serializeCode(inline.value)
            is Link -> serializeLink(inline)
            is WikiLink ->
                buildString {
                    append("[[")
                    append(inline.target)
                    inline.heading?.let {
                        append('#')
                        append(it)
                    }
                    inline.alias?.let {
                        append('|')
                        append(it)
                    }
                    append("]]")
                }
            is Image ->
                "![" + inline.alt + "](" + serializeImageTarget(inline.target) + serializeTitle(inline.title) + ")"
            SoftBreak -> "\n"
            // The parser tokenizes a hard break as trailing spaces (`BR`) followed
            // by its own EOL token, which becomes a separate SoftBreak node right
            // after this one — so this must not also emit "\n", or two adjacent
            // newlines would read back as a blank line (a new paragraph) instead
            // of one hard-broken line.
            HardBreak -> "  "
            is UnsupportedInline -> inline.raw
        }

    /**
     * `<url>` autolinks and an explicit `[url](url)` inline link parse to the
     * identical [Link] shape (a single [Text] child equal to the
     * destination, no title) — GFM's own autolink detection can't be told
     * apart from a deliberately explicit link at the AST level. Always
     * re-emitting the bracket form for that shape is unstable: reparsing
     * `[https://x](https://x)` lets GFM's bare-URL autolink extension fire a
     * *second* time inside the link text, nesting an extra [Link]. Emitting
     * the compact `<url>` form for this exact shape sidesteps that and is
     * the more idiomatic serialization anyway.
     */
    private fun serializeLink(link: Link): String {
        val onlyChild = link.inlines.singleOrNull()
        if (link.title == null && onlyChild is Text && onlyChild.value == link.destination) {
            return "<${link.destination}>"
        }
        return "[" + serializeInlines(link.inlines) + "](" + link.destination + serializeTitle(link.title) + ")"
    }

    private fun serializeTitle(title: String?): String = if (title == null) "" else " \"$title\""

    private fun serializeCode(value: String): String {
        val runLen = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(runLen + 1)
        val needsPad = value.isEmpty() || value.startsWith("`") || value.endsWith("`")
        return if (needsPad) "$fence $value $fence" else "$fence$value$fence"
    }

    private fun serializeImageTarget(target: ImageTarget): String =
        when (target) {
            is ImageTarget.AttachmentRef -> "$ATTACHMENT_PREFIX${target.uuid}"
            is ImageTarget.ExternalUrl -> target.url
        }
}
