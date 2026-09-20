package app.skein.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeClockTest {
    @Test
    fun `now returns the start time before any advance`() {
        val clock = FakeClock(startMillis = 42L)

        assertThat(clock.now()).isEqualTo(42L)
    }

    @Test
    fun `advanceBy moves the clock forward and returns the new time`() {
        val clock = FakeClock(startMillis = 0L)

        val result = clock.advanceBy(1_000L)

        assertThat(result).isEqualTo(1_000L)
        assertThat(clock.now()).isEqualTo(1_000L)
    }

    @Test
    fun `set jumps to an exact time`() {
        val clock = FakeClock(startMillis = 0L)

        clock.set(9_999L)

        assertThat(clock.now()).isEqualTo(9_999L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `advanceBy rejects a negative duration`() {
        FakeClock().advanceBy(-1L)
    }
}
