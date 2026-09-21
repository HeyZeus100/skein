// skein-7p0 (E4.I9) — ThermalBackoffTable lookup boundary + hysteresis
// tests. Uses a small fixture table (NOT `DEFAULT`, which is a placeholder
// expected to change wholesale post-skein-5hr) so these tests pin the
// *mechanism*, not any particular threshold values.

package app.skein.core.inference.thermal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalBackoffTableTest {
    // -------------------------------------------------------------- fixture

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

    // -------------------------------------------------------------- indexFor (no hysteresis)

    @Test
    fun `indexFor below first non-zero threshold is Nominal`() {
        // Arrange / Act
        val index = table.indexFor(0.69f)
        // Assert
        assertThat(index).isEqualTo(0)
    }

    @Test
    fun `indexFor exactly at Reduced threshold is Reduced`() {
        // Arrange / Act
        val index = table.indexFor(0.70f)
        // Assert
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `indexFor just below Paused threshold is still Reduced`() {
        // Arrange / Act
        val index = table.indexFor(0.89f)
        // Assert
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `indexFor exactly at Paused threshold is Paused`() {
        // Arrange / Act
        val index = table.indexFor(0.90f)
        // Assert
        assertThat(index).isEqualTo(2)
    }

    @Test
    fun `indexFor headroom above 1 stays at the highest entry`() {
        // Arrange / Act
        val index = table.indexFor(1.20f)
        // Assert
        assertThat(index).isEqualTo(2)
    }

    // -------------------------------------------------------------- nextIndex (hysteresis)

    @Test
    fun `nextIndex escalates immediately at the threshold with no margin`() {
        // Arrange — currently Nominal (index 0).
        // Act — headroom crosses straight to the Reduced threshold.
        val index = table.nextIndex(0.70f, currentIndex = 0)
        // Assert
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `nextIndex does not de-escalate within the hysteresis margin`() {
        // Arrange — currently Reduced (index 1), threshold 0.70, margin 0.05.
        // Act — headroom dips to 0.68, i.e. within [0.65, 0.70).
        val index = table.nextIndex(0.68f, currentIndex = 1)
        // Assert — holds at Reduced; does not flap back to Nominal.
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `nextIndex de-escalates once headroom clears the margin`() {
        // Arrange — currently Reduced (index 1), threshold 0.70, margin 0.05.
        // Act — headroom drops to 0.64, i.e. below 0.70 - 0.05.
        val index = table.nextIndex(0.64f, currentIndex = 1)
        // Assert
        assertThat(index).isEqualTo(0)
    }

    @Test
    fun `nextIndex does not flap across a threshold plus or minus epsilon`() {
        // Arrange
        var index = 0
        // Act — oscillate tightly around the Reduced threshold, staying
        // within the hysteresis band on every step after the first crossing.
        val readings = listOf(0.71f, 0.69f, 0.72f, 0.66f, 0.73f)
        val observed = readings.map { reading -> table.nextIndex(reading, index).also { index = it } }
        // Assert — escalates once and then holds; never drops back to Nominal.
        assertThat(observed).isEqualTo(listOf(1, 1, 1, 1, 1))
    }

    @Test
    fun `nextIndex escalation cascades multiple levels in one reading`() {
        // Arrange — currently Nominal (index 0).
        // Act — a single reading jumps straight into the Paused band.
        val index = table.nextIndex(0.95f, currentIndex = 0)
        // Assert
        assertThat(index).isEqualTo(2)
    }

    @Test
    fun `nextIndex de-escalation cascades multiple levels in one reading`() {
        // Arrange — currently Paused (index 2).
        // Act — headroom crashes well below every threshold's margin.
        val index = table.nextIndex(0.10f, currentIndex = 2)
        // Assert
        assertThat(index).isEqualTo(0)
    }

    // -------------------------------------------------------------- validation

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects an empty entry list`() {
        ThermalBackoffTable(entries = emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects a first entry with minHeadroom above zero`() {
        ThermalBackoffTable(entries = listOf(ThermalBackoffTable.Entry(0.10f, ThermalAction.Nominal)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects entries not strictly ascending`() {
        ThermalBackoffTable(
            entries =
                listOf(
                    ThermalBackoffTable.Entry(0.00f, ThermalAction.Nominal),
                    ThermalBackoffTable.Entry(0.50f, ThermalAction.Reduced(threads = 2)),
                    ThermalBackoffTable.Entry(0.50f, ThermalAction.Paused(durationSeconds = 60)),
                ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects a negative hysteresis margin`() {
        ThermalBackoffTable(
            entries = listOf(ThermalBackoffTable.Entry(0.00f, ThermalAction.Nominal)),
            hysteresisMargin = -0.01f,
        )
    }

    // -------------------------------------------------------------- DEFAULT placeholder

    @Test
    fun `DEFAULT is ascending and starts at Nominal`() {
        // Arrange / Act
        val default = ThermalBackoffTable.DEFAULT
        // Assert
        assertThat(default.entries.first().action).isEqualTo(ThermalAction.Nominal)
    }
}
