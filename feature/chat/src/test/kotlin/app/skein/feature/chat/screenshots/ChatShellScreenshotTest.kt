// skein-xtov.24.8 (AL-09a): goldens for the Chat destination in the
// NavDisplay shell on the owner's Fold — outer (524 dp: drawer, one pane) and
// inner (1007 dp: rail, Conversations │ detail), light and dark. Recorded with
// `tools/ux/shots record chat --tests
// "app.skein.feature.chat.screenshots.ChatShellScreenshotTest"`.
package app.skein.feature.chat.screenshots

import androidx.compose.ui.test.onRoot
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.Role
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.contentKey
import app.skein.feature.chat.entries.EntriesHost
import app.skein.feature.chat.entries.pipelineOver
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.FakeClock
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
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
import java.time.Instant

private val NOW = Instant.parse("2026-09-26T16:00:00Z").toEpochMilli()
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatShellScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private val clock = FakeClock()
    private lateinit var foldChat: Document
    private val vault =
        fakeVault(clock = clock::now) {
            clock.set(NOW - 9 * DAY)
            chat(
                "Mycology research",
                Role.USER to "How long does straw take to colonise?",
                Role.ASSISTANT to "About 14 days.",
            )
            clock.set(NOW - DAY - 5 * HOUR)
            chat("Chat about quantisation", Role.USER to "Is Q3_K_M good enough?", Role.ASSISTANT to "For chat, yes.")
            clock.set(NOW - 3 * HOUR)
            note("Fold launch plan", "Targets M2 for the ask path. Owner smoke test on the Fold before tagging.")
            clock.set(NOW - 40 * MINUTE)
            foldChat =
                chat(
                    "Skein UX redesign",
                    Role.USER to "Explain the adaptive layout on the Fold.",
                    Role.ASSISTANT to
                        "The outer screen keeps one pane and a drawer; the inner screen shows Conversations beside the chat.",
                )
        }
    private val fold: SkeinId get() = SkeinId.of(foldChat.id)
    private lateinit var shell: SkeinShellState

    private fun show() {
        assumeTrue(spec.fontScale == 1f)
        assumeTrue(spec.device == SkeinDevice.FOLD_OUTER_524 || spec.device == SkeinDevice.FOLD_INNER_1007)
        val pipeline = pipelineOver(vault, scriptedEngine())
        composeRule.setContent {
            SkeinTheme { EntriesHost(vault, pipeline, size = null, onShell = { shell = it }, clock = { NOW }) }
        }
        composeRule.waitForIdle()
    }

    /** Outer: the landing ("What are you working on?"); inner: Conversations │ the landing. */
    @Test
    fun chatRoot() {
        show()
        composeRule.onRoot().captureUx(spec, "shell-chat-root")
    }

    /** Outer: the conversation with ☰ and ✎; inner: Conversations │ the conversation, its row selected. */
    @Test
    fun conversation() {
        show()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(fold)) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "shell-chat-conversation")
    }

    /** Outer: the inspector as a bottom sheet over the chat; inner: the conversation │ Context (the list yields). */
    @Test
    fun inspector() {
        show()
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(fold)) }
            shell.navigate { follow(it, ChatContextKey(fold)) }
            shell.sheets.expand(ChatContextKey(fold).contentKey)
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "shell-chat-inspector")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
