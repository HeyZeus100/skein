package app.skein.feature.shell.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinHingeSafeAreaTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `restricted initial composition never places content across crease while origin is unknown`() {
        val observed = mutableListOf<Rect>()
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(1000.dp, 1000.dp))) {
                    Box(Modifier.fillMaxSize().testTag("window")) {
                        Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                            SkeinHingeSafeArea(DpRect(0.dp, 0.dp, 1000.dp, 490.dp)) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .testTag("initial-surface")
                                        .onGloballyPositioned { observed += it.boundsInWindow() },
                                ) {
                                    Button(onClick = {}) { Text("Restricted") }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val scale =
            composeRule
                .onNodeWithTag("window")
                .fetchSemanticsNode()
                .boundsInWindow.width / 1000f
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.toString(), observed.all { it.height == 0f || it.bottom <= 490 * scale + 1 })
        assertTrue(
            composeRule
                .onNodeWithTag("initial-surface")
                .fetchSemanticsNode()
                .boundsInWindow.height > 48 * scale,
        )
        composeRule.onNodeWithText("Restricted").performClick()
    }

    @Test
    fun `actual child bounds respect a shifted parent and state survives flat tabletop book`() {
        val layout = mutableStateOf(decision(vertical = false, separating = false))
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(1000.dp, 1000.dp))) {
                    Box(Modifier.fillMaxSize().testTag("window")) {
                        Box(Modifier.fillMaxSize().padding(start = 80.dp, top = 24.dp).testTag("parent")) {
                            SkeinHingeSafeArea(
                                layout.value
                                    .takeUnless { it.posture == SkeinPosture.Flat }
                                    ?.surfaceBounds(SecondarySurface.COMMAND_PALETTE),
                            ) {
                                Box(Modifier.fillMaxSize().testTag("surface")) {
                                    var count by remember { mutableStateOf(0) }
                                    Button(onClick = { count++ }) { Text("count:$count") }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("count:0").performClick()
        val window = composeRule.onNodeWithTag("window").fetchSemanticsNode().boundsInWindow
        val scale = window.width / 1000f
        val flat = composeRule.onNodeWithTag("surface").fetchSemanticsNode().boundsInWindow
        assertEquals(window.left + 80 * scale, flat.left, 1f)
        assertEquals(window.top + 24 * scale, flat.top, 1f)
        assertEquals(window.bottom, flat.bottom, 1f)

        layout.value = decision(vertical = false, separating = true)
        composeRule.waitForIdle()
        val top = composeRule.onNodeWithTag("surface").fetchSemanticsNode().boundsInWindow
        assertEquals(490 * scale, top.bottom, 1f)
        assertTrue(top.top >= window.top + 24 * scale - 1f)
        composeRule.onNodeWithText("count:1").assertExists()

        layout.value = decision(vertical = true, separating = true)
        composeRule.waitForIdle()
        val book = composeRule.onNodeWithTag("surface").fetchSemanticsNode().boundsInWindow
        assertEquals(window.left + 510 * scale, book.left, 1f)
        assertEquals(window.right, book.right, 1f)
        composeRule.onNodeWithText("count:1").assertExists()
        composeRule.onNodeWithText("count:1").performClick()
        composeRule.onNodeWithText("count:2").assertExists()
        layout.value = decision(vertical = false, separating = false)
        composeRule.waitForIdle()
        assertEquals(flat, composeRule.onNodeWithTag("surface").fetchSemanticsNode().boundsInWindow)
        composeRule.onNodeWithText("count:2").assertExists()
    }
}
