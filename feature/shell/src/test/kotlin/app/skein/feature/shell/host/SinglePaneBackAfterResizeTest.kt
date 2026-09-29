package app.skein.feature.shell.host

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigationevent.DirectNavigationEventInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.NavMode
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinKey
import app.skein.core.vault.session.UnlockManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** skein-xlc4: baseline-reproduced consuming Back failure after multi-pane to Single resize. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SinglePaneBackAfterResizeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private val ledger = ProbeLedger()
    private val size = mutableStateOf(SINGLE)
    private lateinit var shell: SkeinShellState
    private var mode: NavMode? = null
    private var fallbackBacks = 0
    private val draft = NewChatKey(DRAFT_D)

    @Test
    fun `fresh Single landing consumes Back to Conversations`() {
        setHost(SINGLE)
        composeRule.runOnIdle { shell.navigate { goTo(it, draft) } }
        dispatchBack(listOf(ChatHomeKey))
    }

    @Test
    fun `Triple to Single landing consumes Back to Conversations`() {
        setHost(TRIPLE)
        composeRule.runOnIdle { shell.navigate { goTo(it, draft) } }
        resizeToSingle()
        dispatchBack(listOf(ChatHomeKey))
    }

    @Test
    fun `Triple to Single followed detail consumes Back to the same draft`() {
        setHost(TRIPLE)
        composeRule.runOnIdle {
            shell.navigate { goTo(it, draft) }
            shell.navigate { follow(it, NoteKey(NOTE_B)) }
        }
        resizeToSingle()
        dispatchBack(listOf(ChatHomeKey, draft))
    }

    @Test
    fun `fresh direct input after Triple to Single reaches the same Back handler`() {
        setHost(TRIPLE)
        composeRule.runOnIdle { shell.navigate { goTo(it, draft) } }
        resizeToSingle()
        dispatchBack(listOf(ChatHomeKey), directInput = true)
    }

    private fun setHost(window: DpSize) {
        size.value = window
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                    BackHandler { fallbackBacks++ }
                    shell = rememberSkeinShellState(manager)
                    SkeinShellHost(shell, { ids -> ids.associateWith { ObjectKind.CHAT } }) { key ->
                        val currentMode = LocalSkeinWindowLayout.current.navMode()
                        SideEffect { mode = currentMode }
                        Probe(key, ledger)
                    }
                }
            }
        }
    }

    private fun resizeToSingle() {
        composeRule.waitForIdle()
        trace("before resize")
        size.value = SINGLE
        composeRule.waitForIdle()
        assertEquals(NavMode.SINGLE, mode)
        trace("after resize")
    }

    private fun dispatchBack(
        expected: List<SkeinKey>,
        directInput: Boolean = false,
    ) {
        composeRule.waitForIdle()
        trace("before Back")
        composeRule.runOnIdle {
            if (directInput) {
                val dispatcher = composeRule.activity.navigationEventDispatcher
                val input = DirectNavigationEventInput()
                dispatcher.addInput(input)
                try {
                    input.backCompleted()
                } finally {
                    dispatcher.removeInput(input)
                }
            } else {
                composeRule.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                    BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT),
                )
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
        }
        trace("after Back")
        composeRule.waitUntil("Back reaches the expected stack", 5_000) { shell.nav.currentStack == expected }
        assertEquals("navigation consumed Back", 0, fallbackBacks)
    }

    /** Only type names, counts and enum state: no key, draft text or document metadata. */
    private fun trace(phase: String) {
        composeRule.runOnIdle {
            val dispatcher = composeRule.activity.navigationEventDispatcher
            val history = dispatcher.history.value
            println(
                "$phase mode=$mode current=${history.mergedHistory.getOrNull(history.currentIndex)?.javaClass?.simpleName} " +
                    "history=${history.mergedHistory.map { it.javaClass.simpleName }} " +
                    "transition=${dispatcher.transitionState.value.javaClass.simpleName} entries=${shell.nav.currentStack.size}",
            )
        }
    }

    private companion object {
        val SINGLE = DpSize(700.dp, 900.dp)
        val TRIPLE = DpSize(1600.dp, 1100.dp)
    }
}
