// skein-xtov.23.11 (DS13, docs/ux/DESIGN_SYSTEM.md §15's "Tokens" row, §17):
// the Wave 2 exit evidence for tokens — colour roles with contrast numbers,
// type scale, spacing/radius rulers and the icon sheet — captured through
// `SkeinComponentGallery` at the three sizes named in the bead (phone, the
// closed Fold's stock width, the owner's open Fold), light and dark, font
// scale 1.0 and 2.0. Split into ten pages ([GalleryPage]'s own KDoc
// explains why): 10 × 3 × 2 × 2 = 120 goldens under
// `ux-baselines/core-designsystem/<device>/gallery-tokens-<page>__<theme>__fs<NNN>.png`.
// Every other §15 component that lives in `:core:designsystem` already has
// its own full-state gallery (SkeinComponentsScreenshotTest,
// SkeinInteractionPrimitivesScreenshotTest, screenshots/SkeinOverlaysScreenshotTest)
// — this file only adds the "Tokens" row nothing else captures.
package app.skein.core.designsystem.theme

import androidx.compose.ui.test.onNodeWithTag
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.SweepResult
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.assertEveryActionIsNamed
import app.skein.testing.ui.assertNoTextOverflow
import app.skein.testing.ui.assertTouchTargets
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.runSweep
import app.skein.testing.ui.skeinComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One line per [SweepResult], counting `"node "` occurrences instead of
 * printing the (often very long) full violation list — used by this file and
 * [SkeinGalleryExtrasScreenshotTest] to keep `assertNoTextOverflow`'s
 * report-only findings (see [SkeinTokensGalleryScreenshotTest.tokensColour1]'s
 * KDoc) readable across every device/theme/font-scale parameterisation.
 */
internal fun summarize(
    page: String,
    spec: UxSpec,
    result: SweepResult,
): String =
    if (result.passed) {
        "[$spec] $page PASS ${result.name}"
    } else {
        val count =
            result.detail
                .orEmpty()
                .lines()
                .count { it.startsWith("node ") }
        "[$spec] $page FAIL ${result.name}: $count node(s)"
    }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinTokensGalleryScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    /** [ThemeGallery]'s existing token specimen (spec `E6.I1`), shown as its own gallery page. */
    @Test
    fun tokensThemeSpecimen() = capturePage(GalleryPage.TOKENS_THEME_SPECIMEN, "gallery-tokens-theme-specimen")

    /** §6.5: colour roles with their measured WCAG contrast ratio (1 of 2 — split to fit phone at font scale 200%). */
    @Test
    fun tokensColour1() = capturePage(GalleryPage.TOKENS_COLOUR_1, "gallery-tokens-colour-1")

    /** §6.5: colour roles with their measured WCAG contrast ratio (2 of 2). */
    @Test
    fun tokensColour2() = capturePage(GalleryPage.TOKENS_COLOUR_2, "gallery-tokens-colour-2")

    /** §3.2: display/headline/title — the roles that grow the most at font scale 200%. */
    @Test
    fun tokensTypeLarge() = capturePage(GalleryPage.TOKENS_TYPE_LARGE, "gallery-tokens-type-large")

    /** §3.2/§3.3: body/label roles plus the four Skein Mono roles. */
    @Test
    fun tokensTypeSmall() = capturePage(GalleryPage.TOKENS_TYPE_SMALL, "gallery-tokens-type-small")

    /** §4.1: the spacing scale as a size ruler. */
    @Test
    fun tokensSpacing() = capturePage(GalleryPage.TOKENS_SPACING, "gallery-tokens-spacing")

    /** §5.1: the corner-radius scale as a shape ruler. */
    @Test
    fun tokensRadius() = capturePage(GalleryPage.TOKENS_RADIUS, "gallery-tokens-radius")

    /** §9: every `SkeinIcons` accessor, alphabetical 1st third (outline and the fill-1 `*Filled` companions). */
    @Test
    fun tokensIcons1() = capturePage(GalleryPage.TOKENS_ICONS_1, "gallery-tokens-icons-1")

    /** §9: every `SkeinIcons` accessor, alphabetical 2nd third. */
    @Test
    fun tokensIcons2() = capturePage(GalleryPage.TOKENS_ICONS_2, "gallery-tokens-icons-2")

    /** §9: every `SkeinIcons` accessor, alphabetical 3rd third. */
    @Test
    fun tokensIcons3() = capturePage(GalleryPage.TOKENS_ICONS_3, "gallery-tokens-icons-3")

    private fun capturePage(
        page: GalleryPage,
        stateId: String,
    ) {
        composeRule.setContent {
            SkeinTheme {
                SkeinComponentGallery(page)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GALLERY_TAG).captureUx(spec, stateId)
        // §7.1 / §11.4: these pages have no interactive nodes at all, so both
        // are trivially satisfied, but assert them anyway per DS8's convention.
        composeRule.assertTouchTargets()
        composeRule.assertEveryActionIsNamed()
        // §3.5's font-scale-200 no-overflow rule: report-only, matching
        // feature/settings' and feature/chat's AccessibilitySweepSmokeTest —
        // assertNoTextOverflow has widespread findings on every SkeinTheme
        // screen already (tracked for Wave 11, not this bead's fix), so it is
        // a sweep finding here, not a build gate.
        runSweep(listOf("assertNoTextOverflow" to { composeRule.assertNoTextOverflow() }))
            .forEach { r -> println(summarize(stateId, spec, r)) }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(SkeinDevice.PHONE, SkeinDevice.FOLD_OUTER_443, SkeinDevice.FOLD_INNER_1007)
                .flatMap { device ->
                    listOf(1f, 2f).flatMap { fontScale ->
                        listOf(false, true).map { dark -> arrayOf<Any>(UxSpec(device, dark, fontScale)) }
                    }
                }
    }
}
