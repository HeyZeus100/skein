// skein-7p0 (E4.I9) — BatteryOkEvaluator JVM unit tests (charging / >= 20% /
// < 20% unplugged), per the bd brief's JVM test list.

package app.skein.core.inference.thermal

import android.os.BatteryManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatteryOkEvaluatorTest {
    @Test
    fun `charging is ok regardless of level`() {
        // Arrange / Act
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_CHARGING, level = 1, scale = 100)
        // Assert
        assertThat(ok).isTrue()
    }

    @Test
    fun `full is ok regardless of level`() {
        // Arrange / Act
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_FULL, level = 1, scale = 100)
        // Assert
        assertThat(ok).isTrue()
    }

    @Test
    fun `unplugged at or above 20 percent is ok`() {
        // Arrange / Act
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_DISCHARGING, level = 20, scale = 100)
        // Assert
        assertThat(ok).isTrue()
    }

    @Test
    fun `unplugged below 20 percent is not ok`() {
        // Arrange / Act
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_DISCHARGING, level = 19, scale = 100)
        // Assert
        assertThat(ok).isFalse()
    }

    @Test
    fun `unplugged 20 percent scaled to a non-100 scale is ok`() {
        // Arrange / Act — 10 out of 50 == 20%.
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_DISCHARGING, level = 10, scale = 50)
        // Assert
        assertThat(ok).isTrue()
    }

    @Test
    fun `not charging and unknown status is not ok below the floor`() {
        // Arrange / Act
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING, level = 5, scale = 100)
        // Assert
        assertThat(ok).isFalse()
    }

    @Test
    fun `an unreadable scale fails closed`() {
        // Arrange / Act
        val ok = BatteryOkEvaluator.isOk(status = BatteryManager.BATTERY_STATUS_DISCHARGING, level = 50, scale = 0)
        // Assert
        assertThat(ok).isFalse()
    }
}
