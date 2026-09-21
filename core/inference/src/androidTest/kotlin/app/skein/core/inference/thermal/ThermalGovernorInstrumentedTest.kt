// skein-7p0 (E4.I9) — instrumented smoke test for the ThermalGovernor
// Android adapter. Compiled unconditionally so the real-PowerManager/
// BatteryManager wiring stays type-safe under refactors; a full on-device
// run is gated by skein-k3b2 (emulator provisioning), same pattern as
// core/vault's VaultKeyProviderInstrumentedTest — see that file's KDoc.

package app.skein.core.inference.thermal

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThermalGovernorInstrumentedTest {
    @Test
    fun bindsToRealPowerManagerWithoutThrowing() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Context>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val governor = ThermalGovernor(context, powerManager)

        // Act — activate/deactivate exercise addThermalStatusListener/
        // removeThermalStatusListener; batteryOk exercises the sticky-intent
        // registerReceiver read. None of this should throw on a real device.
        governor.activate("instrumented-test")
        val batteryOk = governor.batteryOk
        governor.deactivate("instrumented-test")

        // Assert — either boolean outcome is acceptable; the point is
        // nothing threw and the adapter is wired to real system services.
        assertThat(batteryOk == true || batteryOk == false).isTrue()
    }
}
