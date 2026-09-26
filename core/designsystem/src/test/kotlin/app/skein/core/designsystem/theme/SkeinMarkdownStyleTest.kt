package app.skein.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.skein.core.markdown.render.MarkdownStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * skein-xtov.23.10 (DS10, docs/ux/DESIGN_SYSTEM.md §10.16-§10.18): every
 * [MarkdownStyle] field [skeinMarkdownStyle] sets must trace back to a token
 * — [SkeinColors]/[SkeinExtendedColors] (colour), [SkeinTypography]/
 * [SkeinMonoTypography] (type) — in both themes, never a literal repeated
 * here. [skeinMarkdownStyle] is the plain function behind
 * [rememberSkeinMarkdownStyle], so this exercises it with no composition
 * needed, the same way `SkeinColorContrastTest` drives [SkeinColors] directly.
 */
class SkeinMarkdownStyleTest {
    private val dark =
        skeinMarkdownStyle(SkeinColors.dark, SkeinColors.darkExtended, SkeinTypography, SkeinMonoTypography)
    private val light =
        skeinMarkdownStyle(SkeinColors.light, SkeinColors.lightExtended, SkeinTypography, SkeinMonoTypography)

    @Test
    fun `body and muted colour come from the color scheme, in both themes`() {
        assertEquals(SkeinColors.dark.onSurface, dark.bodyColor)
        assertEquals(SkeinColors.dark.onSurfaceVariant, dark.mutedColor)
        assertEquals(SkeinColors.light.onSurface, light.bodyColor)
        assertEquals(SkeinColors.light.onSurfaceVariant, light.mutedColor)
    }

    @Test
    fun `inline code and code block pairs come from the distinct extended tokens`() {
        assertEquals(SkeinColors.darkExtended.onCodeInline, dark.codeColor)
        assertEquals(SkeinColors.darkExtended.codeInlineContainer, dark.codeBackground)
        assertEquals(SkeinColors.darkExtended.onCodeBlock, dark.codeBlockColor)
        assertEquals(SkeinColors.darkExtended.codeBlockContainer, dark.codeBlockBackground)
        // §10.17's "well" is not the same surface as inline code's chip.
        assertNotEquals(dark.codeBackground, dark.codeBlockBackground)

        assertEquals(SkeinColors.lightExtended.onCodeBlock, light.codeBlockColor)
        assertEquals(SkeinColors.lightExtended.codeBlockContainer, light.codeBlockBackground)
    }

    @Test
    fun `links and wikilinks both use the link token — same style, per spec`() {
        assertEquals(SkeinColors.darkExtended.link, dark.linkColor)
        assertEquals(SkeinColors.darkExtended.link, dark.wikilinkColor)
        assertEquals(SkeinColors.lightExtended.link, light.linkColor)
        assertEquals(light.linkColor, light.wikilinkColor)
    }

    @Test
    fun `citation marker is em-sized, on the citation container, and never shrunk`() {
        assertEquals(SkeinColors.darkExtended.onCitation, dark.citationColor)
        assertEquals(SkeinColors.darkExtended.citationContainer, dark.citationBackground)
        assertEquals(1.em, dark.citationStyle.fontSize)
    }

    @Test
    fun `code stays in Skein Mono, sized per §3-3 in both themes`() {
        assertEquals(SkeinMono, dark.codeFontFamily)
        assertEquals(SkeinMonoTypography.codeInline.fontSize, dark.codeInlineFontSize)
        assertEquals(0.9.em, dark.codeInlineFontSize)
        assertEquals(SkeinMonoTypography.codeBlock.fontSize, dark.codeBlockFontSize)
        assertEquals(13.sp, dark.codeBlockFontSize)
        assertEquals(SkeinMono, light.codeFontFamily)
    }

    @Test
    fun `headings scale off bodyLarge at weight 600, not a synthesised bold`() {
        assertEquals(SkeinTypography.bodyLarge.fontSize, dark.headingBaseSize)
        assertEquals(16.sp, dark.headingBaseSize)
        assertEquals(FontWeight.W600, dark.headingWeight)
        assertEquals(SkeinTypography.bodyLarge.fontSize, light.headingBaseSize)
    }

    @Test
    fun `quotes are not italic and use the muted colour`() {
        assertNull(dark.quoteStyle.fontStyle)
        assertEquals(SkeinColors.dark.onSurfaceVariant, dark.quoteColor)
        assertEquals(SkeinColors.light.onSurfaceVariant, light.quoteColor)
    }

    @Test
    fun `dark and light themes disagree wherever the underlying palette does`() {
        assertNotEquals(dark.bodyColor, light.bodyColor)
        assertNotEquals(dark.codeBackground, light.codeBackground)
        assertNotEquals(dark.codeBlockBackground, light.codeBlockBackground)
    }

    @Test
    fun `no field is left at MarkdownStyle's neutral fallback`() {
        val neutral = MarkdownStyle.Default
        assertNotEquals(Color.Unspecified, dark.bodyColor)
        assertNotEquals(neutral.codeBackground, dark.codeBackground)
        assertNotEquals(neutral.linkColor, dark.linkColor)
        assertNotEquals(neutral.citationBackground, dark.citationBackground)
    }
}
