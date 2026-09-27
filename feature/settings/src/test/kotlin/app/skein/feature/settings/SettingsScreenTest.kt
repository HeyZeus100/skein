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
import app.skein.core.navigation.SettingsCategory
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Category entry regressions: indexing guidance and readable security controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the Indexing hint row`() {
        composeRule.setContent {
            SettingsCategoryScreen(
                category = SettingsCategory.KNOWLEDGE_AND_SEARCH,
                viewModel = rememberSettingsViewModel(flowOf(true), {}),
                appVersion = "0.1.0 (1)",
            )
        }

        composeRule.onNodeWithTag("settings_indexing_hint").assertExists()
    }

    /** The category supplies its own Surface, including when hosted without a wrapper. */
    @Test
    fun `Lock after inactivity row is legible on the dark theme, not near-black`() {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.DARK) {
                SettingsCategoryScreen(
                    category = SettingsCategory.PRIVACY_AND_SECURITY,
                    viewModel = rememberSettingsViewModel(flowOf(true), {}),
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
