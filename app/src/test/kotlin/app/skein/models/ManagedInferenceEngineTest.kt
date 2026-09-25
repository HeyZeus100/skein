// skein-gg11.22: `warmUp` is the lazy load `stream` performs, exposed so the
// send pipeline can run it before prompt assembly (token counting needs the
// bound service). Pure JVM over `FakeInferenceEngine`.

package app.skein.models

import app.skein.core.model.Capability
import app.skein.core.model.EngineState
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.testing.FakeInferenceEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedInferenceEngineTest {
    private val model =
        Model(
            id = "qwen-test-000000000000",
            name = "qwen-test",
            path = "/models/qwen-test-000000000000/model.gguf",
            // Not FakeInferenceEngine's BAD_HASH sentinel.
            sha256 = "ab".repeat(32),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 4_096L,
            contextLength = 4_096,
            attestationUrl = null,
            companions = emptyMap(),
            importedAt = 0L,
        )

    @Test
    fun `warmUp loads the default when nothing is loaded and is then a no-op`() =
        runTest {
            val delegate = FakeInferenceEngine()
            val managed = ManagedInferenceEngine(delegate) { model }
            assertThat(managed.status.value.state).isEqualTo(EngineState.UNLOADED)

            managed.warmUp()

            assertThat(delegate.currentModel).isEqualTo(model)
            assertThat(managed.status.value).isEqualTo(
                app.skein.core.model
                    .ModelStatus(model.id, EngineState.READY),
            )

            managed.warmUp()

            assertThat(managed.status.value.state).isEqualTo(EngineState.READY)
        }

    @Test
    fun `warmUp without a default is ModelNotLoaded, exactly like a send would be`() =
        runTest {
            val managed = ManagedInferenceEngine(FakeInferenceEngine()) { null }

            assertThrows(InferenceException.ModelNotLoaded::class.java) {
                kotlinx.coroutines.runBlocking { managed.warmUp() }
            }
            assertThat(managed.status.value.state).isEqualTo(EngineState.UNLOADED)
        }
}
