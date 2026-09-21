// skein-7p0 (E4.I9) — the state consumers actually observe.
//
// `ThermalGovernorCore`/`ThermalGovernor` publish this via a `StateFlow`.
// The two planned consumers per the plan/bd brief:
//  - `IngestWorker` (E5.I10, skein-7v3): pauses or reduces its batch size.
//  - `LlamaCppEngine` (E4.I4, skein-1uw): lowers `threads` on the *next*
//    model load. It CANNOT change the thread count of an in-flight
//    `llama_decode` — llama.cpp's threadpool is fixed for the lifetime of a
//    generation. A `Reduced`/`Paused` transition observed mid-generation is
//    only actionable once that generation completes and the engine loads
//    (or re-configures) the next context.

package app.skein.core.inference.thermal

/**
 * The governor's current recommendation to inference/ingest consumers.
 */
public sealed interface ThermalState {
    /** No thermal mitigation in effect. */
    public data object Nominal : ThermalState

    /**
     * Consumers should reduce work: `LlamaCppEngine` uses [threads] as the
     * thread count for its *next* model load; `IngestWorker` reduces its
     * batch size.
     */
    public data class Reduced(
        val threads: Int,
    ) : ThermalState {
        init {
            require(threads >= 1) { "Reduced.threads must be >= 1, was $threads" }
        }
    }

    /**
     * Consumers should stop entirely until [untilMs] (epoch millis, same
     * clock as the governor's injected `Clock`). `IngestWorker` should
     * treat this like a `retry()`; `LlamaCppEngine` should not start a new
     * load before this deadline.
     *
     * [untilMs] is re-armed (pushed further out) if the governor
     * re-evaluates at expiry and headroom is still in the paused band —
     * see `ThermalGovernorCore`'s KDoc for the expiry/re-evaluation rule.
     */
    public data class Paused(
        val untilMs: Long,
    ) : ThermalState
}
