// skein-7p0 (E4.I9) — ordered headroom-threshold -> action table.
//
// `PowerManager.getThermalHeadroom(forecastSeconds)` returns a value on the
// convention documented at
// https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int):
// 0.0 = cool, 1.0 = the device's own throttling threshold, values above 1.0
// mean the device is already throttling. Higher headroom == worse. This
// mirrors `tools/m0-benchmark/lib/thermal.sh`'s convention (kept for naming
// consistency only — that script's thresholds gate *benchmark* runs, not
// runtime inference/ingest, and are not reused here).
//
// Coordinator note on skein-7p0 (2026-09-21): dispatched ahead of skein-5hr
// (docs/MEASUREMENTS.md, which does not exist yet in this worktree) and
// skein-1uw. Ship an INJECTED placeholder table — no measurement-derived
// constants baked in. `DEFAULT` below is that placeholder: conservative,
// clearly marked, and expected to be replaced wholesale (thresholds AND
// actions) once skein-5hr lands real Fold thermal-headroom-vs-throughput
// data.

package app.skein.core.inference.thermal

/**
 * An ordered table mapping thermal headroom to a [ThermalAction], plus the
 * [hysteresisMargin] used to avoid flapping across a threshold.
 *
 * @param entries Must be non-empty, strictly ascending by
 *   [Entry.minHeadroom], and the first entry's [Entry.minHeadroom] must be
 *   `<= 0f` so every non-negative headroom reading matches some entry.
 * @param hysteresisMargin See [nextIndex] for the exact rule. Must be `>= 0f`.
 */
public data class ThermalBackoffTable(
    val entries: List<Entry>,
    val hysteresisMargin: Float = 0.05f,
) {
    /** One row: apply [action] once headroom is `>= minHeadroom` (subject to hysteresis — see [nextIndex]). */
    public data class Entry(
        val minHeadroom: Float,
        val action: ThermalAction,
    )

    init {
        require(entries.isNotEmpty()) { "ThermalBackoffTable requires at least one entry" }
        require(entries.first().minHeadroom <= 0f) {
            "first entry's minHeadroom must be <= 0 so it matches every reading, was " +
                "${entries.first().minHeadroom}"
        }
        require(entries.zipWithNext().all { (a, b) -> a.minHeadroom < b.minHeadroom }) {
            "entries must be strictly ascending by minHeadroom: $entries"
        }
        require(hysteresisMargin >= 0f) { "hysteresisMargin must be >= 0, was $hysteresisMargin" }
    }

    /**
     * The entry index that applies to [headroom] with no hysteresis — the
     * highest index whose [Entry.minHeadroom] is `<= headroom`. Used to seed
     * the very first evaluation (no prior state to hold hysteresis against).
     */
    public fun indexFor(headroom: Float): Int {
        var idx = 0
        for (i in entries.indices) {
            if (headroom >= entries[i].minHeadroom) idx = i else break
        }
        return idx
    }

    /**
     * The next entry index given a [currentIndex] and a fresh [headroom]
     * reading, applying hysteresis so a reading oscillating within
     * [hysteresisMargin] of a threshold does not flap the resulting action
     * back and forth.
     *
     * The rule is asymmetric, and deliberately fails toward more mitigation:
     *  - **Escalating** (moving to a higher-severity entry) happens
     *    immediately, at the entry's own [Entry.minHeadroom] — no margin.
     *    A worsening reading is trusted right away.
     *  - **De-escalating** (moving to a lower-severity entry) requires
     *    headroom to drop *below* the current entry's `minHeadroom -
     *    hysteresisMargin` — i.e. it must clear the threshold that
     *    triggered escalation by at least the margin before backing off.
     *
     * Both directions cascade: a single reading can jump multiple levels
     * (e.g. cool -> Paused directly, or Paused -> Nominal directly if the
     * reading crashes well below every threshold).
     */
    public fun nextIndex(
        headroom: Float,
        currentIndex: Int,
    ): Int {
        var idx = currentIndex.coerceIn(entries.indices)
        while (idx + 1 < entries.size && headroom >= entries[idx + 1].minHeadroom) {
            idx++
        }
        while (idx > 0 && headroom < entries[idx].minHeadroom - hysteresisMargin) {
            idx--
        }
        return idx
    }

    public companion object {
        /**
         * TODO(skein-5hr): pin from `docs/MEASUREMENTS.md` once the M0 Fold
         * benchmark harness (`tools/m0-benchmark/`) records real
         * thermal-headroom-vs-throughput curves for the target device.
         *
         * These are conservative PLACEHOLDER values only — nothing here is
         * derived from a device measurement. They are chosen to fail safe
         * (govern earlier/harder rather than let the device throttle
         * unbounded) and exist only so the state machine has something to
         * drive during development and testing. Replace this entire table
         * (thresholds and actions) when skein-5hr lands.
         */
        public val DEFAULT: ThermalBackoffTable =
            ThermalBackoffTable(
                entries =
                    listOf(
                        Entry(minHeadroom = 0.00f, action = ThermalAction.Nominal),
                        Entry(minHeadroom = 0.70f, action = ThermalAction.Reduced(threads = 2)),
                        Entry(minHeadroom = 0.90f, action = ThermalAction.Paused(durationSeconds = 60)),
                    ),
                hysteresisMargin = 0.05f,
            )
    }
}
