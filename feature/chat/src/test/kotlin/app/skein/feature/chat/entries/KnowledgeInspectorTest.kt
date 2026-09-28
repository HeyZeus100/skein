package app.skein.feature.chat.entries

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.SkeinId
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.feature.chat.ChatKnowledge
import app.skein.feature.chat.KNOWLEDGE_SWITCH_TEST_TAG
import app.skein.feature.chat.SendPipeline
import app.skein.feature.chat.contextRowTestTag
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import app.skein.testing.ui.skeinComposeRule
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KnowledgeInspectorTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    private val repository = InMemoryVaultRepository()
    private val chat = runBlocking { repository.createDocument(NewDocument(DocumentKind.CHAT, "First chat", null)) }
    private val otherChat =
        runBlocking { repository.createDocument(NewDocument(DocumentKind.CHAT, "Other chat", null)) }
    private val size = mutableStateOf(EXPANDED)
    private lateinit var shell: SkeinShellState

    private fun show(
        pipeline: SendPipeline = pipelineOver(repository, scriptedEngine()),
        vault: VaultRepository = repository,
    ) {
        composeRule.setContent {
            SkeinTheme { EntriesHost(vault, pipeline, size.value, { shell = it }) }
        }
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(chat.id))) } }
        composeRule.waitForIdle()
    }

    private fun openInspector() {
        composeRule.onNodeWithTag(ChatEntryTestTags.CONTEXT_ACTION).performClick()
        composeRule.onNodeWithTag(ChatEntryTestTags.INSPECTOR).assertIsDisplayed()
    }

    @Test
    fun `chip opens inspector without toggling and Knowledge off persists across chat reentry`() {
        show()
        composeRule
            .onNodeWithTag(ChatEntryTestTags.CONTEXT_ACTION)
            .assertContentDescriptionEquals("Context: Knowledge on. Inspect context.")
            .assertHeightIsAtLeast(48.dp)
        openInspector()
        assertTrue(runBlocking { ChatKnowledge.enabled(repository.getDocument(chat.id)!!) })
        composeRule.onNodeWithTag(KNOWLEDGE_SWITCH_TEST_TAG).assertIsOn().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(KNOWLEDGE_SWITCH_TEST_TAG).performClick()
        composeRule.waitUntil(5_000) { runBlocking { !ChatKnowledge.enabled(repository.getDocument(chat.id)!!) } }
        composeRule.onNodeWithTag(KNOWLEDGE_SWITCH_TEST_TAG).assertIsOff()
        composeRule
            .onNodeWithTag(ChatEntryTestTags.CONTEXT_ACTION)
            .assertContentDescriptionEquals("Context: Knowledge off. Inspect context.")
        composeRule
            .onNodeWithText(
                "Searches your notes and files. Changes apply to your next message.",
            ).assertIsDisplayed()

        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(otherChat.id))) } }
        composeRule
            .onNodeWithTag(ChatEntryTestTags.CONTEXT_ACTION)
            .assertContentDescriptionEquals("Context: Knowledge on. Inspect context.")
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(chat.id))) } }
        openInspector()
        composeRule.onNodeWithTag(KNOWLEDGE_SWITCH_TEST_TAG).assertIsOff()
        assertFalse(runBlocking { ChatKnowledge.enabled(repository.getDocument(chat.id)!!) })
    }

    @Test
    fun `inspector lists only assembled evidence grouped by document and never another chat's sources`() {
        val included =
            runBlocking { repository.createDocument(NewDocument(DocumentKind.NOTE, "Included note", "Evidence")) }
        val trimmed = runBlocking { repository.createDocument(NewDocument(DocumentKind.NOTE, "Trimmed note", "Large")) }
        val first = source(1, included.id, included.title, "First passage")
        val second = source(2, included.id, included.title, "Second passage")
        val tooLarge = source(3, trimmed.id, trimmed.title, "irrelevant ".repeat(2_000))
        val engine = scriptedEngine()
        runBlocking { engine.load(TEXT_MODEL).getOrThrow() }
        val pipeline =
            SendPipeline(
                vaultRepository = repository,
                retrievalService = FakeRetrievalService(listOf(first, second, tooLarge)),
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, 500) },
                countTokens = { it.length / 4 },
            )
        runBlocking { pipeline.send(chat.id, "What does my evidence say?").toList() }
        assertEquals(
            3,
            pipeline.lastOutcome.value!!
                .retrieved.size,
        )
        assertEquals(
            listOf(first, second),
            pipeline.lastOutcome.value!!
                .assembled.citations.values
                .toList(),
        )
        show(pipeline)
        openInspector()
        val inspector = hasAnyAncestor(hasTestTag(ChatEntryTestTags.INSPECTOR))
        composeRule.onNode(hasText("Included note") and inspector).assertIsDisplayed()
        composeRule.onNode(hasText("Trimmed note") and inspector).assertDoesNotExist()
        composeRule.onNodeWithText("1 source").assertIsDisplayed()
        composeRule.onNodeWithText("2 passages").assertIsDisplayed()
        composeRule.onNodeWithTag(contextRowTestTag(first)).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(contextRowTestTag(second)).assertDoesNotExist()

        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(otherChat.id))) } }
        openInspector()
        composeRule.onNode(hasText("Included note") and inspector).assertDoesNotExist()
        composeRule.onNodeWithText("Sources appear here after a reply in this session.").assertIsDisplayed()
        size.value = COMPACT
        composeRule.waitForIdle()
        composeRule
            .onNode(
                hasText("Knowledge on") and hasAnyAncestor(hasTestTag(ChatEntryTestTags.INSPECTOR_PEEK)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun `a failed preference write leaves the persisted switch on and explains the failure`() {
        val failing =
            object : VaultRepository by repository {
                override suspend fun updateFrontmatter(
                    id: String,
                    frontmatter: JsonObject,
                ): Document {
                    error("fixture write failure")
                }
            }
        show(vault = failing)
        openInspector()
        composeRule.onNodeWithTag(KNOWLEDGE_SWITCH_TEST_TAG).performClick()
        composeRule.onNodeWithText("Couldn't change Knowledge. Try again.").assertIsDisplayed()
        composeRule.onNodeWithTag(KNOWLEDGE_SWITCH_TEST_TAG).assertIsOn()
        assertTrue(runBlocking { ChatKnowledge.enabled(repository.getDocument(chat.id)!!) })
    }

    private fun source(
        chunk: Long,
        doc: String,
        title: String,
        text: String,
    ) = Retrieved(
        chunkId = chunk,
        docId = doc,
        docTitle = title,
        text = text,
        score = 1.0,
        sourceKind = DocumentKind.NOTE,
        recalledBy = setOf(RecallSource.LEXICAL),
    )
}
