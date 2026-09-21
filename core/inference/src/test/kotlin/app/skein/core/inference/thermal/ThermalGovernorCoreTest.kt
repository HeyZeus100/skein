// skein-7p0 (E4.I9) — ThermalGovernorCore JVM unit tests. One behaviour per
// test, AAA structure. Covers the bd brief's JVM test list: table lookup
// via the governor's published state; hysteresis (no flap across a
// threshold +/- epsilon); poll cadence <=1 Hz and only while active (fake
// clock + counting headroom source); status-listener short-circuit; Paused
// expiry re-evaluation; NaN handling; no polling when inactive.

package app.skein.core.inference.thermal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalGovernorCoreTest {
    private val table =
        ThermalBackoffTable(
            entries =
                listOf(
                    ThermalBackoffTable.Entry(0.00f, ThermalAction.Nominal),
                    ThermalBackoffTable.Entry(0.70f, ThermalAction.Reduced(threads = 2)),
                    ThermalBackoffTable.Entry(0.90f, ThermalAction.Paused(durationSeconds = 60)),
                ),
            hysteresisMargin = 0.05f,
        )

    private class Harness(
        table: ThermalBackoffTable,
    ) {
        val clock = TestClock()
        val headroom = FakeHeadroomSource()
        val status = FakeThermalStatusSource()
        var unsupportedCount = 0
        val core =
            ThermalGovernorCore(
                table = table,
                clock = clock,
                headroomSource = headroom,
                statusSource = status,
                onUnsupportedHeadroom = { unsupportedCount++ },
            )
    }

    // -------------------------------------------------------------- inactive / activation

    @Test
    fun `no polling when inactive`() {
        // Arrange
        val h = Harness(table)
        // Act
        h.core.tick()
        h.core.tick()
        // Assert
        assertThat(h.headroom.callCount).isEqualTo(0)
    }

    @Test
    fun `initial state is Nominal`() {
        // Arrange
        val h = Harness(table)
        // Act / Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Nominal)
    }

    @Test
    fun `activate then tick polls headroom exactly once`() {
        // Arrange
        val h = Harness(table)
        h.headroom.value = 0.10f
        // Act
        h.core.activate("inference")
        h.core.tick()
        // Assert
        assertThat(h.headroom.callCount).isEqualTo(1)
    }

    @Test
    fun `activate subscribes to the status source`() {
        // Arrange
        val h = Harness(table)
        // Act
        h.core.activate("inference")
        // Assert
        assertThat(h.status.isSubscribed).isTrue()
    }

    @Test
    fun `deactivate unsubscribes from the status source`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        // Act
        h.core.deactivate("inference")
        // Assert
        assertThat(h.status.isSubscribed).isFalse()
    }

    @Test
    fun `deactivate stops polling`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.core.tick()
        h.core.deactivate("inference")
        // Act — advance well past the cadence and tick again.
        h.clock.advance(5_000)
        h.core.tick()
        // Assert — no additional poll after deactivation.
        assertThat(h.headroom.callCount).isEqualTo(1)
    }

    @Test
    fun `reference counting requires a matching deactivate per activate`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.core.activate("ingest")
        // Act — only one of the two activations releases.
        h.core.deactivate("inference")
        // Assert — still active.
        assertThat(h.core.isActive).isTrue()
    }

    @Test
    fun `reference count reaching zero deactivates`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.core.activate("ingest")
        // Act
        h.core.deactivate("inference")
        h.core.deactivate("ingest")
        // Assert
        assertThat(h.core.isActive).isFalse()
    }

    // -------------------------------------------------------------- table lookup via state

    @Test
    fun `headroom well below the first threshold yields Nominal`() {
        // Arrange
        val h = Harness(table)
        h.headroom.value = 0.10f
        // Act
        h.core.activate("inference")
        h.core.tick()
        // Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Nominal)
    }

    @Test
    fun `headroom in the Reduced band yields Reduced with the table's thread count`() {
        // Arrange
        val h = Harness(table)
        h.headroom.value = 0.80f
        // Act
        h.core.activate("inference")
        h.core.tick()
        // Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Reduced(threads = 2))
    }

    @Test
    fun `headroom in the Paused band yields Paused with untilMs from the clock plus duration`() {
        // Arrange
        val h = Harness(table)
        h.headroom.value = 0.95f
        // Act
        h.core.activate("inference")
        h.core.tick()
        // Assert
        val expected = ThermalState.Paused(untilMs = h.clock.millis() + 60_000L)
        assertThat(h.core.state.value).isEqualTo(expected)
    }

    // -------------------------------------------------------------- hysteresis via state

    @Test
    fun `state does not flap back to Nominal within the hysteresis margin`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.headroom.value = 0.72f
        h.core.tick() // escalate to Reduced
        // Act — dip within the margin, forcing a fresh poll each time.
        h.headroom.value = 0.68f
        h.clock.advance(1_000)
        h.core.tick()
        // Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Reduced(threads = 2))
    }

    @Test
    fun `state de-escalates once headroom clears the hysteresis margin`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.headroom.value = 0.72f
        h.core.tick() // escalate to Reduced
        // Act
        h.headroom.value = 0.60f
        h.clock.advance(1_000)
        h.core.tick()
        // Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Nominal)
    }

    // -------------------------------------------------------------- poll cadence

    @Test
    fun `repeated ticks within one second do not repoll`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.core.tick() // poll #1, sets lastPoll = t0
        // Act
        h.clock.advance(500)
        h.core.tick()
        h.clock.advance(400)
        h.core.tick()
        // Assert — still under 1000ms since the first poll.
        assertThat(h.headroom.callCount).isEqualTo(1)
    }

    @Test
    fun `a tick at or past the one second cadence repolls`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.core.tick() // poll #1
        // Act
        h.clock.advance(1_000)
        h.core.tick()
        // Assert
        assertThat(h.headroom.callCount).isEqualTo(2)
    }

    // -------------------------------------------------------------- status listener short-circuit

    @Test
    fun `a status change event polls immediately even under the cadence`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.core.tick() // poll #1
        // Act — no clock advance at all.
        h.status.fireStatusChanged()
        // Assert
        assertThat(h.headroom.callCount).isEqualTo(2)
    }

    // -------------------------------------------------------------- NaN handling

    @Test
    fun `NaN headroom yields Nominal`() {
        // Arrange
        val h = Harness(table)
        h.headroom.value = Float.NaN
        // Act
        h.core.activate("inference")
        h.core.tick()
        // Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Nominal)
    }

    @Test
    fun `NaN headroom fires the unsupported callback exactly once across repeated NaN readings`() {
        // Arrange
        val h = Harness(table)
        h.headroom.value = Float.NaN
        h.core.activate("inference")
        // Act
        h.core.tick()
        h.clock.advance(1_000)
        h.core.tick()
        h.clock.advance(1_000)
        h.core.tick()
        // Assert
        assertThat(h.unsupportedCount).isEqualTo(1)
    }

    // -------------------------------------------------------------- Paused expiry

    @Test
    fun `Paused holds and does not poll before expiry`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.headroom.value = 0.95f
        h.core.tick() // enters Paused, untilMs = t0 + 60_000
        val callsAtPause = h.headroom.callCount
        // Act — advance but stay short of the deadline.
        h.clock.advance(30_000)
        h.core.tick()
        // Assert — no new poll while still within the pause window.
        assertThat(h.headroom.callCount).isEqualTo(callsAtPause)
    }

    @Test
    fun `Paused re-evaluates and de-escalates once expired and cooled`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.headroom.value = 0.95f
        h.core.tick() // enters Paused, untilMs = t0 + 60_000
        // Act — expire the pause with headroom now cooled well below Nominal's margin.
        h.headroom.value = 0.10f
        h.clock.advance(60_000)
        h.core.tick()
        // Assert
        assertThat(h.core.state.value).isEqualTo(ThermalState.Nominal)
    }

    @Test
    fun `Paused re-arms with a fresh deadline if still hot at expiry`() {
        // Arrange
        val h = Harness(table)
        h.core.activate("inference")
        h.headroom.value = 0.95f
        h.core.tick() // enters Paused, untilMs = t0 + 60_000
        val firstDeadline = (h.core.state.value as ThermalState.Paused).untilMs
        // Act — expire the pause with headroom still in the Paused band.
        h.clock.advance(60_000)
        h.core.tick()
        // Assert — still Paused, with a strictly later deadline.
        val renewed = h.core.state.value as ThermalState.Paused
        assertThat(renewed.untilMs).isGreaterThan(firstDeadline)
    }
}
