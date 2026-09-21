// skein-7p0 (E4.I9) — pure `batteryOk` computation (charging OR >= 20%),
// split out from the `ACTION_BATTERY_CHANGED` sticky-intent read in
// `ThermalGovernor` so the rule itself is JVM-unit-testable without
// Robolectric. `BatteryManager`'s `BATTERY_STATUS_*`/`EXTRA_*` constants are
// plain compile-time `int`s (preserved as real values in AGP's mockable
// android.jar, unlike stubbed methods), so referencing them here costs
// nothing on the host JVM.

package app.skein.core.inference.thermal

import android.os.BatteryManager

/**
 * Pure evaluator for the governor's `batteryOk` signal: `true` when
 * charging (or full), or when unplugged battery level is `>= 20%`.
 */
public object BatteryOkEvaluator {
    /** The ingest worker's opportunistic-run floor (spec/bd: "charging or >= 20%"). */
    public const val MIN_UNPLUGGED_PERCENT: Int = 20

    /**
     * @param status one of `BatteryManager.BATTERY_STATUS_*`, as read from
     *   `Intent.EXTRA_STATUS` on the `ACTION_BATTERY_CHANGED` sticky intent.
     * @param level `Intent.EXTRA_LEVEL` from the same intent.
     * @param scale `Intent.EXTRA_SCALE` from the same intent — the value
     *   [level] is out of (Android does not guarantee this is 100).
     */
    public fun isOk(
        status: Int,
        level: Int,
        scale: Int,
    ): Boolean {
        if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
            return true
        }
        if (scale <= 0 || level < 0) {
            // No usable reading — fail closed rather than assume "probably fine".
            return false
        }
        val percent = (level * 100) / scale
        return percent >= MIN_UNPLUGGED_PERCENT
    }
}
