package app.skein.feature.chat

import app.skein.core.model.AnswerScope
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.Prompt
import app.skein.core.model.PromptMeasurement
import app.skein.core.model.RecallSource
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.chat.Segment
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.core.rag.retrieval.LexicalEvidenceGate
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeScopeTest {
    @Test
    fun `cancellation during final evidence review cannot publish or generate`() =
        runTest {
            for (accepted in listOf(false, true)) {
                lateinit var fixture: Fixture
                val retrieval =
                    object : RetrievalService by FakeRetrievalService(listOf(source())) {
                        override fun acceptsEvidence(
                            query: String,
                            candidates: List<Retrieved>,
                        ): Boolean {
                            runBlocking { fixture.pipeline.cancel() }
                            return accepted
                        }
                    }
                fixture = Fixture(listOf(source()), retrievalOverride = retrieval)
                val chat = fixture.chat()
                val segments = mutableListOf<Segment>()
                try {
                    fixture.pipeline.send(chat, "Which bay?").toList(segments)
                    throw AssertionError("Expected cancelled preparation")
                } catch (_: CancellationException) {
                    assertTrue(segments.isEmpty())
                    assertEquals(0, fixture.engine.calls)
                    assertEquals(null, fixture.pipeline.lastOutcome.value)
                    assertEquals(listOf(Role.USER), fixture.repository.listMessages(chat).map { it.role })
                }
            }
        }

    @Test
    fun `budget removing the only supporting chunk cannot leave weak sources authorized`() =
        runTest {
            val query = "What is the satellite frequency?"
            val weak = source().copy(text = "The museum map is displayed beside reception.")
            val support =
                source().copy(
                    chunkId = 2,
                    docId = "support",
                    text = "The satellite frequency is 145 MHz. " + "Maintenance instructions. ".repeat(150),
                )
            val candidates = listOf(weak, support)
            val gate = LexicalEvidenceGate()
            assertEquals(candidates, gate.select(query, candidates))
            for ((budget, exactTrim) in listOf(256 to false, 3072 to true, 3072 to false)) {
                var reviewed = emptyList<Retrieved>()
                val retrieval =
                    object : RetrievalService by FakeRetrievalService(candidates) {
                        override fun acceptsEvidence(
                            query: String,
                            candidates: List<Retrieved>,
                        ): Boolean {
                            reviewed = candidates
                            return gate.select(query, candidates).isNotEmpty()
                        }
                    }
                val measure: (suspend (Prompt, SamplingParams) -> PromptMeasurement)? =
                    if (exactTrim) {
                        { prompt, _ ->
                            PromptMeasurement(
                                promptTokens = if (prompt.messages.any { support.text in it.content }) 2048 else 100,
                                contextLength = 2048,
                                modelSha256 = "a".repeat(64),
                            )
                        }
                    } else {
                        null
                    }
                val fixture = Fixture(candidates, budget, retrieval, measure)
                val chat = fixture.chat()
                val segments = fixture.pipeline.send(chat, query).toList()
                val outcome = fixture.pipeline.lastOutcome.value!!
                if (budget == 256 || exactTrim) {
                    assertEquals(listOf(weak), reviewed)
                    assertEquals(0, fixture.engine.calls)
                    assertEquals(listOf(Segment.Text(NO_KNOWLEDGE_EVIDENCE)), segments)
                    assertTrue(outcome.generationSkipped)
                    assertTrue(outcome.assembled.citations.isEmpty())
                    assertFalse(
                        outcome.assembled.prompt.messages.any {
                            weak.text in it.content ||
                                support.text in it.content
                        },
                    )
                    assertEquals(2, outcome.assembled.droppedRetrievedItems)
                    assertEquals(null, outcome.assembled.formattedTokens)
                    assertTrue(
                        fixture.repository
                            .listMessages(chat)
                            .last()
                            .citations
                            ?.retrieved
                            .isNullOrEmpty(),
                    )
                    assertTrue(
                        fixture.repository
                            .listMessages(chat)
                            .last()
                            .citations
                            ?.cited
                            .isNullOrEmpty(),
                    )
                } else {
                    assertEquals(candidates, reviewed)
                    assertEquals(1, fixture.engine.calls)
                    assertFalse(outcome.generationSkipped)
                    assertEquals(
                        2,
                        fixture.repository
                            .listMessages(chat)
                            .last()
                            .citations
                            ?.retrieved
                            ?.size,
                    )
                    assertEquals(
                        candidates,
                        outcome.assembled.citations.values
                            .toList(),
                    )
                }
            }
        }

    @Test
    fun `missing and fully trimmed evidence never invoke generation or invent citations`() =
        runTest {
            for (retrieved in listOf(emptyList(), listOf(source()))) {
                val fixture = Fixture(retrieved, retrievedBudget = 0)
                val chat = fixture.chat()
                val segments = fixture.pipeline.send(chat, "Which bay is assigned?").toList()

                assertEquals(listOf(Segment.Text(NO_KNOWLEDGE_EVIDENCE)), segments)
                assertEquals(0, fixture.engine.calls)
                assertEquals(1, fixture.retrieval.callCount)
                val messages = fixture.repository.listMessages(chat)
                assertEquals(listOf(Role.USER, Role.ASSISTANT), messages.map { it.role })
                assertEquals(NO_KNOWLEDGE_EVIDENCE, messages.last().contentMd)
                assertTrue(
                    messages
                        .last()
                        .citations
                        ?.cited
                        .isNullOrEmpty(),
                )
                assertTrue(
                    fixture.pipeline.lastOutcome.value!!
                        .generationSkipped,
                )
                assertTrue(
                    fixture.pipeline.lastOutcome.value!!
                        .assembled.citations
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `Knowledge off skips retrieval and preserves unrelated chat preferences`() =
        runTest {
            val fixture = Fixture(listOf(source()))
            val chat = fixture.chat()
            ChatKnowledge.setEnabled(fixture.repository, chat, false)
            val document = fixture.repository.getDocument(chat)!!
            assertEquals(JsonPrimitive("keep me"), document.frontmatter["custom"])
            assertFalse(ChatKnowledge.enabled(document))

            fixture.pipeline.send(chat, "Explain a rainbow").toList()

            assertEquals(0, fixture.retrieval.callCount)
            assertEquals(1, fixture.engine.calls)
            val outcome = fixture.pipeline.lastOutcome.value!!
            assertEquals(AnswerScope.GENERAL, outcome.answerScope)
            assertTrue(outcome.assembled.citations.isEmpty())
            assertFalse(outcome.generationSkipped)
            assertTrue(
                fixture.engine.prompt!!
                    .messages
                    .first()
                    .content
                    .contains("Knowledge is off"),
            )
            assertFalse(
                fixture.engine.prompt!!
                    .messages
                    .any { source().text in it.content },
            )
        }

    @Test
    fun `scope is captured before warmup and changing preference applies to the next turn`() =
        runTest {
            val fixture = Fixture(listOf(source()))
            val chat = fixture.chat()
            fixture.onWarmUp = { ChatKnowledge.setEnabled(fixture.repository, chat, false) }
            fixture.pipeline.send(chat, "Which bay?").toList()
            assertEquals(
                AnswerScope.KNOWLEDGE,
                fixture.pipeline.lastOutcome.value!!
                    .answerScope,
            )
            assertEquals(
                listOf(source()),
                fixture.pipeline.lastOutcome.value!!
                    .assembled.citations.values
                    .toList(),
            )
            assertEquals(1, fixture.retrieval.callCount)

            fixture.pipeline.send(chat, "Now explain rainbows").toList()
            assertEquals(
                AnswerScope.GENERAL,
                fixture.pipeline.lastOutcome.value!!
                    .answerScope,
            )
            assertEquals(1, fixture.retrieval.callCount)
            assertEquals(4, fixture.repository.listMessages(chat).size)
        }

    private class Fixture(
        sources: List<Retrieved>,
        retrievedBudget: Int = 3072,
        retrievalOverride: RetrievalService? = null,
        measurePrompt: (suspend (Prompt, SamplingParams) -> PromptMeasurement)? = null,
    ) {
        val repository = InMemoryVaultRepository()
        val retrieval = FakeRetrievalService(sources)
        val engine = RecordingEngine()
        var onWarmUp: suspend () -> Unit = {}
        val pipeline =
            SendPipeline(
                vaultRepository = repository,
                retrievalService = retrievalOverride ?: retrieval,
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, retrievedBudget) },
                countTokens = { it.length / 4 },
                warmUp = { onWarmUp() },
                measurePrompt = measurePrompt,
            )

        suspend fun chat(): String =
            repository
                .createDocument(
                    NewDocument(
                        kind = DocumentKind.CHAT,
                        title = "Fixture chat",
                        bodyMd = null,
                        frontmatter = JsonObject(mapOf("custom" to JsonPrimitive("keep me"))),
                    ),
                ).id
    }

    private class RecordingEngine : InferenceEngine by scriptedEngine() {
        var calls = 0
        var prompt: Prompt? = null

        override fun stream(
            prompt: Prompt,
            params: SamplingParams,
        ): Flow<Token> {
            calls++
            this.prompt = prompt
            return flowOf(Token.Text("Fixture answer", 1), Token.Done(StopReason.EOS, 0, 1, 0, 0f))
        }
    }

    private companion object {
        fun source() =
            Retrieved(
                chunkId = 1,
                docId = "source-note",
                docTitle = "Assignment",
                text = "The Zefra crate is assigned to bay 74.",
                score = 0.9,
                sourceKind = DocumentKind.NOTE,
                recalledBy = setOf(RecallSource.LEXICAL),
                revisionHash = "synthetic-revision",
                locator = Locator(0, 39, 0),
            )
    }
}
