// skein-7p0 (E4.I9) — a scriptable, call-counting HeadroomSource for
// ThermalGovernorCore's poll-cadence tests ("fake clock + counting headroom
// source" per the bd brief).

package app.skein.core.inference.thermal

internal class FakeHeadroomSource(
    @Volatile var value: Float = 0f,
) : HeadroomSource {
    var callCount: Int = 0
        private set

    override fun headroomNow(): Float {
        callCount++
        return value
    }
}
