// skein-6as (E6.I8) — `ChatScreenTest`: the bead's six Compose acceptance
// criteria, Robolectric (mirroring `:feature:shell`/`:feature:editor`'s own
// setup — `createAndroidComposeRule<ComponentActivity>()`, SDK 34, merged
// resources via `isIncludeAndroidResources`).
package app.skein.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skein.core.model.Capability
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Locator
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.autocomplete.wikilinkSuggestionTestTag
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.RecordedTabKind
import app.skein.testing.RecordingTabController
import app.skein.testing.SkeinLogCaptureRule
import app.skein.testing.TabAction
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val logCapture = SkeinLogCaptureRule()

    private fun textModel(): Model =
        Model(
            id = "fake-model",
            name = "Fake",
            path = "/dev/null/fake.gguf",
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1_000L,
        )

    private fun budget(): TokenBudget =
        TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072)

    private fun retrievedItem(): Retrieved =
        Retrieved(
            chunkId = 1L,
            docId = "doc-1",
            docTitle = "Source Note",
            text = "the source excerpt",
            score = 0.87,
            sourceKind = DocumentKind.NOTE,
            recalledBy = setOf(RecallSource.VECTOR),
            revisionHash = "revhash",
            locator = Locator(byteStart = 0, byteEnd = 10),
        )

    private fun newChat(): Pair<InMemoryVaultRepository, Document> {
        lateinit var doc: Document
        val vault = fakeVault { doc = chat("Chat") }
        return vault to doc
    }

    private fun tabController(recording: RecordingTabController): TabController =
        TabController { docId, title, kind -> recording.openPreview(docId, title, RecordedTabKind.valueOf(kind.name)) }

    @Test
    fun `send shows user bubble, streaming bubble, citation chip - tap opens preview, second tap shows excerpt`() {
        val (vault, doc) = newChat()
        val engine = scriptedEngine("q" to listOf("answer ", "[1]"), tokenDelay = 20.milliseconds)
        runBlocking { engine.load(textModel()).getOrThrow() }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(listOf(retrievedItem())),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> budget() },
                countTokens = { it.length / 4 },
            )
        val recording = RecordingTabController()

        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    docId = doc.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = tabController(recording),
                    wikilinkSuggest = { emptyList() },
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("q")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()

        // 1) user bubble.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("q").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("q").assertIsDisplayed()

        // 2) a streaming assistant bubble, then 3) a citation chip [1].
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(citationChipTestTag(1)).fetchSemanticsNodes().isNotEmpty()
        }

        // Tap 1: opens the source as a preview tab.
        composeRule.onNodeWithTag(citationChipTestTag(1)).performClick()
        composeRule.waitForIdle()
        val opened = recording.actions.filterIsInstance<TabAction.OpenPreview>()
        assertThat(opened).isNotEmpty()
        assertThat(opened.first().docId).isEqualTo("doc-1")

        // Tap 2 (same chip): expands the inline excerpt.
        composeRule.onNodeWithTag(citationChipTestTag(1)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithTag(citationExcerptTestTag(STREAMING_MESSAGE_ID, 1))
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule
                    .onAllNodesWithText(
                        "the source excerpt",
                        substring = true,
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    @Test
    fun `cancel during streaming keeps the partial text and persists an interrupted turn`() {
        val (vault, doc) = newChat()
        val pieces = (1..200).map { "t$it " }
        val engine = scriptedEngine("q" to pieces, tokenDelay = 15.milliseconds)
        runBlocking { engine.load(textModel()).getOrThrow() }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(emptyList()),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> budget() },
                countTokens = { it.length / 4 },
            )
        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    docId = doc.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = TabController { _, _, _ -> "tab" },
                    wikilinkSuggest = { emptyList() },
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("q")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(CANCEL_BUTTON_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CANCEL_BUTTON_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(INTERRUPTED_BADGE_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        val messages = runBlocking { vault.listMessages(doc.id) }
        assertThat(messages).hasSize(2)
        val assistant = messages[1]
        assertThat(assistant.contentMd).contains("stopReason=CANCELLED")
        assertThat(stripInterruptedMarker(assistant.contentMd)).isNotEmpty()
    }

    @Test
    fun `context panel lists the retrieved item with its score`() {
        val (vault, doc) = newChat()
        val engine = scriptedEngine("q" to listOf("answer"))
        runBlocking { engine.load(textModel()).getOrThrow() }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(listOf(retrievedItem())),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> budget() },
                countTokens = { it.length / 4 },
            )
        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    docId = doc.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = TabController { _, _, _ -> "tab" },
                    wikilinkSuggest = { emptyList() },
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("q")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("q").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CONTEXT_TOGGLE_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(contextRowTestTag(retrievedItem())).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Source Note").assertIsDisplayed()
        composeRule.onNodeWithText("score 0.87", substring = true).assertIsDisplayed()
    }

    @Test
    fun `ServiceDied shows the banner and retry re-sends the same prompt`() {
        val (vault, doc) = newChat()
        var callCount = 0
        val flakyEngine =
            object : InferenceEngine {
                override suspend fun load(model: Model) = Result.success(Unit)

                override fun stream(
                    prompt: Prompt,
                    params: SamplingParams,
                ): Flow<Token> =
                    flow {
                        callCount += 1
                        if (callCount == 1) {
                            throw InferenceException.ServiceDied()
                        }
                        emit(Token.Text("recovered answer", 0))
                        emit(Token.Done(StopReason.EOS, 1, 1, 0L, 0f))
                    }

                override suspend fun embed(text: String): FloatArray = FloatArray(0)

                override suspend fun cancel() = Unit

                override suspend fun unload() = Unit
            }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(emptyList()),
                promptAssembler = SimplePromptAssembler(),
                engine = flakyEngine,
                personaProvider = { null },
                budgetFor = { _, _ -> budget() },
                countTokens = { it.length / 4 },
            )
        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    docId = doc.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = TabController { _, _, _ -> "tab" },
                    wikilinkSuggest = { emptyList() },
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("q")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(ERROR_BANNER_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(SERVICE_DIED_BANNER_TEXT).assertIsDisplayed()

        composeRule.onNodeWithTag(RETRY_BUTTON_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("recovered answer", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val userMessages =
            runBlocking {
                vault.listMessages(
                    doc.id,
                )
            }.filter { it.role == app.skein.core.model.Role.USER }
        assertThat(userMessages).hasSize(2)
        assertThat(userMessages.map { it.contentMd }).containsExactly("q", "q")
    }

    @Test
    fun `bottom bar double bracket opens the autocomplete and selecting inserts the wikilink`() {
        val (vault, doc) = newChat()
        val engine = scriptedEngine()
        runBlocking { engine.load(textModel()).getOrThrow() }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(emptyList()),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> budget() },
                countTokens = { it.length / 4 },
            )
        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    docId = doc.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = TabController { _, _, _ -> "tab" },
                    wikilinkSuggest = { query ->
                        listOf(Suggestion(title = "Target Note")).filter {
                            it.title.contains(query, ignoreCase = true) ||
                                query.isBlank()
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("[[")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(wikilinkSuggestionTestTag(0)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(0)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("[[Target Note]]", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
