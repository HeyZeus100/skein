// E5.I10 (skein-7v3) — maps `ThermalGovernor` (E4.I9, skein-7p0) and the
// battery signal onto the pipeline's `IngestPace`, per bd skein-7v3
// (`Reduced` → batch 16, `Paused` → stop/retry) and the amended plan
// (`LOCK_POLICY_INDEXING.md` §7.1: charging is a batch-size input read at
// run time, never a `Constraints` gate — unplugged means a smaller batch,
// not "don't run").
//
// Split into a plain-Kotlin core over three lambdas (JVM-testable) and a
// `forGovernor` factory that binds the real Android governor, mirroring
// `LockPolicyObserver`.

package app.skein.ingest

import app.skein.core.inference.thermal.ThermalGovernor
import app.skein.core.inference.thermal.ThermalState
import app.skein.core.rag.ingest.IngestPace

/** Throughput signal for one pass; `begin`/`end` bracket the pass so the governor polls only while ingest runs. */
interface IngestPacer {
    fun begin() {}

    fun end() {}

    fun pace(): IngestPace
}

/** See the file header. */
class ThermalIngestPacer(
    private val thermalState: () -> ThermalState,
    private val batteryOk: () -> Boolean,
    private val activate: () -> Unit = {},
    private val deactivate: () -> Unit = {},
) : IngestPacer {
    override fun begin() = activate()

    override fun end() = deactivate()

    override fun pace(): IngestPace =
        when (thermalState()) {
            is ThermalState.Paused -> IngestPace.PAUSED
            is ThermalState.Reduced -> IngestPace.REDUCED
            ThermalState.Nominal -> if (batteryOk()) IngestPace.FULL else IngestPace.REDUCED
        }

    companion object {
        /** `ThermalGovernor.activate`/`deactivate` reason tag for the ingest pass. */
        const val REASON: String = "ingest"

        fun forGovernor(governor: ThermalGovernor): ThermalIngestPacer =
            ThermalIngestPacer(
                thermalState = { governor.state.value },
                batteryOk = { governor.batteryOk },
                activate = { governor.activate(REASON) },
                deactivate = { governor.deactivate(REASON) },
            )
    }
}
