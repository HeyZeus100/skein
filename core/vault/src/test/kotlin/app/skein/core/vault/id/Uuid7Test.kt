// JVM unit tests for `Uuid7` (`E2.I3` acceptance criterion 1): 10,000
// generated UUIDs must be unique, lexically increasing when the clock is
// monotonic, and every UUID must carry version nibble `7` and variant bits
// `10`. Reuses `app.skein.core.vault.session.TestClock` (skein-pya) — it is
// `internal`, so visible across packages within this module's test source
// set — rather than hand-rolling another fake `java.time.Clock`.

package app.skein.core.vault.id

import app.skein.core.vault.session.TestClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class Uuid7Test {
    // 8-4-4-4-12 hex groups; version nibble fixed to `7`, variant nibble one
    // of `8`/`9`/`a`/`b` (top two bits `10`).
    private val canonicalForm = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    public fun `10000 uuids under a monotonic clock are unique lexically increasing and version-variant correct`() {
        val clock = TestClock(startEpochMillis = 1_700_000_000_000L)
        val ids =
            (1..10_000).map {
                clock.advance(1)
                Uuid7.generate(clock)
            }

        assertThat(ids.toSet()).hasSize(ids.size)
        for (id in ids) {
            assertThat(canonicalForm.matches(id)).isTrue()
        }
        for (i in 0 until ids.size - 1) {
            assertThat(ids[i] < ids[i + 1]).isTrue()
        }
    }

    @Test
    public fun `uuids in the same millisecond are unique and lexically increasing via the sub-ms counter`() {
        val clock = TestClock(startEpochMillis = 1_700_000_000_000L)
        // 5,000 > 4,096 (12-bit counter space) to also exercise the
        // counter-overflow-borrows-a-millisecond path.
        val ids = (1..5_000).map { Uuid7.generate(clock) }

        assertThat(ids.toSet()).hasSize(ids.size)
        for (i in 0 until ids.size - 1) {
            assertThat(ids[i] < ids[i + 1]).isTrue()
        }
    }

    @Test
    public fun `default clock parameter produces a well-formed uuid`() {
        val id = Uuid7.generate()
        assertThat(canonicalForm.matches(id)).isTrue()
    }

    @Test
    public fun `a clock that goes backward still yields a strictly greater uuid than the previous call`() {
        val clock = TestClock(startEpochMillis = 1_700_000_000_000L)
        val first = Uuid7.generate(clock)
        clock.advance(-500)
        val second = Uuid7.generate(clock)

        assertThat(second > first).isTrue()
    }
}
