// skein-up0 (E3.I14) — on-device Compose UI test: the idle-timeout selector
// survives activity recreation once its value is hoisted through
// `rememberSaveable` (the shape any real host — e.g. `:app`'s
// `MainActivity`, once it wires `SecurityPrefs.idleTimeoutMinutes` in a
// follow-up — is expected to use). `SettingsScreen` itself is stateless
// (`idleTimeoutMinutes`/`onIdleTimeoutMinutesChange` are hoisted, same shape
// as `flagSecureEnabled`), so this test supplies a small `rememberSaveable`
// host in place of a real `SecurityPrefs`-backed one, and asserts the UI
// reflects the recreated value — the DataStore-level persistence itself is
// already covered by `:app`'s JVM `SecurityPrefsTest`.
//
// Compile-only in this worktree: no provisioned emulator (bd `skein-k3b2`,
// same gate `:feature:graph`'s `GraphViewInstrumentedTest` and `:core:vault`'s
// androidTest suite are already under).

package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SettingsScreenLockPolicyInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun idleTimeoutSelectionSurvivesActivityRecreation() {
        composeRule.setContent {
            var minutes by rememberSaveable { mutableIntStateOf(5) }
            SettingsScreen(
                flagSecureEnabled = true,
                onFlagSecureEnabledChange = {},
                appVersion = "test",
                idleTimeoutMinutes = minutes,
                onIdleTimeoutMinutesChange = { minutes = it },
            )
        }

        // Open the dropdown (the row's subtitle shows the current value) and
        // pick a different one.
        composeRule.onNodeWithText("5 minutes").performClick()
        composeRule.onNodeWithText("1 minute").performClick()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("1 minute").assertIsDisplayed()
    }
}
