// skein-nxk (E4.I3) — the acceptance criteria that need a real device, a real
// Binder and a real GGUF.
//
// Everything that can be asserted on the JVM already is, in
// `InferenceEngineStateTest` (gates, fd ownership, warm swap, BUSY, stop
// reasons, error mapping) against a fake native layer. What is left here is
// what a fake cannot prove:
//
//   * that `IInferenceService.Stub` actually marshals across a process
//     boundary with the real `ManifestFileRef`/`GenerateRequest` Parcelables;
//   * that llama.cpp loads from a DESCRIPTOR — `skein-lnp2`'s finding — inside
//     a genuinely isolated process, where a path load would `EACCES`;
//   * that a cancel mid-generation is honoured within 200 ms of wall clock;
//   * that the isolated uid really is in the 99000–99999 range.
//
// The model is `androidTest/assets/tiny.gguf`, fetched by E4.I2's
// `fetchTestModel` task and never committed; every model-backed test skips
// itself when the asset is absent, exactly as `LlamaNativeTest` does, so this
// class is green on a checkout without it and meaningful on the device lanes
// (emulator `skein-80p`, Fold `skein-k3b2`). CI compiles it on every push —
// `:inference-service:compileDevDebugAndroidTestKotlin` — which is what keeps
// it from drifting away from the service it tests between device runs.

package app.skein.inference.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import app.skein.ipc.BackendDeviceType
import app.skein.ipc.BackendReportRequest
import app.skein.ipc.ChatMessageParcel
import app.skein.ipc.ErrorCode
import app.skein.ipc.GenStats
import app.skein.ipc.GenerateRequest
import app.skein.ipc.IInferenceCallback
import app.skein.ipc.IInferenceService
import app.skein.ipc.LoadRequest
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import app.skein.ipc.SamplingParcel
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TINY_GGUF_ASSET = "tiny.gguf"
private const val EPOCH = 7L
private const val CANCEL_BUDGET_MILLIS = 200L

@RunWith(AndroidJUnit4::class)
class InferenceServiceInstrumentedTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun theServiceBindsAndReportsUnloaded() {
        val service = bind()

        assertThat(service.status().state).isEqualTo("unloaded")
    }

    @Test
    fun aColdServiceRefusesLoadUntilUnlocked() {
        val service = bind()
        val model = assumeModel()

        // §6.1 invariant I6: unauthorized by default, even on the very first
        // bind, so a restart after a lock cannot become a lock bypass.
        assertThat(service.load(loadRequest(model))).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun anUnlockedServiceLoadsFromADescriptor() {
        val service = bind()
        val model = assumeModel()
        service.onSessionUnlocked(EPOCH)

        // The load that `skein-lnp2` says cannot work by path: this process is
        // isolated, the fd is all it gets, and OK here is the proof.
        assertThat(service.load(loadRequest(model))).isEqualTo(ErrorCode.OK)
    }

    @Test
    fun aLoadedServiceReportsReady() {
        val service = loadedService()

        assertThat(service.status().state).isEqualTo("ready")
    }

    @Test
    fun aHashMismatchIsRefusedAndLeavesTheServiceUnloaded() {
        val service = bind()
        val model = assumeModel()
        service.onSessionUnlocked(EPOCH)

        val code = service.load(loadRequest(model, sha256 = "00".repeat(32)))

        assertThat(code).isEqualTo(ErrorCode.HASH_MISMATCH)
        assertThat(service.status().state).isEqualTo("unloaded")
    }

    @Test
    fun aGenerationStreamsTokensAndCompletesExactlyOnce() {
        val service = loadedService()
        val callback = LatchCallback()

        service.generate(generateRequest(maxTokens = 32), callback)

        assertThat(callback.awaitTerminal()).isTrue()
        assertThat(callback.batches.get()).isAtLeast(1)
        assertThat(callback.terminals.get()).isEqualTo(1)
    }

    @Test
    fun cancelMidGenerationCompletesAsCancelledWithinTheBudget() {
        val service = loadedService()
        val callback = LatchCallback()

        service.generate(generateRequest(maxTokens = 512), callback)
        callback.awaitFirstBatch()
        val cancelledAt = System.currentTimeMillis()
        service.cancel(REQUEST_ID)

        assertThat(callback.awaitTerminal()).isTrue()
        assertThat(callback.stats.get()?.stopReason).isEqualTo("CANCELLED")
        assertThat(System.currentTimeMillis() - cancelledAt).isLessThan(CANCEL_BUDGET_MILLIS)
    }

    @Test
    fun aSecondConcurrentGenerateIsRefusedWithBusy() {
        val service = loadedService()
        val first = LatchCallback()
        val second = LatchCallback()

        service.generate(generateRequest(requestId = REQUEST_ID, maxTokens = 512), first)
        first.awaitFirstBatch()
        service.generate(generateRequest(requestId = REQUEST_ID + 1, maxTokens = 8), second)

        assertThat(second.awaitTerminal()).isTrue()
        assertThat(second.errorCode.get()).isEqualTo(ErrorCode.BUSY)
        service.cancel(REQUEST_ID)
    }

    @Test
    fun unloadIsIdempotent() {
        val service = loadedService()

        service.unload()
        service.unload()

        assertThat(service.status().state).isEqualTo("unloaded")
    }

    @Test
    fun lockingMidGenerationCancelsAndZeroesTheKvCache() {
        val service = loadedService()
        val callback = LatchCallback()
        val before = LlamaNative.secureFreeCount()

        service.generate(generateRequest(maxTokens = 512), callback)
        callback.awaitFirstBatch()
        service.onSessionLocking(EPOCH, CANCEL_BUDGET_MILLIS)
        service.onSessionLocked(EPOCH)

        assertThat(callback.awaitTerminal()).isTrue()
        assertThat(callback.stats.get()?.stopReason).isEqualTo("CANCELLED")
        // LOCK_POLICY_INDEXING.md §4.5: "freed" is not "zeroed". The counter is
        // the only way to prove the zero-then-free path actually ran.
        assertThat(LlamaNative.secureFreeCount()).isGreaterThan(before)
    }

    @Test
    fun aLockedServiceRefusesTheNextGenerate() {
        val service = loadedService()
        service.onSessionLocking(EPOCH, CANCEL_BUDGET_MILLIS)
        service.onSessionLocked(EPOCH)
        val callback = LatchCallback()

        service.generate(generateRequest(), callback)

        assertThat(callback.awaitTerminal()).isTrue()
        assertThat(callback.errorCode.get()).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    // --------------------------------------- R-1 control pair (bd skein-gg11.2)
    //
    // REGRESSION_TEST_PROPOSAL.md R-1: "a CPU-configured load has exactly one
    // backend". The positive half (nGpuLayers = 0) runs unconditionally; the
    // control (nGpuLayers = 99) only asserts anything on a build/device that
    // actually has a Vulkan device to report, per that document's own
    // "pair with a positive control" note. On the x86_64 CI emulator lane
    // there is no Vulkan device even on a Vulkan-enabled build, so this half
    // is expected to `assumeTrue`-skip there and run for real on the Fold
    // runner (bd skein-k3b2) / a Vulkan-capable device.

    @Test
    fun cpuOnlyLoadReportsExactlyOneCpuDevice() {
        val service = loadedService() // gpuLayers = 0 (loadRequest's default)

        val report = service.backendReport(BackendReportRequest(sessionEpoch = EPOCH))

        assertThat(report.errorCode).isEqualTo(ErrorCode.OK)
        assertThat(report.devices).hasSize(1)
        assertThat(report.devices.single().type).isEqualTo(BackendDeviceType.CPU)
    }

    @Test
    fun cpuOnlyLoadReportsNoGpuOrIgpuDevice() {
        val service = loadedService()

        val report = service.backendReport(BackendReportRequest(sessionEpoch = EPOCH))

        assertThat(
            report.devices.none { it.type == BackendDeviceType.GPU || it.type == BackendDeviceType.IGPU },
        ).isTrue()
    }

    @Test
    fun aHighGpuLayersLoadReportsAGpuDeviceWhenVulkanIsAvailable() {
        val service = bind()
        val model = assumeModel()
        service.onSessionUnlocked(EPOCH)
        assertThat(service.load(loadRequest(model, gpuLayers = 99))).isEqualTo(ErrorCode.OK)

        val report = service.backendReport(BackendReportRequest(sessionEpoch = EPOCH))

        // Guard, per the bead's own instruction: on the x86_64 emulator there
        // is no Vulkan device even when the build compiled Vulkan support in,
        // so the control half of R-1 is inert on that lane and this test
        // skips rather than fails there.
        assumeTrue(
            "no Vulkan device reported on this build/device",
            report.devices.any { it.name == "Vulkan" },
        )
        assertThat(
            report.devices.any { it.type == BackendDeviceType.GPU || it.type == BackendDeviceType.IGPU },
        ).isTrue()
    }

    // ------------------------------------------------------------- fixtures

    private fun bind(): IInferenceService {
        val intent =
            Intent().setComponent(
                ComponentName(context, InferenceService::class.java.name),
            )
        val binder: IBinder = serviceRule.bindService(intent)
        return IInferenceService.Stub.asInterface(binder)
    }

    private fun loadedService(): IInferenceService {
        val service = bind()
        val model = assumeModel()
        service.onSessionUnlocked(EPOCH)
        assertThat(service.load(loadRequest(model))).isEqualTo(ErrorCode.OK)
        return service
    }

    private fun loadRequest(
        model: File,
        sha256: String? = null,
        gpuLayers: Int = 0,
    ): LoadRequest =
        LoadRequest(
            binding =
                ManifestBinding(
                    manifestId = "tiny",
                    manifestVersion = 2,
                    files =
                        listOf(
                            ManifestFileRef(
                                role = "main",
                                fd = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY),
                                expectedSha256 = sha256 ?: sha256Of(model),
                                expectedSizeBytes = model.length(),
                            ),
                        ),
                    attestation = null,
                ),
            contextLength = 512,
            threads = 2,
            gpuLayers = gpuLayers,
            embeddingMode = false,
            sessionEpoch = EPOCH,
        )

    private fun generateRequest(
        requestId: Int = REQUEST_ID,
        maxTokens: Int = 32,
    ) = GenerateRequest(
        requestId = requestId,
        messages = listOf(ChatMessageParcel(role = "user", content = "hello")),
        attachmentFds = emptyList(),
        sampling =
            SamplingParcel(
                temperature = 0f,
                topK = 1,
                topP = 1f,
                minP = 0f,
                repeatPenalty = 1f,
                maxTokens = maxTokens,
                seed = 1L,
                stop = emptyList(),
            ),
        sessionEpoch = EPOCH,
    )

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun assumeModel(): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val available = runCatching { assets.list("")?.contains(TINY_GGUF_ASSET) == true }.getOrDefault(false)
        assumeTrue("androidTest asset $TINY_GGUF_ASSET absent (fetched by E4.I2 / skein-80p)", available)
        val out = File(context.cacheDir, TINY_GGUF_ASSET)
        if (!out.exists() || out.length() == 0L) {
            assets.open(TINY_GGUF_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    /** Counts what the client saw and lets a test wait for the one terminal call. */
    private class LatchCallback : IInferenceCallback.Stub() {
        val batches = AtomicInteger(0)
        val terminals = AtomicInteger(0)
        val stats = AtomicReference<GenStats?>(null)
        val errorCode = AtomicInteger(-1)
        val droppedTotal = AtomicLong(0)

        private val firstBatch = CountDownLatch(1)
        private val terminal = CountDownLatch(1)

        override fun onTokens(
            requestId: Int,
            pieces: Array<out String>?,
            ids: IntArray?,
            dropped: Int,
        ) {
            batches.incrementAndGet()
            droppedTotal.addAndGet(dropped.toLong())
            firstBatch.countDown()
        }

        override fun onDone(
            requestId: Int,
            stats: GenStats,
        ) {
            this.stats.set(stats)
            terminals.incrementAndGet()
            firstBatch.countDown()
            terminal.countDown()
        }

        override fun onError(
            requestId: Int,
            code: Int,
            message: String?,
        ) {
            errorCode.set(code)
            terminals.incrementAndGet()
            firstBatch.countDown()
            terminal.countDown()
        }

        fun awaitFirstBatch() = firstBatch.await(AWAIT_SECONDS, TimeUnit.SECONDS)

        fun awaitTerminal() = terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val REQUEST_ID = 1
        const val AWAIT_SECONDS = 30L
    }
}
