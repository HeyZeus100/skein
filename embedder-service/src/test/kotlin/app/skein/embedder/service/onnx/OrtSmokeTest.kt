package app.skein.embedder.service.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test for E1.I6: proves ONNX Runtime is wired into `:embedder-service`
 * end to end — native library load, session creation, and inference — using a
 * tiny bundled 1x1 identity model (`src/test/resources/identity.onnx`) rather
 * than a real embedding/NER model.
 *
 * Runs under Robolectric because [OnnxSession] is exercised the same way real
 * consumers (`EmbedPipeline`, `NerPipeline` — E5.I1) will use it, on an
 * Android-shaped JVM test classpath. The desktop `onnxruntime` JVM artifact
 * (testImplementation only, see `embedder-service/build.gradle.kts`) supplies
 * the native library for this host OS; the `onnxruntime-android` artifact used
 * by production code ships arm64-v8a `.so`s that only run on-device.
 */
@RunWith(RobolectricTestRunner::class)
class OrtSmokeTest {
    @Test
    fun `loads identity model and runs inference`() {
        val modelBytes = javaClass.classLoader!!.getResourceAsStream("identity.onnx")!!.use { it.readBytes() }

        OnnxSession.load(modelBytes).use { session ->
            assertEquals(setOf("input"), session.inputNames)
            assertEquals(setOf("output"), session.outputNames)

            val input = floatArrayOf(3.5f)
            val output = session.runFloat(inputName = "input", shape = longArrayOf(1, 1), data = input)

            assertArrayEquals(input, output, 0.0f)
        }
    }
}
