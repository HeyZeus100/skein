package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E6.I18 (skein-fsn): [SettingsScreen] shows a Settings › Indexing hint row
 * (`settings_indexing_hint`) explaining that indexing progress is surfaced
 * via notifications — the coordinator's decision note's "Settings hint" for
 * a denied/never-asked `POST_NOTIFICATIONS` permission (no separate crash or
 * empty state; the row is simply always present, independent of permission
 * state, since `SettingsScreen` itself never reads permission state).
 *
 * Pinned to SDK 34, matching every other Robolectric test in this repo (bd
 * memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the Indexing hint row`() {
        composeRule.setContent {
            SettingsScreen(
                flagSecureEnabled = true,
                onFlagSecureEnabledChange = {},
                appVersion = "0.1.0 (1)",
            )
        }

        composeRule.onNodeWithTag("settings_indexing_hint").assertExists()
    }

    /**
     * DS3 (skein-xtov.23.3), §6.7 / §14 item 3: [SettingsScreen] is composed
     * here with NO manual `Surface` wrapper (unlike `SettingsScreenshotTest`,
     * which has always added one) — this is the exact shape `SkeinApp`'s
     * `destinationContent` slot renders it in on a device. Before this
     * bead's fix, `SettingsScreen`'s root was a bare `Box`, and rows like
     * `LockPolicyControls`' "Lock after inactivity" label (no explicit
     * `color =`) rendered near-black on the dark theme (the "Settings row
     * titles dark-on-dark" bug this bead's report names). Sampling that
     * exact row's rendered pixels is a real contrast check, not just a
     * `LocalContentColor` probe.
     */
    @Test
    fun `Lock after inactivity row is legible on the dark theme, not near-black`() {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.DARK) {
                SettingsScreen(
                    flagSecureEnabled = true,
                    onFlagSecureEnabledChange = {},
                    appVersion = "0.1.0 (1)",
                )
            }
        }

        val luminance =
            composeRule
                .onNodeWithText("Lock after inactivity", useUnmergedTree = true)
                .averageLuminance()
        assertTrue(
            "expected \"Lock after inactivity\" to be legible on the dark theme " +
                "(onSurface, not a black fallback), luminance was $luminance",
            luminance > MIN_LEGIBLE_LUMINANCE,
        )
    }

    private fun SemanticsNodeInteraction.averageLuminance(): Double {
        val pixelMap = captureToImage().toPixelMap()
        var total = 0.0
        var count = 0
        for (x in 0 until pixelMap.width) {
            for (y in 0 until pixelMap.height) {
                total += relativeLuminance(pixelMap[x, y].toArgb())
                count++
            }
        }
        return total / count
    }

    private fun relativeLuminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF

        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private companion object {
        // Measured directly (see this bead's calibration): the same label
        // rendered with an explicit `Color.Black` (the pre-fix fallback)
        // averages ~0.0044 over its own bounds — almost entirely background,
        // since black text on a near-black surface leaves no lit glyph
        // pixels. Correctly coloured (`onSurface`) it averages ~0.083, a
        // ~19x gap. 0.03 sits well inside that gap either way.
        const val MIN_LEGIBLE_LUMINANCE = 0.03
    }
}
