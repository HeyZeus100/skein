package app.skein.feature.chat

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Prompt
import app.skein.core.model.PromptMeasurement
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.testing.FakeRetrievalService
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SendPipelineExactBudgetTest {
    private val source =
        Retrieved(1L, "doc", "Source", "quoted evidence", 1.0, DocumentKind.NOTE, setOf(RecallSource.LEXICAL))

    @Test
    fun `exact fitting that removes all evidence returns the app reply without streaming`() =
        runTest {
            lateinit var chat: Document
            val vault = fakeVault { chat = chat("Chat") }
            var streams = 0
            val engine =
                object : InferenceEngine by scriptedEngine() {
                    override fun stream(
                        prompt: Prompt,
                        params: SamplingParams,
                    ): Flow<Token> {
                        streams++
                        error("must not stream without surviving evidence")
                    }
                }
            val pipeline =
                SendPipeline(
                    vault,
                    FakeRetrievalService(listOf(source)),
                    PromptAssemblerImpl(),
                    engine,
                    personaProvider = { null },
                    budgetFor = { _, _ -> TokenBudget(16_384, 20, 3_072) },
                    countTokens = { 0 },
                    samplingParams = { SamplingParams(maxTokens = 20) },
                    measurePrompt = { prompt, _ ->
                        PromptMeasurement(
                            if (prompt.messages
                                    .last()
                                    .content
                                    .contains("quoted evidence")
                            ) {
                                100
                            } else {
                                10
                            },
                            30,
                            "ab".repeat(32),
                        )
                    },
                )
            pipeline.send(chat.id, "q").toList()
            assertThat(streams).isEqualTo(0)
            assertThat(vault.listMessages(chat.id).map { it.role }).containsExactly(Role.USER, Role.ASSISTANT).inOrder()
            assertThat(vault.listMessages(chat.id).last().contentMd).isEqualTo(NO_KNOWLEDGE_EVIDENCE)
            assertThat(
                pipeline.lastOutcome.value!!
                    .assembled.droppedRetrievedItems,
            ).isEqualTo(1)
            assertThat(
                pipeline.lastOutcome.value!!
                    .assembled.citations,
            ).isEmpty()
        }

    @Test
    fun `cancelling during measurement cannot begin generation`() =
        runTest {
            lateinit var chat: Document
            val vault = fakeVault { chat = chat("Chat") }
            lateinit var pipeline: SendPipeline
            var measurements = 0
            pipeline =
                SendPipeline(
                    vault,
                    FakeRetrievalService(listOf(source)),
                    PromptAssemblerImpl(),
                    scriptedEngine(),
                    personaProvider = { null },
                    budgetFor = { _, _ -> TokenBudget(16_384, 20, 3_072) },
                    countTokens = { 0 },
                    samplingParams = { SamplingParams(maxTokens = 20) },
                    measurePrompt = { _, _ ->
                        measurements++
                        pipeline.cancel(chat.id)
                        PromptMeasurement(10, 30, "ab".repeat(32))
                    },
                )
            val failure = runCatching { pipeline.send(chat.id, "q").toList() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(measurements).isEqualTo(1)
            assertThat(vault.listMessages(chat.id).map { it.role }).containsExactly(Role.USER)
        }

    @Test
    fun `oversized mandatory query and failed measurement persist only user and never stream`() =
        runTest {
            for (refuse in listOf(false, true)) {
                lateinit var chat: Document
                val vault = fakeVault { chat = chat("Chat") }
                var streams = 0
                val engine =
                    object : InferenceEngine by scriptedEngine() {
                        override fun stream(
                            prompt: Prompt,
                            params: SamplingParams,
                        ): Flow<Token> {
                            streams++
                            error("must not stream a refused prompt")
                        }
                    }
                val pipeline =
                    SendPipeline(
                        vault,
                        FakeRetrievalService(listOf(source)),
                        PromptAssemblerImpl(),
                        engine,
                        personaProvider = { null },
                        budgetFor = { _, _ -> TokenBudget(16_384, 20, 3_072) },
                        countTokens = { 0 },
                        samplingParams = { SamplingParams(maxTokens = 20) },
                        measurePrompt = { _, _ ->
                            if (refuse) throw InferenceException.ServiceDied()
                            PromptMeasurement(11, 30, "ab".repeat(32))
                        },
                    )
                val failure = runCatching { pipeline.send(chat.id, "q").toList() }.exceptionOrNull()
                assertThat(
                    failure,
                ).isInstanceOf(
                    if (refuse) {
                        InferenceException.ServiceDied::class.java
                    } else {
                        InferenceException.ContextFull::class.java
                    },
                )
                assertThat(streams).isEqualTo(0)
                assertThat(vault.listMessages(chat.id).map { it.role }).containsExactly(Role.USER)
                assertThat(pipeline.lastOutcome.value).isNull()
            }
        }
}
