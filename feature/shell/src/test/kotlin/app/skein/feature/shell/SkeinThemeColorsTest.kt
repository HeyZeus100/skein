package app.skein.feature.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.skein.core.designsystem.theme.LocalSkeinColors
import app.skein.core.designsystem.theme.SkeinColors
import app.skein.core.designsystem.theme.SkeinExtendedColors
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-xtov.23.2 (DS2): [SkeinTheme] provides the resolved theme's full
 * `ColorScheme`, its [SkeinExtendedColors] through [LocalSkeinColors], and
 * the §6.3 selection colours (primary @ 30 %, not Material's 40 %).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinThemeColorsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `SkeinTheme provides the resolved theme's colours`() {
        val seen = mutableMapOf<SkeinThemeMode, Triple<ColorScheme, SkeinExtendedColors, TextSelectionColors>>()
        composeRule.setContent {
            for (mode in listOf(SkeinThemeMode.DARK, SkeinThemeMode.LIGHT)) {
                SkeinTheme(mode = mode) {
                    seen[mode] =
                        Triple(MaterialTheme.colorScheme, LocalSkeinColors.current, LocalTextSelectionColors.current)
                }
            }
        }
        composeRule.waitForIdle()

        val (darkScheme, darkExtended, darkSelection) = seen.getValue(SkeinThemeMode.DARK)
        assertSame(SkeinColors.dark, darkScheme)
        assertSame(SkeinColors.darkExtended, darkExtended)
        assertEquals(SkeinColors.darkExtended.textSelectionColors, darkSelection)
        assertEquals(0.30f, darkSelection.backgroundColor.alpha, 0.01f)

        val (lightScheme, lightExtended, lightSelection) = seen.getValue(SkeinThemeMode.LIGHT)
        assertSame(SkeinColors.light, lightScheme)
        assertSame(SkeinColors.lightExtended, lightExtended)
        assertEquals(SkeinColors.lightExtended.textSelectionColors, lightSelection)
    }
}
