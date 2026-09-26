package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX-P0-13 / CMS-P0-04 (Stage H, skein-xtov.22): Settings shows no row that
 * does nothing — Export/Erase vault, the model placeholders and "Coming in
 * v1.1" are hidden — and the licenses row reaches the licenses screen
 * through [SettingsRoute] (the entry point `MainActivity` now uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsNoDeadControlsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `settings hides rows that do nothing`() {
        composeRule.setContent {
            SettingsScreen(flagSecureEnabled = true, onFlagSecureEnabledChange = {}, appVersion = "0.1.0 (1)")
        }

        listOf("Export vault", "Erase vault", "Biometric unlock", "View NOTICE").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        listOf("Coming in", "skein-", "Available once").forEach { fragment ->
            composeRule.onNodeWithText(fragment, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `the licenses row opens the licenses screen`() {
        composeRule.setContent {
            val viewModel = rememberSettingsViewModel(flagSecureEnabledFlow = flowOf(true), onSetFlagSecureEnabled = {})
            SettingsRoute(viewModel = viewModel, appVersion = "0.1.0 (1)")
        }

        composeRule.onNodeWithText("Open-source licenses").performScrollTo().performClick()

        composeRule.onNodeWithText("Search licenses").assertExists()
    }
}
