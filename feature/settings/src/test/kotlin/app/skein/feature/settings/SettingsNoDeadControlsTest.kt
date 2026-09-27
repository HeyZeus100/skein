package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.skein.core.navigation.SettingsCategory
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No dead security controls; the About category reaches actual licenses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsNoDeadControlsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `settings hides rows that do nothing`() {
        composeRule.setContent {
            SettingsCategoryScreen(
                category = SettingsCategory.PRIVACY_AND_SECURITY,
                viewModel = rememberSettingsViewModel(flowOf(true), {}),
                appVersion = "0.1.0 (1)",
            )
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
            SettingsCategoryScreen(SettingsCategory.ABOUT, viewModel, "0.1.0 (1)")
        }

        composeRule.onNodeWithText("Open-source licenses").performScrollTo().performClick()

        composeRule.onNodeWithText("Search licenses").assertExists()
    }
}
