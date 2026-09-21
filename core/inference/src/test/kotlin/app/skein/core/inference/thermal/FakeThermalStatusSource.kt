// skein-7p0 (E4.I9) — a manually-triggerable ThermalStatusSource, standing
// in for `PowerManager.OnThermalStatusChangedListener`, so tests can fire a
// status-change event on demand and assert it short-circuits the poll
// cadence.

package app.skein.core.inference.thermal

internal class FakeThermalStatusSource : ThermalStatusSource {
    private val listeners = mutableListOf<() -> Unit>()

    var subscribeCount: Int = 0
        private set

    var unsubscribeCount: Int = 0
        private set

    override fun addListener(onChanged: () -> Unit): AutoCloseable {
        subscribeCount++
        listeners += onChanged
        return AutoCloseable {
            unsubscribeCount++
            listeners -= onChanged
        }
    }

    /** Simulates the platform firing `onThermalStatusChanged`. */
    fun fireStatusChanged() {
        listeners.toList().forEach { it() }
    }

    val isSubscribed: Boolean get() = listeners.isNotEmpty()
}
