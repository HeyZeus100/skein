package app.skein.feature.chat

import app.skein.core.model.AnswerScope
import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.feature.chat.drafts.DraftLoadState
import app.skein.feature.chat.drafts.SessionDraftStore
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatTurnControllerTest {
    @Test fun `queued USER is durable and engine remains FIFO across navigation and delayed idle`() =
        runTest {
            val f = Fixture(this)
            val first = f.chat()
            val second = f.chat()
            f.controller.enqueue(first, "first question").await()
            runCurrent()
            f.controller.enqueue(second, "second question").await()
            runCurrent()
            assertEquals(listOf(Role.USER), f.repo.listMessages(second).map { it.role })
            assertTrue(
                f.controller
                    .state(second)
                    .value.turn is ChatTurnState.Queued,
            )
            assertEquals(1, f.engine.prompts.size)
            val before = f.vm(first)
            runCurrent()
            f.visible("visible answer [1]")
            runCurrent()
            before.dispose()
            assertTrue(before.messages.isEmpty())
            val after = f.vm(first)
            runCurrent()
            assertTrue(after.turnState is ChatTurnState.Streaming)
            assertEquals("visible answer [1]", (after.turnState as ChatTurnState.Streaming).text)
            f.idle = CompletableDeferred()
            f.engine.finish(0)
            runCurrent()
            assertEquals(2, f.repo.listMessages(first).size)
            assertEquals(1, f.engine.prompts.size)
            f.idle.complete(Unit)
            runCurrent()
            assertEquals(2, f.engine.prompts.size)
            f.engine.finish(1)
            runCurrent()
            f.close()
        }

    @Test fun `Stop saves visible plain text before Done even while engine cancellation is delayed`() =
        runTest {
            val f = Fixture(this)
            val chat = f.chat()
            f.controller.enqueue(chat, "question").await()
            runCurrent()
            f.visible("visible partial answer")
            val view = f.controller.state(chat)
            assertEquals("visible partial answer", (view.value.turn as ChatTurnState.Streaming).text)
            assertEquals(listOf(Role.USER), f.repo.listMessages(chat).map { it.role })

            f.engine.cancelGate = CompletableDeferred()
            f.engine.collectorExit = CompletableDeferred()
            f.controller.stop(chat)
            runCurrent()

            val messages = f.repo.listMessages(chat)
            assertEquals(listOf(Role.USER, Role.ASSISTANT), messages.map { it.role })
            assertEquals("visible partial answer" + INTERRUPTED_MARKER, messages.last().contentMd)
            assertEquals("visible partial answer", (view.value.turn as ChatTurnState.Interrupted).partial)
            assertEquals(StopReason.CANCELLED, view.value.outcome?.stopReason)

            f.engine.channels[0].trySend(Token.Text(" late output", 2))
            f.controller.stop(chat)
            f.engine.cancelGate.complete(Unit)
            f.engine.collectorExit.complete(Unit)
            runCurrent()

            assertEquals(messages, f.repo.listMessages(chat))
            f.close()
        }

    @Test fun `Stop and lock share one frozen visible answer while engine exceeds 150 ms`() =
        runTest {
            val f = Fixture(this)
            val chat = f.chat()
            f.controller.enqueue(chat, "question").await()
            runCurrent()
            f.visible("visible [1]")
            val view = f.controller.state(chat)
            assertEquals("visible [1]", (view.value.turn as ChatTurnState.Streaming).text)
            f.engine.cancelGate = CompletableDeferred()
            f.engine.collectorExit = CompletableDeferred()
            f.idle = CompletableDeferred()
            f.controller.stop(chat)
            f.locking = 7L
            f.unlocked = null
            f.controller.onLockingHigh(7L, 500L)
            f.engine.channels[0].trySend(Token.Text("private late output [1]", 2))
            val began = testScheduler.currentTime
            f.controller.onLockingLow(7L, 500L)
            assertEquals(150L, testScheduler.currentTime - began)
            val messages = f.repo.listMessages(chat)
            assertEquals(2, messages.size)
            assertEquals("visible [1]" + INTERRUPTED_MARKER, messages.last().contentMd)
            assertEquals(
                1,
                messages
                    .last()
                    .citations!!
                    .retrieved.size,
            )
            f.controller.onLockingLow(7L, 500L)
            assertEquals(2, f.repo.listMessages(chat).size)
            f.controller.close()
            assertNull(view.value.turn)
            assertNull(view.value.outcome)
            assertTrue(view.value.citations.isEmpty())
            f.engine.cancelGate.complete(Unit)
            f.engine.collectorExit.complete(Unit)
            f.idle.complete(Unit)
            runCurrent()
            assertEquals(2, f.repo.listMessages(chat).size)
        }

    @Test fun `blank lock saves no ghost and retry after a new epoch reuses durable USER`() =
        runTest {
            val f = Fixture(this)
            val chat = f.chat()
            f.controller.enqueue(chat, "question").await()
            runCurrent()
            val user = f.repo.listMessages(chat).single()
            f.unlocked = null
            f.locking = 7L
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            f.close()
            assertEquals(listOf(Role.USER), f.repo.listMessages(chat).map { it.role })
            val next = Fixture(this, f.repo, epoch = 8L)
            val vm = next.vm(chat)
            runCurrent()
            assertEquals(ChatBanner.NO_ANSWER_SAVED, vm.banner)
            assertTrue(vm.canRetry)
            vm.retry()
            runCurrent()
            next.engine.channels[0].send(Token.Text("recovered [1]", 1))
            next.engine.finish(0)
            runCurrent()
            assertEquals(listOf(Role.USER, Role.ASSISTANT), f.repo.listMessages(chat).map { it.role })
            assertEquals(
                user.id,
                f.repo
                    .listMessages(chat)
                    .first()
                    .id,
            )
            next.close()
        }

    @Test fun `pending delete suppresses partial and close synchronously clears a stopped view`() =
        runTest {
            val f = Fixture(this)
            val chat = f.chat()
            val vm = f.vm(chat)
            f.controller.enqueue(chat, "question").await()
            runCurrent()
            f.visible("visible [1]")
            assertFalse(vm.messages.isEmpty())
            f.controller.markDeleting(chat)
            f.unlocked = null
            f.locking = 7L
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            assertEquals(1, f.repo.listMessages(chat).size)
            f.close()
            // No lifecycle/recomposition/scheduler advancement needed to clear old UI holders.
            assertTrue(vm.messages.isEmpty())
            assertNull(vm.turnState)
            assertTrue(vm.streamingCitations.isEmpty())
            runCurrent()
            assertTrue(vm.messages.isEmpty())
        }

    @Test fun `lock removes queued turns and old epoch cannot dispatch after delayed cancel`() =
        runTest {
            val f = Fixture(this)
            val first = f.chat()
            val second = f.chat()
            f.controller.enqueue(first, "first").await()
            f.controller.enqueue(second, "second").await()
            runCurrent()
            f.engine.cancelGate = CompletableDeferred()
            f.unlocked = null
            f.locking = 7L
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            f.close()
            f.engine.cancelGate.complete(Unit)
            f.unlocked = 8L
            runCurrent()
            assertEquals(1, f.engine.prompts.size)
            assertEquals(1, f.repo.listMessages(second).size)
            assertNull(
                f.controller
                    .state(second)
                    .value.turn,
            )
        }

    @Test fun `admission captures Space model and parameters and history ends before committed USER`() =
        runTest {
            val f = Fixture(this)
            val first = f.chat()
            val second = f.chat()
            f.controller.enqueue(first, "first").await()
            f.controller.enqueue(second, "second").await()
            runCurrent()
            f.persona = f.persona.copy(systemPrompt = "Changed instructions", defaultModel = "different-model")
            f.repo.appendMessage(second, NewMessage(Role.USER, "later user must not enter earlier history"))
            f.engine.finish(0)
            runCurrent()
            assertEquals(listOf("original-model", "original-model"), f.preparedModels)
            val prompt = f.engine.prompts[1]
            assertTrue(
                prompt.messages
                    .first()
                    .content
                    .contains("Original instructions"),
            )
            assertFalse(
                prompt.messages.any {
                    it.content.contains("Changed instructions") ||
                        it.content.contains("later user")
                },
            )
            f.engine.finish(1)
            runCurrent()
            f.close()
        }

    @Test fun `first send atomically creates chat USER and consumes draft even if navigation waiter is gone`() =
        runTest {
            val f = Fixture(this)
            val key = ChatDraftKey.New("space", "new-id")
            val state = f.drafts.state(key)
            runCurrent()
            assertTrue(state.value is DraftLoadState.Ready)
            val captured = f.drafts.update(key, ChatDraft("durable first"))!!
            val send = f.controller.enqueueNew(key, captured.version, captured.draft.text, "First chat")
            val waiter = backgroundScope.async { send.await() }
            waiter.cancel()
            val id = send.await()
            runCurrent()
            assertEquals("space", f.repo.getDocument(id)!!.personaId)
            assertTrue(ChatKnowledge.enabled(f.repo.getDocument(id)!!))
            assertEquals(
                "durable first",
                f.repo
                    .listMessages(id)
                    .single()
                    .contentMd,
            )
            assertNull(f.repo.readDraft(key))
            assertEquals("", (state.value as DraftLoadState.Ready).snapshot.draft.text)
            f.engine.finish(0)
            runCurrent()
            f.close()
        }

    @Test fun `first send with Knowledge off persists general scope before admission and never retrieves`() =
        runTest {
            val f = Fixture(this)
            val key = ChatDraftKey.New("space", "general-draft")
            val state = f.drafts.state(key)
            runCurrent()
            val captured = f.drafts.update(key, ChatDraft("Explain a rainbow"))!!
            val id =
                f.controller
                    .enqueueNew(
                        key,
                        captured.version,
                        captured.draft.text,
                        "A general question",
                        knowledgeEnabled = false,
                    ).await()
            runCurrent()

            assertFalse(ChatKnowledge.enabled(f.repo.getDocument(id)!!))
            assertEquals("space", f.repo.getDocument(id)!!.personaId)
            assertEquals(listOf(Role.USER), f.repo.listMessages(id).map { it.role })
            assertEquals(0, f.retrieval.callCount)
            assertEquals(1, f.engine.prompts.size)
            val prompt = f.engine.prompts.single()
            assertTrue(
                prompt.messages
                    .first()
                    .content
                    .contains("Knowledge is off"),
            )
            assertNull(f.repo.readDraft(key))
            assertEquals("", (state.value as DraftLoadState.Ready).snapshot.draft.text)

            f.visible("A rainbow comes from light interacting with water droplets.")
            f.engine.finish(0)
            runCurrent()
            assertEquals(
                AnswerScope.GENERAL,
                f.pipeline.lastOutcome.value!!
                    .answerScope,
            )
            assertTrue(
                f.pipeline.lastOutcome.value!!
                    .assembled.citations
                    .isEmpty(),
            )
            assertEquals(listOf(Role.USER, Role.ASSISTANT), f.repo.listMessages(id).map { it.role })
            f.close()
        }

    @Test
    fun `completion waiting for writer yields frozen interrupted save to LOW`() =
        runTest {
            val gated = GatedRepository()
            val f = Fixture(this, gated)
            val chat = f.chat()
            f.controller.enqueue(chat, "question").await()
            runCurrent()
            f.visible("visible [1]")
            gated.blockNext = true
            f.engine.finish(0)
            runCurrent()
            assertTrue(gated.entered.isCompleted)
            f.unlocked = null
            f.locking = 7L
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            assertEquals(
                "visible [1]" + INTERRUPTED_MARKER,
                f.repo
                    .listMessages(chat)
                    .last()
                    .contentMd,
            )
            assertEquals(2, f.repo.listMessages(chat).size)
            gated.release.complete(Unit)
            runCurrent()
            assertEquals(2, f.repo.listMessages(chat).size)
            f.close()
        }

    @Test
    fun `revocation before HIGH does not mark a refused normal persist finished`() =
        runTest {
            val gated = GatedRepository()
            val f = Fixture(this, gated)
            val chat = f.chat()
            f.controller.enqueue(chat, "question").await()
            runCurrent()
            f.visible("visible [1]")
            gated.blockNext = true
            f.engine.finish(0)
            runCurrent()
            assertTrue(gated.entered.isCompleted)
            f.unlocked = null
            f.locking = 7L
            gated.release.complete(Unit)
            runCurrent()
            assertEquals(1, f.repo.listMessages(chat).size)
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            assertEquals(
                "visible [1]" + INTERRUPTED_MARKER,
                f.repo
                    .listMessages(chat)
                    .last()
                    .contentMd,
            )
            assertEquals(2, f.repo.listMessages(chat).size)
            f.close()
        }

    @Test
    fun `LOW and close return while independently owned status Binder is genuinely blocked`() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            val unblock = watchdog.schedule({ release.countDown() }, 2, TimeUnit.SECONDS)
            val f = Fixture(this)
            try {
                val chat = f.chat()
                f.idleCheck = {
                    withContext(Dispatchers.IO) {
                        entered.countDown()
                        release.await()
                        Unit
                    }
                }
                f.controller.enqueue(chat, "question").await()
                runCurrent()
                f.visible("visible [1]")
                f.engine.finish(0)
                runCurrent()
                assertTrue(withContext(Dispatchers.IO) { entered.await(1, TimeUnit.SECONDS) })
                f.unlocked = null
                f.locking = 7L
                f.controller.onLockingHigh(7L, 500L)
                f.controller.onLockingLow(7L, 500L)
                f.close()
                // A real two-way call remains blocked: LOW never became its structured parent.
                assertEquals(1L, release.count)
                assertNull(
                    f.controller
                        .state(chat)
                        .value.turn,
                )
            } finally {
                release.countDown()
                unblock.cancel(false)
                watchdog.shutdownNow()
                f.close()
            }
        }

    @Test
    fun `HIGH cancels pending admission before USER commit`() =
        runTest {
            val gated = GatedRepository()
            val f = Fixture(this, gated)
            val chat = f.chat()
            gated.blockNext = true
            val pending = f.controller.enqueue(chat, "not committed")
            runCurrent()
            assertTrue(gated.entered.isCompleted)
            f.unlocked = null
            f.locking = 7L
            f.controller.onLockingHigh(7L, 500L)
            f.controller.onLockingLow(7L, 500L)
            runCurrent()
            assertTrue(pending.isCancelled)
            gated.release.complete(Unit)
            runCurrent()
            assertTrue(f.repo.listMessages(chat).isEmpty())
            assertTrue(f.engine.prompts.isEmpty())
            f.close()
        }

    private class GatedRepository(
        private val actual: InMemoryVaultRepository = InMemoryVaultRepository(),
    ) : VaultRepository by actual {
        var blockNext = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun <T> transaction(block: suspend () -> T): T {
            if (blockNext) {
                blockNext = false
                entered.complete(Unit)
                release.await()
            }
            return actual.transaction(block)
        }
    }

    private class Fixture(
        val test: TestScope,
        val repo: VaultRepository = InMemoryVaultRepository(),
        val epoch: Long = 7L,
    ) {
        var unlocked: Long? = epoch
        var locking: Long? = null
        var idle = CompletableDeferred(Unit)
        var idleCheck: suspend () -> Unit = { idle.await() }
        var persona = Persona("space", "Space", "Original instructions", "original-model", 0)
        val engine = ControlledEngine()
        val retrieval = FakeRetrievalService(listOf(SOURCE))
        val preparedModels = mutableListOf<String?>()
        val pipeline =
            SendPipeline(
                vaultRepository = repo,
                retrievalService = retrieval,
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { persona },
                personaById = { persona },
                budgetFor = { _, _ -> TokenBudget(16_384, 1024, 3072) },
                countTokens = { it.length / 4 },
                selectModel = { selected ->
                    TurnModelSelection(selected?.defaultModel) { preparedModels += selected?.defaultModel }
                },
            )
        val drafts = SessionDraftStore(repo, epoch, { unlocked }, { locking }, test.backgroundScope)
        val controller =
            ChatTurnController(
                repo,
                pipeline,
                epoch,
                { unlocked },
                { locking },
                test.backgroundScope,
                awaitEngineIdle = { idleCheck() },
                commitDraft = { key, version, append -> drafts.commitSend(key, version, append) },
                nanoTime = { test.testScheduler.currentTime * 1_000_000L },
            )

        suspend fun chat() = repo.createDocument(NewDocument(DocumentKind.CHAT, "Chat", "", personaId = "space")).id

        fun vm(id: String) = ChatViewModel(id, repo, pipeline, {}, test.backgroundScope, controller = controller)

        suspend fun visible(text: String) {
            engine.channels.last().send(Token.Text(text, 1))
            test.runCurrent()
            test.advanceTimeBy(31L)
            test.runCurrent()
        }

        fun close() {
            controller.close()
            drafts.close()
        }
    }

    private class ControlledEngine : InferenceEngine by scriptedEngine() {
        val prompts = mutableListOf<Prompt>()
        val channels = mutableListOf<Channel<Token>>()
        var cancelGate = CompletableDeferred(Unit)
        var collectorExit = CompletableDeferred(Unit)

        override suspend fun cancel() {
            cancelGate.await()
        }

        override fun stream(
            prompt: Prompt,
            params: SamplingParams,
        ): Flow<Token> =
            flow {
                prompts += prompt
                val channel = Channel<Token>(Channel.UNLIMITED).also { channels += it }
                try {
                    for (token in channel) {
                        emit(token)
                        if (token is Token.Done) break
                    }
                } finally {
                    withContext(NonCancellable) { collectorExit.await() }
                }
            }

        suspend fun finish(index: Int) {
            channels[index].send(Token.Done(StopReason.EOS, 0, 1, 0, 0f))
        }
    }

    private companion object {
        val SOURCE =
            Retrieved(
                1,
                "source",
                "Source",
                "evidence",
                0.9,
                DocumentKind.NOTE,
                recalledBy = setOf(RecallSource.LEXICAL),
                revisionHash = "synthetic-revision",
                locator = Locator(byteStart = 0, byteEnd = 8),
            )
    }
}
