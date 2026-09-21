package app.skein.core.inference.thermal

/**
 * Android-free seam over `PowerManager.OnThermalStatusChangedListener`
 * (`PowerManager.addThermalStatusListener`/`removeThermalStatusListener`).
 * [ThermalGovernorCore] subscribes only while active (`activate()`
 * reference count > 0) and unsubscribes as soon as the last consumer
 * releases, per [ThermalGovernorCore]'s KDoc.
 */
public fun interface ThermalStatusSource {
    /**
     * Registers [onChanged] to be invoked (with no arguments — the listener
     * doesn't need the new status, just that *a* status change happened, so
     * the governor can re-poll headroom immediately) whenever the
     * platform's thermal status changes. Returns an [AutoCloseable] that
     * unregisters it; callers must close it exactly once.
     */
    public fun addListener(onChanged: () -> Unit): AutoCloseable

    public companion object {
        /** A source with no underlying platform listener — every poll is cadence-driven only. */
        public val NONE: ThermalStatusSource = ThermalStatusSource { AutoCloseable { } }
    }
}
