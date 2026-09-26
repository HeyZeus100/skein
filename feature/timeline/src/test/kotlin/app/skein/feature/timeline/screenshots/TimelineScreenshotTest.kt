// skein-xtov.9 — "before" captures of the timeline screen on its own, full
// window, inside `SkeinTheme` (the in-shell placement — expanded left pane
// on the open Fold, `TimelineRail` as the closed-Fold landing — is captured
// by :feature:shell's `shell-landing`). `expanded` follows the posture the
// way `:app` uses it: true only in the dual-pane layout.
package app.skein.feature.timeline.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.skein.core.model.VaultRepository
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.timeline.TimelineScreen
import app.skein.feature.timeline.rememberTimelineState
import app.skein.testing.fakeVault
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    @Test
    fun timeline() {
        composeRule.setContent { Timeline(uxFixtureVault()) }
        composeRule.onRoot().captureUx(spec, "timeline")
    }

    @Test
    fun timelineEmpty() {
        composeRule.setContent { Timeline(fakeVault {}) }
        composeRule.onRoot().captureUx(spec, "timeline-empty")
    }

    @Composable
    private fun Timeline(vault: VaultRepository) {
        SkeinTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
                TimelineScreen(
                    state = rememberTimelineState(repo = vault),
                    onEntryClick = {},
                    expanded = !spec.isFolded,
                    zone = UX_ZONE,
                    now = { UX_NOW },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
