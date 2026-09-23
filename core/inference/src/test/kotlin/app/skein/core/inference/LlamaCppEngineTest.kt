// skein-1uw (E4.I4) — the `InferenceEngine` contract suite, run against the
// real `LlamaCppEngine`.
//
// This file replaces the `E10.I3` (skein-gzr) `@Ignore("pending skein-1uw")`
// placeholder that stood here. Its class name and package are unchanged, which
// is deliberate: `ContractReportTask.SUITES` (`build-logic/guards`) names
// `app.skein.core.inference.LlamaCppEngineTest` as the LlamaCppEngine row's
// test class, and a passing run now reports "passed" instead of "pending
// skein-1uw" with no change to that task (`statusFor`: `pendingBead` only
// applies while the class reports SKIPPED tests). The now-redundant
// `pendingBead = "skein-1uw"` field in `SUITES` is cosmetic and left for the
// owner of `build-logic`.
//
// ## Why this runs on the JVM, and what still has to run on a device
//
// The placeholder's header said the real suite "will need to be instrumented
// … it binds a real isolated Android service". That is true of the BIND, and
// only of the bind — which is exactly why `skein-1uw` put the bind behind
// `ServiceConnector`. With a fake `IInferenceService` injected there, every
// semantic the contract locks (one `Token.Done` last, cancellation latency,
// `Busy` on a second collector, `embed` capability, `load` never throwing,
// `ModelNotLoaded` after `unload`) is engine logic and runs here, on every
// push, with no emulator.
//
// What a fake cannot prove is that the real service agrees: that the isolated
// process accepts the descriptors this engine sends, that a real `load`
// returns `OK` for a real GGUF, and that a killed process surfaces as
// `ServiceDied`. `app/src/androidTest/.../LlamaCppEngineInstrumentedTest`
// subclasses the same suite against the real `:inference` service and the tiny
// GGUF, and adds the death test. The two are complementary, not redundant:
// this one runs everywhere, that one proves the boundary.
//
// Robolectric, not plain JUnit: `ParcelFileDescriptor` and `Parcel` have no
// JVM implementation in AGP's mockable `android.jar` (the same reason
// `WireBindingsTest` and `:core:ipc`'s round-trip tests need it), and both are
// on `load`'s and `stream`'s real paths.

package app.skein.core.inference

import app.skein.core.inference.engine.FakeInferenceService
import app.skein.core.inference.engine.FakeServiceConnector
import app.skein.core.inference.engine.LlamaCppEngine
import app.skein.core.inference.engine.StoreFixture
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Model
import app.skein.testing.InferenceEngineContractTest
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlamaCppEngineTest : InferenceEngineContractTest() {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var fixture: StoreFixture
    private lateinit var service: FakeInferenceService
    private lateinit var engine: LlamaCppEngine

    @Before
    fun setUp() {
        fixture = StoreFixture(temporaryFolder.newFolder("models"))
        service = FakeInferenceService()
        engine =
            LlamaCppEngine(
                connector = FakeServiceConnector(service),
                pins = fixture.pins,
                spillDir = temporaryFolder.newFolder("spill"),
                sessionEpoch = { EPOCH },
            )
    }

    override fun engine(): InferenceEngine = engine

    override fun textModel(): Model = fixture.model()

    private companion object {
        const val EPOCH = 42L
    }
}
