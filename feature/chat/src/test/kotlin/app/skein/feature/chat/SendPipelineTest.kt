// skein-6as (E6.I8) — `SendPipelineTest`: JVM, fakes only. Covers the plan's
// step 1 ("ordering of persist -> retrieve -> assemble -> stream ->
// persist") and the B-3 behaviors landed inside this bead
// (POCKETPAL_RECON.md PP-61/PP-63/PP-64/PP-66/PP-67).
package app.skein.feature.chat

import app.skein.core.model.Capability
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Locator
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.PersonaId
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.chat.Segment
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.SkeinLogCaptureRule
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SendPipelineTest {
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

    private fun retrievedItem(): Retrieved =
        Retrieved(
            chunkId = 1L,
            docId = "doc-1",
            docTitle = "Source Note",
            text = "the source text",
            score = 0.9,
            sourceKind = DocumentKind.NOTE,
            recalledBy = setOf(RecallSource.VECTOR),
            revisionHash = "revhash",
            locator = Locator(byteStart = 0, byteEnd = 10),
        )

    private fun budget(): TokenBudget =
        TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072)

    /** [chat] creates the chat document inline so the returned id is always available. */
    private fun newChat(): Pair<InMemoryVaultRepository, String> {
        lateinit var doc: Document
        val vault = fakeVault { doc = chat("Chat") }
        return vault to doc.id
    }

    private fun pipeline(
        vault: InMemoryVaultRepository,
        engine: InferenceEngine,
        retrievalService: RetrievalService = FakeRetrievalService(emptyList()),
    ): SendPipeline =
        SendPipeline(
            vaultRepository = vault,
            retrievalService = retrievalService,
            promptAssembler = SimplePromptAssembler(),
            engine = engine,
            personaProvider = { null },
            budgetFor = { _, _ -> budget() },
            countTokens = { it.length / 4 },
        )

    @Test
    fun `send persists user then retrieves then assembles then streams then persists assistant with citations`() =
        runTest {
            val (vault, chatId) = newChat()
            val retrieval = FakeRetrievalService(listOf(retrievedItem()))
            var userPersistedBeforeRetrieve = false
            val orderCheckingRetrieval =
                object : RetrievalService {
                    override suspend fun retrieveContext(
                        query: String,
                        k: Int,
                        personaId: PersonaId?,
                    ): List<Retrieved> {
                        userPersistedBeforeRetrieve =
                            vault.listMessages(chatId).any { it.role == Role.USER && it.contentMd == query }
                        return retrieval.retrieveContext(query, k, personaId)
                    }
                }
            val engine = scriptedEngine("q" to listOf("answer ", "[1]"))
            engine.load(textModel()).getOrThrow()

            val segments = pipeline(vault, engine, orderCheckingRetrieval).send(chatId, "q").toList()

            assertThat(userPersistedBeforeRetrieve).isTrue()
            assertThat(retrieval.lastQuery).isEqualTo("q")
            assertThat(retrieval.lastK).isEqualTo(8)

            val messages = vault.listMessages(chatId)
            assertThat(messages).hasSize(2)
            assertThat(messages[0].role).isEqualTo(Role.USER)
            assertThat(messages[0].contentMd).isEqualTo("q")
            assertThat(messages[1].role).isEqualTo(Role.ASSISTANT)

            // NORTH_STAR_REVIEW.md §3.6: citations, never retrievedChunks.
            assertThat(messages[1].citations).isNotNull()
            assertThat(messages[1].retrievedChunks).isEmpty()
            assertThat(messages[1].citations!!.cited).containsExactly(1)

            val citationSegments = segments.filterIsInstance<Segment.Citation>()
            assertThat(citationSegments).isNotEmpty()
            assertThat(citationSegments.first().marker).isEqualTo(1)

            assertThat(logCapture.captured()).isEmpty()
        }

    @Test
    fun `cancel during a high-rate stream is honored within a bounded time and persists an interrupted partial turn`() =
        runBlocking {
            val (vault, chatId) = newChat()
            val pieces = (1..500).map { "t$it " }
            val engine = scriptedEngine("q" to pieces, tokenDelay = 3.milliseconds)
            engine.load(textModel()).getOrThrow()
            val sendPipeline = pipeline(vault, engine)

            val collectJob = launch { sendPipeline.send(chatId, "q").toList() }
            delay(30)
            sendPipeline.cancel()

            withTimeout(2.seconds) { collectJob.join() }

            val outcome = sendPipeline.lastOutcome.value
            assertThat(outcome).isNotNull()
            assertThat(outcome!!.stopReason).isEqualTo(StopReason.CANCELLED)
            assertThat(outcome.interrupted).isTrue()

            val messages = vault.listMessages(chatId)
            assertThat(messages).hasSize(2)
            val assistant = messages[1]
            assertThat(assistant.contentMd).endsWith(INTERRUPTED_MARKER)
            val stripped = stripInterruptedMarker(assistant.contentMd)
            assertThat(stripped).isNotEmpty()
            // Did not run all 500 pieces to completion (500 * 3ms = 1500ms+) —
            // proves the ~30ms cancel was honored promptly rather than the
            // engine running to completion regardless.
            assertThat(stripped.trim().split(" ").size).isLessThan(500)
        }

    @Test
    fun `an empty (cancelled before any content) turn is never persisted - no ghost row`() =
        runBlocking {
            val (vault, chatId) = newChat()
            // `FakeInferenceEngine.cancel()` only affects an *in-flight*
            // stream — `stream()`'s own first line drains any signal sent
            // before it started (its own KDoc: "Drain any stale cancel
            // signal from a prior stream"), so pre-arming it here would be
            // silently discarded rather than exercising this path. A tiny
            // stub that reports `Done(CANCELLED)` with zero `Token.Text`
            // pieces is the direct, engine-contract-agnostic way to trigger
            // PP-64's "no content at all -> delete the empty turn" branch.
            val zeroContentEngine =
                object : InferenceEngine {
                    override suspend fun load(model: Model) = Result.success(Unit)

                    override fun stream(
                        prompt: Prompt,
                        params: SamplingParams,
                    ): Flow<Token> =
                        flow {
                            emit(Token.Done(StopReason.CANCELLED, 1, 0, 0L, 0f))
                        }

                    override suspend fun embed(text: String): FloatArray = FloatArray(0)

                    override suspend fun cancel() = Unit

                    override suspend fun unload() = Unit
                }
            val sendPipeline = pipeline(vault, zeroContentEngine)

            sendPipeline.send(chatId, "q").toList()

            val messages = vault.listMessages(chatId)
            assertThat(messages).hasSize(1)
            assertThat(messages[0].role).isEqualTo(Role.USER)
            assertThat(sendPipeline.lastOutcome.value?.assistantMessage).isNull()
            assertThat(sendPipeline.lastOutcome.value?.interrupted).isTrue()
        }

    @Test
    fun `an InferenceException mid-stream propagates and persists nothing beyond the user turn`() =
        runBlocking {
            val (vault, chatId) = newChat()
            val dyingEngine =
                object : InferenceEngine {
                    override suspend fun load(model: Model) = Result.success(Unit)

                    override fun stream(
                        prompt: Prompt,
                        params: SamplingParams,
                    ): Flow<Token> =
                        flow {
                            emit(Token.Text("partial", 0))
                            throw InferenceException.ServiceDied()
                        }

                    override suspend fun embed(text: String): FloatArray = FloatArray(0)

                    override suspend fun cancel() = Unit

                    override suspend fun unload() = Unit
                }
            val sendPipeline = pipeline(vault, dyingEngine)

            var thrown: InferenceException.ServiceDied? = null
            try {
                sendPipeline.send(chatId, "q").toList()
            } catch (e: InferenceException.ServiceDied) {
                thrown = e
            }
            assertThat(thrown).isNotNull()

            val messages = vault.listMessages(chatId)
            assertThat(messages).hasSize(1)
            assertThat(messages[0].role).isEqualTo(Role.USER)
        }

    @Test
    fun `context exhaustion is surfaced as the typed StopReason LENGTH, never a message string`() =
        runBlocking {
            val (vault, chatId) = newChat()
            val lengthEngine =
                object : InferenceEngine {
                    override suspend fun load(model: Model) = Result.success(Unit)

                    override fun stream(
                        prompt: Prompt,
                        params: SamplingParams,
                    ): Flow<Token> =
                        flow {
                            emit(Token.Text("partial answer", 0))
                            emit(Token.Done(StopReason.LENGTH, 10, 2, 0L, 0f))
                        }

                    override suspend fun embed(text: String): FloatArray = FloatArray(0)

                    override suspend fun cancel() = Unit

                    override suspend fun unload() = Unit
                }
            val sendPipeline = pipeline(vault, lengthEngine)
            sendPipeline.send(chatId, "q").toList()

            val outcome = sendPipeline.lastOutcome.value
            assertThat(outcome).isNotNull()
            assertThat(outcome!!.stopReason).isEqualTo(StopReason.LENGTH)
            assertThat(outcome.interrupted).isFalse()
            // The assistant turn still persists normally (LENGTH is not a
            // cancel) — the typed enum drives this, nothing inspects a message
            // string anywhere in this class.
            assertThat(vault.listMessages(chatId)).hasSize(2)
        }

    @Test
    fun `streamed segments arrive coalesced into fewer bursts than the underlying token count`() =
        runTest {
            val (vault, chatId) = newChat()
            val pieces = (1..12).flatMap { listOf("w$it ", "[1]") }
            val engine = scriptedEngine("q" to pieces, tokenDelay = 5.milliseconds)
            engine.load(textModel()).getOrThrow()
            val sendPipeline = pipeline(vault, engine, FakeRetrievalService(listOf(retrievedItem())))

            val scheduler = testScheduler
            val arrivalTimes = mutableListOf<Long>()
            sendPipeline.send(chatId, "q").collect {
                arrivalTimes += scheduler.currentTime
            }

            val distinctBursts = arrivalTimes.toSet().size
            assertThat(distinctBursts).isLessThan(arrivalTimes.size)
        }
}
