package app.skein.core.markdown.ast

import kotlinx.serialization.Serializable

/**
 * Skein-owned Markdown AST. This is the *only* Markdown representation that
 * crosses `:core:markdown`'s module boundary — no third-party parser type
 * (e.g. `org.intellij.markdown.ast.ASTNode`) is ever exposed to callers.
 *
 * Deliberately unsupported in v1 (spec non-negotiables, deferred to v2):
 * tables, inline/block math (MathJax), raw HTML embeds, collapsible
 * sections. Markdown containing these constructs still parses — the
 * unsupported region is captured as [UnsupportedBlock] / [UnsupportedInline]
 * with its original source text preserved, so `plainText`/serialization
 * degrade gracefully instead of losing data.
 *
 * `@Serializable` throughout is *only* so tests can render the tree to a
 * stable JSON form for golden-fixture comparisons — it is not part of the
 * type's role as a cross-module contract.
 */
@Serializable
sealed interface SkeinNode

/** The root of a parsed document: an ordered list of top-level blocks. */
@Serializable
data class SkeinDocument(
    val blocks: List<BlockNode>,
) : SkeinNode

@Serializable
sealed interface BlockNode : SkeinNode

@Serializable
data class Heading(
    val level: Int,
    val inlines: List<InlineNode>,
) : BlockNode

@Serializable
data class Paragraph(
    val inlines: List<InlineNode>,
) : BlockNode

@Serializable
data class CodeBlock(
    val language: String?,
    val text: String,
) : BlockNode

@Serializable
data class Quote(
    val blocks: List<BlockNode>,
) : BlockNode

@Serializable
data class BulletList(
    val items: List<ListItem>,
) : BlockNode

@Serializable
data class OrderedList(
    val items: List<ListItem>,
    val startNumber: Int = 1,
) : BlockNode

@Serializable
data object ThematicBreak : BlockNode

/**
 * A block-level construct Skein v1 deliberately does not parse into a
 * first-class type (tables, block math, raw HTML blocks). [raw] is the
 * original Markdown source for the block, preserved verbatim so it survives
 * a parse -> serialize round trip unchanged.
 */
@Serializable
data class UnsupportedBlock(
    val raw: String,
) : BlockNode

/**
 * One item of a [BulletList] or [OrderedList]. [checked] is `null` for a
 * plain list item, and `true`/`false` for a GFM task-list item (`- [ ]` /
 * `- [x]`).
 */
@Serializable
data class ListItem(
    val blocks: List<BlockNode>,
    val checked: Boolean? = null,
)

@Serializable
sealed interface InlineNode : SkeinNode

@Serializable
data class Text(
    val value: String,
) : InlineNode

@Serializable
data class Emph(
    val inlines: List<InlineNode>,
) : InlineNode

@Serializable
data class Strong(
    val inlines: List<InlineNode>,
) : InlineNode

@Serializable
data class Strikethrough(
    val inlines: List<InlineNode>,
) : InlineNode

@Serializable
data class Code(
    val value: String,
) : InlineNode

@Serializable
data class Link(
    val inlines: List<InlineNode>,
    val destination: String,
    val title: String? = null,
) : InlineNode

/**
 * `[[title]]`, `[[title|alias]]`, `[[title#heading]]`, or
 * `[[title#heading|alias]]`. First-class: recognized at the same parser
 * stage as CommonMark links/emphasis (see `WikiLinkParser`), not detected by
 * a post-hoc regex pass over rendered text.
 */
@Serializable
data class WikiLink(
    val target: String,
    val alias: String? = null,
    val heading: String? = null,
) : InlineNode

@Serializable
data class Image(
    val alt: String,
    val target: ImageTarget,
    val title: String? = null,
) : InlineNode

/** Line break within a paragraph: `\n` (soft) or trailing-two-spaces/backslash `\n` (hard). */
@Serializable
data object SoftBreak : InlineNode

@Serializable
data object HardBreak : InlineNode

/** An inline-level construct Skein v1 does not parse (raw inline HTML). */
@Serializable
data class UnsupportedInline(
    val raw: String,
) : InlineNode

@Serializable
sealed interface ImageTarget {
    /** `attachment:<uuid>` — an in-vault attachment, resolved later by the editor/exporters. */
    @Serializable
    data class AttachmentRef(
        val uuid: String,
    ) : ImageTarget

    /** Any other image destination (external URL, relative path, etc). */
    @Serializable
    data class ExternalUrl(
        val url: String,
    ) : ImageTarget
}
