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

    // bd `skein-jit3`: the editor's own surface — see `SkeinColorHex`'s doc
    // on `DARK_EDITOR_SURFACE`/`LIGHT_EDITOR_SURFACE` for why this pair is
    // the one that actually needed guarding (the on-surface/surface pair
    // above never was the pair the device rendered for the editor's text).

    @Test
    fun `dark editor on-surface vs editor surface meets AAA contrast`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.DARK_ON_EDITOR_SURFACE, SkeinColorHex.DARK_EDITOR_SURFACE)
        assertTrue("dark editor on-surface/surface contrast was $ratio, need >= 7.0", ratio >= 7.0)
    }

    @Test
    fun `light editor on-surface vs editor surface meets AAA contrast`() {
        val ratio = WcagContrast.ratio(SkeinColorHex.LIGHT_ON_EDITOR_SURFACE, SkeinColorHex.LIGHT_EDITOR_SURFACE)
        assertTrue("light editor on-surface/surface contrast was $ratio, need >= 7.0", ratio >= 7.0)
    }

    @Test
    fun `dark editor muted vs editor surface meets AA contrast`() {
        val ratio =
            WcagContrast.ratio(SkeinColorHex.DARK_ON_EDITOR_SURFACE_MUTED, SkeinColorHex.DARK_EDITOR_SURFACE)
        assertTrue("dark editor muted/surface contrast was $ratio, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun `light editor muted vs editor surface meets AA contrast`() {
        val ratio =
            WcagContrast.ratio(SkeinColorHex.LIGHT_ON_EDITOR_SURFACE_MUTED, SkeinColorHex.LIGHT_EDITOR_SURFACE)
        assertTrue("light editor muted/surface contrast was $ratio, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun `the pre-fix bug- default black text on the dark pane background- fails AA contrast`() {
        // Regression guard for the actual bug (bd `skein-jit3`): before this
        // fix, `SkeinEditor`'s `BasicTextField` left its `textStyle` color
        // unspecified, which Compose Foundation silently renders as opaque
        // black (confirmed empirically by rendering the real composition
        // under Robolectric and sampling pixels) — on `DARK_BACKGROUND`
        // (the color that was actually behind the editor, not `DARK_SURFACE`
        // this test class otherwise guards). This documents why that pair
        // was broken, so nobody "fixes" the new editor pair by routing it
        // back through `background`.
        val black = 0xFF000000L
        val ratio = WcagContrast.ratio(black, SkeinColorHex.DARK_BACKGROUND)
        assertTrue("expected the old pair to fail 4.5:1 (it was the bug), ratio was $ratio", ratio < 4.5)
    }
}
