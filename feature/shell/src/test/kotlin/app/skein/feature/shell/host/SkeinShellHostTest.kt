// skein-xtov.24.7 (AL-08): the host's navigation-side obligations —
// nothing composes before unlock and nothing renders before the sanitise
// (SECURITY_REVIEW_D7.md M4a, M4d), entry state survives a live
// Compact ↔ Expanded flip (ADAPTIVE_LAYOUT_SPEC.md Test G, §8.9 item 10), and a
// restore drops ids deleted meanwhile (M4d, §7.7 step 3). Flips are live, via
// `DeviceConfigurationOverride.WindowSize`, like `SkeinNavigationContainerTest`.
package app.skein.feature.shell.host

import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.NewNoteKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinId
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.container.SkeinDestination
import app.skein.feature.shell.container.SkeinNavContainerTestTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Fold's outer display: Compact, a drawer, one pane. */
private val COMPACT = DpSize(524.dp, 1175.dp)

/** The Fold's inner display: Expanded, a rail, two panes. */
private val EXPANDED = DpSize(1006.dp, 1043.dp)

private const val GATE_TAG = "gate"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinShellHostTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private val ledger = ProbeLedger()
    private lateinit var shell: SkeinShellState

    @Test
    fun `nothing composes before unlock and nothing renders before the sanitise`() {
        val open = mutableStateOf(false)
        val lookup = CompletableDeferred<Map<SkeinId, ObjectKind>>()
        var lookups = 0
        composeRule.setContent {
            SkeinTheme {
                // Hoisted above the gate, as MainActivity does (spec §8.8).
                shell = rememberSkeinShellState(manager)
                if (open.value) {
                    SkeinShellHost(shell, {
                        lookups++
                        lookup.await()
                    }) { Probe(it, ledger) }
                } else {
                    Text("Unlock Skein", Modifier.testTag(GATE_TAG))
                }
            }
        }
        // A stack restored from the Bundle names a chat; the gate is still closed.
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }

        composeRule.onNodeWithTag(GATE_TAG).assertExists()
        composeRule.onNodeWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.ROOT).assertDoesNotExist()
        assertEquals("no lookup before unlock", 0, lookups)

        open.value = true
        composeRule.waitForIdle()
        // Unlocked, but the ids are not resolved yet: still nothing (M4d).
        assertEquals(1, lookups)
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.ROOT).assertDoesNotExist()
        assertEquals("no entry composed before the sanitise", 0, ledger.compositions.get())

        lookup.complete(mapOf(CHAT_A to ObjectKind.CHAT))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertExists()
        composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A))).assertExists()
    }

    @Test
    fun `entry state survives a Compact to Expanded size change and back (Test G)`() {
        val size = mutableStateOf(COMPACT)
        setHost(size)
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        val chat = probeTag(ChatKey(CHAT_A))
        composeRule.onNodeWithTag(chat).performClick()
        composeRule.onNodeWithTag(chat).performClick()
        val state = textOf(chat)
        assertTrue(state, state.endsWith("t2=2"))

        size.value = EXPANDED
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertExists()
        composeRule.onNodeWithTag(probeTag(ChatHomeKey)).assertExists()
        assertEquals(state, textOf(chat))

        size.value = COMPACT
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertDoesNotExist()
        assertEquals(state, textOf(chat))
        assertEquals(
            "the chat entry's ViewModel is never re-created",
            1,
            ledger.created.count { it.startsWith("chat/") },
        )
        assertTrue("nothing cleared by a flip", ledger.cleared.isEmpty())
    }

    @Test
    fun `the inspector is a pane on two panes, a peek after a shrink, and a sheet once expanded`() {
        val size = mutableStateOf(EXPANDED)
        setHost(size)
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(CHAT_A)) }
            shell.navigate { follow(it, ChatContextKey(CHAT_A)) }
        }
        val inspector = probeTag(ChatContextKey(CHAT_A))
        composeRule.onNodeWithTag(inspector).assertExists()
        composeRule.onNodeWithTag(SheetTestTags.PEEK).assertDoesNotExist()

        size.value = COMPACT
        composeRule.waitForIdle()
        // §7.4 item 2: a peek under the chat, no scrim; the chat stays on screen.
        composeRule.onNodeWithTag(SheetTestTags.PEEK).assertExists()
        composeRule.onNodeWithTag(SheetTestTags.SCRIM).assertDoesNotExist()
        composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A))).assertExists()

        // The peek's own action (a pointer click at its centre would land on the probe inside it).
        composeRule.onNodeWithTag(SheetTestTags.PEEK).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(SheetTestTags.EXPANDED).assertExists()
        composeRule.onNodeWithTag(SheetTestTags.SCRIM).assertExists()
        composeRule.onNodeWithTag(inspector).assertExists()
    }

    @Test
    fun `a restore reads the saved stacks and silently drops ids deleted meanwhile`() {
        val existing = mutableMapOf(CHAT_A to ObjectKind.CHAT, NOTE_B to ObjectKind.NOTE)
        val asked = mutableListOf<Set<SkeinId>>()
        val restorer = StateRestorationTester(composeRule)
        restorer.setContent {
            SkeinTheme {
                shell = rememberSkeinShellState(manager)
                SkeinShellHost(shell, { ids ->
                    asked += ids
                    existing.filterKeys { it in ids }
                }) { Probe(it, ledger) }
            }
        }
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(CHAT_A)) }
            shell.navigate { follow(it, ChatContextKey(CHAT_A)) }
            shell.navigate { goTo(it, NewNoteKey(DRAFT_D)) }
            shell.navigate { follow(it, NoteKey(NOTE_B)) }
            shell.navigate { switchTo(it, Destination.CHAT) }
        }
        val before = shell

        // The note is deleted while the process is gone (SECURITY_REVIEW_D7.md N7).
        existing.remove(NOTE_B)
        restorer.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertNotSame("restored from the saved state", before, shell)
        assertEquals(Destination.CHAT, shell.nav.topLevel)
        assertEquals(listOf(ChatHomeKey, ChatKey(CHAT_A), ChatContextKey(CHAT_A)), shell.nav.stack(Destination.CHAT))
        assertEquals(listOf(KnowledgeHomeKey, NewNoteKey(DRAFT_D)), shell.nav.stack(Destination.KNOWLEDGE))
        assertEquals("draft ids are never looked up", setOf(CHAT_A, NOTE_B), asked.last())
    }

    @Test
    fun `the container's destinations are the navigation destinations, in rail order`() {
        assertEquals(Destination.entries.map { it.name }, SkeinDestination.entries.map { it.name })
    }

    private fun setHost(size: MutableState<DpSize>) {
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                    shell = rememberSkeinShellState(manager)
                    SkeinShellHost(shell, { ids -> ids.associateWith { ObjectKind.CHAT } }) { Probe(it, ledger) }
                }
            }
        }
    }

    private fun textOf(tag: String): String =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()
}
