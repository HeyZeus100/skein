package app.skein.feature.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.DocumentKind
import app.skein.core.model.EngineState
import app.skein.core.model.InferenceException
import app.skein.core.model.NewDocument
import app.skein.core.model.Prompt
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.testing.FakeInferenceEngine
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w524dp-h1175dp-port-330dpi")
class ChatModelIndicatorTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `observed loading loaded generating error and unloaded render distinct accessible states`() {
        val status = mutableStateOf<ChatModelStatus>(ChatModelStatus.Unavailable)
        val turn = mutableStateOf<ChatTurnState?>(null)
        composeRule.setContent { SkeinTheme { ChatModelIndicator(status.value, turn.value) } }
        composeRule.onNodeWithTag(CHAT_MODEL_STATUS_TEST_TAG).assertDoesNotExist()
        val states =
            listOf(
                EngineState.UNLOADED to "No model loaded",
                EngineState.LOADING to "Loading",
                EngineState.READY to "Loaded",
                EngineState.GENERATING to "Busy",
                EngineState.ERROR to "Model unavailable",
            )
        for ((state, label) in states) {
            composeRule.runOnIdle { status.value = ChatModelStatus.Observed(state, NAME) }
            val description = if (state == EngineState.UNLOADED) label else "$NAME. $label"
            composeRule
                .onNodeWithTag(
                    CHAT_MODEL_STATUS_TEST_TAG,
                ).assertContentDescriptionEquals(description)
                .assertIsDisplayed()
        }
        composeRule.runOnIdle { status.value = ChatModelStatus.Observed(EngineState.GENERATING, NAME) }
        for (owned in listOf(ChatTurnState.Thinking(0), ChatTurnState.Streaming("answer"))) {
            composeRule.runOnIdle { turn.value = owned }
            composeRule.onNodeWithTag(CHAT_MODEL_STATUS_TEST_TAG).assertContentDescriptionEquals("$NAME. Answering")
        }
        for (inactive in listOf(
            null,
            ChatTurnState.Queued,
            ChatTurnState.Done,
            ChatTurnState.Interrupted("partial"),
            ChatTurnState.Failed(InferenceException.Internal()),
        )) {
            composeRule.runOnIdle { turn.value = inactive }
            composeRule.onNodeWithTag(CHAT_MODEL_STATUS_TEST_TAG).assertContentDescriptionEquals("$NAME. Busy")
        }
        composeRule.runOnIdle { status.value = ChatModelStatus.Observed(EngineState.READY, NAME) }
        capture("loaded")
        composeRule.runOnIdle {
            status.value = ChatModelStatus.Observed(EngineState.GENERATING, NAME)
            turn.value = ChatTurnState.Thinking(0)
        }
        capture("answering")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `long full name remains accessible in a narrow Fold pane at large font`() {
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    ChatModelIndicator(
                        ChatModelStatus.Observed(EngineState.READY, NAME),
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag(CHAT_MODEL_STATUS_TEST_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(300.dp)
            .assertContentDescriptionEquals("$NAME. Loaded")
        val height =
            composeRule
                .onNodeWithTag(CHAT_MODEL_STATUS_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertThat(height).isAtMost(with(composeRule.density) { 80.dp.toPx() })
        capture("narrow-font2")
    }

    @Test
    @Config(qualifiers = "w1006dp-h1043dp-port-330dpi")
    fun `two real chat screens attribute only the controller active chat and stop clears answering`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vault = InMemoryVaultRepository()
        val first = runBlocking { vault.createDocument(chat("First")) }
        val second = runBlocking { vault.createDocument(chat("Second")) }
        val entered = CompletableDeferred<Unit>()
        val engine =
            object : app.skein.core.model.InferenceEngine by FakeInferenceEngine() {
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
        val controller = ChatTurnController(vault, pipeline, 7, { 7 }, { null }, scope)
        try {
            composeRule.setContent {
                SkeinTheme {
                    Column(Modifier.fillMaxSize()) {
                        for (chat in listOf(first, second)) {
                            Column(Modifier.weight(1f).testTag(chat.id)) {
                                ChatScreen(
                                    docId = chat.id,
                                    vaultRepository = vault,
                                    sendPipeline = pipeline,
                                    onOpenSource = {},
                                    wikilinkSuggest = { emptyList() },
                                    turnController = controller,
                                    modelStatus = ChatModelStatus.Observed(EngineState.GENERATING, NAME),
                                )
                            }
                        }
                    }
                }
            }
            runBlocking { controller.enqueue(first.id, "first question").await() }
            runBlocking { withTimeout(5000) { entered.await() } }
            runBlocking { controller.enqueue(second.id, "queued question").await() }
            assertThat(controller.state(second.id).value.turn).isEqualTo(ChatTurnState.Queued)
            composeRule.waitForIdle()
            composeRule
                .onNode(
                    hasContentDescription("$NAME. Answering") and hasAnyAncestor(hasTestTag(first.id)),
                ).assertIsDisplayed()
            composeRule
                .onNode(
                    hasContentDescription("$NAME. Busy") and hasAnyAncestor(hasTestTag(second.id)),
                ).assertIsDisplayed()
            // Remove queued work first so stopping the first cannot begin a second answer.
            controller.stop(second.id)
            controller.stop(first.id)
            composeRule.waitUntil(5000) {
                composeRule.onAllNodesWithContentDescription("$NAME. Answering").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithContentDescription("$NAME. Answering").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    private fun chat(title: String) =
        NewDocument(
            DocumentKind.CHAT,
            title,
            "",
            frontmatter = JsonObject(mapOf(ChatKnowledge.KEY to JsonPrimitive(false))),
        )

    private fun capture(name: String) {
        val directory = System.getenv("SKEIN_CHAT_MODEL_CAPTURE_DIR") ?: return
        val output = File(directory, "$name.png")
        output.parentFile?.mkdirs()
        composeRule.waitForIdle()
        val bitmap = composeRule.onNodeWithTag(CHAT_MODEL_STATUS_TEST_TAG).captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val NAME = "Qwen2.5-1.5B-Instruct-Q4_K_M-full-display-name.gguf"
    }
}
