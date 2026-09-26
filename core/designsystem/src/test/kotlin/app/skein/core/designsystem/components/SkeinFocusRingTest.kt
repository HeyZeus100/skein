package app.skein.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinColors
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §7.3: the ring is shown "only in keyboard input mode" — never for touch,
 * always in TalkBack (which draws its own indicator). [LocalSkeinKeyboardMode]
 * lets this test force each side of that rule without simulating a real
 * hardware key press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinFocusRingTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `pure rule - ring is visible only while focused and in keyboard mode`() {
        assertTrue(skeinFocusRingVisible(focused = true, keyboardMode = true))
        assertFalse(skeinFocusRingVisible(focused = true, keyboardMode = false))
        assertFalse(skeinFocusRingVisible(focused = false, keyboardMode = true))
        assertFalse(skeinFocusRingVisible(focused = false, keyboardMode = false))
    }

    @Test
    fun `a focused element paints the focus-ring colour when keyboard mode is forced on`() {
        assertTrue(
            "expected the focus-ring colour somewhere in the capture when keyboard mode is on",
            renderFocused(keyboardMode = true).containsColor(SkeinColors.lightExtended.focusRing.toArgb()),
        )
    }

    @Test
    fun `a focused element never paints the focus-ring colour when keyboard mode is off (touch)`() {
        assertFalse(
            "did not expect the focus-ring colour anywhere in the capture when keyboard mode is off " +
                "(touch input never shows the ring)",
            renderFocused(keyboardMode = false).containsColor(SkeinColors.lightExtended.focusRing.toArgb()),
        )
    }

    @Test
    fun `an unfocused element never paints the focus-ring colour, even in keyboard mode`() {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.LIGHT) {
                CompositionLocalProvider(LocalSkeinKeyboardMode provides true) {
                    OuterAndTarget()
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onNodeWithTag(OUTER_TAG).captureToImage()
        assertFalse(
            "an unfocused target must never show the keyboard focus ring",
            bitmap.containsColor(SkeinColors.lightExtended.focusRing.toArgb()),
        )
    }

    private fun renderFocused(keyboardMode: Boolean): ImageBitmap {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.LIGHT) {
                CompositionLocalProvider(LocalSkeinKeyboardMode provides keyboardMode) {
                    OuterAndTarget()
                }
            }
        }
        composeRule.onNodeWithTag(INNER_TAG).requestFocus()
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(OUTER_TAG).captureToImage()
    }

    @Composable
    private fun OuterAndTarget() {
        Box(
            modifier =
                Modifier
                    .testTag(OUTER_TAG)
                    .size(CAPTURE_SIZE)
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .testTag(INNER_TAG)
                        .size(TARGET_SIZE)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        // skeinFocusRing's onFocusEvent must sit outside (before)
                        // the actual focus target to observe it — exactly the
                        // order a real caller gets for free by passing this
                        // modifier into a chip/button's own `modifier` param,
                        // ahead of that component's internal `.focusable()`.
                        .skeinFocusRing(cornerRadius = 0.dp)
                        .focusable(),
            )
        }
    }

    private fun ImageBitmap.containsColor(argb: Int): Boolean {
        val pixelMap = toPixelMap()
        for (x in 0 until width step 2) {
            for (y in 0 until height step 2) {
                if (pixelMap[x, y].toArgb() == argb) return true
            }
        }
        return false
    }

    private companion object {
        const val OUTER_TAG = "focus-ring-capture"
        const val INNER_TAG = "focus-ring-target"
        val CAPTURE_SIZE = 100.dp
        val TARGET_SIZE = 60.dp
    }
}
