package app.skein.feature.shell.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criterion (`E6.I1`): on-surface vs surface contrast ≥ 7:1 (WCAG
 * AAA for body text) in both dark and light themes.
 */
class SkeinColorContrastTest {
    @Test
    fun `dark on-surface vs surface meets AAA contrast`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.DARK_ON_SURFACE, SkeinColorHex.DARK_SURFACE)
        assertTrue("dark on-surface/surface contrast was $ratio, need >= 7.0", ratio >= 7.0)
    }

    @Test
    fun `light on-surface vs surface meets AAA contrast`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.LIGHT_ON_SURFACE, SkeinColorHex.LIGHT_SURFACE)
        assertTrue("light on-surface/surface contrast was $ratio, need >= 7.0", ratio >= 7.0)
    }

    @Test
    fun `dark on-background vs background meets AAA contrast`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.DARK_ON_BACKGROUND, SkeinColorHex.DARK_BACKGROUND)
        assertTrue("dark on-background/background contrast was $ratio, need >= 7.0", ratio >= 7.0)
    }

    @Test
    fun `light on-background vs background meets AAA contrast`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.LIGHT_ON_BACKGROUND, SkeinColorHex.LIGHT_BACKGROUND)
        assertTrue("light on-background/background contrast was $ratio, need >= 7.0", ratio >= 7.0)
    }

    @Test
    fun `identical colors have a contrast ratio of exactly 1`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.DARK_SURFACE, SkeinColorHex.DARK_SURFACE)
        assertTrue(ratio == 1.0)
    }
}
