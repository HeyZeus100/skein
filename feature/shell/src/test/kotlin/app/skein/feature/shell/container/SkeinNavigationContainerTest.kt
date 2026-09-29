// skein-xtov.24.6 (AL-07, UX Wave 3): `SkeinNavigationContainer`'s two
// acceptance criteria from the bead — `content` keeps its composition
// state across a live drawer↔rail flip (spec §8.9 item 10: "keep
// `NavDisplay` in one place"), and the drawer snaps closed when the
// container stops being the drawer (spec §3.3 Test G). Both flips are
// live, in one composition, via `DeviceConfigurationOverride.WindowSize`
// (`ANDROID_ADAPTIVE_SAMPLES.md` §5.5's recipe) — never
// `ActivityScenario.recreate()`, which this test doesn't need.
package app.skein.feature.shell.container

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.feature.shell.layout.skeinWindowLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compact/short (spec §3.1: a drawer — this is `FOLD_OUTER_443`'s size). */
private val COMPACT = DpSize(443.dp, 994.dp)

/** Expanded (a rail — `FOLD_INNER_1007`'s size). */
private val EXPANDED = DpSize(1007.dp, 1043.dp)

private const val COUNTER_TAG = "counter"
private const val OPEN_DRAWER_TAG = "open-drawer"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinNavigationContainerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** One container, driven by a live-mutable [size] the test flips mid-composition. */
    private fun setContent(
        size: MutableState<DpSize>,
        onNavigate: (SkeinDestination) -> Unit = {},
        onNewChat: () -> Unit = {},
        onSearch: () -> Unit = {},
        history: List<ChatHistoryItem> = emptyList(),
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                    val decision =
                        skeinWindowLayout(
                            currentWindowAdaptiveInfoV2(),
                            LocalWindowInfo.current.containerDpSize,
                            LocalDensity.current,
                        )
                    SkeinNavigationContainer(
                        decision = decision,
                        destination = SkeinDestination.CHAT,
                        onNavigate = onNavigate,
                        onNewChat = onNewChat,
                        onSearch = onSearch,
                        history = history,
                        spaces = emptyList(),
                        content = content,
                    )
                }
            }
        }
    }

    @Test
    fun `drawer destination closes a drawer opened by content beside a rail`() {
        assertRailDrawerActionCloses("Settings", "navigate:SETTINGS")
    }

    @Test
    fun `drawer New chat closes a drawer opened by content beside a rail`() {
        assertRailDrawerActionCloses(" New chat", "new-chat")
    }

    @Test
    fun `drawer Search closes a drawer opened by content beside a rail`() {
        assertRailDrawerActionCloses("Search or run a command", "search")
    }

    @Test
    fun `drawer history closes a drawer opened by content beside a rail`() {
        assertRailDrawerActionCloses("Retained chat", "history")
    }

    private fun assertRailDrawerActionCloses(
        label: String,
        expectedAction: String,
    ) {
        val actions = mutableListOf<String>()
        setContent(
            mutableStateOf(EXPANDED),
            onNavigate = { actions += "navigate:$it" },
            onNewChat = { actions += "new-chat" },
            onSearch = { actions += "search" },
            history =
                listOf(
                    ChatHistoryItem(
                        id = "retained-chat",
                        title = "Retained chat",
                        lastMessageAtMillis = 0,
                        timeLabel = "Earlier",
                        onOpen = { actions += "history" },
                    ),
                ),
        ) {
            val opener = LocalSkeinDrawerOpener.current
            Text("open", modifier = Modifier.testTag(OPEN_DRAWER_TAG).clickable(onClick = opener))
        }
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(OPEN_DRAWER_TAG).performClick()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsDisplayed()
        composeRule
            .onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(SkeinNavContainerTestTags.DRAWER_SHEET)))
            .performScrollToNode(hasText(label))
        composeRule
            .onNode(hasText(label) and hasAnyAncestor(hasTestTag(SkeinNavContainerTestTags.DRAWER_SHEET)))
            .performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(expectedAction), actions)
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsNotDisplayed()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertIsDisplayed()
    }

    @Test
    fun `content keeps its rememberSaveable state across a drawer to rail flip`() {
        val size = mutableStateOf(COMPACT)
        setContent(size) {
            var count by rememberSaveable { mutableStateOf(0) }
            Text(
                "count:$count",
                modifier = Modifier.testTag(COUNTER_TAG).clickable { count++ }.fillMaxSize(),
            )
        }

        composeRule.onNodeWithTag(COUNTER_TAG).performClick()
        composeRule.onNodeWithTag(COUNTER_TAG).performClick()
        composeRule.onNodeWithText("count:2").assertExists()

        size.value = EXPANDED
        composeRule.waitForIdle()

        composeRule.onNodeWithText("count:2").assertExists()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertIsDisplayed()
    }

    @Test
    fun `drawer closes when the container becomes the rail (Test G)`() {
        val size = mutableStateOf(COMPACT)
        setContent(size) {
            val opener = LocalSkeinDrawerOpener.current
            Text("open", modifier = Modifier.testTag(OPEN_DRAWER_TAG).clickable(onClick = opener))
        }

        composeRule.onNodeWithTag(OPEN_DRAWER_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsDisplayed()

        size.value = EXPANDED
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsNotDisplayed()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertIsDisplayed()
    }

    @Test
    fun `the drawer starts closed again after a re-fold`() {
        val size = mutableStateOf(COMPACT)
        setContent(size) {
            val opener = LocalSkeinDrawerOpener.current
            Text("open", modifier = Modifier.testTag(OPEN_DRAWER_TAG).clickable(onClick = opener))
        }

        composeRule.onNodeWithTag(OPEN_DRAWER_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsDisplayed()

        size.value = EXPANDED
        composeRule.waitForIdle()
        size.value = COMPACT
        composeRule.waitForIdle()

        // Back to a Drawer window: it comes back Closed, never re-opened (spec §3.3).
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsNotDisplayed()
    }
}
