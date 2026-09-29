package app.skein.feature.shell.host

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NavMode
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.contentKey
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.runBlocking
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
class NewChatNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private val ledger = ProbeLedger()
    private val size = mutableStateOf(PHONE)
    private val scrolls = mutableMapOf<String, ScrollState>()
    private lateinit var shell: SkeinShellState
    private var fallbackBacks = 0
    private var mode: NavMode? = null

    @Test
    fun `explicit draft keeps its entry state and scroll through every layout and a second New chat is fresh`() {
        setHost()
        val first = NewChatKey(DRAFT_D)
        composeRule.runOnIdle { shell.navigate { goTo(it, first) } }
        composeRule.onNodeWithTag(probeTag(first)).performClick()
        val original = textOf(first)
        assertTrue(original, original.endsWith("t2=1"))
        composeRule.runOnIdle {
            val scroll = scrolls.getValue(first.contentKey)
            assertTrue("draft has scrollable content: ${scroll.maxValue}", scroll.maxValue > 120)
            runBlocking { scroll.scrollTo(120) }
            assertEquals("scroll moves before resizing", 120, scroll.value)
        }

        for ((window, expectedMode) in WINDOWS + WINDOWS.reversed()) {
            size.value = window
            composeRule.waitForIdle()
            assertEquals(expectedMode, mode)
            assertEquals(original, textOf(first))
            assertEquals("$expectedMode keeps scroll", 120, scrolls.getValue(first.contentKey).value)
            assertEquals(listOf(ChatHomeKey, first), shell.nav.currentStack)
        }
        assertEquals(1, ledger.created.count { it == first.contentKey })
        assertTrue("resizing must not clear the active draft entry", first.contentKey !in ledger.cleared)

        val second = NewChatKey(NOTE_B)
        composeRule.runOnIdle { shell.navigate { goTo(it, second) } }
        composeRule.onNodeWithTag(probeTag(first)).assertDoesNotExist()
        assertTrue(textOf(second).endsWith("t2=0"))
        assertEquals(0, scrolls.getValue(second.contentKey).value)
        assertEquals(listOf(ChatHomeKey, second), shell.nav.currentStack)
    }

    @Test
    fun `bare landing Back reaches the system in Phone Dual Triple`() {
        setHost()
        val draft = NewChatKey(DRAFT_D)
        composeRule.runOnIdle { shell.navigate { goTo(it, draft) } }
        for ((window, expectedMode) in WINDOWS.filter { it.second != NavMode.SINGLE }) {
            size.value = window
            composeRule.waitForIdle()
            assertEquals(expectedMode, mode)
            val before = fallbackBacks
            dispatchBack()
            assertEquals("$expectedMode must not consume a latent Back", before + 1, fallbackBacks)
            assertEquals(listOf(ChatHomeKey, draft), shell.nav.currentStack)
        }
    }

    @Test
    fun `Phone Dual Triple followed details return to the draft and existing chats pop to their root`() {
        setHost()
        val draft = NewChatKey(DRAFT_D)
        for ((window, expectedMode) in WINDOWS.filter { it.second != NavMode.SINGLE }) {
            size.value = window
            composeRule.runOnIdle {
                shell.navigate { goTo(it, draft) }
                shell.navigate { follow(it, NoteKey(NOTE_B)) }
            }
            composeRule.waitForIdle()
            assertEquals(expectedMode, mode)
            val before = fallbackBacks
            dispatchBack(expected = listOf(ChatHomeKey, draft))
            assertEquals(before, fallbackBacks)
            assertEquals(listOf(ChatHomeKey, draft), shell.nav.currentStack)
            composeRule.onNodeWithTag(probeTag(draft)).assertExists()

            composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
            dispatchBack(expected = listOf(ChatHomeKey))
            assertEquals(before, fallbackBacks)
            assertEquals(listOf(ChatHomeKey), shell.nav.stack(Destination.CHAT))
        }
    }

    private fun setHost() {
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                    BackHandler { fallbackBacks++ }
                    shell = rememberSkeinShellState(manager)
                    SkeinShellHost(shell, { ids -> ids.associateWith { ObjectKind.CHAT } }) { key ->
                        val currentMode = LocalSkeinWindowLayout.current.navMode()
                        val scroll = rememberScrollState()
                        SideEffect {
                            mode = currentMode
                            scrolls[key.contentKey] = scroll
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            Probe(key, ledger)
                            Spacer(Modifier.height(2400.dp))
                        }
                    }
                }
            }
        }
    }

    private fun dispatchBack(expected: List<SkeinKey>? = null) {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT),
            )
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        if (expected != null) {
            composeRule.waitUntil("Back reaches the expected stack", 5_000) { shell.nav.currentStack == expected }
        }
        composeRule.waitForIdle()
    }

    private fun textOf(key: NewChatKey): String =
        composeRule
            .onNodeWithTag(probeTag(key))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()

    private companion object {
        val PHONE = DpSize(524.dp, 1175.dp)
        val SINGLE = DpSize(700.dp, 900.dp)
        val DUAL = DpSize(1006.dp, 1043.dp)
        val TRIPLE = DpSize(1600.dp, 1100.dp)
        val WINDOWS =
            listOf(
                PHONE to NavMode.PHONE,
                DUAL to NavMode.DUAL,
                TRIPLE to NavMode.TRIPLE,
                SINGLE to NavMode.SINGLE,
            )
    }
}
