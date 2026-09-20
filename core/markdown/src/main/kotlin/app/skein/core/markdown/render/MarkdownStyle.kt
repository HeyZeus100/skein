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
 */
data class MarkdownStyle(
    val bodyColor: Color = Color.Unspecified,
    val mutedColor: Color = Color(0xFF8A8A8A),
    val codeColor: Color = Color(0xFFD7BA7D),
    val codeBackground: Color = Color(0xFF2B2B2B),
    val linkColor: Color = Color(0xFF6AB0F3),
    val wikilinkColor: Color = Color(0xFF7FD1B9),
    val quoteColor: Color = Color(0xFFB0B0B0),
    val citationColor: Color = Color(0xFF6AB0F3),
    val codeFontFamily: FontFamily = FontFamily.Monospace,
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
        baseSize: TextUnit = 16.sp,
    ): SpanStyle {
        val scale = headingScale[level] ?: 1f
        return SpanStyle(color = bodyColor, fontWeight = FontWeight.Bold, fontSize = baseSize * scale)
    }

    val emphStyle: SpanStyle get() = SpanStyle(fontStyle = FontStyle.Italic)
    val strongStyle: SpanStyle get() = SpanStyle(fontWeight = FontWeight.Bold)
    val strikethroughStyle: SpanStyle get() = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val codeStyle: SpanStyle get() =
        SpanStyle(
            color = codeColor,
            background = codeBackground,
            fontFamily = codeFontFamily,
        )
    val linkStyle: SpanStyle get() = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    val wikilinkStyle: SpanStyle get() = SpanStyle(color = wikilinkColor, textDecoration = TextDecoration.Underline)
    val quoteStyle: SpanStyle get() = SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic)
    val citationStyle: SpanStyle get() = SpanStyle(color = citationColor, fontSize = 0.75.em)
    val unsupportedStyle: SpanStyle get() = SpanStyle(color = mutedColor, fontFamily = codeFontFamily)
    val imagePlaceholderStyle: SpanStyle get() = SpanStyle(color = mutedColor, fontStyle = FontStyle.Italic)

    companion object {
        val Default = MarkdownStyle()
    }
}
