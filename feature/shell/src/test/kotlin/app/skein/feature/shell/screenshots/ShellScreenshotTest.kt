// skein-xtov.9 — "before" captures of the whole app shell (SkeinApp) at every
// device size, wired the way `:app`'s MainActivity wires it: timeline pane /
// landing content from `:feature:timeline`, the model chip text MainActivity
// derives, everything else the shell's own defaults.
package app.skein.feature.shell.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.skein.core.model.VaultRepository
import app.skein.feature.shell.DestinationPlaceholder
import app.skein.feature.shell.SkeinApp
import app.skein.feature.shell.layout.FoldPosture
import app.skein.feature.timeline.TimelineRail
import app.skein.feature.timeline.TimelineScreen
import app.skein.feature.timeline.rememberTimelineState
import app.skein.testing.fakeVault
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    /** A vault with a week of notes/chats and a default model that is not loaded yet — the usual launch. */
    @Test
    fun landing() {
        composeRule.setContent { AppShell(uxFixtureVault(), UX_NOT_LOADED_CHIP, modelStatusActive = false) }
        composeRule.onRoot().captureUx(spec, "shell-landing")
    }

    /** First run: empty vault, no model imported (MainActivity's "no model" chip). */
    @Test
    fun empty() {
        spec.assumeStandard()
        composeRule.setContent { AppShell(fakeVault {}, "no model", modelStatusActive = false) }
        composeRule.onRoot().captureUx(spec, "shell-empty")
    }

    /** The chip once the engine is up: MainActivity shows the model id untruncated. */
    @Test
    fun modelLoaded() {
        spec.assumeStandard()
        composeRule.setContent { AppShell(uxFixtureVault(), UX_LONG_MODEL_FILE, modelStatusActive = true) }
        composeRule.onRoot().captureUx(spec, "shell-model-loaded")
    }

    @Test
    fun drawerOpen() {
        spec.assumeStandard()
        assumeTrue(spec.isFolded)
        composeRule.setContent { AppShell(uxFixtureVault(), UX_NOT_LOADED_CHIP, modelStatusActive = false) }
        composeRule.onNodeWithContentDescription("Open navigation drawer").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "shell-drawer-open")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs(UxSpec(UxDevice.FOLD_INNER_STOCK, dark = false), *UX_FONT_150)
    }
}

/**
 * `SkeinApp` as `MainActivity` composes it for an open vault: the timeline
 * pane (expanded `TimelineScreen` on dual-pane, `TimelineRail` as the
 * single-pane landing) over [vault], and the chip text MainActivity derives
 * from the engine status. Posture is pinned to `Unknown`, which the shell
 * lays out exactly like a flat (open) Fold — only tabletop changes layout.
 */
@Composable
internal fun AppShell(
    vault: VaultRepository,
    modelStatusName: String,
    modelStatusActive: Boolean,
) {
    val timelineState = rememberTimelineState(repo = vault)
    SkeinApp(
        vaultRepository = vault,
        modelStatusName = modelStatusName,
        modelStatusActive = modelStatusActive,
        destinationContent = { destination -> DestinationPlaceholder(label = destination.name) },
        timelinePane = { expanded, onEntryOpen, onEntryPin ->
            if (expanded) {
                TimelineScreen(
                    state = timelineState,
                    onEntryClick = { document -> onEntryOpen(document.id, document.title) },
                    onEntryLongPress = { document -> onEntryPin(document.id, document.title) },
                    // Stage H5: MainActivity wires the New note / New chat buttons.
                    onNewNote = {},
                    onNewChat = {},
                    expanded = true,
                    zone = UX_ZONE,
                    now = { UX_NOW },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TimelineRail(
                    state = timelineState,
                    onEntryClick = { document -> onEntryOpen(document.id, document.title) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        posture = FoldPosture.Unknown,
    )
}
