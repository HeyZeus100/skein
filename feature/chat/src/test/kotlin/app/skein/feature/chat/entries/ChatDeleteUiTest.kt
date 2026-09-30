package app.skein.feature.chat.entries

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Prompt
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.chat.ChatKnowledge
import app.skein.feature.chat.ChatTurnController
import app.skein.feature.chat.ChatTurnState
import app.skein.feature.chat.SendPipeline
import app.skein.feature.chat.SimplePromptAssembler
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.FakeInferenceEngine
import app.skein.testing.FakeRetrievalService
import app.skein.testing.fakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class ChatDeleteUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var first: Document
    private lateinit var second: Document
    private val vault =
        fakeVault {
            first = chat("First saved chat", Role.USER to "First question")
            second = chat("Second saved chat", Role.USER to "Second question")
        }
    private val requested = mutableListOf<DocId>()
    private lateinit var shell: SkeinShellState

    private fun setHost(
        onDelete: ((DocId) -> Unit)? = { requested += it },
        turns: ChatTurnController? = null,
        pipeline: SendPipeline = pipelineOver(vault, FakeInferenceEngine()),
        expanded: Boolean = false,
    ) {
        composeRule.setContent {
            SkeinTheme {
                EntriesHost(
                    vault,
                    pipeline,
                    if (expanded) EXPANDED else COMPACT,
                    { shell = it },
                    onDelete = onDelete,
                    turnController = turns,
                )
            }
        }
    }

    private fun open(chat: Document) =
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(chat.id))) } }

    private fun historyRow(chat: Document) =
        composeRule.onNode(hasText(chat.title) and hasAnyAncestor(hasTestTag(ChatEntryTestTags.CONVERSATIONS)))

    @Test
    fun `saved chat header only requests confirmation with its exact id`() {
        setHost()
        open(first)
        composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).performClick()
        composeRule.onNodeWithText("Delete…").assertIsEnabled()
        assertTrue(requested.isEmpty())
        composeRule.onNodeWithText("Delete…").performClick()
        assertEquals(listOf(first.id), requested)
        assertNotNull(runBlocking { vault.getDocument(first.id) })
        assertEquals(1, runBlocking { vault.listMessages(first.id).size })
    }

    @Test
    fun `history long press requests that row without opening or deleting it`() {
        setHost(expanded = true)
        historyRow(first).performTouchInput { longClick() }
        composeRule.onNodeWithText("Delete…").performClick()
        assertEquals(listOf(first.id), requested)
        assertNotNull(runBlocking { vault.getDocument(first.id) })
        assertTrue(shell.nav.stack(app.skein.core.navigation.Destination.CHAT).none { it is ChatKey })
    }

    @Test
    fun `no requester keeps existing saved chat header unchanged`() {
        setHost(onDelete = null)
        open(first)
        composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).assertDoesNotExist()
    }

    @Test
    fun `unsaved new chat and deleted stored row expose no delete action`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, NewChatKey(SkeinId.random())) } }
        composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).assertDoesNotExist()
        open(first)
        composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).assertIsDisplayed()
        runBlocking { vault.deleteDocument(first.id) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).assertDoesNotExist()
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `real active and queued turns disable header and history until stopped`() {
        runBlocking {
            ChatKnowledge.setEnabled(vault, first.id, false)
            ChatKnowledge.setEnabled(vault, second.id, false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val engine =
            object : InferenceEngine by FakeInferenceEngine() {
                override fun stream(
                    prompt: Prompt,
                    params: SamplingParams,
                ): Flow<Token> =
                    flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
            }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, 3072) },
                countTokens = { it.length / 4 },
            )
        val turns = ChatTurnController(vault, pipeline, 7, { 7 }, { null }, scope)
        try {
            setHost(turns = turns, pipeline = pipeline, expanded = true)
            runBlocking { turns.enqueue(first.id, "Active question").await() }
            runBlocking { withTimeout(5000) { entered.await() } }
            runBlocking { turns.enqueue(second.id, "Queued question").await() }
            assertEquals(ChatTurnState.Queued, turns.state(second.id).value.turn)
            composeRule.waitForIdle()
            for (chat in listOf(first, second)) {
                val actions = historyRow(chat).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                assertTrue("busy chat has no Delete accessibility bypass", actions.none { it.label == "Delete" })
            }
            historyRow(second).performTouchInput { longClick() }
            composeRule.onNodeWithText("Delete…").assertIsNotEnabled().performTouchInput { click() }
            composeRule.onNodeWithText(CHAT_DELETE_BUSY_REASON, substring = true).assertIsDisplayed()
            assertTrue(requested.isEmpty())
            // Stop the queued owner while its menu is open; only that menu becomes enabled.
            turns.stop(second.id)
            composeRule.waitUntil(5000) { turns.state(second.id).value.turn is ChatTurnState.Interrupted }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Delete…").assertIsEnabled().performClick()
            assertEquals(listOf(second.id), requested)
            requested.clear()
            open(first)
            composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).performClick()
            composeRule.onNodeWithText("Delete…").assertIsNotEnabled().performTouchInput { click() }
            composeRule.onNodeWithText(CHAT_DELETE_BUSY_REASON, substring = true).assertIsDisplayed()
            assertTrue(requested.isEmpty())
            turns.stop(first.id)
            composeRule.waitUntil(5000) { turns.state(first.id).value.turn is ChatTurnState.Interrupted }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Delete…").assertIsEnabled().performClick()
            assertEquals(listOf(first.id), requested)
            assertNotNull(runBlocking { vault.getDocument(first.id) })
            assertNotNull(runBlocking { vault.getDocument(second.id) })
        } finally {
            turns.close()
            scope.cancel()
        }
    }

    @Test
    fun `streaming is deletion blocked and terminal states are available`() {
        assertEquals(CHAT_DELETE_BUSY_REASON, chatDeleteDisabledReason(ChatTurnState.Streaming("partial")))
        assertEquals(null, chatDeleteDisabledReason(ChatTurnState.Done))
        assertEquals(null, chatDeleteDisabledReason(ChatTurnState.Interrupted("partial")))
        assertEquals(null, chatDeleteDisabledReason(null))
    }
}
