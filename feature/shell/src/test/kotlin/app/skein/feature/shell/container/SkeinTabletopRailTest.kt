package app.skein.feature.shell.container

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.feature.shell.layout.SkeinPosture
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
class SkeinTabletopRailTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `all actual rail targets avoid the hinge at 130 percent font`() = verifyTargets(fontScale = 1.3f, height = 1006)

    @Test
    fun `all actual rail targets remain reachable at 200 percent font with a crowded top partition`() =
        verifyTargets(fontScale = 2f, height = 620)

    private fun verifyTargets(
        fontScale: Float,
        height: Int,
    ) {
        val hinge = mutableStateOf(DpRect(0.dp, 200.dp, 1043.dp, 216.dp))
        val selected = mutableStateOf(SkeinDestination.CHAT)
        var chats = 0
        val selectedDestinations = mutableListOf<SkeinDestination>()
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(1043.dp, height.dp))) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        Box(Modifier.fillMaxSize().testTag("window")) {
                            SkeinRailContent(
                                destination = selected.value,
                                onNavigate = {
                                    selected.value = it
                                    selectedDestinations += it
                                },
                                onNewChat = { chats++ },
                                spaces =
                                    listOf(
                                        SkeinSpace("one", "Personal", true, {}),
                                        SkeinSpace("two", "Work", false, {}),
                                    ),
                                posture = SkeinPosture.Tabletop(hinge.value),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        fun verify(node: SemanticsNodeInteraction) {
            node.performScrollTo().assertIsDisplayed()
            val window = composeRule.onNodeWithTag("window").fetchSemanticsNode().boundsInWindow
            val scale = window.width / 1043f
            val bounds = node.fetchSemanticsNode().boundsInWindow
            assertTrue("target width $bounds", bounds.width >= 48 * scale - 1)
            assertTrue("target height $bounds", bounds.height >= 48 * scale - 1)
            assertTrue(
                "target intersects hinge: $bounds",
                bounds.bottom <= hinge.value.top.value * scale + 1 ||
                    bounds.top >= hinge.value.bottom.value * scale - 1,
            )
            assertTrue(
                "target exceeds viewport: $bounds",
                bounds.bottom <= window.bottom + 1 && bounds.top >= window.top - 1,
            )
        }
        verify(composeRule.onNodeWithTag(SkeinNavContainerTestTags.SPACE_SWITCHER))
        val newChat = composeRule.onNodeWithContentDescription("New chat")
        verify(newChat)
        newChat.performClick()
        for (destination in SkeinDestination.entries.filter { it != SkeinDestination.SETTINGS }) {
            val node = composeRule.onNodeWithText(destination.label)
            verify(node)
            node.performClick()
        }
        // Settings is deliberately outside the two scrolling regions.
        val settings = composeRule.onNodeWithText("Settings").assertIsDisplayed()
        val bounds = settings.fetchSemanticsNode().boundsInWindow
        val window = composeRule.onNodeWithTag("window").fetchSemanticsNode().boundsInWindow
        val scale = window.width / 1043f
        assertTrue(bounds.top >= hinge.value.bottom.value * scale)
        assertTrue(bounds.height >= 48 * scale - 1)
        settings.performClick()
        assertEquals(1, chats)
        assertEquals(SkeinDestination.entries.toList(), selectedDestinations)

        // A live posture/band change must recalculate the actual placement.
        hinge.value = DpRect(0.dp, 490.dp, 1043.dp, 510.dp)
        composeRule.waitForIdle()
        verify(composeRule.onNodeWithText("Knowledge"))
    }
}
