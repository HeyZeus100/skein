package app.skein.feature.chat

import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.NewDocument
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSpaceBindingTest {
    @Test
    fun `chat owner instructions retrieval and model stay fixed while the selected Space changes`() =
        runTest {
            val fixture = Fixture()
            val chat = fixture.chat(WORK.id)
            fixture.onPrepare = {
                fixture.current = PERSONAL
                fixture.work = WORK.copy(systemPrompt = "Changed during loading", defaultModel = "later-model")
            }

            fixture.pipeline.send(chat, "Which bay?").toList()

            assertEquals(WORK, fixture.prepared)
            assertEquals(WORK.id, fixture.retrieval.lastPersonaId)
            val outcome = fixture.pipeline.lastOutcome.value!!
            assertEquals(WORK, outcome.persona)
            assertEquals(WORK.defaultModel, outcome.modelId)
            assertEquals(
                WORK.defaultModel,
                fixture.repository
                    .listMessages(chat)
                    .last()
                    .modelId,
            )
            assertTrue(
                outcome.assembled.prompt.messages
                    .first()
                    .content
                    .contains(WORK.systemPrompt!!),
            )
            assertTrue(
                !outcome.assembled.prompt.messages
                    .first()
                    .content
                    .contains("Changed during loading"),
            )

            fixture.pipeline.send(chat, "And now?").toList()
            assertEquals(
                fixture.work,
                fixture.pipeline.lastOutcome.value!!
                    .persona,
            )
            assertEquals(
                "later-model",
                fixture.repository
                    .listMessages(chat)
                    .last()
                    .modelId,
            )
        }

    @Test
    fun `a second chat cannot change models or persist a user turn while the first prepares`() =
        runTest {
            val fixture = Fixture()
            val first = fixture.chat(WORK.id)
            val second = fixture.chat(PERSONAL.id)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.onPrepare = {
                entered.complete(Unit)
                release.await()
            }
            val active = async { fixture.pipeline.send(first, "First").toList() }
            entered.await()

            val failure = runCatching { fixture.pipeline.send(second, "Second").toList() }.exceptionOrNull()
            assertTrue(failure is InferenceException.Busy)
            assertTrue(fixture.repository.listMessages(second).isEmpty())
            assertEquals(WORK, fixture.prepared)
            fixture.pipeline.cancel(second)
            assertEquals(0, fixture.engine.cancels)
            fixture.pipeline.cancel(first)
            assertEquals(1, fixture.engine.cancels)

            release.complete(Unit)
            active.await()
            fixture.pipeline.send(second, "Second after completion").toList()
            assertEquals(PERSONAL, fixture.prepared)
            assertEquals(2, fixture.repository.listMessages(second).size)
        }

    @Test
    fun `missing chat owner fails before persistence rather than using the default Space`() =
        runTest {
            val fixture = Fixture()
            val chat = fixture.chat("deleted-space")
            val failure = runCatching { fixture.pipeline.send(chat, "Question").toList() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(fixture.repository.listMessages(chat).isEmpty())
            assertEquals(0, fixture.retrieval.callCount)
        }

    private class Fixture {
        val repository = InMemoryVaultRepository()
        val engine = RecordingEngine()
        val retrieval = FakeRetrievalService(listOf(SOURCE))
        var current = PERSONAL
        var work = WORK
        var prepared: Persona? = null
        var onPrepare: suspend () -> Unit = {}
        val pipeline =
            SendPipeline(
                vaultRepository = repository,
                retrievalService = retrieval,
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { current },
                personaById = { id ->
                    when (id) {
                        WORK.id -> work
                        PERSONAL.id -> PERSONAL
                        else -> null
                    }
                },
                prepareModel = { persona ->
                    prepared = persona
                    onPrepare()
                    persona?.defaultModel
                },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, 3072) },
                countTokens = { it.length / 4 },
            )

        suspend fun chat(owner: String): String =
            repository
                .createDocument(
                    NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = null, personaId = owner),
                ).id
    }

    private class RecordingEngine : InferenceEngine by scriptedEngine() {
        var cancels = 0

        override suspend fun cancel() {
            cancels++
        }

        override fun stream(
            prompt: Prompt,
            params: SamplingParams,
        ): Flow<Token> = flowOf(Token.Text("Bay 74 [1]", 1), Token.Done(StopReason.EOS, 0, 1, 0, 0f))
    }

    private companion object {
        val WORK = Persona("work", "Work", "Use Work instructions", "work-model", 0)
        val PERSONAL = Persona("personal", "Personal", "Use Personal instructions", "personal-model", 1)
        val SOURCE =
            Retrieved(
                1,
                "source",
                "Assignment",
                "Bay 74",
                0.9,
                DocumentKind.NOTE,
                recalledBy = setOf(RecallSource.LEXICAL),
            )
    }
}
