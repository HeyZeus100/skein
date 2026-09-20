// Concrete `InferenceEngineContractTest` for `FakeInferenceEngine`. Proves
// the fake honors every semantic locked by spec § 4.1 on the JVM. The real
// engine (`LlamaCppEngine`, `E4.I4`) will subclass the same contract test
// on an instrumented device.

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.InferenceEngine
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.ModelFormat

public class FakeInferenceEngineTest : InferenceEngineContractTest() {
    override fun engine(): InferenceEngine =
        FakeInferenceEngine(
            script =
                mapOf(
                    // Matches `samplePrompt()`'s default userMessage.
                    "hi" to listOf("hello", " ", "world"),
                ),
        )

    override fun textModel(): Model =
        Model(
            id = "fake-text-model",
            name = "Fake Text Model",
            path = "/dev/null/fake-text-model.gguf",
            // A stable non-zero digest so `load_bad_hash_returns_failure`
            // can flip it to `badHashSentinel` and observe failure.
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1_000L,
        )
}
