package app.skein.feature.chat

import app.skein.core.model.AnswerScope
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.NewDocument
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.chat.Segment
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeScopeTest {
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
    ) {
        val repository = InMemoryVaultRepository()
        val retrieval = FakeRetrievalService(sources)
        val engine = RecordingEngine()
        var onWarmUp: suspend () -> Unit = {}
        val pipeline =
            SendPipeline(
                vaultRepository = repository,
                retrievalService = retrieval,
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { null },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, retrievedBudget) },
                countTokens = { it.length / 4 },
                warmUp = { onWarmUp() },
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
            )
    }
}
