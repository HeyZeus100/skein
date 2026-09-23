// Proves `FakeEmbedderService` satisfies `EmbedderContractTest` on the JVM
// (`E0.I17`).

package app.skein.testing

import app.skein.core.model.Capability
import app.skein.core.model.EmbedderService
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat

public class FakeEmbedderServiceTest : EmbedderContractTest() {
    override fun embedder(): EmbedderService = FakeEmbedderService()

    override fun embedModel(): Model =
        Model(
            id = "fake-embed-model",
            name = "Fake Embed Model",
            path = "/dev/null/fake-embed-model.onnx",
            sha256 = "b".repeat(64),
            format = ModelFormat.ONNX,
            capabilities = setOf(Capability.EMBEDDING),
            sizeBytes = 1_000L,
        )

    override fun rerankModel(): Model =
        Model(
            id = "fake-rerank-model",
            name = "Fake Rerank Model",
            path = "/dev/null/fake-rerank-model.onnx",
            sha256 = "c".repeat(64),
            format = ModelFormat.ONNX,
            capabilities = setOf(Capability.RERANK),
            sizeBytes = 1_000L,
        )
}
