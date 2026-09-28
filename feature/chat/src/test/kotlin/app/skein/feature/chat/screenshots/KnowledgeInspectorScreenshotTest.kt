package app.skein.feature.chat.screenshots

import androidx.compose.ui.test.onRoot
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.TokenBudget
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.contentKey
import app.skein.feature.chat.ChatKnowledge
import app.skein.feature.chat.SendPipeline
import app.skein.feature.chat.SimplePromptAssembler
import app.skein.feature.chat.entries.EntriesHost
import app.skein.feature.chat.entries.TEXT_MODEL
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import app.skein.testing.ui.UX_FONT_150
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnowledgeInspectorScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private lateinit var shell: SkeinShellState
    private lateinit var chatId: SkeinId

    private fun show(knowledgeOnForNextMessage: Boolean) {
        val repository = InMemoryVaultRepository(clock = { NOW })
        val note =
            runBlocking {
                repository.createDocument(
                    NewDocument(DocumentKind.NOTE, "Fold launch plan", "Complete the owner smoke test."),
                )
            }
        val chat = runBlocking { repository.createDocument(NewDocument(DocumentKind.CHAT, "Launch evidence", null)) }
        chatId = SkeinId.of(chat.id)
        val sources =
            listOf(
                Retrieved(
                    1,
                    note.id,
                    note.title,
                    "Complete the owner smoke test.",
                    1.0,
                    DocumentKind.NOTE,
                    setOf(RecallSource.LEXICAL),
                ),
                Retrieved(
                    2,
                    note.id,
                    note.title,
                    "Check both Fold displays.",
                    0.9,
                    DocumentKind.NOTE,
                    setOf(RecallSource.LEXICAL),
                ),
            )
        val engine =
            scriptedEngine(
                "What remains before launch?" to
                    listOf("The plan calls for the owner smoke test on both Fold displays [1]."),
            )
        runBlocking { engine.load(TEXT_MODEL).getOrThrow() }
        val pipeline =
            SendPipeline(
                vaultRepository = repository,
                retrievalService = FakeRetrievalService(sources),
                promptAssembler = SimplePromptAssembler(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, 3072) },
                countTokens = { it.length / 4 },
            )
        runBlocking {
            pipeline.send(chat.id, "What remains before launch?").toList()
            ChatKnowledge.setEnabled(repository, chat.id, knowledgeOnForNextMessage)
        }
        composeRule.setContent {
            SkeinTheme { EntriesHost(repository, pipeline, size = null, onShell = { shell = it }, clock = { NOW }) }
        }
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(chatId)) } }
        composeRule.waitForIdle()
    }

    @Test
    fun knowledgeOff() {
        show(knowledgeOnForNextMessage = false)
        composeRule.onRoot().captureUx(spec, "chat-knowledge-off")
    }

    @Test
    fun inspectorUsed() {
        show(knowledgeOnForNextMessage = true)
        openInspector()
        composeRule.onRoot().captureUx(spec, "chat-knowledge-inspector-used")
    }

    @Test
    fun inspectorOffKeepsPreviousAnswerSources() {
        show(knowledgeOnForNextMessage = false)
        openInspector()
        composeRule.onRoot().captureUx(spec, "chat-knowledge-inspector-off")
    }

    private fun openInspector() {
        composeRule.runOnIdle {
            val key = ChatContextKey(chatId)
            shell.navigate { follow(it, key) }
            shell.sheets.expand(key.contentKey)
        }
        composeRule.waitForIdle()
    }

    companion object {
        private const val NOW = 1_790_000_000_000L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs(*UX_FONT_150)
    }
}
