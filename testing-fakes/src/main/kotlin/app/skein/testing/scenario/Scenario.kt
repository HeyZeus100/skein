// skein-xtov.23.18 (UT-4): the scenario vocabulary of docs/ux/UX_TEST_PLAN.md
// §6.3 — "what the model does" as plain data, played by
// `ScenarioInferenceEngine` (+ `DelayingRetrievalService`) for JVM tests,
// screenshots, previews and the Mac UX lab. The catalogue is `Scenarios`.

package app.skein.testing.scenario

import app.skein.core.model.InferenceException
import app.skein.core.model.StopReason
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One scripted model turn, step by step.
 *
 * @param stopLatencyMs how long a stop takes to land after `cancel()`: the
 *   real engine only checks between prompt chunks and tokens, so "Stopping…"
 *   is on screen for a while (CHAT_UX_SPEC.md §9.8).
 */
public data class Scenario(
    val id: String,
    val steps: List<Step>,
    val stopLatencyMs: Long = 0,
)

/** A timed step. Durations are scripted; nothing is sampled, tokenised or ranked. */
public sealed interface Step {
    /** Played by `load()`: the model takes [ms] to start, or fails with [failWith]. */
    public data class LoadModel(
        val ms: Long,
        val failWith: InferenceException? = null,
    ) : Step

    /** Played by `ScenarioInferenceEngine.retrievalService()`: takes [ms] and finds [passages] from [documents]. */
    public data class Retrieve(
        val ms: Long,
        val passages: Int,
        val documents: Int,
    ) : Step

    /** Reading the prompt: [promptTokens] over [ms], reported as [chunks] [ScenarioActivity.PrefillProgress] events. */
    public data class Prefill(
        val ms: Long,
        val promptTokens: Int,
        val chunks: Int = 1,
    ) : Step

    /**
     * A reasoning span in the token stream: `<think>`, [pieces] at [msPerPiece]
     * each, then `</think>` — unless [closed] is false (the model ran out of
     * room while thinking).
     */
    public data class Reason(
        val pieces: List<String>,
        val msPerPiece: Long,
        val closed: Boolean = true,
    ) : Step

    /** Answer text: [pieces] at [msPerPiece] each. */
    public data class Answer(
        val pieces: List<String>,
        val msPerPiece: Long,
    ) : Step

    /** Holds the stream open until `cancel()` or the collector is cancelled. */
    public data object Stall : Step

    /** Closes the stream with [error], as the real engine does (plan §4.1). */
    public data class Fail(
        val error: InferenceException,
    ) : Step

    /** Ends the turn with [reason] (without it, a scenario that runs out of steps ends with `EOS`). */
    public data class Finish(
        val reason: StopReason,
    ) : Step
}

/** How a scenario's durations are spent. */
public sealed interface Pacing {
    /** No waits at all: logic tests that only care about order. */
    public data object Instant : Pacing

    /**
     * `delay(ms * scale)`: virtual time under `runTest`, wall-clock time in the
     * lab harness (`RealTime(0.25)` plays four times faster). Never in a
     * Robolectric/Compose test (UX_TEST_PLAN.md §6.3): use [Gated] there.
     */
    public data class RealTime(
        val scale: Double = 1.0,
    ) : Pacing

    /** Every duration becomes a [Checkpoint] that waits for [gate] to release it. */
    public class Gated(
        public val gate: ScenarioGate,
    ) : Pacing
}

/** Where a scenario can be held under [Pacing.Gated]: before each unit of scripted time. */
public sealed interface Checkpoint {
    public data object LoadModel : Checkpoint

    public data object Retrieve : Checkpoint

    /** Before prompt chunk [index] (0-based) is read. */
    public data class PrefillChunk(
        val index: Int,
    ) : Checkpoint

    /** Before the `Token.Text` with this [id]; `<think>`/`</think>` markers are not gated. */
    public data class Piece(
        val id: Int,
    ) : Checkpoint

    /** Between `cancel()` and `Done(CANCELLED)`, when the scenario has a stop latency. */
    public data object Stopping : Checkpoint
}

/**
 * The test's hand on a [Pacing.Gated] scenario. The engine waits at each
 * [Checkpoint]; the test calls [release] (or [open]) and then lets the engine
 * run (`runCurrent()` under `runTest`, `waitForIdle()` in a Compose test).
 */
public class ScenarioGate {
    private val permits = Channel<Unit>(Channel.UNLIMITED)
    private val held = MutableStateFlow<Checkpoint?>(null)

    @Volatile private var opened = false

    /** The checkpoint the engine is waiting at, or null while it runs (or before it starts). */
    public val heldAt: StateFlow<Checkpoint?> = held.asStateFlow()

    /** Lets the next [count] checkpoints pass. */
    public fun release(count: Int = 1) {
        repeat(count) { permits.trySend(Unit) }
    }

    /** Lets every checkpoint pass from now on: the scenario runs to its end. */
    public fun open() {
        opened = true
        permits.trySend(Unit)
    }

    /** Waits at [at]; true if [stop] completed first. */
    internal suspend fun await(
        at: Checkpoint,
        stop: Deferred<Unit>?,
    ): Boolean {
        if (opened) return false
        held.value = at
        try {
            return select {
                stop?.onAwait { true }
                permits.onReceive { false }
            }
        } finally {
            held.value = null
        }
    }
}

/**
 * The side channel for what the token stream cannot carry yet (UX_TEST_PLAN.md
 * §6.3): model start, retrieval counts and prefill progress. The chat's
 * `TurnActivityEvent` source consumes it in tests and the lab; production has
 * no prefill-progress seam yet (CHAT_UX_SPEC.md §9.2, ask B1).
 */
public sealed interface ScenarioActivity {
    public data object ModelStarting : ScenarioActivity

    public data object ModelReady : ScenarioActivity

    public data object RetrievalStarted : ScenarioActivity

    public data class RetrievalDone(
        val passages: Int,
        val documents: Int,
    ) : ScenarioActivity

    public data class PrefillProgress(
        val processed: Int,
        val total: Int,
    ) : ScenarioActivity
}

/** Spends one unit of scripted time at [at]. True as soon as [stop] completes (the unit is abandoned). */
internal suspend fun Pacing.wait(
    ms: Long,
    at: Checkpoint,
    stop: Deferred<Unit>? = null,
): Boolean {
    if (stop?.isCompleted == true) return true
    return when (this) {
        Pacing.Instant -> false
        is Pacing.RealTime -> {
            val scaled = (ms * scale).toLong()
            if (stop == null) {
                delay(scaled)
                false
            } else {
                withTimeoutOrNull(scaled) { stop.await() } != null
            }
        }
        is Pacing.Gated -> gate.await(at, stop)
    }
}
