// skein-nxk (E4.I3, coordinator decision skein-hiwb): the pure binding
// vocabulary the re-typed `ModelVerifier` speaks.
//
// `:core:inference`'s `ManifestBinding` is a *store-side* value — it carries
// `java.io.File` paths and is produced by binding a parsed manifest against an
// `ImmutableModelStore` directory. The isolated service has neither, so the
// verifier cannot be typed in it. `VerifyBinding` is the intersection both
// callers can produce: the app side via `ManifestBinding.toVerifyBinding()`,
// the service side from the wire `ManifestFileRef`s it was handed.

package app.skein.core.verify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class VerifyBindingTest {
    @Test
    fun `main is the file whose role is MAIN`() {
        val binding = VerifyBinding(listOf(companionFile(), mainFile()))

        assertThat(binding.main.role).isEqualTo(ModelFileRole.MAIN)
    }

    @Test
    fun `byRole finds a companion`() {
        val binding = VerifyBinding(listOf(mainFile(), companionFile()))

        assertThat(binding.byRole(ModelFileRole.TOKENIZER)).isEqualTo(companionFile())
    }

    @Test
    fun `byRole returns null for a role the binding does not carry`() {
        val binding = VerifyBinding(listOf(mainFile()))

        assertThat(binding.byRole(ModelFileRole.MMPROJ)).isNull()
    }

    @Test
    fun `companions excludes the main file`() {
        val binding = VerifyBinding(listOf(mainFile(), companionFile()))

        assertThat(binding.companions).containsExactly(companionFile())
    }

    @Test
    fun `a binding without a main file is rejected at construction`() {
        val thrown =
            runCatching { VerifyBinding(listOf(companionFile())) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty binding is rejected at construction`() {
        val thrown = runCatching { VerifyBinding(emptyList()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a duplicate role is rejected at construction`() {
        val thrown =
            runCatching { VerifyBinding(listOf(mainFile(), mainFile())) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `expectedBlake3 is optional — the wire binding carries no post-mmap digest`() {
        // `us.aherrera.skein.ipc.ManifestFileRef` has `expectedSha256` and
        // `expectedSizeBytes` and nothing else, so a service-side binding
        // cannot supply one. `ModelVerifier` falls back to the BLAKE3 it
        // observed during the pre-mmap pass — see `ModelVerifierPinnedTest`.
        assertThat(mainFile().expectedBlake3).isNull()
    }

    @Test
    fun `path is optional — the service never has one`() {
        assertThat(mainFile().path).isNull()
    }

    @Test
    fun `a path-carrying file keeps its path`() {
        val file = mainFile().copy(path = File("/models/x/model.gguf"))

        assertThat(file.path).isEqualTo(File("/models/x/model.gguf"))
    }

    private fun mainFile() =
        VerifyFile(
            role = ModelFileRole.MAIN,
            expectedSha256 = "00".repeat(32),
            expectedSizeBytes = 4_096L,
        )

    private fun companionFile() =
        VerifyFile(
            role = ModelFileRole.TOKENIZER,
            expectedSha256 = "11".repeat(32),
            expectedSizeBytes = 512L,
        )
}
