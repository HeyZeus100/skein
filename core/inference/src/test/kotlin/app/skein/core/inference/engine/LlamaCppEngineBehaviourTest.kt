// skein-1uw (E4.I4) + skein-ktvz (H2) — everything the engine does that the
// `InferenceEngine` contract suite does not reach: the status machine, the
// per-message spill, cancellation on collector close, process death and the
// rebind after it, the session epoch on every request, `TokenCounter`, and
// `inspect`.
//
// One assertion per test where the assertion IS the claim; a few tests assert
// an ordered sequence, which is one claim expressed as a list.

package app.skein.core.inference.engine

import app.skein.core.inference.InferenceConfig
import app.skein.core.model.ChatMessage
import app.skein.core.model.EngineState
import app.skein.core.model.InferenceException
import app.skein.core.model.Prompt
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.ipc.ErrorCode
import app.skein.ipc.ModelInspection
import app.skein.ipc.TransportRules
import app.skein.testing.SkeinLogCaptureRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LlamaCppEngineBehaviourTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    /**
     * Handoff non-negotiable §3: no prompt, answer or model content in logs.
     * The rule fails the test on any entry `SkeinLog` flagged sensitive; the
     * test below adds the positive assertion for this file's own call sites.
     */
    @get:Rule
    val logCapture: SkeinLogCaptureRule = SkeinLogCaptureRule()

    private lateinit var fixture: StoreFixture
    private lateinit var service: FakeInferenceService
    private lateinit var connector: FakeServiceConnector
    private lateinit var engine: LlamaCppEngine
    private var epoch: Long = 7L

    @Before
    fun setUp() {
        fixture = StoreFixture(temporaryFolder.newFolder("models"))
        service = FakeInferenceService()
        connector = FakeServiceConnector(service)
        engine =
            LlamaCppEngine(
                connector = connector,
                pins = fixture.pins,
                spillDir = temporaryFolder.newFolder("spill"),
                sessionEpoch = { epoch },
                config = InferenceConfig(),
                io = Dispatchers.IO,
            )
    }

    // ------------------------------------------------------- status machine

    @Test
    fun statusMovesUnloadedLoadingReadyGeneratingReady(): Unit =
        runTest {
            val observed = mutableListOf<EngineState>()
            val watcher = launch(Dispatchers.Unconfined) { engine.status.collect { observed += it.state } }

            val loading = CountDownLatch(1)
            service.beforeLoadReturns = { loading.await(5, TimeUnit.SECONDS) }
            val loadJob = launch(Dispatchers.IO) { engine.load(fixture.model()) }
            awaitState(EngineState.LOADING)
            loading.countDown()
            loadJob.join()
            awaitState(EngineState.READY)

            val generating = CountDownLatch(1)
            service.beforeDone = { generating.await(5, TimeUnit.SECONDS) }
            val streamJob = launch(Dispatchers.IO) { engine.stream(prompt(), params()).toList() }
            awaitState(EngineState.GENERATING)
            generating.countDown()
            streamJob.join()
            awaitState(EngineState.READY)

            watcher.cancel()
            assertThat(observed)
                .containsAtLeast(
                    EngineState.UNLOADED,
                    EngineState.LOADING,
                    EngineState.READY,
                    EngineState.GENERATING,
                    EngineState.READY,
                ).inOrder()
        }

    @Test
    fun aRefusedLoadLeavesTheEngineInError(): Unit =
        runTest {
            service.loadCode = ErrorCode.OOM

            engine.load(fixture.model())

            assertThat(engine.status.value.state).isEqualTo(EngineState.ERROR)
        }

    @Test
    fun unloadReturnsToUnloadedAndReleasesTheStoreLock(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            engine.unload()

            // The shared read lock is what makes `ModelManager.delete` and
            // re-import answerable; an engine that kept it after `unload`
            // would refuse both forever.
            assertThat(fixture.store.isOpen(fixture.model().id)).isFalse()
        }

    @Test
    fun unloadTellsTheService(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            engine.unload()

            assertThat(service.unloads).isEqualTo(1)
        }

    @Test
    fun unloadKeepsTheBinding(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            engine.unload()

            // Epic skein-gg11 DoD step 5: locking the vault unloads the model
            // and kills nothing in `:app`. Unbinding would tear down the
            // isolated process and make the next unlock pay a process start.
            assertThat(connector.disconnects).isEqualTo(0)
        }

    // ----------------------------------------------------------- the stream

    @Test
    fun streamEmitsOneTokenTextPerPiece(): Unit =
        runTest {
            service.pieces = listOf("Hel", "lo", "!")
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()

            val texts = engine.stream(prompt(), params()).toList().filterIsInstance<Token.Text>()

            assertThat(texts.map { it.text }).containsExactly("Hel", "lo", "!").inOrder()
        }

    @Test
    fun streamEndsWithDoneCarryingTheServicesStats(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()

            val done = engine.stream(prompt(), params()).toList().last() as Token.Done

            assertThat(done.reason).isEqualTo(StopReason.EOS)
        }

    @Test
    fun closingTheFlowCancelsTheRequest(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            // `first()` closes the flow as soon as one token arrives, which is
            // the `awaitClose { cancel(requestId) }` path.
            engine.stream(prompt(), params()).first()

            val requestId = service.generates.single().requestId
            assertThat(service.cancels).contains(requestId)
        }

    @Test
    fun eachStreamUsesAFreshRequestId(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()

            engine.stream(prompt(), params()).toList()
            service.awaitIdle()
            engine.stream(prompt(), params()).toList()

            assertThat(service.generates.map { it.requestId }.toSet()).hasSize(2)
        }

    @Test
    fun streamWithoutALoadedModelFailsWithModelNotLoaded(): Unit =
        runTest {
            val failure = runCatching { engine.stream(prompt(), params()).first() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(InferenceException.ModelNotLoaded::class.java)
        }

    // ------------------------------------------------------------- spilling

    @Test
    fun aLongPromptSpillsPerMessageWithRolesPreserved(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()
            val huge = "R".repeat(TransportRules.INLINE_BUDGET_BYTES)
            val messages =
                listOf(
                    ChatMessage(Role.SYSTEM, "you are a helpful assistant"),
                    ChatMessage(Role.USER, huge),
                    ChatMessage(Role.ASSISTANT, "ok"),
                )

            engine.stream(Prompt(messages), params()).toList()

            // Roles and order survive the spill — the data/instruction
            // boundary is carried by `ChatMessageParcel.role` and nothing else
            // (skein-0rkg).
            assertThat(service.seenMessages.map { it.role })
                .containsExactly("system", "user", "assistant")
                .inOrder()
        }

    @Test
    fun aSpilledMessageArrivesByteForByte(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()
            val huge = "R".repeat(TransportRules.INLINE_BUDGET_BYTES) + "…tail"

            engine.stream(Prompt(listOf(ChatMessage(Role.USER, huge))), params()).toList()

            assertThat(service.seenMessages.single().content).isEqualTo(huge)
        }

    @Test
    fun onlyTheOversizedMessageSpills(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()
            val messages =
                listOf(
                    ChatMessage(Role.SYSTEM, "short"),
                    ChatMessage(Role.USER, "R".repeat(TransportRules.INLINE_BUDGET_BYTES)),
                )

            engine.stream(Prompt(messages), params()).toList()

            assertThat(service.seenMessages.map { it.spilled }).containsExactly(false, true).inOrder()
        }

    @Test
    fun aShortPromptTravelsInline(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()

            engine.stream(prompt(), params()).toList()

            assertThat(service.seenMessages.single().spilled).isFalse()
        }

    @Test
    fun manyMediumMessagesSpillUntilTheRequestFits(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()
            // None of these exceeds the per-message budget on its own; together
            // they exceed it, which is the aggregate case §3.2 is about.
            val messages = List(6) { ChatMessage(Role.USER, "M".repeat(8_000)) }

            engine.stream(Prompt(messages), params()).toList()

            // Measured by the fake at RECEIVE time, before it closed the
            // descriptors — the same measurement `TransportRules` makes.
            assertThat(TransportRules.mustSpill(service.lastGenerateSize)).isFalse()
        }

    // -------------------------------------------------------- service death

    @Test
    fun deathFailsTheInFlightStreamWithServiceDied(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()
            service.beforeDone = { Thread.sleep(2_000L) }

            val killer =
                launch(Dispatchers.IO) {
                    awaitGenerateStarted()
                    connector.killService()
                }

            val failure = runCatching { engine.stream(prompt(), params()).toList() }.exceptionOrNull()
            killer.join()

            assertThat(failure).isInstanceOf(InferenceException.ServiceDied::class.java)
        }

    @Test
    fun deathReturnsTheEngineToUnloaded(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            connector.killService()

            assertThat(engine.status.value.state).isEqualTo(EngineState.UNLOADED)
        }

    @Test
    fun theNextLoadAfterADeathRebinds(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()
            connector.killService()

            val result = engine.load(fixture.model())

            assertThat(result.isSuccess).isTrue()
            assertThat(connector.connects).isEqualTo(2)
        }

    @Test
    fun deathReleasesTheStoreLock(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            connector.killService()

            assertThat(fixture.store.isOpen(fixture.model().id)).isFalse()
        }

    // ------------------------------------------------------- session epochs

    @Test
    fun everyRequestCarriesTheEpochCurrentAtTheTimeOfTheCall(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            epoch = 11L
            engine.load(fixture.model()).getOrThrow()
            epoch = 12L
            engine.stream(prompt(), params()).toList()
            service.awaitIdle()
            epoch = 13L
            engine.count("hello")

            // The epoch is READ per call, never cached at construction:
            // the vault can lock between two requests.
            assertThat(service.epochs).containsExactly(11L, 12L).inOrder()
        }

    @Test
    fun loadCarriesTheSessionEpoch(): Unit =
        runTest {
            epoch = 99L

            engine.load(fixture.model()).getOrThrow()

            assertThat(service.loads.single().sessionEpoch).isEqualTo(99L)
        }

    // ------------------------------------------------------------- logging

    @Test
    fun neitherThePromptNorTheServiceDiagnosticReachesTheLog(): Unit =
        runTest {
            service.generateError = ErrorCode.INTERNAL to "diagnostic-$MARKER"
            engine.load(fixture.model()).getOrThrow()

            runCatching { engine.stream(prompt("note body $MARKER"), params()).toList() }

            // A service diagnostic is UNTRUSTED text; it belongs in the
            // exception the caller receives, never in a log line an attacker
            // could otherwise forge entries into.
            assertThat(logCapture.captured().filter { MARKER in it.message }).isEmpty()
        }

    // --------------------------------------------------------- lock policy

    @Test
    fun theUnlockedEpochIsPushedToTheService(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            engine.onSessionUnlocked(21L)

            assertThat(service.unlockedPushes).containsExactly(21L)
        }

    @Test
    fun theUnlockedEpochIsResentOnAFreshBind(): Unit =
        runTest {
            engine.onSessionUnlocked(21L)
            engine.load(fixture.model()).getOrThrow()
            connector.killService()

            engine.load(fixture.model()).getOrThrow()

            // LOCK_POLICY_INDEXING.md §5.3: the service starts at
            // SessionEpoch.NONE and refuses everything, so a rebind that did
            // not re-send would leave a perfectly unlocked vault refused.
            assertThat(service.unlockedPushes).containsExactly(21L, 21L).inOrder()
        }

    @Test
    fun aLockedSessionIsNotReauthorizedByARebind(): Unit =
        runTest {
            engine.onSessionUnlocked(21L)
            engine.load(fixture.model()).getOrThrow()
            engine.onSessionLocked(21L)
            service.unlockedPushes.clear()

            connector.killService()
            engine.load(fixture.model())

            // "The vault is locked" must not last only until the isolated
            // process next restarts.
            assertThat(service.unlockedPushes).isEmpty()
        }

    @Test
    fun theLockingBudgetIsPushedToTheService(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            engine.onSessionLocking(21L, 250L)

            assertThat(service.lockingPushes).containsExactly(21L)
        }

    // ------------------------------ admission after an unlock (bd skein-gg11.8)
    //
    // `onSessionUnlocked` is the one session push that is NOT `oneway`: it
    // returns only once the service's `IsolatedSessionGate` holds the epoch
    // (`IInferenceService.aidl`, and `AidlContractTest` asserts the transaction
    // flag itself). What these prove is the half that lives on THIS side of the
    // wire — that the engine does not hand the push off and return early, and
    // that a `load` issued the instant the push returns is admitted even when
    // the service is slow to apply it. `FakeInferenceService.enforceSessionGate`
    // makes the fake refuse exactly as the service does, and
    // `unlockApplyDelayMillis` makes the window wide.

    @Test
    fun aGateEnforcingServiceRefusesALoadItWasNeverUnlockedFor(): Unit =
        runTest {
            service.enforceSessionGate = true

            val failure = engine.load(fixture.model()).exceptionOrNull()

            // The control for the two tests below: without it they would be
            // green against a fake that admits everything.
            assertThat(failure).isInstanceOf(InferenceException.SessionLocked::class.java)
        }

    @Test
    fun aLoadIssuedStraightAfterAnUnlockPushIsAdmitted(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow() // bind first; this one is ungated
            service.enforceSessionGate = true
            service.unlockApplyDelayMillis = SLOW_APPLY_MILLIS
            epoch = 21L

            engine.onSessionUnlocked(21L)

            assertThat(engine.load(fixture.model()).isSuccess).isTrue()
        }

    @Test
    fun theFirstLoadAfterARebindIsAdmitted(): Unit =
        runTest {
            epoch = 21L
            engine.onSessionUnlocked(21L)
            engine.load(fixture.model()).getOrThrow()
            service.enforceSessionGate = true
            service.unlockApplyDelayMillis = SLOW_APPLY_MILLIS
            connector.killService()
            service.forgetSession()

            // §5.3's re-send, on the path that has no caller to sequence it.
            assertThat(engine.load(fixture.model()).isSuccess).isTrue()
        }

    @Test
    fun aLoadConcurrentWithARebindDoesNotOvertakeTheResentUnlock(): Unit =
        runTest {
            epoch = 21L
            engine.onSessionUnlocked(21L)
            engine.load(fixture.model()).getOrThrow()
            service.enforceSessionGate = true
            service.unlockApplyDelayMillis = SLOW_APPLY_MILLIS
            connector.killService()
            service.forgetSession()

            // `inspect` binds on its own lock, not `load`'s, so these two
            // really do reach `connect` concurrently. `connect` pushes the
            // remembered epoch and publishes the binding only afterwards: the
            // load either finds nothing published yet and waits on the bind
            // lock, or finds a binding whose authorization has already landed.
            // Publishing first — which is what this file's engine used to do —
            // lets the load pick up a binding mid-push and be refused.
            val rebinding = launch(Dispatchers.IO) { engine.inspect(fixture.binding()) }
            awaitUnlockPushes(2)
            val racer = engine.load(fixture.model())
            rebinding.join()

            assertThat(racer.isSuccess).isTrue()
        }

    // ------------------------------------------------------- the LoadRequest

    @Test
    fun loadRequestDefaultsToCpuOnly(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            // OfflineLLM E-2 / OL-01: an isolated process may have no GPU
            // access at all, so the client never assumes offload.
            assertThat(service.loads.single().gpuLayers).isEqualTo(0)
        }

    @Test
    fun loadRequestTakesThreadsFromTheConfig(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            assertThat(service.loads.single().threads).isEqualTo(InferenceConfig.DEFAULT_THREADS)
        }

    @Test
    fun loadRequestClampsTheContextToTheConfiguredCap(): Unit =
        runTest {
            val roomy = fixture.model().copy(contextLength = 1_000_000)

            engine.load(roomy).getOrThrow()

            assertThat(service.loads.single().contextLength).isEqualTo(InferenceConfig().contextLengthCap)
        }

    @Test
    fun loadRequestAsksForEmbeddingModeOnlyForAnEmbeddingModel(): Unit =
        runTest {
            engine.load(fixture.model()).getOrThrow()

            assertThat(service.loads.single().embeddingMode).isFalse()
        }

    // ---------------------------------------------------------- sampling

    @Test
    fun aCallerWithNoOpinionGetsTheModelFamilyDefaults(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            // The fixture model's id is `qwen-2-5-3b-test` (ModelStoreFixtures).
            engine.load(fixture.model()).getOrThrow()

            engine.stream(prompt(), SamplingParams()).toList()

            assertThat(
                service.generates
                    .single()
                    .sampling.topP,
            ).isEqualTo(SamplingDefaults.QWEN.topP)
        }

    @Test
    fun anExplicitSamplingParamsWins(): Unit =
        runTest {
            service.completionDelayMillis = 0L
            engine.load(fixture.model()).getOrThrow()

            engine.stream(prompt(), SamplingParams(topP = 0.33f)).toList()

            assertThat(
                service.generates
                    .single()
                    .sampling.topP,
            ).isEqualTo(0.33f)
        }

    // ------------------------------------------------------- TokenCounter

    @Test
    fun countAsksTheServicesTokenizer(): Unit =
        runTest {
            service.tokenCountResult = 19
            engine.load(fixture.model()).getOrThrow()

            assertThat(engine.count("some text")).isEqualTo(19)
        }

    @Test
    fun countWithoutAServiceFailsWithModelNotLoaded(): Unit =
        runTest {
            val failure = runCatching { engine.count("some text") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(InferenceException.ModelNotLoaded::class.java)
        }

    // ------------------------------------------------------------ H2 inspect

    @Test
    fun inspectReturnsWhatTheServiceRead(): Unit =
        runTest {
            val inspection = engine.inspect(fixture.binding())

            assertThat(inspection.architecture).isEqualTo("llama")
        }

    @Test
    fun inspectNeedsNoLoadedModel(): Unit =
        runTest {
            // §3.3's whole point: a model is inspected BEFORE the user accepts
            // it, so requiring a load first would defeat the method.
            engine.inspect(fixture.binding())

            assertThat(service.inspects).hasSize(1)
        }

    @Test
    fun inspectCarriesTheSessionEpoch(): Unit =
        runTest {
            epoch = 5L

            engine.inspect(fixture.binding())

            assertThat(service.inspects.single().sessionEpoch).isEqualTo(5L)
        }

    @Test
    fun inspectSurfacesARefusalAsACodeNotAnException(): Unit =
        runTest {
            service.inspectResult = ModelInspection.refused(ErrorCode.BUSY)

            val inspection = engine.inspect(fixture.binding())

            assertThat(inspection.errorCode).isEqualTo(ErrorCode.BUSY)
        }

    @Test
    fun inspectSendsOneDescriptorPerBoundFile(): Unit =
        runTest {
            engine.inspect(fixture.binding())

            assertThat(
                service.inspects
                    .single()
                    .binding.files,
            ).hasSize(fixture.binding().files.size)
        }

    // --------------------------------------------------------------- embed

    @Test
    fun embedReturnsTheServicesVector(): Unit =
        runTest {
            service.embedResult = floatArrayOf(1f, 2f, 3f)
            engine.load(fixture.embeddingModel()).getOrThrow()

            assertThat(engine.embed("hello").toList()).containsExactly(1f, 2f, 3f).inOrder()
        }

    // ------------------------------------------------------------- helpers

    private fun prompt(text: String = "hi"): Prompt = Prompt(listOf(ChatMessage(Role.USER, text)))

    private companion object {
        /** A string no fixed log text could contain by accident. */
        const val MARKER = "zzq-marker-9f31"

        /**
         * bd skein-gg11.8: how long the fake service spends applying an unlock
         * push. Long enough that a `load` racing it would land first on any
         * machine; short enough not to matter to the suite's wall clock.
         */
        const val SLOW_APPLY_MILLIS = 150L
    }

    private fun params(): SamplingParams = SamplingParams(maxTokens = 8, topP = 0.91f)

    /**
     * Wall-clock, not `withTimeout`: inside `runTest` a `withTimeout` is
     * scheduled in VIRTUAL time, which nothing here advances, so it would
     * never fire and a regression would hang instead of failing.
     */
    private fun awaitState(state: EngineState) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (engine.status.value.state != state && System.nanoTime() < deadline) {
            Thread.sleep(1L)
        }
        assertThat(engine.status.value.state).isEqualTo(state)
    }

    /**
     * bd skein-gg11.8: waits until the fake has RECEIVED [count] unlock pushes.
     * It records each one on entry, before it spends
     * `unlockApplyDelayMillis` applying it, so this returns while the push is
     * still in flight — which is the moment the racing call has to be issued at.
     */
    private fun awaitUnlockPushes(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (service.unlockedPushes.size < count && System.nanoTime() < deadline) {
            Thread.sleep(1L)
        }
        assertThat(service.unlockedPushes.size).isAtLeast(count)
    }

    private fun awaitGenerateStarted() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (service.generates.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(1L)
        }
    }
}
