package app.skein.feature.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-xtov.23.8 (DS8): Settings › Appearance on `SkeinSegmentedControl` —
 * each option is a 48 dp radio button, the current mode is the selected one,
 * and tapping another reports it (the hand-rolled row it replaces was ≈ 34 dp
 * with no role, DESIGN_SYSTEM.md §7.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeModeRowTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `theme options are 48 dp radio buttons, the current mode selected, a tap reports the new mode`() {
        val changes = mutableListOf<SkeinThemeMode>()
        composeRule.setContent {
            SkeinTheme { ThemeModeRow(mode = SkeinThemeMode.SYSTEM, onModeChange = { changes += it }) }
        }

        for (label in listOf("System", "Light", "Dark")) {
            composeRule
                .onNodeWithText(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithText("System").assertIsSelected()
        composeRule.onNodeWithText("Dark").assertIsNotSelected()

        composeRule.onNodeWithText("Dark").performClick()

        assertEquals(listOf(SkeinThemeMode.DARK), changes)
    }
}
