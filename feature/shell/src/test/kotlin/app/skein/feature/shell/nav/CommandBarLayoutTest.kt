package app.skein.feature.shell.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `skein-wr7m` (hardware-verified on the Pixel 9 Pro Fold): [CommandBar]'s
 * `Surface` used to force an *exact* `44.dp` height — smaller than Material 3
 * [androidx.compose.material3.TextField]'s natural minimum height for a
 * `bodyMedium` line with no label, so the "search or /command" placeholder's
 * decoration box was coerced short and the glyph was vertically clipped
 * (only the top half of the letters rendered; confirmed on-device via the
 * accessibility tree).
 *
 * Reproduced here under Robolectric by comparing the placeholder's actual
 * rendered height inside [CommandBar] against a reference [Text] composed
 * with the identical style/content/single-line-ness outside any height
 * constraint — if [CommandBar] compresses the line, its height comes out
 * strictly smaller than the reference. This comparison is deliberately
 * relative rather than a hardcoded expected dp value: this bug-hunt found
 * that this module's bundled monospace font measures a noticeably taller
 * single `bodyMedium` line under Robolectric than the ~20 dp the bug report
 * describes from the real device, so a fixed height threshold tuned to one
 * environment would be a false pass (or a flaky failure) in the other.
 *
 * The actual fix is [CommandBar]'s `Surface` switching from
 * `Modifier.height(tokens.commandBarHeight)` to
 * `Modifier.heightIn(min = tokens.commandBarHeight)` (plus raising the token
 * itself to Material's `56.dp` minimum) — a floor, not an exact size, so the
 * bar always grows to fit whatever its content's real natural height turns
 * out to be on a given device/font, rather than clipping it. A plain "is the
 * placeholder inside the command bar" containment check alone would not
 * have caught the original regression — Compose still places the compressed
 * placeholder well inside the old 44 dp box; the
 * height-matches-the-uncompressed-line assertion is what proves nothing is
 * clipped, and the containment assertion still guards against a future
 * change overflowing the bar instead.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), matching
 * every other Robolectric test in this repo (e.g. `SkeinAppTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandBarLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val referenceTag = "referencePlaceholderText"
    private val barTag = "commandBarUnderTest"

    @Test
    fun `placeholder is not vertically compressed and stays within the command bar`() {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.DARK) {
                Column {
                    CommandBar(
                        query = "",
                        onQueryChange = {},
                        onMenuClick = {},
                        modelName = "qwen",
                        modelActive = true,
                        modifier = Modifier.testTag(barTag),
                    )
                    // Same style/content/single-line-ness as CommandBar's
                    // placeholder, laid out with no height constraint — the "what a
                    // fully visible line looks like" reference. `maxLines = 1`
                    // matters: without it this reference wraps to two lines under
                    // Robolectric's narrow default window, which would silently
                    // inflate the reference height and defeat the comparison.
                    Text(
                        text = "search or /command",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.testTag(referenceTag),
                    )
                }
            }
        }

        val barBounds = composeRule.onNodeWithTag(barTag).getUnclippedBoundsInRoot()
        val placeholderBounds =
            composeRule
                .onNodeWithTag(COMMAND_BAR_PLACEHOLDER_TEST_TAG, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val referenceBounds = composeRule.onNodeWithTag(referenceTag).getUnclippedBoundsInRoot()

        val placeholderHeight = placeholderBounds.bottom - placeholderBounds.top
        val referenceHeight = referenceBounds.bottom - referenceBounds.top

        assertTrue(
            "placeholder height ($placeholderHeight) was compressed below its " +
                "uncompressed line height ($referenceHeight) — the command bar is " +
                "clipping the text, same as skein-wr7m",
            placeholderHeight >= referenceHeight,
        )

        assertBoundsContain(outer = barBounds, inner = placeholderBounds)
    }

    /**
     * UX-P0-02 / CMS-P0-06 (Stage H6, skein-xtov.22): on the Fold's cover
     * screen a loaded model's untruncated id (51 characters) took the bar's
     * width first and collapsed the command field to ~0 dp. The chip is now
     * one line, ellipsized and width-capped, so the field keeps its width.
     */
    @Test
    @Config(qualifiers = "w411dp-h923dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so a long name can wrap
    fun `a long model name never crushes the command field on the cover screen`() {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.DARK) {
                CommandBar(
                    query = "/",
                    onQueryChange = {},
                    onMenuClick = {},
                    modelName = "qwen2.5-3b-instruct-abliterated-q3_k_m-3f9a1c2b7e",
                    modelActive = true,
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val chipLayout = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithContentDescription("Model status", substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(chipLayout) }

        assertTrue("command field is ${field.right - field.left} wide", field.right - field.left >= 100.dp)
        assertEquals("the chip wraps", 1, chipLayout.single().lineCount)
    }

    private fun assertBoundsContain(
        outer: DpRect,
        inner: DpRect,
    ) {
        assertTrue(
            "placeholder top (${inner.top}) is above the command bar's top (${outer.top})",
            inner.top >= outer.top,
        )
        assertTrue(
            "placeholder bottom (${inner.bottom}) is below the command bar's bottom (${outer.bottom})",
            inner.bottom <= outer.bottom,
        )
        assertTrue(
            "placeholder left (${inner.left}) is left of the command bar's left (${outer.left})",
            inner.left >= outer.left,
        )
        assertTrue(
            "placeholder right (${inner.right}) is right of the command bar's right (${outer.right})",
            inner.right <= outer.right,
        )
    }
}
