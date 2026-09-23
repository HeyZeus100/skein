// skein-5oi (E4.I6), app-side acceptance criterion: "`SamplingDefaultsTest`
// pins the per-model values and the override merge (`persona.sampling ?:
// defaults`)."
//
// PROVENANCE OF THE NUMBERS PINNED HERE
// ============================================================================
//
// Plan `E4.I6` says the values come "per the model cards recorded in
// `models/MANIFEST.md`". **That file does not exist** — `E0.I4`, the bead that
// writes it, is unstarted and there is no `models/` directory in this
// repository. So what this test pins is the PLAN LINE:
//
//   "Qwen: temp 0.7/top-p 0.8/rep 1.05; Gemma: temp 1.0/top-k 64/top-p 0.95"
//
// and nothing stronger. When `E0.I4` lands, these values must be checked
// against the cards `models/MANIFEST.md` records; if a card disagrees, this
// test is the one line that changes with it. Pinning them anyway is the point
// of the test: an accidental edit to `SamplingDefaults` fails here rather than
// quietly changing how every answer is sampled.

package app.skein.core.inference.engine

import app.skein.core.model.Capability
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.SamplingParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SamplingDefaultsTest {
    // --------------------------------------------------------------- Qwen

    @Test
    fun qwenTemperatureIsPinned() {
        assertThat(SamplingDefaults.forModel(qwen()).temperature).isEqualTo(0.7f)
    }

    @Test
    fun qwenTopPIsPinned() {
        assertThat(SamplingDefaults.forModel(qwen()).topP).isEqualTo(0.8f)
    }

    @Test
    fun qwenRepeatPenaltyIsPinned() {
        assertThat(SamplingDefaults.forModel(qwen()).repeatPenalty).isEqualTo(1.05f)
    }

    @Test
    fun qwenLeavesUnstatedFieldsAtTheSpecBaseline() {
        // The plan states three values for Qwen and no more. Inventing a
        // per-family `topK` would be an unmeasured number with no authority.
        assertThat(SamplingDefaults.forModel(qwen()).topK).isEqualTo(SamplingParams().topK)
    }

    // -------------------------------------------------------------- Gemma

    @Test
    fun gemmaTemperatureIsPinned() {
        assertThat(SamplingDefaults.forModel(gemma()).temperature).isEqualTo(1.0f)
    }

    @Test
    fun gemmaTopKIsPinned() {
        assertThat(SamplingDefaults.forModel(gemma()).topK).isEqualTo(64)
    }

    @Test
    fun gemmaTopPIsPinned() {
        assertThat(SamplingDefaults.forModel(gemma()).topP).isEqualTo(0.95f)
    }

    @Test
    fun gemmaLeavesUnstatedFieldsAtTheSpecBaseline() {
        assertThat(SamplingDefaults.forModel(gemma()).repeatPenalty).isEqualTo(SamplingParams().repeatPenalty)
    }

    // ----------------------------------------------------------- detection

    @Test
    fun theFamilyIsFoundInTheModelId() {
        assertThat(SamplingDefaults.forModel(model(id = "qwen-2.5-3b-abliterated-q4km", name = "Local model")))
            .isEqualTo(SamplingDefaults.QWEN)
    }

    @Test
    fun theFamilyIsFoundInTheModelName() {
        assertThat(SamplingDefaults.forModel(model(id = "imported-0001", name = "Gemma 4 E4B Instruct Q4_K_M")))
            .isEqualTo(SamplingDefaults.GEMMA)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertThat(SamplingDefaults.forModel(model(id = "QWEN-2.5-3B", name = "x")))
            .isEqualTo(SamplingDefaults.QWEN)
    }

    @Test
    fun anImportedModelOfNoKnownFamilyGetsTheSpecBaseline() {
        // Spec §3.1: "user can import any GGUF". An unrecognised model is a
        // first-class case, not an error.
        assertThat(SamplingDefaults.forModel(model(id = "imported-0001", name = "Some Other Model")))
            .isEqualTo(SamplingParams())
    }

    // -------------------------------------------------------- the override

    @Test
    fun aPersonaOverrideReplacesTheFamilyDefaults() {
        val override = SamplingParams(temperature = 0.2f, topP = 0.5f)

        assertThat(SamplingDefaults.forPersona(qwen(), override)).isEqualTo(override)
    }

    @Test
    fun noPersonaOverrideFallsBackToTheFamilyDefaults() {
        assertThat(SamplingDefaults.forPersona(qwen(), null)).isEqualTo(SamplingDefaults.QWEN)
    }

    @Test
    fun theOverrideReplacesRatherThanMerges() {
        // `persona.sampling ?: defaults` — a persona that set only
        // `temperature` must NOT silently inherit Gemma's topK when the user
        // switches models.
        val override = SamplingParams(temperature = 0.2f)

        assertThat(SamplingDefaults.forPersona(gemma(), override).topK).isEqualTo(SamplingParams().topK)
    }

    // ------------------------------------------------------------ fixtures

    private fun qwen(): Model = model(id = "qwen-2.5-3b-abliterated-q4km", name = "Qwen2.5 3B Instruct (abliterated)")

    private fun gemma(): Model = model(id = "gemma-4-e4b-it-q4km", name = "Gemma 4 E4B Instruct Q4_K_M")

    private fun model(
        id: String,
        name: String,
    ): Model =
        Model(
            id = id,
            name = name,
            path = "/dev/null",
            sha256 = "0".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1L,
        )
}
