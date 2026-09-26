// skein-xtov.9 — "before" captures of the real ChatScreen (ViewModel,
// SendPipeline, bottom bar), full window inside SkeinTheme — the content
// `:app` hands `SkeinApp`'s `chatTabContent` slot, without the shell's
// command bar / tab strip above it. Conversations are seeded straight into
// the in-memory vault (with citation records) and turns are driven through
// the same fakes `ChatScreenTest` uses.
package app.skein.feature.chat.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skein.core.model.Capability
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Locator
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.feature.chat.CANCEL_BUTTON_TEST_TAG
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.CONTEXT_TOGGLE_TEST_TAG
import app.skein.feature.chat.ChatScreen
import app.skein.feature.chat.SEND_BUTTON_TEST_TAG
import app.skein.feature.chat.SendPipeline
import app.skein.feature.chat.SimplePromptAssembler
import app.skein.feature.chat.TabController
import app.skein.feature.chat.contextRowTestTag
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import com.github.takahirom.roborazzi.RoborazziActivity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    private val vault = InMemoryVaultRepository(clock = { 1_790_000_000_000L })
    private val launchPlan = runBlocking { vault.note("Fold launch plan", "Targets M2 for the ask path.") }
    private val syncDesign = runBlocking { vault.note("Sync design", "No network permission; export/import only.") }

    private val retrieved =
        listOf(
            retrieved(
                1L,
                launchPlan,
                "Targets M2 for the ask path. Owner smoke test on the Fold before tagging.",
                0.87,
            ),
            retrieved(2L, syncDesign, "No network permission, so sync is export/import only.", 0.61),
        )

    @Test
    fun chatEmpty() {
        spec.assumeStandard()
        showChat(newChat(), scriptedEngine())
        composeRule.onRoot().captureUx(spec, "chat-empty")
    }

    /** A long conversation with citations, a list and a code block; also captured at font scale 1.5 on the cover. */
    @Test
    fun chatLong() {
        showChat(longChat(), scriptedEngine())
        composeRule.onRoot().captureUx(spec, "chat-long")
    }

    /** Mid-generation: the engine has streamed part of an answer (with a citation) and is still going. */
    @Test
    fun chatStreaming() {
        spec.assumeStandard()
        showChat(longChat(), StallingEngine(listOf("The cover screen keeps ", "a single pane [1]", " and moves the ")))
        send("And on the cover screen?")
        // CitationParser only releases text when a `[n]` closes (or at the end), so the
        // text up to the citation is all that shows while the engine stalls: wait for it.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("single pane", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CANCEL_BUTTON_TEST_TAG).assertExists()
        composeRule.onRoot().captureUx(spec, "chat-streaming")
    }

    /** The "◇ context" panel open after a completed turn, listing what retrieval returned. */
    @Test
    fun chatContextOpen() {
        spec.assumeStandard()
        val engine = scriptedEngine("What is blocking M2?" to listOf("Only the owner smoke test ", "[1]", " is left."))
        runBlocking { engine.load(TEXT_MODEL).getOrThrow() }
        showChat(longChat(), engine)
        send("What is blocking M2?")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { vault.listMessages(chatId).size } == LONG_CHAT_TURNS.size + 2
        }
        composeRule.onNodeWithTag(CONTEXT_TOGGLE_TEST_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(contextRowTestTag(retrieved.first())).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().captureUx(spec, "chat-context-open")
    }

    private lateinit var chatId: String

    private fun newChat(): String =
        runBlocking { vault.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = "")).id }
            .also { chatId = it }

    private fun longChat(): String {
        val id = newChat()
        runBlocking {
            for ((role, text) in LONG_CHAT_TURNS) {
                val citations =
                    if (role == Role.ASSISTANT && "[1]" in text) {
                        CitationRecord(
                            retrieved =
                                retrieved.mapIndexed {
                                    i,
                                    r,
                                    ->
                                    r.toCitation(i + 1)
                                },
                            cited = listOf(1, 2),
                        )
                    } else {
                        null
                    }
                vault.appendMessage(id, NewMessage(role = role, contentMd = text, citations = citations))
            }
        }
        return id
    }

    private fun showChat(
        docId: String,
        engine: InferenceEngine,
    ) {
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(retrieved),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = {
                    _,
                    _,
                    ->
                    TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072)
                },
                countTokens = { it.length / 4 },
            )
        composeRule.setContent {
            SkeinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatScreen(
                        docId = docId,
                        vaultRepository = vault,
                        sendPipeline = pipeline,
                        tabController = TabController { _, _, _ -> "tab" },
                        wikilinkSuggest = { emptyList() },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun send(text: String) {
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(text)
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()
    }

    companion object {
        private val TEXT_MODEL =
            Model(
                id = "qwen2.5-3b-instruct-abliterated-q3_k_m",
                name = "qwen2.5-3b-instruct-abliterated-q3_k_m.gguf",
                path = "/dev/null/model.gguf",
                sha256 = "a".repeat(64),
                format = ModelFormat.GGUF,
                capabilities = setOf(Capability.TEXT),
                sizeBytes = 1_000L,
            )

        private val LONG_CHAT_TURNS: List<Pair<Role, String>> =
            listOf(
                Role.USER to "What did my notes say about the Fold launch plan?",
                Role.ASSISTANT to
                    "The launch plan targets **M2** for the ask path [1]. Sync stays export/import only, " +
                    "because the app has no network permission [2].",
                Role.USER to "Summarise what is left before tagging.",
                Role.ASSISTANT to
                    "Three things are open:\n\n" +
                    "1. The owner smoke test on the Pixel 9 Pro Fold [1]\n" +
                    "2. Fold/unfold keeping the draft and the stream\n" +
                    "3. A model picker that fits long GGUF names",
                Role.USER to "Show me how the posture is read in Compose.",
                Role.ASSISTANT to
                    "The shell reads it once and passes it down:\n\n" +
                    "```kotlin\n" +
                    "val posture by rememberFoldPosture()\n" +
                    "val layout = computeAdaptiveLayout(\n" +
                    "    classifyWidth(windowSizeClass), posture, layoutState,\n" +
                    ")\n" +
                    "```\n\n" +
                    "Only tabletop (half-open, horizontal hinge) changes the layout.",
                Role.USER to "Thanks. Draft a checklist note from this?",
                Role.ASSISTANT to
                    "Sure — here is a draft you can paste into a new note:\n\n" +
                    "- [ ] Smoke test on the Fold (ask path, citations)\n" +
                    "- [ ] Fold mid-generation, confirm the stream survives\n" +
                    "- [ ] Import `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf`, set as default",
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs(*UX_FONT_150)
    }
}

private suspend fun InMemoryVaultRepository.note(
    title: String,
    body: String,
): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))

private fun retrieved(
    chunkId: Long,
    document: Document,
    text: String,
    score: Double,
): Retrieved =
    Retrieved(
        chunkId = chunkId,
        docId = document.id,
        docTitle = document.title,
        text = text,
        score = score,
        sourceKind = DocumentKind.NOTE,
        recalledBy = setOf(RecallSource.VECTOR, RecallSource.LEXICAL),
        revisionHash = document.contentHash,
        locator = Locator(byteStart = 0, byteEnd = text.length),
    )

private fun Retrieved.toCitation(marker: Int): Citation =
    Citation(
        marker = marker,
        documentId = docId,
        revisionHash = revisionHash.orEmpty(),
        locator = locator ?: Locator(byteStart = 0, byteEnd = text.length),
        excerpt = text,
        sourceKind = CitationSourceKind.VECTOR,
    )

/** Streams [pieces] and then never finishes — a generation caught mid-flight. */
private class StallingEngine(
    private val pieces: List<String>,
) : InferenceEngine {
    override suspend fun load(model: Model): Result<Unit> = Result.success(Unit)

    override fun stream(
        prompt: Prompt,
        params: SamplingParams,
    ): Flow<Token> =
        flow {
            pieces.forEachIndexed { index, piece -> emit(Token.Text(text = piece, id = index)) }
            awaitCancellation()
        }

    override suspend fun embed(text: String): FloatArray = FloatArray(0)

    override suspend fun cancel() = Unit

    override suspend fun unload() = Unit
}
