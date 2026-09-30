package app.skein.feature.chat

import app.skein.core.model.ContextualEvidenceMode
import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.ContextualRetrievalService
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.InferenceEngine
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Persona
import app.skein.core.model.PersonaId
import app.skein.core.model.Prompt
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.core.rag.rank.RankerConfig
import app.skein.core.rag.retrieval.RecallStages
import app.skein.core.rag.retrieval.RetrievalServiceImpl
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real production queue/send/retrieval composition over synthetic repository and inference ports. */
class ContextualSendPathTest {
    @Test
    fun `real send resolves prior committed citation and preserves raw query and original scores`() =
        runTest {
            val f = Fixture(this)
            val source = f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            val prior = f.repo.listMessages(chat)
            assertEquals(listOf(Role.USER, Role.ASSISTANT), prior.map { it.role })
            assertEquals(listOf(1), prior.last().citations?.cited)
            f.send(chat, FOLLOW_UP)

            val request = f.requests.single()
            val result = f.results.single()
            assertEquals(FOLLOW_UP, request.query)
            assertEquals(FIRST_QUERY, request.followUp?.priorUserQuery)
            assertEquals(
                source.id,
                request.followUp
                    ?.citedSources
                    ?.single()
                    ?.documentId,
            )
            assertEquals(
                source.contentHash,
                request.followUp
                    ?.citedSources
                    ?.single()
                    ?.revisionHash,
            )
            assertEquals(ContextualEvidenceMode.INDEXED, request.evidenceMode)
            assertEquals(FollowUpResolution.RESOLVED, result.resolution)
            assertEquals("$FOLLOW_UP\nCase guide\n$FIRST_QUERY", result.recallQuery)
            assertFalse(result.recallQuery.contains(GENERATED_ANSWER))
            assertEquals(result.originalCandidates, result.evidence)
            assertEquals(2, f.engine.prompts.size)
            assertEquals(
                FOLLOW_UP,
                f.engine.prompts
                    .last()
                    .messages
                    .last()
                    .content
                    .substringAfterLast("\n\nUser: "),
            )
            assertFalse(
                f.engine.prompts
                    .last()
                    .messages
                    .last()
                    .content
                    .contains(result.recallQuery),
            )
            assertEquals(FOLLOW_UP, f.reviewedQueries.last())
            assertEquals(listOf(FIRST_QUERY), f.directQueries)
            assertFalse(
                f.controller
                    .state(chat)
                    .value.outcome!!
                    .generationSkipped,
            )
            f.close()
        }

    @Test
    fun `independent named question stays direct despite a previous citation`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.send(chat, "What is the Harbor case color?")
            f.send(chat, "What is Mars and its climate?")
            assertTrue(f.requests.isEmpty())
            assertEquals(
                listOf(FIRST_QUERY, "What is the Harbor case color?", "What is Mars and its climate?"),
                f.directQueries,
            )
            f.close()
        }

    @Test
    fun `source edit before followup rejects the prior revision without repinning`() =
        runTest {
            val f = Fixture(this)
            val source = f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.repo.replaceBody(source.id, "Case color is orange. Case storage is shelf nine.")
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.SOURCE_CHANGED, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `deleted source cannot be replaced by same title source`() =
        runTest {
            val f = Fixture(this)
            val source = f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.repo.deleteDocument(source.id)
            val replacement = f.seed()
            assertFalse(source.id == replacement.id)
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.SOURCE_UNAVAILABLE, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `edit during prompt budgeting is caught before model generation`() =
        runTest {
            val f = Fixture(this)
            val source = f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.onBudget = { f.repo.replaceBody(source.id, "Case color is orange.") }
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.RESOLVED, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `delete during prompt budgeting is caught before model generation`() =
        runTest {
            val f = Fixture(this)
            val source = f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.onBudget = { f.repo.deleteDocument(source.id) }
            f.send(chat, FOLLOW_UP)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `previous Space cannot be fabricated from the new default Space`() =
        runTest {
            val f = Fixture(this)
            f.persona = Persona("first", "First", "", null, 0)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.persona = Persona("second", "Second", "", null, 0)
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            assertTrue(
                f.requests
                    .single()
                    .followUp!!
                    .citedSources
                    .isEmpty(),
            )
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `uncited assistant output cannot become a source pin`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            f.engine.answer = "The case color is gold without a citation."
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `offered but uncited second document cannot make a resolved pin ambiguous`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            f.seed("Other case")
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.RESOLVED, f.results.single().resolution)
            assertEquals(
                1,
                f.requests
                    .single()
                    .followUp!!
                    .citedSources.size,
            )
            assertEquals(
                1,
                f.results
                    .single()
                    .evidence.size,
            )
            f.close()
        }

    @Test
    fun `two actually cited documents refuse ambiguous source references`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            f.seed("Other case")
            f.engine.answer = "Both cases are stored nearby [1] [2]."
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.AMBIGUOUS_CONTEXT, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `historical cited pair without this sessions completion attestation is refused`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            val saved = f.repo.listMessages(chat)
            f.close()
            val reopened = Fixture(this, f.repo, f.store)
            reopened.send(chat, FOLLOW_UP)
            assertEquals(saved, reopened.repo.listMessages(chat).take(2))
            assertEquals(FollowUpResolution.MISSING_CONTEXT, reopened.results.single().resolution)
            reopened.assertAbstained(chat, expectedGenerations = 0)
            reopened.close()
        }

    @Test
    fun `intervening unanswered user cannot inherit older cited pair`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.repo.appendMessage(chat, NewMessage(Role.USER, "Another question with no answer"))
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `cancelled prior answer is not an attested complete source context`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            f.engine.reason = StopReason.CANCELLED
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.engine.reason = StopReason.EOS
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `unsupported followup is rejected under raw query rather than successful prior query`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            f.send(chat, "What is its manufacturer?")
            assertEquals(FollowUpResolution.RESOLVED, f.results.single().resolution)
            assertTrue(
                f.results
                    .single()
                    .originalCandidates
                    .isNotEmpty(),
            )
            assertTrue(
                f.results
                    .single()
                    .evidence
                    .isEmpty(),
            )
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `oversized prior user input is not truncated into eligible context`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, "$FIRST_QUERY " + "case ".repeat(850))
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `another chats completion does not replace this chats canonical provenance`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val first = f.chat()
            val second = f.chat()
            f.send(first, FIRST_QUERY)
            f.send(second, "What is case color?")
            f.send(first, FOLLOW_UP)
            assertEquals(
                FIRST_QUERY,
                f.requests
                    .single()
                    .followUp
                    ?.priorUserQuery,
            )
            assertEquals(FollowUpResolution.RESOLVED, f.results.single().resolution)
            f.close()
        }

    @Test
    fun `Knowledge off bypasses both contextual and direct retrieval`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            ChatKnowledge.setEnabled(f.repo, chat, false)
            f.send(chat, FOLLOW_UP)
            assertTrue(f.requests.isEmpty())
            assertEquals(listOf(FIRST_QUERY), f.directQueries)
            assertEquals(2, f.engine.prompts.size)
            f.close()
        }

    @Test
    fun `Stop while contextual retrieval returns late cannot generate or save a second answer`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            val returned = CompletableDeferred<Unit>()
            f.afterContext = { withContext(NonCancellable) { returned.await() } }
            f.controller.enqueue(chat, FOLLOW_UP).await()
            runCurrent()
            assertEquals(1, f.results.size)
            f.controller.stop(chat)
            runCurrent()
            returned.complete(Unit)
            runCurrent()
            assertEquals(1, f.engine.prompts.size)
            assertEquals(3, f.repo.listMessages(chat).size)
            assertEquals(
                Role.USER,
                f.repo
                    .listMessages(chat)
                    .last()
                    .role,
            )
            f.close()
        }

    @Test
    fun `lock while source freshness recheck returns late cannot publish context or generate`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            val entered = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            f.afterRecheck = {
                entered.complete(Unit)
                withContext(NonCancellable) { returned.await() }
            }
            f.controller.enqueue(chat, FOLLOW_UP).await()
            runCurrent()
            assertTrue(entered.isCompleted)
            f.unlocked = null
            f.locking = 7L
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            f.close()
            returned.complete(Unit)
            runCurrent()
            assertEquals(1, f.engine.prompts.size)
            assertEquals(3, f.repo.listMessages(chat).size)
            assertNull(f.pipeline.lastOutcome.value)
            assertNull(
                f.controller
                    .state(chat)
                    .value.turn,
            )
        }

    @Test
    fun `copied text and citation wrapper cannot impersonate canonical committed message IDs`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val chat = f.chat()
            f.send(chat, FIRST_QUERY)
            val saved = f.repo.listMessages(chat)
            for (message in saved) {
                f.repo.appendMessage(chat, NewMessage(message.role, message.contentMd, citations = message.citations))
            }
            f.send(chat, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            f.assertAbstained(chat)
            f.close()
        }

    @Test
    fun `session context attestation capacity is bounded and eviction refuses old followups`() =
        runTest {
            val f = Fixture(this)
            f.seed()
            val oldest = f.chat()
            f.send(oldest, FIRST_QUERY)
            repeat(32) { f.send(f.chat(), FIRST_QUERY) }
            f.send(oldest, FOLLOW_UP)
            assertEquals(FollowUpResolution.MISSING_CONTEXT, f.results.single().resolution)
            f.assertAbstained(oldest, expectedGenerations = 33)
            f.close()
        }

    private class Fixture(
        val test: TestScope,
        val repo: InMemoryVaultRepository = InMemoryVaultRepository(),
        val store: InMemoryIndexStore = InMemoryIndexStore(),
    ) {
        var persona: Persona? = null
        var unlocked: Long? = 7L
        var locking: Long? = null
        var onBudget: suspend () -> Unit = {}
        var afterContext: suspend () -> Unit = {}
        var afterRecheck: suspend () -> Unit = {}
        val requests = mutableListOf<ContextualRetrievalRequest>()
        val results = mutableListOf<ContextualRetrievalResult>()
        val directQueries = mutableListOf<String>()
        val reviewedQueries = mutableListOf<String>()
        val engine = RecordingEngine()
        private val real =
            RetrievalServiceImpl(
                store,
                repo,
                null,
                config = RankerConfig(recallWeight = 1.0, pprWeight = 0.0, neighborHops = 0),
                io = StandardTestDispatcher(test.testScheduler),
                warn = {},
                stages = RecallStages(vector = false, graph = false),
            )
        private val retrieval =
            object : ContextualRetrievalService by real {
                override suspend fun retrieveContext(request: ContextualRetrievalRequest): ContextualRetrievalResult {
                    requests += request
                    return real.retrieveContext(request).also {
                        results += it
                        afterContext()
                    }
                }

                override suspend fun retrieveContext(
                    query: String,
                    k: Int,
                    personaId: PersonaId?,
                ): List<Retrieved> {
                    directQueries += query
                    return real.retrieveContext(query, k, personaId)
                }

                override suspend fun isContextCurrent(
                    request: ContextualRetrievalRequest,
                    evidence: List<Retrieved>,
                ): Boolean = real.isContextCurrent(request, evidence).also { afterRecheck() }

                override fun acceptsEvidence(
                    query: String,
                    candidates: List<Retrieved>,
                ): Boolean {
                    reviewedQueries += query
                    return real.acceptsEvidence(query, candidates)
                }
            }
        val pipeline =
            SendPipeline(
                vaultRepository = repo,
                retrievalService = retrieval,
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { persona },
                budgetFor = { _, _ ->
                    onBudget()
                    TokenBudget(16_384, 1_024, 8_192)
                },
                countTokens = { it.length / 4 },
            )
        val controller = ChatTurnController(repo, pipeline, 7L, { unlocked }, { locking }, test.backgroundScope)

        suspend fun seed(title: String = "Case guide"): Document {
            val body = "Case color is violet. Case storage is shelf four."
            val source = repo.createDocument(NewDocument(DocumentKind.NOTE, title, body))
            store.replaceChunks(
                source.id,
                listOf(NewChunk(0, body, 12, byteStart = 0, byteEnd = body.toByteArray().size)),
                "send-path-development",
                1,
                revisionHash = source.contentHash,
            )
            return source
        }

        suspend fun chat(): DocId = repo.createDocument(NewDocument(DocumentKind.CHAT, "Chat", "")).id

        suspend fun send(
            chat: DocId,
            query: String,
        ) {
            controller.enqueue(chat, query).await()
            test.runCurrent()
            assertNotNull(controller.state(chat).value.outcome)
        }

        suspend fun assertAbstained(
            chat: DocId,
            expectedGenerations: Int = 1,
        ) {
            assertEquals(expectedGenerations, engine.prompts.size)
            assertTrue(
                controller
                    .state(chat)
                    .value.outcome!!
                    .generationSkipped,
            )
            assertEquals(NO_KNOWLEDGE_EVIDENCE, repo.listMessages(chat).last().contentMd)
            assertTrue(
                repo
                    .listMessages(chat)
                    .last()
                    .citations!!
                    .cited
                    .isEmpty(),
            )
            assertTrue(
                repo
                    .listMessages(chat)
                    .last()
                    .citations!!
                    .retrieved
                    .isEmpty(),
            )
        }

        fun close() = controller.close()
    }

    private class RecordingEngine : InferenceEngine by scriptedEngine() {
        var answer = GENERATED_ANSWER
        var reason = StopReason.EOS
        val prompts = mutableListOf<Prompt>()

        override fun stream(
            prompt: Prompt,
            params: SamplingParams,
        ): Flow<Token> {
            prompts += prompt
            return flowOf(Token.Text(answer, 1), Token.Done(reason, 0, 1, 0, 0f))
        }
    }

    private companion object {
        const val FIRST_QUERY = "What is case storage?"
        const val FOLLOW_UP = "What is its color?"
        const val GENERATED_ANSWER = "The case is stored beside giant zebras [1]."
    }
}
