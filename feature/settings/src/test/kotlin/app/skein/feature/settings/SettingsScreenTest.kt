package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
