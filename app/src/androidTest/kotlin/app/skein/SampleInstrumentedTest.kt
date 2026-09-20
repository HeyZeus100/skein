package app.skein

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E10.I1's sample instrumented test: proves the instrumented lane
 * (`connectedDevDebugAndroidTest`, gated behind `emulator.yml`'s
 * `workflow_dispatch` + nightly schedule) actually runs something real on a
 * device/emulator, rather than an empty task succeeding vacuously. Real
 * instrumented coverage (`DocumentsProvider` handshake, real `WorkManager`,
 * JNI `.so` loading) lands with the issues that need it (`E10.I9`,
 * `E10.I14`, etc.).
 */
@RunWith(AndroidJUnit4::class)
class SampleInstrumentedTest {
    @Test
    fun instrumentationTargetsTheAppUnderTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("app.skein", context.packageName)
    }
}
