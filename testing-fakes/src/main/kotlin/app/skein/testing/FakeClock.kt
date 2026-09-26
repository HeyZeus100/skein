package app.skein.testing

/**
 * A deterministic, manually-advanced clock for tests that need reproducible
 * timestamps (UUIDv7 generation, ingest ordering, TTL/expiry logic). Never
 * reads the system clock.
 *
 * ```kotlin
 * val clock = FakeClock(startMillis = 0L)
 * clock.now() // 0L
 * clock.advanceBy(1_000L)
 * clock.now() // 1_000L
 * ```
 */
class FakeClock(
    startMillis: Long = 0L,
) {
    private var currentMillis: Long = startMillis

    /** Current time in epoch millis. */
    fun now(): Long = currentMillis

    /** Advances the clock by [millis] (must be >= 0) and returns the new time. */
    fun advanceBy(millis: Long): Long {
        require(millis >= 0) { "millis must be >= 0, was $millis" }
        currentMillis += millis
        return currentMillis
    }

    /** Sets the clock to an exact [millis] value. */
    fun set(millis: Long) {
        currentMillis = millis
    }
}
