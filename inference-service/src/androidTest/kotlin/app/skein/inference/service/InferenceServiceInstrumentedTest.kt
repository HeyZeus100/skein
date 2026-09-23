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
import android.util.Log
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
private const val CANCEL_BUDGET_MILLIS = 200L

// bd skein-gg11.6. `ServiceTestRule` binds the REAL `:inference` process, and
// nothing tears its in-memory `IsolatedSessionGate` down between test methods
// inside one instrumentation run (Android is free to keep an isolated
// process warm across a rapid unbind/rebind when nothing else claims the
// component). A single shared epoch constant let one test's lock — or
// unlock — leak into the next: run 35847825560 showed
// `aHashMismatchIsRefusedAndLeavesTheServiceUnloaded` getting SESSION_LOCKED
// (11) instead of HASH_MISMATCH (1), because a PRECEDING test's lock was
// still in effect when this one unlocked-then-loaded with the SAME epoch. A
// monotonic counter, shared across every test instance in this process
// (companion-shaped: a top-level `val`, not a member — JUnit4 makes a fresh
// instance per @Test), makes an accidental epoch collision across methods
// structurally impossible, regardless of process reuse or method order.
private val epochSequence = AtomicLong(1_000L)

private fun nextEpoch(): Long = epochSequence.incrementAndGet()

// bd skein-gg11.6. `onSessionUnlocked`/`onSessionLocking`/`onSessionLocked`
// are all `oneway` (`IInferenceService.aidl`): the call returns to the
// calling thread the instant the transaction is enqueued, with NO guarantee
// the service has processed it. Binder orders oneway transactions relative
// to EACH OTHER on the same target binder, but gives no ordering guarantee
// relative to a LATER two-way call from the same calling thread — that call
// can be dispatched to a different, already-idle thread in the target's
// binder-thread pool and run concurrently with the still-queued oneway push.
// A test that fires a lock/unlock push and immediately issues a synchronous
// call assuming the push already landed is racing the service, not testing
// it. This is the mechanism behind both the SESSION_LOCKED leak above and
// the historical `errorCode == -1` on `aLockedServiceRefusesTheNextGenerate`
// (`generate` raced ahead of the lock, so it ran to a normal completion and
// delivered `onDone` instead of `onError`, leaving `LatchCallback.errorCode`
// at its untouched `-1` sentinel). The fix is a bounded poll of the gate's
// OWN observable state through `backendReport` — side-effect-free and gated
// identically to every other plaintext entry point — never a fixed sleep and
// never an assumption about oneway delivery order.
private const val GATE_SETTLE_TIMEOUT_MILLIS = 5_000L
private const val GATE_POLL_INTERVAL_MILLIS = 20L
private const val NANOS_PER_MILLI = 1_000_000L

@RunWith(AndroidJUnit4::class)
class InferenceServiceInstrumentedTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

    private lateinit var context: Context

    /** bd skein-gg11.6: unique per test method — see the file header note. */
    private val epoch: Long = nextEpoch()

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
        service.onSessionUnlocked(epoch)
        service.awaitGateAdmits(epoch)

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
        service.onSessionUnlocked(epoch)
        service.awaitGateAdmits(epoch)

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

        service.generate(generateRequest(maxTokens = 512), callback)
        // bd skein-gg11.6: gate the lock on a REAL onTokens, not
        // `awaitFirstBatch()` (which also counts down on onDone/onError) — a
        // lock issued before a single token has streamed is not testing
        // "mid-generation" cancellation.
        assertThat(callback.awaitFirstTokens()).isTrue()
        service.onSessionLocking(epoch, CANCEL_BUDGET_MILLIS)
        service.onSessionLocked(epoch)

        assertThat(callback.awaitTerminal()).isTrue()
        assertThat(callback.stats.get()?.stopReason).isEqualTo("CANCELLED")
        // bd skein-gg11.6: NOT `LlamaNative.secureFreeCount()`. That counter is
        // a process-local `std::atomic<int>` (native/llama/jni/skein_jni.cpp)
        // and this test class runs in the TEST's own process, never in
        // `:inference` — `android:isolatedProcess="true"`
        // (inference-service/src/main/AndroidManifest.xml) guarantees a
        // genuinely separate OS process with its own independent copy of the
        // native library's static state. Calling `LlamaNative.secureFreeCount()`
        // from here reads a counter that can NEVER observe what
        // `freeContextSecure` does inside the isolated process — the
        // assertion was unconditionally false on every run, not flaky timing.
        // What IS observable across the AIDL boundary is `status()`, which
        // (per LOCK_POLICY_INDEXING.md §4.1: "diagnostic calls that touch no
        // plaintext" stay answerable while locked) is gated on nothing and
        // flips to "unloaded" only once `unload()` — the one code path that
        // ever calls `freeContextSecure` — has run. `onSessionLocked` is
        // `oneway`, so poll for it rather than asserting immediately. The
        // SPECIFIC zero-then-free call, same-process against
        // `FakeLlamaBackend`, is proven deterministically in
        // `InferenceEngineStateTest`'s "lock gate: mid-generation" section.
        service.awaitUnloaded()
        assertThat(service.status().state).isEqualTo("unloaded")
    }

    @Test
    fun aLockedServiceRefusesTheNextGenerate() {
        val service = loadedService()
        service.onSessionLocking(epoch, CANCEL_BUDGET_MILLIS)
        service.onSessionLocked(epoch)
        // bd skein-gg11.6: `onSessionLocked` is oneway; wait for the gate to
        // actually observe the revoked epoch before racing a `generate`
        // against it — this is the exact mechanism that produced the
        // historical `errorCode == -1` (see the file header note).
        service.awaitGateRefuses(epoch)
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

        val report = service.backendReport(BackendReportRequest(sessionEpoch = epoch))

        assertThat(report.errorCode).isEqualTo(ErrorCode.OK)
        assertThat(report.devices).hasSize(1)
        assertThat(report.devices.single().type).isEqualTo(BackendDeviceType.CPU)
    }

    @Test
    fun cpuOnlyLoadReportsNoGpuOrIgpuDevice() {
        val service = loadedService()

        val report = service.backendReport(BackendReportRequest(sessionEpoch = epoch))

        assertThat(
            report.devices.none { it.type == BackendDeviceType.GPU || it.type == BackendDeviceType.IGPU },
        ).isTrue()
    }

    @Test
    fun aHighGpuLayersLoadReportsAGpuDeviceWhenVulkanIsAvailable() {
        val service = bind()
        val model = assumeModel()
        service.onSessionUnlocked(epoch)
        service.awaitGateAdmits(epoch)
        assertThat(service.load(loadRequest(model, gpuLayers = 99))).isEqualTo(ErrorCode.OK)

        val report = service.backendReport(BackendReportRequest(sessionEpoch = epoch))

        // Guard: on the x86_64 emulator there is no Vulkan device even when the
        // build compiled Vulkan support in, so the control half of R-1 is inert
        // on that lane. Return early with a log line instead of skipping.
        if (report.devices.none { it.name == "Vulkan" }) {
            Log.i(
                "aHighGpuLayersLoadReportsAGpuDeviceWhenVulkanIsAvailable",
                "no Vulkan device reported on this build/device",
            )
            return
        }
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
        service.onSessionUnlocked(epoch)
        service.awaitGateAdmits(epoch)
        assertThat(service.load(loadRequest(model))).isEqualTo(ErrorCode.OK)
        return service
    }

    /**
     * bd skein-gg11.6: `onSessionUnlocked` is `oneway`; poll the gate's own
     * observable state (through the side-effect-free, identically-gated
     * `backendReport`) rather than assuming the push has already landed by
     * the time this call returns. See the file header note.
     */
    private fun IInferenceService.awaitGateAdmits(sessionEpoch: Long) = awaitGate(sessionEpoch, admitted = true)

    /** The `onSessionLocking`/`onSessionLocked` counterpart of [awaitGateAdmits]. */
    private fun IInferenceService.awaitGateRefuses(sessionEpoch: Long) = awaitGate(sessionEpoch, admitted = false)

    private fun IInferenceService.awaitGate(
        sessionEpoch: Long,
        admitted: Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + GATE_SETTLE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (System.nanoTime() < deadlineNanos) {
            val report = backendReport(BackendReportRequest(sessionEpoch = sessionEpoch))
            val isAdmitted = report.errorCode != ErrorCode.SESSION_LOCKED
            if (isAdmitted == admitted) return
            Thread.sleep(GATE_POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "gate for sessionEpoch=$sessionEpoch never reached admitted=$admitted within ${GATE_SETTLE_TIMEOUT_MILLIS}ms",
        )
    }

    /**
     * bd skein-gg11.6: `unload()` — the only code path that ever calls
     * `freeContextSecure` — flips `status().state` to "unloaded" as one of
     * its first acts, before the (also queued, also asynchronous from this
     * thread's perspective) native free even runs. `status()` is gated on
     * nothing (LOCK_POLICY_INDEXING.md §4.1), so this is a side-effect-free,
     * bounded wait for the lock's release path to have actually executed,
     * not an assumption about `onSessionLocked`'s oneway delivery.
     */
    private fun IInferenceService.awaitUnloaded() {
        val deadlineNanos = System.nanoTime() + GATE_SETTLE_TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (System.nanoTime() < deadlineNanos) {
            if (status().state == "unloaded") return
            Thread.sleep(GATE_POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("service never reported unloaded within ${GATE_SETTLE_TIMEOUT_MILLIS}ms")
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
            sessionEpoch = epoch,
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
        sessionEpoch = epoch,
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

        /** bd skein-gg11.6: counts down on a REAL onTokens only — see [awaitFirstTokens]. */
        private val firstTokens = CountDownLatch(1)
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
            firstTokens.countDown()
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

        /** Unlike [awaitFirstBatch], only a real onTokens satisfies this. */
        fun awaitFirstTokens() = firstTokens.await(AWAIT_SECONDS, TimeUnit.SECONDS)

        fun awaitTerminal() = terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val REQUEST_ID = 1
        const val AWAIT_SECONDS = 30L
    }
}
