// skein-xtov.23.18 (UT-4): `ScenarioInferenceEngine` must honour the same
// §4.1 contract as `FakeInferenceEngine` and the real engine (single `Done`,
// `Busy` on a second collector, cooperative cancellation, `ModelNotLoaded`
// after `unload`) — UX_TEST_PLAN.md §6.3's "runnable check". It plays
// S-FAST-ANSWER on RealTime pacing so the delays are real suspensions.

package app.skein.testing

import app.skein.core.model.Capability
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.testing.scenario.Pacing
import app.skein.testing.scenario.ScenarioInferenceEngine
import app.skein.testing.scenario.Scenarios

public class ScenarioInferenceEngineContractTest : InferenceEngineContractTest() {
    override fun engine(): InferenceEngine = ScenarioInferenceEngine(Scenarios.fastAnswer, Pacing.RealTime())

    override fun textModel(): Model =
        Model(
            id = "scenario-text-model",
            name = "Scenario Text Model",
            path = "/dev/null/scenario-text-model.gguf",
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1_000L,
        )
}
