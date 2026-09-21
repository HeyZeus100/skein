// E2.I11 (bd skein-80m): the pure-Kotlin block model `MarkdownFlattener`
// produces from the `:core:markdown` render tree (`SkeinDocument`) and that
// `Paginator`/`BlockMeasurer` operate on. Deliberately Android-free (no
// `android.text`/`android.graphics` import anywhere in this file or in
// `MarkdownFlattener.kt`) so the pagination logic is unit-testable on the
// plain JVM with a fake measurer, per the task brief's "Keep the
// Android-free layout logic separable and unit-testable from the
// PrintDocumentAdapter shell." `AndroidBlockMeasurer`/`PagePainter`
// (`:core:export`) and `Paginator`/`BlockMeasurer` (same module, still
// `app.skein.core.export.pdf.layout`) translate this model into real
// `StaticLayout`/`Canvas` calls or page-fitting decisions.
//
// E2.I12 (bd skein-jq8): moved here from `:core:export`'s
// `app.skein.core.export.pdf.layout` package so it can be a *shared*
// render-tree flattening step for both PDF (`:core:export`, which still
// depends on `:core:markdown`) and the DOCX writer (`:core:vault`'s
// `app.skein.core.vault.export.docx`, `E2.I12`) without introducing a
// `:core:vault` <-> `:core:export` module cycle (`:core:export` already
// depends on `:core:vault` for `SafeFileName`). `:core:markdown` is already
// documented as "shared by ... the PDF/DOCX exporters" (see its
// `build.gradle.kts`), so this is the natural home. [TextSpan.href] is new
// (E2.I12): DOCX renders real `Link` inlines as `w:hyperlink` elements,
// which needs the destination the PDF path never had to keep around
// (PDF/`BlockLineLayout` renders links as plain underlined text and ignores
// it).

package app.skein.core.markdown.layout

/** One inline run of text plus the subset of emphasis it carries. Multiple emphases can combine (e.g. bold *and* a link). */
public data class TextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val monospace: Boolean = false,
    /** Set for `Link`/`WikiLink` inlines — plan `E2.I11`: "wikilinks (rendered as plain underlined text)". */
    val underline: Boolean = false,
    /**
     * The `Link` inline's destination URL, or `null` for everything else
     * (including `WikiLink`, which the plan keeps as plain text even in
     * DOCX — `E2.I12`). PDF (`BlockLineLayout`) ignores this field; DOCX's
     * `DocxWriter` uses it to emit a real `w:hyperlink` relationship instead
     * of plain underlined text.
     */
    val href: String? = null,
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
