// skein-xtov.24.6 (AL-07, UX Wave 3): goldens for the new adaptive
// navigation container — the drawer open on Compact (443/524 dp) and the
// rail on Expanded (1007 dp), light and dark. Recorded with
// `tools/ux/shots record shell --tests
// "app.skein.feature.shell.screenshots.SkeinNavigationContainerScreenshotTest"`
// (never `clearRoborazziDebug` directly — it deletes committed goldens).
package app.skein.feature.shell.screenshots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.feature.shell.container.ChatHistoryItem
import app.skein.feature.shell.container.LocalSkeinDrawerOpener
import app.skein.feature.shell.container.SkeinDestination
import app.skein.feature.shell.container.SkeinNavigationContainer
import app.skein.feature.shell.container.SkeinSpace
import app.skein.feature.shell.layout.skeinWindowLayout
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val NOW = 1_800_000_000_000L
private const val OPEN_DRAWER_TAG = "screenshot-open-drawer"

private fun fixtureHistory(): List<ChatHistoryItem> =
    listOf(
        ChatHistoryItem(
            id = "1",
            title = "Skein UX redesign",
            lastMessageAtMillis = NOW,
            timeLabel = "9:41",
            preview = "Straw colonised in 14 days, hardwood in 21…",
            isSelected = true,
            onOpen = {},
            onRename = {},
            onDelete = {},
        ),
        ChatHistoryItem(
            id = "2",
            title = "RAG architecture",
            lastMessageAtMillis = NOW,
            timeLabel = "9:20",
            onOpen = {},
            onRename = {},
            onDelete = {},
        ),
        ChatHistoryItem(
            id = "3",
            title = "Mycology research",
            lastMessageAtMillis = NOW - 26 * 60 * 60 * 1000L,
            timeLabel = "Yesterday",
            onOpen = {},
            onRename = {},
            onDelete = {},
        ),
    )

private fun fixtureSpaces(): List<SkeinSpace> =
    listOf(
        SkeinSpace("s1", "Personal", isSelected = true, onSelect = {}),
        SkeinSpace("s2", "Work", isSelected = false, onSelect = {}),
    )

@Composable
private fun ContainerFixture() {
    val decision =
        skeinWindowLayout(
            currentWindowAdaptiveInfoV2(),
            LocalWindowInfo.current.containerDpSize,
            LocalDensity.current,
        )
    SkeinTheme {
        SkeinNavigationContainer(
            decision = decision,
            destination = SkeinDestination.CHAT,
            onNavigate = {},
            onNewChat = {},
            onSearch = {},
            history = fixtureHistory(),
            spaces = fixtureSpaces(),
            now = { NOW },
        ) {
            val opener = LocalSkeinDrawerOpener.current
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag(OPEN_DRAWER_TAG)
                        .clickable(onClick = opener),
            ) {
                Text("Skein UX redesign")
            }
        }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinNavigationContainerScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    /** Compact drawer, opened (spec §3.3): FOLD_OUTER_443 (stock) and FOLD_OUTER_524 (owner's Fold). */
    @Test
    fun drawerOpen() {
        assumeTrue(spec.device == SkeinDevice.FOLD_OUTER_443 || spec.device == SkeinDevice.FOLD_OUTER_524)
        assumeTrue(spec.fontScale == 1f)
        composeRule.setContent { ContainerFixture() }
        composeRule.onNodeWithTag(OPEN_DRAWER_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "nav-container-drawer-open")
    }

    /** Expanded rail (spec §3.4): FOLD_INNER_1007, the owner's open Fold. */
    @Test
    fun rail() {
        assumeTrue(spec.device == SkeinDevice.FOLD_INNER_1007)
        assumeTrue(spec.fontScale == 1f)
        composeRule.setContent { ContainerFixture() }
        composeRule.onRoot().captureUx(spec, "nav-container-rail")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
