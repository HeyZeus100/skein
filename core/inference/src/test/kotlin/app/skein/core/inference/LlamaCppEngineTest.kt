// `E10.I3` (skein-gzr) placeholder for `E4.I4` / skein-1uw — `LlamaCppEngine`,
// the app-side `InferenceEngine` over the bound `:inference` service (plan
// `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` lines 2983-3022).
// `InferenceEngineContractTest`'s own file header already names this exact
// class (`LlamaCppEngineTest : InferenceEngineContractTest()`), so this
// file's name and package are the ones skein-1uw's implementer should keep
// — only the `@Ignore` and the two overrides below need to change.
//
// ## Why this lives in `src/test` (JVM), not `src/androidTest`
//
// The *real* `LlamaCppEngineTest` will need to be instrumented (plan Step
// 2: "Contract subclass + death test on emulator" — it binds a real
// isolated Android service). This placeholder never builds a working
// engine at all (every override below is `TODO()`, and the class is
// `@Ignore`d), so nothing about it actually needs the Android runtime
// today. Keeping the placeholder JVM-runnable is also what lets
// `./gradlew test` / `contractReport` show its "pending skein-1uw" status
// without an emulator or device — this worktree's non-negotiables forbid
// touching one (`E10.I3`).
//
// `ContractReportTask.SUITES` (`build-logic/guards`) is the source of
// truth for the "skein-1uw" bead id shown by `contractReport` — not the
// `@Ignore` reason string here, which Gradle's JUnit XML writer drops (see
// that task's KDoc).
//
// When skein-1uw is claimed: delete this file and create the real
// `core/inference/src/androidTest/kotlin/app/skein/core/inference/LlamaCppEngineTest.kt`
// per the plan's Step 2, then update `ContractReportTask.SUITES`' entry
// for this suite/implementation to point at it and drop `pendingBead`.
package app.skein.core.inference

import app.skein.core.model.InferenceEngine
import app.skein.core.model.Model
import app.skein.testing.InferenceEngineContractTest
import org.junit.Ignore

@Ignore("pending skein-1uw")
class LlamaCppEngineTest : InferenceEngineContractTest() {
    override fun engine(): InferenceEngine =
        TODO(
            "skein-1uw (E4.I4): construct LlamaCppEngine(context, InferenceConfig, io) once it exists; " +
                "see the plan's E4.I4 section for the binder/DeathRecipient wiring.",
        )

    override fun textModel(): Model =
        TODO(
            "skein-1uw (E4.I4): a fixture Model carrying Capability.TEXT and NOT Capability.EMBEDDING, " +
                "loadable by the real LlamaCppEngine.",
        )
}
