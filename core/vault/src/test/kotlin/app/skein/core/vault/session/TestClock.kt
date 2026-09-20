// skein-pya (E3.I3a) — a controllable `java.time.Clock` for the JVM
// UnlockManagerTest suite. The vanilla `Clock.fixed()` is immutable; we
// want tests to advance time between transitions to exercise the idle-lock
// deadline math, so this fake exposes `advance(millis)` as a mutating op.

package app.skein.core.vault.session

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
