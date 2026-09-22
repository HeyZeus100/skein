// `E10.I3` (skein-gzr) placeholder for `E5.I3` / skein-079 — the app-side
// `EmbedderServiceImpl` wrapping the `:embedder-service` Binder client
// (plan `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` lines
// 3284-3311). `EmbedderContractTest`'s own file header already names this
// exact class (`EmbedderServiceImplTest : EmbedderContractTest()`), so this
// file's name and package are the ones skein-079's implementer should
// keep — only the `@Ignore` and the three overrides below need to change.
//
// ## Why this lives in `src/test` (JVM), not `src/androidTest`
//
// The *real* `EmbedderServiceImplTest` runs "against the real service on
// the emulator" per the plan's `E5.I3` acceptance criteria — instrumented.
// This placeholder never builds a working service at all (every override
// below is `TODO()`, and the class is `@Ignore`d), so nothing about it
// actually needs the Android runtime today. Keeping the placeholder
// JVM-runnable is also what lets `./gradlew test` / `contractReport` show
// its "pending skein-079" status without an emulator or device — this
// worktree's non-negotiables forbid touching one (`E10.I3`).
//
// `ContractReportTask.SUITES` (`build-logic/guards`) is the source of
// truth for the "skein-079" bead id shown by `contractReport` — not the
// `@Ignore` reason string here, which Gradle's JUnit XML writer drops (see
// that task's KDoc).
//
// When skein-079 is claimed: delete this file and create the real
// `core/rag/src/androidTest/kotlin/app/skein/core/rag/embed/EmbedderServiceImplTest.kt`
// per the plan's Step 1 ("contract subclass on emulator → PASS"), then
// update `ContractReportTask.SUITES`' entry for this suite/implementation
// to point at it and drop `pendingBead`.
package app.skein.core.rag.embed

import org.junit.Ignore
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.testing.EmbedderContractTest

@Ignore("pending skein-079")
class EmbedderServiceImplTest : EmbedderContractTest() {
    override fun embedder(): EmbedderService =
        TODO(
            "skein-079 (E5.I3): construct EmbedderServiceImpl wrapping the :embedder-service Binder " +
                "client, with the batching (<= 32) and RemoteException mapping the plan describes.",
        )

    override fun embedModel(): Model = TODO("skein-079 (E5.I3): a fixture Model carrying Capability.EMBEDDING.")

    override fun rerankModel(): Model = TODO("skein-079 (E5.I3): a fixture Model carrying Capability.RERANK.")
}
