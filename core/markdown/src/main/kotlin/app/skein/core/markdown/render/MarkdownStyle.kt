package app.skein.core.markdown.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Visual styling for [MarkdownRenderer.toAnnotatedString]. Callers (the
 * live-preview editor for inactive lines, chat message bubbles, timeline
 * previews) each pass a [MarkdownStyle] tuned to their surface; there is no
 * hidden global theme dependency here so `:core:markdown` stays UI-framework
 * agnostic beyond `AnnotatedString`/`SpanStyle` themselves.
 *
 * [Default] deliberately holds no brand colour: it names only framework
 * constants ([Color.Gray], [Color.Blue], …), never a picked hex value, so it
 * stays a legitimate fallback for a caller with no theme to read from — a
 * unit test, or `:core:export`'s PDF renderer (which drives its own print
 * layout straight from the AST and never touches this class at all, so
 * changing these defaults cannot change a rendered PDF). The real,
 * `docs/ux/DESIGN_SYSTEM.md`-derived colours and Skein Sans/Mono type live in
 * `:core:designsystem`'s `rememberSkeinMarkdownStyle()` (skein-xtov.23.10),
 * which `:core:markdown` cannot depend on without a module cycle — see that
 * function's KDoc for the dependency-direction note.
 */
data class MarkdownStyle(
    val bodyColor: Color = Color.Unspecified,
    val mutedColor: Color = Color.Gray,
    /** Inline `` `code` `` text/background (§10.16: `codeInline` on `codeInlineContainer`). */
    val codeColor: Color = Color.Unspecified,
    val codeBackground: Color = Color.LightGray,
    /**
     * Fenced code *block* text/background — the "well" (§10.17:
     * `codeBlockContainer`). Distinct from the inline pair above so a
     * themed caller can give the block its own recessed surface.
     */
    val codeBlockColor: Color = Color.Unspecified,
    val codeBlockBackground: Color = Color.LightGray,
    val linkColor: Color = Color.Blue,
    /** Wikilinks render with "same style" as links (§10.16) — its own field for a caller that wants them distinct. */
    val wikilinkColor: Color = Color.Blue,
    val quoteColor: Color = Color.Gray,
    val citationColor: Color = Color.Unspecified,
    /** `citationContainer` (§10.18); [Color.Transparent] in the neutral default so an unthemed caller draws no box. */
    val citationBackground: Color = Color.Transparent,
    val codeFontFamily: FontFamily = FontFamily.Monospace,
    /**
     * Inline code's size relative to the surrounding text (§3.3: "0.9 em
     * because at equal size the mono's wider set looks larger than Plex
     * Sans"). [TextUnit.Unspecified] in the neutral default — a generic
     * `FontFamily.Monospace` paired with an unknown surrounding face has no
     * such mismatch to correct for, so it inherits the ambient size.
     */
    val codeInlineFontSize: TextUnit = TextUnit.Unspecified,
    /** The code block's absolute size (§3.3 `codeBlock`: 13 sp); unspecified inherits the ambient size, like inline. */
    val codeBlockFontSize: TextUnit = TextUnit.Unspecified,
    /** Base size [headingStyle] scales by [headingScale] when a call site doesn't pass its own `baseSize`. */
    val headingBaseSize: TextUnit = 16.sp,
    /**
     * Skein Sans stops at 600 — 700 would be synthesised and looks smeared
     * (§3.2). The neutral default keeps [FontWeight.Bold] since it may be
     * paired with a font family that has no 600 weight at all.
     */
    val headingWeight: FontWeight = FontWeight.Bold,
    /** Multiplier applied to the surrounding font size per heading level (1..6). */
    val headingScale: Map<Int, Float> = mapOf(1 to 1.8f, 2 to 1.5f, 3 to 1.3f, 4 to 1.15f, 5 to 1.05f, 6 to 1f),
    val bulletMarker: String = "• ",
    val checkedMarker: String = "☑ ",
    val uncheckedMarker: String = "☐ ",
    val quotePrefix: String = "▎ ",
    val thematicBreak: String = "─".repeat(24),
    val imagePlaceholderPrefix: String = "🖼 ",
) {
    fun headingStyle(
        level: Int,
        baseSize: TextUnit = headingBaseSize,
    ): SpanStyle {
        val scale = headingScale[level] ?: 1f
        return SpanStyle(color = bodyColor, fontWeight = headingWeight, fontSize = baseSize * scale)
    }

    val emphStyle: SpanStyle get() = SpanStyle(fontStyle = FontStyle.Italic)
    val strongStyle: SpanStyle get() = SpanStyle(fontWeight = FontWeight.Bold)
    val strikethroughStyle: SpanStyle get() = SpanStyle(textDecoration = TextDecoration.LineThrough)

    /** Inline `` `code` `` span (§10.16). */
    val codeStyle: SpanStyle get() =
        SpanStyle(
            color = codeColor,
            background = codeBackground,
            fontFamily = codeFontFamily,
            fontSize = codeInlineFontSize,
        )

    /** Fenced code block span — the whole block shares one background (§10.17), never per-line. */
    val codeBlockStyle: SpanStyle get() =
        SpanStyle(
            color = codeBlockColor,
            background = codeBlockBackground,
            fontFamily = codeFontFamily,
            fontSize = codeBlockFontSize,
        )
    val linkStyle: SpanStyle get() = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    val wikilinkStyle: SpanStyle get() = SpanStyle(color = wikilinkColor, textDecoration = TextDecoration.Underline)

    /** §10.16: "not italic" — a quote is distinguished by its bar and muted colour alone. */
    val quoteStyle: SpanStyle get() = SpanStyle(color = quoteColor)

    /**
     * §10.18: sized in `em` off the surrounding text, never shrunk below it
     * (the earlier 0.75 em clipped a tall digit against its own box), so it
     * scales with font size and can never clip. The neutral default's
     * [citationBackground] is transparent, so there is no box to clip
     * against there in the first place.
     */
    val citationStyle: SpanStyle get() =
        SpanStyle(color = citationColor, background = citationBackground, fontSize = 1.em, fontWeight = FontWeight.W600)
    val unsupportedStyle: SpanStyle get() = SpanStyle(color = mutedColor, fontFamily = codeFontFamily)
    val imagePlaceholderStyle: SpanStyle get() = SpanStyle(color = mutedColor, fontStyle = FontStyle.Italic)

    companion object {
        val Default = MarkdownStyle()
    }
}
