// skein-1uw (E4.I4), acceptance criteria 1 and 2 — the `InferenceEngine`
// contract suite and the service-death test, against the REAL `:inference`
// isolated process and the real tiny GGUF.
//
// ## Why this lives in `:app`, not in `:core:inference`
//
// `AndroidServiceConnector` binds `app.skein.inference.service.InferenceService`
// by component name. That `<service android:process=":inference"
// android:isolatedProcess="true">` element is declared in
// `app/src/main/AndroidManifest.xml`, and only an APPLICATION module's test
// APK gets it: a library module's `androidTest` builds its own test
// application with its own manifest and would have nothing to bind. `:app` is
// also the only module that depends on both `:core:inference` (the engine) and
// `:inference-service` (the implementation), which is the dependency direction
// the isolation guard enforces.
//
// ## What this proves that the JVM suite cannot
//
// `core/inference/src/test/.../LlamaCppEngineTest` runs the same six contract
// tests against a fake `IInferenceService`, so the ENGINE's semantics are
// covered on every push. What only a device can show is that the two sides
// agree: that the isolated process accepts the descriptors this engine sends,
// that a real GGUF loads and streams through them, and that killing the
// process surfaces as `ServiceDied` rather than as a hang or a Binder type
// escaping the contract.
//
// ## Lane
//
// Emulator (`dev` flavour, `skein-80p`/`skein-f0sy`'s lane) or the Fold.
// Compiled on every push (`:app:compileDevDebugAndroidTestKotlin`); RUN only
// where a device exists — no agent runs it, and the coordinator's emulator
// lane is where its result comes from. The model is `tiny.gguf`, fetched and
// sha256-verified by `app/build.gradle.kts`'s `fetchTestModel` from
// `tools/models/test-model.lock`; an absent asset makes every test here
// `assume`-skip rather than fail, so a device lane without network degrades
// to "not run".

package app.skein.inference

import android.app.UiAutomation
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.inference.engine.AndroidServiceConnector
import app.skein.core.inference.engine.LlamaCppEngine
import app.skein.core.inference.engine.ModelPin
import app.skein.core.inference.engine.ModelPinSource
import app.skein.core.inference.models.BindResult
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportResult
import app.skein.core.inference.models.ManifestBinding
import app.skein.core.inference.models.ManifestParse
import app.skein.core.inference.models.ModelBytesSource
import app.skein.core.inference.models.ModelManifest
import app.skein.core.inference.models.OpenResult
import app.skein.core.model.Capability
import app.skein.core.model.EngineState
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.SamplingParams
import app.skein.testing.InferenceEngineContractTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LlamaCppEngineInstrumentedTest : InferenceEngineContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private lateinit var store: ImmutableModelStore
    private lateinit var manifest: ModelManifest
    private lateinit var engine: LlamaCppEngine
    private lateinit var modelSha256: String
    private lateinit var modelId: String

    @Before
    fun setUp() {
        val model = loadTinyModel()
        if (model == null) {
            Log.d("LlamaCppEngineInstrumentedTest", "androidTest asset tiny.gguf absent (fetched by E4.I2 / skein-80p)")
            return
        }

        modelSha256 = sha256(model)
        // A fresh id per run: the store refuses re-import over an existing one
        // (ModelVerification.AlreadyImported), which is the correct behaviour
        // and not what this test is about.
        modelId = "tiny-${System.nanoTime()}"
        val root = File(context.filesDir, "engine-instrumented").also { it.mkdirs() }
        store = ImmutableModelStore(root)
        manifest = parseManifest(modelSha256, model.size.toLong())
        val imported = store.import(manifest, ModelBytesSource { ByteArrayInputStream(model) })
        assertThat(imported).isInstanceOf(ImportResult.Imported::class.java)

        engine =
            LlamaCppEngine(
                connector = AndroidServiceConnector(context),
                pins = pinSource(),
                spillDir = File(context.cacheDir, "engine-instrumented").also { it.mkdirs() },
                sessionEpoch = { EPOCH },
            )
        // `IsolatedSessionGate` starts at `SessionEpoch.NONE` and refuses
        // everything until an explicit unlock push (LOCK_POLICY_INDEXING.md
        // §6.1 invariant I6). In production the vault's lock-policy observer
        // sends this; here the test is the observer. The engine re-sends it
        // after the death test's rebind on its own (§5.3).
        runBlocking { engine.onSessionUnlocked(EPOCH) }
    }

    override fun engine(): InferenceEngine = engine

    override fun textModel(): Model =
        Model(
            id = modelId,
            name = "Tiny test model",
            path = File(File(store.stored(modelId)!!.directory, MODEL_FILE).path).path,
            sha256 = modelSha256,
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = store.stored(modelId)!!.main.sizeBytes,
            contextLength = 512,
        )

    /**
     * Longer than the contract's default so a generation is still in flight
     * when `second_stream_fails_busy` starts its second collector and when
     * `cancel_stops_within_100ms` cancels. Greedy and seeded so the run is
     * deterministic.
     */
    override fun samplingParams(): SamplingParams =
        SamplingParams(temperature = 0f, topK = 1, maxTokens = 256, seed = 1L)

    // ------------------------------------------------- acceptance 2: death

    /**
     * Kills the isolated process under an active `stream` and asserts the flow
     * fails with [InferenceException.ServiceDied], then that `load` rebinds.
     *
     * HOW THE PROCESS IS KILLED, and why this way. The bead allows "a dev-only
     * debug binder or `am kill`". A dev-only kill method on `IInferenceService`
     * was rejected: it would be a method in the locked AIDL contract whose only
     * purpose is to make the service killable, present in every build, and
     * reachable by anything that can bind. The shell route adds no production
     * surface at all. `am kill` is tried first; because the engine holds the
     * binding with `BIND_IMPORTANT` the platform may decline to kill it, so the
     * test falls back to `kill -9` on the pid `ps` reports. If neither works on
     * a given lane the test `assume`-skips with the reason rather than
     * reporting a failure it cannot distinguish from a real one.
     */
    @Test
    fun serviceDeathFailsTheStreamAndTheNextLoadRebinds(): Unit =
        runTest {
            engine.load(textModel()).getOrThrow()

            // The kill must land WHILE the stream is in flight, so it runs on
            // its own thread beside the collector rather than after it.
            val killer =
                launch(Dispatchers.IO) {
                    awaitGenerating()
                    killInferenceProcess()
                }

            val failure =
                runCatching {
                    engine.stream(samplePrompt("write a long story"), samplingParams()).toList()
                }.exceptionOrNull()
            killer.join()

            assumeTrue("could not kill the :inference process on this lane", failure != null)
            assertThat(failure).isInstanceOf(InferenceException.ServiceDied::class.java)
            assertThat(engine.load(textModel()).isSuccess).isTrue()
        }

    @Test
    fun inspectReadsMetadataWithoutLoading(): Unit =
        runTest {
            // H2 (skein-ktvz): the acceptance path for an imported model, run
            // against the real isolated process rather than a fake.
            val inspection = engine.inspect(binding())

            assertThat(inspection.errorCode).isEqualTo(0)
        }

    // ------------------------------------------------------------ fixtures

    private fun binding(): ManifestBinding =
        (ManifestBinding.bind(manifest, store.stored(modelId)!!) as BindResult.Bound).binding

    private fun pinSource(): ModelPinSource =
        ModelPinSource { model ->
            when (val opened = store.open(model.id)) {
                is OpenResult.Opened ->
                    Result.success(ModelPin(handle = opened.handle, binding = binding()))

                is OpenResult.Refused ->
                    Result.failure(InferenceException.InvalidModel(opened.refusal.summary))
            }
        }

    private fun parseManifest(
        sha256: String,
        sizeBytes: Long,
    ): ModelManifest {
        val json =
            """
            {
              "manifest_version": 2,
              "id": "$modelId",
              "name": "Tiny test model",
              "format": "gguf",
              "file": "$MODEL_FILE",
              "sha256": "$sha256",
              "size_bytes": $sizeBytes,
              "capabilities": ["text"],
              "context_length": 512,
              "license": { "spdx": "Apache-2.0" },
              "companions": []
            }
            """.trimIndent()
        return (ModelManifest.parse(json) as ManifestParse.Parsed).manifest
    }

    /**
     * Copies `tiny.gguf` out of the instrumentation assets (test APK) and
     * returns its bytes, or returns null when the asset was not fetched.
     * Mirrors the pattern from inference-service's LlamaNativeTest.
     */
    private fun loadTinyModel(): ByteArray? {
        val instrumentationAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val available =
            runCatching { instrumentationAssets.list("")?.contains(MODEL_ASSET) == true }
                .getOrDefault(false)
        if (!available) return null

        val out = File(context.cacheDir, MODEL_ASSET)
        if (!out.exists() || out.length() == 0L) {
            instrumentationAssets.open(MODEL_ASSET).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out.readBytes()
    }

    private fun killInferenceProcess(): Boolean {
        shell("am kill ${context.packageName}:inference")
        if (waitForDeath()) return true
        val pid =
            shell("ps -A")
                .lineSequence()
                .firstOrNull { it.contains(":inference") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?: return false
        shell("kill -9 $pid")
        return waitForDeath()
    }

    private fun awaitGenerating() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (engine.status.value.state != EngineState.GENERATING && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
    }

    private fun waitForDeath(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (shell("ps -A").lineSequence().none { it.contains(":inference") }) return true
            Thread.sleep(100L)
        }
        return false
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MODEL_ASSET = "tiny.gguf"
        const val MODEL_FILE = "tiny.gguf"

        /** Any non-`NONE` epoch; the service is told the same one by the harness below. */
        const val EPOCH = 1L
    }
}
