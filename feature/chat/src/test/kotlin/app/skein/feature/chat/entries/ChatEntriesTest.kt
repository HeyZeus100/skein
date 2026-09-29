// skein-xtov.24.8 (AL-09a): the Chat entries in the real NavDisplay shell —
// a conversation's state across a live fold (ADAPTIVE_LAYOUT_SPEC.md Test G),
// sources opened by kind (LC-20), the gone state and the prune after a delete
// (OBJECT_LIFECYCLE_SPEC.md §3.5, §4.7), and create-on-first-send (CHAT_UX_SPEC.md §11.4).
package app.skein.feature.chat.entries

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.Role
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.SkeinId
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.SEND_BUTTON_TEST_TAG
import app.skein.feature.editor.entries.FileRouteTestTags
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.shell.container.ChatHistoryItem
import app.skein.feature.shell.host.EntryChromeTestTags
import app.skein.feature.shell.host.SheetTestTags
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.rememberSkeinShellState
import app.skein.testing.FakeClock
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatEntriesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var launchPlan: Document
    private lateinit var spec: Document
    private lateinit var fold: Document
    private lateinit var quant: Document
    private val clock = FakeClock(1_790_000_000_000L)
    private val vault =
        fakeVault(clock = { clock.advanceBy(MINUTE) }) {
            launchPlan = note("Fold launch plan", "Targets M2 for the ask path.")
            spec = attachment("spec.pdf", "application/pdf", byteArrayOf(1, 2, 3))
            note(
                "spec",
                "Extracted text of the spec.",
                frontmatter =
                    JsonObject(
                        mapOf(
                            FrontmatterKeys.SOURCE to JsonPrimitive(spec.id),
                        ),
                    ),
            )
            quant = chat("Chat about quantisation", Role.USER to "Is Q3_K_M good enough?", Role.ASSISTANT to "Yes.")
            fold = chat("Fold launch", Role.USER to "What blocks M2?", Role.ASSISTANT to "The smoke test.")
        }
    private val engine = scriptedEngine("What blocks the release?" to listOf("Only ", "the smoke test."))
    private val size = mutableStateOf(COMPACT)
    private lateinit var shell: SkeinShellState

    private fun setHost() {
        runBlocking { engine.load(TEXT_MODEL).getOrThrow() }
        val pipeline = pipelineOver(vault, engine)
        composeRule.setContent { SkeinTheme { EntriesHost(vault, pipeline, size.value, { shell = it }) } }
        composeRule.waitForIdle()
    }

    private fun id(document: Document) = SkeinId.of(document.id)

    @Test
    fun `a conversation keeps its composer draft across a Compact to Expanded flip and back`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(id(fold))) } }
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("half a thought")

        size.value = EXPANDED
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatEntryTestTags.CONVERSATIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assert(hasText("half a thought"))

        size.value = COMPACT
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatEntryTestTags.CONVERSATIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assert(hasText("half a thought"))
    }

    @Test
    fun `composer line budget follows a live short-window resize without replacing its draft`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(id(fold))) } }
        val draft = (1..24).joinToString("\n") { "Draft line $it" }
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(draft)
        val tallHeight =
            composeRule
                .onNodeWithTag(COMPOSER_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        size.value = DpSize(994.dp, 443.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assert(hasText(draft)).assertIsFocused()
        val shortHeight =
            composeRule
                .onNodeWithTag(COMPOSER_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("short window halves the six-line compact budget", shortHeight <= tallHeight / 2f + 4f)
        size.value = COMPACT
        composeRule.waitForIdle()
        assertEquals(
            tallHeight,
            composeRule
                .onNodeWithTag(COMPOSER_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height,
            4f,
        )
    }

    @Test
    fun `the inspector opens beside the chat on Expanded and folds to a peek under it`() {
        size.value = EXPANDED
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(id(fold))) } }
        composeRule.onNodeWithTag(ChatEntryTestTags.CONTEXT_ACTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatEntryTestTags.INSPECTOR).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertIsDisplayed()

        size.value = COMPACT
        composeRule.waitForIdle()
        // The peek row is merged into the sheet scene's clickable peek.
        composeRule.onNodeWithTag(SheetTestTags.PEEK).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatEntryTestTags.INSPECTOR_PEEK, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(ChatEntryTestTags.INSPECTOR).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `a source opens by kind - a note in the editor, a file in the viewer`() {
        setHost()
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(id(fold))) }
            shell.navigate { openSource(it, id(fold), launchPlan.id) }
        }
        composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(FileRouteTestTags.ROOT).assertDoesNotExist()

        composeRule.runOnIdle { shell.navigate { openSource(it, id(fold), spec.id) } }
        composeRule.onNodeWithTag(FileRouteTestTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithText("Extracted text of the spec.").assertIsDisplayed()
        assertEquals("both sources were pushed onto the chat", 4, shell.nav.stack(Destination.CHAT).size)
    }

    @Test
    fun `a deleted open chat shows its gone state, and Go to Chats prunes it to the landing`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(id(fold))) } }
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertIsDisplayed()

        runBlocking { vault.deleteDocument(fold.id) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EntryChromeTestTags.GONE).assertIsDisplayed()
        composeRule.onNodeWithText("This chat was deleted.").assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertDoesNotExist()

        composeRule.onNodeWithText("Go to Chats").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(ChatHomeKey), shell.nav.stack(Destination.CHAT))
        composeRule.onNodeWithTag(ChatEntryTestTags.LANDING).assertIsDisplayed()
    }

    @Test
    fun `a deleted source's gone state returns to the chat it was opened from`() {
        setHost()
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(id(fold))) }
            shell.navigate { openSource(it, id(fold), launchPlan.id) }
        }
        composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertIsDisplayed()

        runBlocking { vault.deleteDocument(launchPlan.id) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("This note was deleted.").assertIsDisplayed()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ChatHomeKey, ChatKey(id(fold))), shell.nav.stack(Destination.CHAT))
        assertEquals(Destination.CHAT, shell.nav.topLevel)
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `deleting the open chat on Expanded keeps the list and opens no other chat`() {
        size.value = EXPANDED
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(id(fold))) } }
        composeRule.onNodeWithText("Chat about quantisation").assertIsDisplayed()

        runBlocking { vault.deleteDocument(fold.id) }
        composeRule.runOnIdle { shell.navigate { prune(it, fold.id) } }
        composeRule.waitForIdle()

        assertEquals(listOf(ChatHomeKey), shell.nav.stack(Destination.CHAT))
        composeRule.onNodeWithTag(ChatEntryTestTags.CONVERSATIONS).assertIsDisplayed()
        val inList = hasAnyAncestor(hasTestTag(ChatEntryTestTags.CONVERSATIONS))
        composeRule.onNode(hasText("Fold launch") and inList).assertDoesNotExist()
        composeRule.onNode(hasText("Chat about quantisation") and inList).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatEntryTestTags.LANDING).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assert(hasText(""))
    }

    @Test
    fun `the landing's first send creates the chat and the new chat sends the message`() {
        setHost()
        composeRule.onNodeWithTag(ChatEntryTestTags.LANDING).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("What blocks the release?")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(ChatEntryTestTags.LANDING).fetchSemanticsNodes().isEmpty()
        }
        val stack = shell.nav.stack(Destination.CHAT)
        assertEquals(2, stack.size)
        val created = runBlocking { vault.getDocument((stack[1] as ChatKey).chatId.value) }
        assertNotNull(created)
        assertEquals(DocumentKind.CHAT, created!!.kind)
        assertEquals("What blocks the release?", created.title)
        composeRule.waitUntil(WAIT_MILLIS) { runBlocking { vault.listMessages(created.id) }.size == 2 }
        assertEquals("What blocks the release?", runBlocking { vault.listMessages(created.id) }.first().contentMd)
    }

    @Test
    fun `first send binds the new chat to the selected Space`() {
        setHost()
        val selected = SkeinId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!!
        composeRule.runOnIdle { shell.navigate { switchSpace(it, selected) } }
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("What blocks the release?")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()
        composeRule.waitUntil(WAIT_MILLIS) { shell.nav.stack(Destination.CHAT).last() is ChatKey }
        val key = shell.nav.stack(Destination.CHAT).last() as ChatKey
        val created = runBlocking { vault.getDocument(key.chatId.value) }!!
        assertEquals(selected.value, created.personaId)
    }

    @Test
    fun `the history lists every chat, newest first, the open one selected`() {
        var history = emptyList<ChatHistoryItem>()
        composeRule.setContent {
            shell = rememberSkeinShellState(remember { idleUnlockManager() })
            history = rememberChatHistory(vault, shell)
        }
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(id(quant))) } }
        composeRule.waitForIdle()
        assertEquals(listOf(fold.id, quant.id), history.map { it.id })
        assertEquals(listOf(false, true), history.map { it.isSelected })
        assertEquals("no Rename or Delete until their dialogs (LC-22)", listOf(null, null), history.map { it.onDelete })
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val MINUTE = 60_000L
    }
}
