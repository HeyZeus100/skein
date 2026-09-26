package app.skein.testing.scenario

import app.skein.core.model.Capability
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.Prompt
import app.skein.core.model.RetrievalService
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.testing.FakeInferenceEngine
import app.skein.testing.FakeRetrievalService
import app.skein.testing.corpus.Corpus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

/**
 * An [InferenceEngine] that plays a [Scenario] (UX_TEST_PLAN.md §6.3), so the
 * real `SendPipeline`, `CitationParser`, persistence and cancellation run
 * against every chat state — slow prefill, reasoning, stop, failure, model
 * loading — without a model.
 *
 * - [load] plays [Step.LoadModel] (once per model; loading the same model
 *   again is a no-op, as `ManagedInferenceEngine.warmUp` is). A model whose
 *   `sha256` is [FakeInferenceEngine.BAD_HASH] fails with `HashMismatch`.
 * - [stream] plays the rest: [Step.Prefill] (progress on the side channel),
 *   [Step.Reason] (a `<think>…</think>` span inside `Token.Text`, which is
 *   what a reasoning model sends today), [Step.Answer], then [Step.Stall],
 *   [Step.Fail] (the flow throws that `InferenceException`) or [Step.Finish];
 *   exactly one `Token.Done` last on every non-exceptional path. `Done`'s
 *   numbers are the scripted ones (prompt tokens, prefill time, piece rate).
 * - [retrievalService] plays [Step.Retrieve].
 * - [cancel] stops the running stream: it ends with `Done(CANCELLED)` after
 *   [Scenario.stopLatencyMs]. Like the real engine today (CMS-P1-04), a
 *   cancel with no stream running is dropped. Cancelling the collector
 *   cancels everything at once.
 * - One stream at a time: a second collector fails with `Busy`.
 *
 * [onActivity] receives the [ScenarioActivity] side channel (model start,
 * retrieval counts, prefill progress) — what production cannot report yet.
 */
public class ScenarioInferenceEngine(
    public val scenario: Scenario,
    private val pacing: Pacing = Pacing.Instant,
    private val onActivity: (ScenarioActivity) -> Unit = {},
) : InferenceEngine {
    private val streamLock = Mutex()

    @Volatile private var loaded: Model? = null

    @Volatile private var stop: CompletableDeferred<Unit>? = null

    override suspend fun load(model: Model): Result<Unit> {
        if (model.sha256 == FakeInferenceEngine.BAD_HASH) {
            return Result.failure(
                InferenceException.HashMismatch(expected = model.sha256, actual = "<scenario digest>"),
            )
        }
        if (loaded == model) return Result.success(Unit)
        scenario.steps.filterIsInstance<Step.LoadModel>().firstOrNull()?.let { step ->
            onActivity(ScenarioActivity.ModelStarting)
            pacing.wait(step.ms, Checkpoint.LoadModel)
            step.failWith?.let {
                loaded = null
                return Result.failure(it)
            }
            onActivity(ScenarioActivity.ModelReady)
        }
        loaded = model
        return Result.success(Unit)
    }

    override fun stream(
        prompt: Prompt,
        params: SamplingParams,
    ): Flow<Token> =
        flow {
            if (loaded == null) throw InferenceException.ModelNotLoaded()
            if (!streamLock.tryLock()) throw InferenceException.Busy()
            val stopSignal = CompletableDeferred<Unit>()
            stop = stopSignal
            try {
                play(stopSignal)
            } finally {
                stop = null
                streamLock.unlock()
            }
        }

    override suspend fun embed(text: String): FloatArray {
        val model = loaded ?: throw InferenceException.ModelNotLoaded()
        if (Capability.EMBEDDING !in model.capabilities) {
            throw InferenceException.InvalidModel("model lacks EMBEDDING capability")
        }
        return FloatArray(8)
    }

    override suspend fun cancel() {
        stop?.complete(Unit)
    }

    override suspend fun unload() {
        cancel()
        loaded = null
    }

    /**
     * A [RetrievalService] that plays this scenario's [Step.Retrieve] — its
     * duration, its [Checkpoint.Retrieve] and its activity events — over
     * [delegate], by default F-SOURCES cut to the step's passage and document
     * counts (nothing, if the scenario has no retrieval step).
     */
    public fun retrievalService(delegate: RetrievalService? = null): RetrievalService {
        val step = scenario.steps.filterIsInstance<Step.Retrieve>().firstOrNull()
        return DelayingRetrievalService(
            delegate =
                delegate ?: FakeRetrievalService(step?.let { Corpus.sources(it.passages, it.documents) }.orEmpty()),
            ms = step?.ms ?: 0,
            pacing = pacing,
            onActivity = onActivity,
        )
    }

    private suspend fun FlowCollector<Token>.play(stop: Deferred<Unit>) {
        var nextId = 0
        var promptTokens = 0
        var ttftMs = 0L
        var msPerPiece = 0L
        var reason = StopReason.EOS

        suspend fun text(piece: String) = emit(Token.Text(piece, nextId++))

        // Emits the pieces; true if a stop landed first.
        suspend fun pieces(
            list: List<String>,
            ms: Long,
        ): Boolean {
            msPerPiece = ms
            for (piece in list) {
                if (pacing.wait(ms, Checkpoint.Piece(nextId), stop)) return true
                text(piece)
            }
            return false
        }

        var stopped = false
        for (step in scenario.steps) {
            stopped =
                when (step) {
                    // Played by load() and retrievalService().
                    is Step.LoadModel, is Step.Retrieve -> false
                    is Step.Prefill -> {
                        promptTokens = step.promptTokens
                        ttftMs += step.ms
                        prefill(step, stop)
                    }
                    is Step.Reason -> {
                        text(THINK_OPEN)
                        pieces(step.pieces, step.msPerPiece).also { if (!it && step.closed) text(THINK_CLOSE) }
                    }
                    is Step.Answer -> pieces(step.pieces, step.msPerPiece)
                    Step.Stall -> {
                        stop.await()
                        true
                    }
                    is Step.Fail -> throw step.error
                    is Step.Finish -> {
                        reason = step.reason
                        break
                    }
                }
            if (stopped) break
        }
        if (stopped) {
            if (scenario.stopLatencyMs > 0) pacing.wait(scenario.stopLatencyMs, Checkpoint.Stopping)
            reason = StopReason.CANCELLED
        }
        emit(
            Token.Done(
                reason = reason,
                promptTokens = promptTokens,
                generatedTokens = nextId,
                ttftMs = ttftMs,
                tokensPerSec = if (msPerPiece > 0) 1_000f / msPerPiece else 0f,
            ),
        )
    }

    /** True if a stop landed first. */
    private suspend fun prefill(
        step: Step.Prefill,
        stop: Deferred<Unit>,
    ): Boolean {
        val chunks = step.chunks.coerceAtLeast(1)
        for (i in 0 until chunks) {
            if (pacing.wait(step.ms / chunks, Checkpoint.PrefillChunk(i), stop)) return true
            onActivity(ScenarioActivity.PrefillProgress(step.promptTokens * (i + 1) / chunks, step.promptTokens))
        }
        return false
    }

    public companion object {
        public const val THINK_OPEN: String = "<think>"
        public const val THINK_CLOSE: String = "</think>"
    }
}
