package app.skein.feature.shell.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.SkeinLogCaptureRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinHingeDebugOverlayTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val logs = SkeinLogCaptureRule()

    @Test
    fun `debug observation is opt in content free and leaves underlying touch active`() {
        val enabled = mutableStateOf(false)
        var clicks = 0
        val info =
            WindowAdaptiveInfo(
                WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(1000, 1000),
                Posture(
                    isTabletop = true,
                    hingeList = listOf(HingeInfo(Rect(0f, 500f, 1000f, 500f), false, false, true, false)),
                ),
            )
        composeRule.setContent {
            SkeinTheme {
                Box {
                    Button(onClick = { clicks++ }) { Text("Underlying") }
                    SkeinHingeDebugOverlay(info, enabled.value, Modifier.testTag("overlay"))
                }
            }
        }
        composeRule.onNodeWithTag("overlay").assertDoesNotExist()
        assertTrue(logs.captured().none { it.tag == "SkeinHinge" })
        enabled.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Underlying").performTouchInput { click() }
        assertEquals(1, clicks)
        assertEquals(
            "tabletop=true count=1 vertical=false separating=true occluding=false boundsPx=0.0,500.0,1000.0,500.0",
            logs.captured().single { it.tag == "SkeinHinge" }.message,
        )
    }
}
