// E2.I11 (bd skein-80m): the pure-Kotlin block model `MarkdownFlattener`
// produces from the `:core:markdown` render tree (`SkeinDocument`) and that
// `Paginator`/`BlockMeasurer` operate on. Deliberately Android-free (no
// `android.text`/`android.graphics` import anywhere in this file or in
// `MarkdownFlattener.kt`/`BlockMeasurer.kt`/`Paginator.kt`) so the
// pagination logic is unit-testable on the plain JVM with a fake measurer,
// per the task brief's "Keep the Android-free layout logic separable and
// unit-testable from the PrintDocumentAdapter shell." `AndroidBlockMeasurer`
// and `PagePainter` (same module, different package-adjacent files) are the
// only places that translate this model into real `StaticLayout`/`Canvas`
// calls.

package app.skein.core.export.pdf.layout

/** One inline run of text plus the subset of emphasis it carries. Multiple emphases can combine (e.g. bold *and* a link). */
public data class TextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val monospace: Boolean = false,
    /** Set for `Link`/`WikiLink` inlines — plan `E2.I11`: "wikilinks (rendered as plain underlined text)". */
    val underline: Boolean = false,
)

/** A run of [TextSpan]s making up one block's inline content. */
public data class StyledText(
    val spans: List<TextSpan>,
) {
    /** Concatenated text across every span, with no styling — used by measurers that only need character counts/wrapping, and by tests. */
    val plain: String get() = spans.joinToString("") { it.text }

    public companion object {
        public val Empty: StyledText = StyledText(emptyList())

        public fun of(text: String): StyledText = StyledText(listOf(TextSpan(text)))
    }
}

/**
 * A single top-level unit the [Paginator] places on pages. Produced by
 * [MarkdownFlattener] from the `:core:markdown` AST (`SkeinDocument`).
 * Nested block structures (block quotes, list items containing multiple
 * paragraphs, nested lists) are flattened into one [StyledText] per
 * [PrintBlock] — the print layout does not need to preserve the AST's
 * recursive shape, only its reading order and per-block styling.
 */
public sealed interface PrintBlock {
    public data class Heading(
        val level: Int,
        val text: StyledText,
    ) : PrintBlock

    public data class Paragraph(
        val text: StyledText,
    ) : PrintBlock

    /**
     * [lines] preserves the code block's original line breaks (unlike
     * [Paragraph]/[Heading], which reflow) so [Paginator] can cut a
     * too-tall code block at a *source* line boundary, matching the plan's
     * "splitting long blocks by line" for code.
     */
    public data class Code(
        val language: String?,
        val lines: List<String>,
    ) : PrintBlock

    public data class Quote(
        val text: StyledText,
    ) : PrintBlock

    /** One list item (bullet, ordered, or task). [marker] is pre-rendered ("• ", "3. ", "☑ ", "☐ "). [depth] is the nesting level (0-based), used to indent. */
    public data class ListEntry(
        val marker: String,
        val text: StyledText,
        val depth: Int,
    ) : PrintBlock

    public data object ThematicBreak : PrintBlock
}
