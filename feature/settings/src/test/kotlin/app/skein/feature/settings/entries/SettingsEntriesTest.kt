// skein-xtov.24.9 (AL-09b): Settings categories│category navigation and
// Back — splitting today's single `SettingsScreen` column into one screen
// per category must not change what Back does: a category two levels deep
// on Compact shows ← (never the drawer ☰, spec §8.6), and it pops back to
// the categories list, exactly like the hardware/predictive Back would.
package app.skein.feature.settings.entries

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import app.skein.core.navigation.Destination
import app.skein.core.navigation.SettingsCategory
import app.skein.core.navigation.SettingsCategoryKey
import app.skein.core.navigation.SettingsHomeKey
import app.skein.feature.settings.rememberSettingsViewModel
import app.skein.feature.shell.host.EntryChromeTestTags
import app.skein.feature.shell.host.SkeinShellState
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsEntriesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: SkeinShellState

    @Test
    fun `Compact — selecting a category shows the back icon, never the drawer, and pops back to the list`() {
        setHost(COMPACT)
        composeRule.onNodeWithTag(SettingsEntryTestTags.LIST).assertExists()
        composeRule.onNodeWithContentDescription("Open navigation").assertExists()

        composeRule.onNodeWithText("Privacy & security").performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(SettingsHomeKey, SettingsCategoryKey(SettingsCategory.PRIVACY_AND_SECURITY)),
            shell.nav.stack(Destination.SETTINGS),
        )
        composeRule.onNodeWithTag(SettingsEntryTestTags.LIST).assertDoesNotExist()
        // §8.6: a detail two levels deep never trades Back for the drawer.
        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
        composeRule.onNodeWithTag(EntryChromeTestTags.NAV_ICON).assertExists()

        composeRule.onNodeWithTag(EntryChromeTestTags.NAV_ICON).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsHomeKey), shell.nav.stack(Destination.SETTINGS))
        composeRule.onNodeWithTag(SettingsEntryTestTags.LIST).assertExists()
    }

    @Test
    fun `Expanded — Appearance preselected as the placeholder detail, and a category is still selectable`() {
        setHost(EXPANDED)
        // spec §8.9: "Appearance preselected as the placeholder detail so the right side is never empty."
        composeRule.onNodeWithText("Theme").assertExists()

        composeRule.onNodeWithText("Privacy & security").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SettingsEntryTestTags.LIST).assertExists()
        assertEquals(
            listOf(SettingsHomeKey, SettingsCategoryKey(SettingsCategory.PRIVACY_AND_SECURITY)),
            shell.nav.stack(Destination.SETTINGS),
        )
    }

    private fun setHost(size: DpSize) {
        composeRule.setContent {
            val viewModel = rememberFixtureSettingsViewModel()
            val deps = SettingsEntryDeps(viewModel, "0.1.0 (1)")
            SettingsHost(deps, size) { shell = it }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.SETTINGS) } }
        composeRule.waitForIdle()
    }
}

@Composable
private fun rememberFixtureSettingsViewModel() =
    rememberSettingsViewModel(
        flagSecureEnabledFlow = flowOf(true),
        onSetFlagSecureEnabled = {},
    )
