// `E10.I3` (skein-gzr) placeholder for `E5.I15` / skein-82g —
// `PromptAssemblerImpl` (plan
// `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` lines 3594-3618).
// `PromptAssemblerContractTest`'s own file header already names this exact
// class (`PromptAssemblerImplTest : PromptAssemblerContractTest()`), so
// this file's name and package are the ones skein-82g's implementer
// should keep — only the `@Ignore` and the override below need to change.
//
// Unlike `LlamaCppEngineTest`/`EmbedderServiceImplTest` (both instrumented
// suites), `PromptAssemblerImpl` is plain Kotlin over `core/model` types —
// the plan's own `E5.I15` steps never mention an emulator, and
// `GuardedPromptAssemblerContractTest` (`core/security/src/test`) already
// proves the §7.3 + `PromptGuard` layout this class must produce entirely
// on the JVM. So `src/test` is this suite's genuine, permanent home, not a
// stand-in for `src/androidTest` the way the other two placeholders are.
//
// `ContractReportTask.SUITES` (`build-logic/guards`) is the source of
// truth for the "skein-82g" bead id shown by `contractReport` — not the
// `@Ignore` reason string here, which Gradle's JUnit XML writer drops (see
// that task's KDoc).
package app.skein.core.rag.prompt

import org.junit.Ignore
import us.aherrera.skein.core.model.PromptAssembler
import us.aherrera.skein.testing.PromptAssemblerContractTest

@Ignore("pending skein-82g")
class PromptAssemblerImplTest : PromptAssemblerContractTest() {
    override fun assembler(): PromptAssembler =
        TODO(
            "skein-82g (E5.I15): construct PromptAssemblerImpl(). See " +
                "core/security's GuardedPromptAssemblerContractTest/GuardedReferenceAssembler for the " +
                "exact §7.3 layout + PromptGuard.wrapRetrieved placement this must match, and " +
                "core/rag's own PromptAssemblerImpl.kt (to be created here) for where it lives.",
        )
}
