// skein-7p0 (E4.I9) — the *template* a `ThermalBackoffTable.Entry` maps a
// headroom threshold to. `ThermalAction.Paused` carries a relative duration
// (seconds), not an absolute deadline, because the table is stateless and
// doesn't know "now" — `ThermalGovernorCore` materializes it into a
// `ThermalState.Paused(untilMs)` at the moment the entry becomes active
// (and re-materializes it, with a fresh deadline, on expiry re-evaluation —
// see `ThermalGovernorCore`).

package app.skein.core.inference.thermal

/**
 * A table entry's action, before the governor turns it into a
 * time-stamped [ThermalState].
 */
public sealed interface ThermalAction {
    public data object Nominal : ThermalAction

    public data class Reduced(
        val threads: Int,
    ) : ThermalAction {
        init {
            require(threads >= 1) { "Reduced.threads must be >= 1, was $threads" }
        }
    }

    public data class Paused(
        val durationSeconds: Long,
    ) : ThermalAction {
        init {
            require(durationSeconds >= 1) { "Paused.durationSeconds must be >= 1, was $durationSeconds" }
        }
    }
}

/** Materializes a [ThermalAction] into the [ThermalState] published at [nowMs]. */
internal fun ThermalAction.toThermalState(nowMs: Long): ThermalState =
    when (this) {
        ThermalAction.Nominal -> ThermalState.Nominal
        is ThermalAction.Reduced -> ThermalState.Reduced(threads)
        is ThermalAction.Paused -> ThermalState.Paused(untilMs = nowMs + durationSeconds * 1_000L)
    }
