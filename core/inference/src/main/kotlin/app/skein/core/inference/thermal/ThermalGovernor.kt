// skein-7p0 (E4.I9) — thin Android adapter over `ThermalGovernorCore`.
//
// This is deliberately small: it only (a) turns `PowerManager` into the two
// core seams (`HeadroomSource`, `ThermalStatusSource`), (b) drives `tick()`
// on a coroutine loop while active, and (c) answers `batteryOk` from the
// `ACTION_BATTERY_CHANGED` sticky intent via `BatteryOkEvaluator`. All the
// actual state-machine logic (cadence gating, hysteresis, Paused expiry)
// lives in `ThermalGovernorCore` and is exercised by JVM tests; this class
// is compile-checked and its device-binding smoke-tested by the (compiled,
// not-yet-run — gated on skein-k3b2) androidTest.
//
// `SkeinLog` (spec §9) doesn't exist in this codebase yet — see
// `ThermalGovernorCore`'s KDoc on `onUnsupportedHeadroom`. Wire this to
// `SkeinLog.w(TAG, ...)` once it lands.

package app.skein.core.inference.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Android-facing `ThermalGovernor`: polls `PowerManager.getThermalHeadroom(10)`
 * (API 30+; this module's `minSdk` is already 30 — see its `build.gradle.kts`)
 * at most once/second while [activate]/[deactivate]'s reference count is
 * above zero, plus `PowerManager.OnThermalStatusChangedListener` events,
 * which short-circuit the cadence for an immediate re-evaluation. Also
 * exposes [batteryOk] (charging or >= 20%) for `IngestWorker`'s
 * opportunistic runs.
 *
 * @param context used only to `registerReceiver`/`unregisterReceiver`
 *   (sticky-intent read) for [batteryOk] and to derive an application
 *   context; not retained beyond that.
 * @param powerManager the `PowerManager` system service (`Context.getSystemService(Context.POWER_SERVICE)`).
 * @param table injected [ThermalBackoffTable] — defaults to the marked
 *   placeholder [ThermalBackoffTable.DEFAULT].
 * @param clock test/production clock seam, mirroring `UnlockManager`'s pattern.
 * @param pollLoopScope the coroutine scope the internal poll loop runs on.
 *   Defaults to a private `Dispatchers.Default` scope so this class needs
 *   no caller-supplied scope wiring; tests that construct a
 *   [ThermalGovernor] directly (rather than via [ThermalGovernorCore]) can
 *   still inject their own.
 */
public class ThermalGovernor
    @JvmOverloads
    constructor(
        context: Context,
        private val powerManager: PowerManager,
        table: ThermalBackoffTable = ThermalBackoffTable.DEFAULT,
        clock: Clock = Clock.systemUTC(),
        private val pollLoopScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        private val tickIntervalMillis: Long = DEFAULT_TICK_INTERVAL_MILLIS,
    ) {
        private val appContext = context.applicationContext

        private val core =
            ThermalGovernorCore(
                table = table,
                clock = clock,
                headroomSource = PowerManagerHeadroomSource(powerManager),
                statusSource = PowerManagerThermalStatusSource(powerManager),
                onUnsupportedHeadroom = {
                    // TODO(skein: SkeinLog not implemented yet, spec §9): replace with SkeinLog.w(TAG, ...).
                    Log.w(TAG, "getThermalHeadroom() returned NaN; defaulting to Nominal")
                },
            )

        /** Current recommendation — see [ThermalState]. */
        public val state: StateFlow<ThermalState> get() = core.state

        private val lock = Any()
        private var tickJob: Job? = null

        /** Registers interest (inference or ingest running). Reference-counted — see [ThermalGovernorCore.activate]. */
        public fun activate(reason: String) {
            synchronized(lock) {
                core.activate(reason)
                if (tickJob == null) {
                    tickJob =
                        pollLoopScope.launch {
                            while (isActive) {
                                core.tick()
                                delay(tickIntervalMillis)
                            }
                        }
                }
            }
        }

        /** Releases interest — see [ThermalGovernorCore.deactivate]. */
        public fun deactivate(reason: String) {
            synchronized(lock) {
                core.deactivate(reason)
                if (!core.isActive) {
                    tickJob?.cancel()
                    tickJob = null
                }
            }
        }

        /**
         * `true` when charging (or full), or unplugged with `>= 20%`
         * battery, per `BatteryOkEvaluator`. Reads the `ACTION_BATTERY_CHANGED`
         * sticky intent fresh on every call — registering a `null` receiver
         * for a sticky action just returns the last broadcast without
         * installing a real receiver (no unregister needed).
         */
        public val batteryOk: Boolean
            get() {
                val sticky = appContext.registerReceiver(null, BATTERY_CHANGED_FILTER)
                val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                return BatteryOkEvaluator.isOk(status, level, scale)
            }

        public companion object {
            private const val TAG = "ThermalGovernor"
            private const val DEFAULT_TICK_INTERVAL_MILLIS = 250L
            private val BATTERY_CHANGED_FILTER = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        }
    }

/** `PowerManager.getThermalHeadroom` wrapped as a [HeadroomSource]. */
private class PowerManagerHeadroomSource(
    private val powerManager: PowerManager,
) : HeadroomSource {
    override fun headroomNow(): Float = powerManager.getThermalHeadroom(FORECAST_SECONDS)

    private companion object {
        // The AC's fake-PowerManager-shim scenarios use a 10s forecast; the
        // plan/bd brief pins the same value.
        const val FORECAST_SECONDS = 10
    }
}

/** `PowerManager.OnThermalStatusChangedListener` wrapped as a [ThermalStatusSource]. */
private class PowerManagerThermalStatusSource(
    private val powerManager: PowerManager,
) : ThermalStatusSource {
    override fun addListener(onChanged: () -> Unit): AutoCloseable {
        val listener = PowerManager.OnThermalStatusChangedListener { _ -> onChanged() }
        powerManager.addThermalStatusListener(listener)
        return AutoCloseable { powerManager.removeThermalStatusListener(listener) }
    }
}
