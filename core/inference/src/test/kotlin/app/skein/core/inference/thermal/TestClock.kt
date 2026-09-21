// skein-7p0 (E4.I9) — a controllable `java.time.Clock` for
// ThermalGovernorCore's JVM tests. Same pattern as
// core/vault's UnlockManager `TestClock` (advance(millis) mutating op).

package app.skein.core.inference.thermal

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class TestClock(
    startEpochMillis: Long = 1_700_000_000_000L,
) : Clock() {
    @Volatile
    private var nowMillis: Long = startEpochMillis

    fun advance(millis: Long) {
        nowMillis += millis
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)

    override fun millis(): Long = nowMillis
}
