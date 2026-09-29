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
// `tools/models/test-model.lock`; an absent asset is a hard failure with the
// fetch instruction (the lane records assumption skips as failures, and the
// fetch task guarantees the asset there).

package app.skein.inference

import android.app.UiAutomation
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
import app.skein.ipc.ErrorCode
import app.skein.testing.InferenceEngineContractTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class LlamaCppEngineInstrumentedTest : InferenceEngineContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private lateinit var store: ImmutableModelStore
    private lateinit var manifest: ModelManifest
    private lateinit var engine: LlamaCppEngine
    private lateinit var modelSha256: String
    private lateinit var modelId: String
    private var currentEpoch = EPOCH

    @Before
    fun setUp() {
        // The asset is a required fixture: :app:fetchTestModel writes it into
        // the test APK before merge*AndroidTestAssets, and the lane records an
        // assumption skip as a failure anyway (skein-i1dz), so a missing asset
        // is a hard, clearly worded failure rather than a silent early return
        // that would leave the lateinit fields unset for every test.
        val model =
            checkNotNull(loadTinyModel()) {
                "androidTest asset tiny.gguf absent — run ./gradlew :app:fetchTestModel on a host with network (E4.I2 / skein-80p)"
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
                sessionEpoch = { currentEpoch },
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
     * A small, bounded generation. Greedy and seeded so the run is
     * deterministic.
     *
     * skein-gg11.12: this used to be `maxTokens = 256`, "longer than the
     * contract's default so a generation is still in flight when
     * `second_stream_fails_busy` starts its second collector and when
     * `cancel_stops_within_100ms` cancels". Both `stream_emits_done_last`
     * and `serviceDeathFailsTheStreamAndTheNextLoadRebinds` then timed out
     * with `UncompletedCoroutinesError: After waiting for 1m` on the
     * emulator (run 35879189936) — 256 tokens implies throughput under
     * 256 / 60 ≈ 4.3 tokens/sec on this lane's (unaccelerated, no-SIMD)
     * x86_64 CPU for a model this size (SmolLM2-135M-Instruct Q2_K, 88 MiB
     * — tools/models/test-model.lock). 32 tokens at that same rate is
     * ~7.5s, leaving roughly 50s of headroom in `runTest`'s 60s budget for
     * model load, the AIDL round trips and — for the death test — a second
     * load on rebind; even at a far more pessimistic 1 token/sec, 32
     * tokens is 32s and still comfortably inside budget.
     *
     * Cancel/busy do not actually need MORE tokens than this to hold: the
     * production `stream()` (`LlamaCppEngine.kt`) buffers the whole
     * generation with `Channel.UNLIMITED` and only tears the flow down in
     * `awaitClose`, which runs when the DOWNSTREAM collector cancels or
     * drains the flow — not when the native side finishes producing
     * tokens. `cancel_stops_within_100ms` and `second_stream_fails_busy`
     * (`InferenceEngineContractTest`) both suspend their collector forever
     * on the FIRST token (`awaitCancellation()` / `holdFirst.await()`), so
     * the engine stays busy/cancellable regardless of how many further
     * tokens the native side has queued up in the meantime.
     */
    override fun samplingParams(): SamplingParams =
        SamplingParams(temperature = 0f, topK = 1, maxTokens = 32, seed = 1L)

    /**
     * Each contract case loads the 88 MiB model into the real isolated process
     * (hash, mmap, `llama_model_load`) before it streams; on the CI emulator's
     * unaccelerated x86_64 CPU that plus one bounded generation exceeded the
     * contract's 60 s default even at 32 tokens (runs 35879189936 and
     * 35885158666 — only the two cases that wait for a generation to finish
     * timed out; the cancel/busy cases, which stop at the first token, pass).
     * The Fold finishes in seconds. The budget is widened here; no assertion
     * changes.
     */
    override val testTimeout: Duration = 180.seconds

    // ------------------------------------------------- acceptance 2: death

    /**
     * Kills the isolated process under an active `stream` and asserts the flow
     * fails with [InferenceException.ServiceDied], then that `load` rebinds.
     *
     * Android's supported `am crash <pid>` route asks system_server to crash
     * this exact process. Unlike a shell-UID signal, it can target the isolated
     * UID without a production debug Binder method. The original PID must
     * disappear; inability to induce death is a test failure, not a skip.
     */
    @Test
    fun serviceDeathFailsTheStreamAndTheNextLoadRebinds(): Unit =
        runTest(timeout = testTimeout) {
            engine.load(textModel()).getOrThrow()

            // The kill must land WHILE the stream is in flight, so it runs on
            // its own thread beside the collector rather than after it.
            val killer =
                async(Dispatchers.IO) {
                    awaitGenerating()
                    crashInferenceProcess()
                }

            val failure =
                runCatching {
                    engine.stream(samplePrompt("write a long story"), samplingParams()).toList()
                }.exceptionOrNull()
            val crashedPid = killer.await()

            assertThat(failure).isInstanceOf(InferenceException.ServiceDied::class.java)
            assertThat(engine.load(textModel()).isSuccess).isTrue()
            val reboundPid = checkNotNull(inferencePid()) { "reloaded inference process is absent" }
            assertThat(reboundPid).isNotEqualTo(crashedPid)
        }

    @Test
    fun inspectReadsMetadataWithoutLoading(): Unit =
        runTest(timeout = testTimeout) {
            // H2 (skein-ktvz): the acceptance path for an imported model, run
            // against the real isolated process rather than a fake.
            val inspection = engine.inspect(binding())

            assertThat(inspection.errorCode).isEqualTo(0)
        }

    @Test
    fun lockAfterInspectionTerminatesTheProcessAndOnlyANewUnlockCanRebind(): Unit =
        runTest(timeout = testTimeout) {
            assertThat(engine.inspect(binding()).errorCode).isEqualTo(ErrorCode.OK)
            val oldPid = checkNotNull(inferencePid()) { "inspection process is absent" }
            engine.onSessionLocking(currentEpoch, 150L)
            engine.onSessionLocked(currentEpoch)
            assertThat(waitForDeath(oldPid)).isTrue()
            assertThat(engine.inspect(binding()).errorCode).isEqualTo(ErrorCode.SESSION_LOCKED)
            assertThat(engine.status.value.state).isEqualTo(EngineState.UNLOADED)
            currentEpoch++
            engine.onSessionUnlocked(currentEpoch)
            engine.load(textModel()).getOrThrow()
            assertThat(checkNotNull(inferencePid())).isNotEqualTo(oldPid)
            assertThat(engine.status.value.state).isEqualTo(EngineState.READY)
            engine.onSessionLocked(currentEpoch)
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

    /**
     * AOSP android-15.0.0_r1 ActivityManagerShellCommand.runCrash accepts a
     * numeric PID; AppErrors.scheduleAppCrashLocked selects that exact process
     * and preserves protected-package checks. ProcessRecord.scheduleCrashLocked
     * requests an exception in the target through system_server (skein-w8bh).
     * Source: https://github.com/aosp-mirror/platform_frameworks_base/tree/android-15.0.0_r1/
     * services/core/java/com/android/server/am
     */
    private fun crashInferenceProcess(): Int {
        val pid = checkNotNull(inferencePid()) { "active inference process is absent" }
        check(pid != android.os.Process.myPid()) { "refusing to crash the test process" }
        shell("am crash $pid")
        check(waitForDeath(pid)) { "am crash did not terminate the selected inference process" }
        return pid
    }

    private fun inferencePid(): Int? {
        val baseName = "${context.packageName}:inference"
        val isolatedName = "$baseName:app.skein.inference.service.InferenceService"
        val matches =
            shell("ps -A -o PID,NAME")
                .lineSequence()
                .map { it.trim().split(Regex("\\s+"), limit = 2) }
                .filter { it.size == 2 && (it[1] == baseName || it[1] == isolatedName) }
                .mapNotNull { it[0].toIntOrNull()?.takeIf { pid -> pid > 0 } }
                .toList()
        check(matches.size <= 1) { "multiple inference processes matched the test package" }
        return matches.singleOrNull()
    }

    private fun awaitGenerating() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (engine.status.value.state != EngineState.GENERATING && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        check(engine.status.value.state == EngineState.GENERATING) { "stream did not enter GENERATING" }
    }

    private fun waitForDeath(pid: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEATH_WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val alive = shell("ps -A -o PID").lineSequence().any { it.trim().toIntOrNull() == pid }
            if (!alive) return true
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

        /** Bound the wait for Android's targeted crash request to terminate the original PID. */
        const val DEATH_WAIT_SECONDS = 10L
    }
}
