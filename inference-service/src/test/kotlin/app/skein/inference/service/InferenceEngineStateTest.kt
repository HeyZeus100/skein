// skein-nxk (E4.I3): the service's entry points, driven against a fake native
// layer.
//
// The acceptance criteria that need a device need a device — a real GGUF, a
// real isolated uid, a real 200 ms cancel latency. Everything that does NOT
// need one is here, because those are the properties that break silently:
// which `ErrorCode` a refusal maps to, that `status()` says "unloaded" after a
// refusal, that the SECOND `generate` is the one refused, that a descriptor is
// closed on every path including the refusals, and that the lock gate is shut
// on a cold start.
//
// Robolectric supplies `ParcelFileDescriptor` and `SystemClock`; the worker is
// inline, so a `load` has finished when `load` returns and there is no
// scheduler to pump.

package app.skein.inference.service

import android.os.ParcelFileDescriptor
import app.skein.ipc.ChatMessageParcel
import app.skein.ipc.ErrorCode
import app.skein.ipc.GenerateRequest
import app.skein.ipc.LoadRequest
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import app.skein.ipc.SamplingParcel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InferenceEngineStateTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val backend = FakeLlamaBackend()
    private val callbacks = CallbackDispatcher(InlineTaskRunner())
    private val engine = InferenceEngineState(backend, InlineTaskRunner(), callbacks)

    private val epoch = 42L

    // ------------------------------------------------------------ lock gate

    @Test
    fun `a cold service refuses load`() {
        assertThat(engine.load(loadRequest())).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun `a cold service stays unloaded`() {
        engine.load(loadRequest())

        assertThat(engine.status().state).isEqualTo("unloaded")
    }

    @Test
    fun `a cold service closes the descriptors it refused`() {
        val request = loadRequest()
        engine.load(request)

        assertThat(request.binding.files.all { isClosed(it.fd) }).isTrue()
    }

    @Test
    fun `an unlocked service admits load`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest())).isEqualTo(ErrorCode.OK)
    }

    @Test
    fun `a load for the wrong epoch is refused`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest(epoch = epoch + 1))).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun `locking revokes admission`() {
        engine.onSessionUnlocked(epoch)
        engine.onSessionLocking(epoch, 500L)

        assertThat(engine.load(loadRequest())).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun `onSessionLocked unloads the model`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.onSessionLocked(epoch)

        assertThat(engine.status().state).isEqualTo("unloaded")
    }

    @Test
    fun `onSessionLocked frees the context through the zeroing path`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.onSessionLocked(epoch)

        assertThat(backend.secureFrees).hasSize(1)
    }

    // ----------------------------------------------------------- load gate

    @Test
    fun `a hash mismatch refuses with HASH_MISMATCH`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest(mainSha256 = "00".repeat(32)))).isEqualTo(ErrorCode.HASH_MISMATCH)
    }

    @Test
    fun `a hash mismatch leaves the engine unloaded`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest(mainSha256 = "00".repeat(32)))

        assertThat(engine.status().state).isEqualTo("unloaded")
    }

    @Test
    fun `a hash mismatch never reaches the engine`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest(mainSha256 = "00".repeat(32)))

        assertThat(backend.loadedFds).isEmpty()
    }

    @Test
    fun `a companion hash mismatch refuses with COMPANION_HASH_MISMATCH`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest(tokenizerSha256 = "00".repeat(32))))
            .isEqualTo(ErrorCode.COMPANION_HASH_MISMATCH)
    }

    @Test
    fun `a size mismatch refuses with INVALID_MODEL`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest(mainSize = 99_999L))).isEqualTo(ErrorCode.INVALID_MODEL)
    }

    @Test
    fun `an unknown role refuses with INVALID_MODEL`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest(mainRole = "not-a-role"))).isEqualTo(ErrorCode.INVALID_MODEL)
    }

    @Test
    fun `a successful load reports ready`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        assertThat(engine.status().state).isEqualTo("ready")
    }

    @Test
    fun `a successful load reports the model digest`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        assertThat(engine.status().modelSha256).isEqualTo(sha256(MODEL_BYTES))
    }

    @Test
    fun `a successful load reports the context length`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        assertThat(engine.status().contextLength).isEqualTo(2048)
    }

    @Test
    fun `the engine is handed a descriptor, never a path`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        assertThat(backend.loadedFds).hasSize(1)
    }

    @Test
    fun `a load closes every descriptor it received`() {
        engine.onSessionUnlocked(epoch)
        val request = loadRequest()
        engine.load(request)

        assertThat(request.binding.files.all { isClosed(it.fd) }).isTrue()
    }

    @Test
    fun `a second load unloads the first`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.load(loadRequest())

        assertThat(backend.liveModels).hasSize(1)
    }

    @Test
    fun `a warm swap frees the old context securely`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.load(loadRequest())

        assertThat(backend.secureFrees).hasSize(1)
    }

    // -------------------------------------------------------------- unload

    @Test
    fun `unload reports unloaded`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.unload()

        assertThat(engine.status().state).isEqualTo("unloaded")
    }

    @Test
    fun `unload releases every native handle`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.unload()

        assertThat(backend.liveModels).isEmpty()
        assertThat(backend.liveContexts).isEmpty()
    }

    @Test
    fun `unload is idempotent`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        engine.unload()
        engine.unload()

        assertThat(backend.secureFrees).hasSize(1)
    }

    @Test
    fun `unload before any load is harmless`() {
        engine.unload()

        assertThat(engine.status().state).isEqualTo("unloaded")
    }

    // ------------------------------------------------------------ generate

    @Test
    fun `generate before load reports NOT_LOADED`() {
        engine.onSessionUnlocked(epoch)
        val cb = RecordingCallback()

        engine.generate(generateRequest(), cb)

        assertThat(cb.errors.single().second).isEqualTo(ErrorCode.NOT_LOADED)
    }

    @Test
    fun `generate on a locked session reports SESSION_LOCKED`() {
        val cb = RecordingCallback()

        engine.generate(generateRequest(), cb)

        assertThat(cb.errors.single().second).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun `a generation delivers exactly one terminal callback`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(maxTokens = 8), cb)

        assertThat(cb.done.size + cb.errors.size).isEqualTo(1)
    }

    @Test
    fun `a generation streams at least one token batch`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(maxTokens = 8), cb)

        assertThat(cb.tokenBatches).isNotEmpty()
    }

    @Test
    fun `a generation that hits the token ceiling stops with LENGTH`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(maxTokens = 8), cb)

        assertThat(
            cb.done
                .single()
                .second.stopReason,
        ).isEqualTo("LENGTH")
    }

    @Test
    fun `a generation reports the tokens it produced`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(maxTokens = 8), cb)

        assertThat(
            cb.done
                .single()
                .second.generatedTokens,
        ).isEqualTo(8)
    }

    @Test
    fun `an end-of-generation token stops with EOS`() {
        val eogBackend =
            object : FakeLlamaBackend() {
                override fun isEog(
                    model: Long,
                    token: Int,
                ): Boolean = true
            }
        val engine = InferenceEngineState(eogBackend, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(maxTokens = 8), cb)

        assertThat(
            cb.done
                .single()
                .second.stopReason,
        ).isEqualTo("EOS")
    }

    @Test
    fun `a stop string stops with STOP_STRING`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        // The fake's piece for token 7 is "t7".
        engine.generate(generateRequest(maxTokens = 8, stop = listOf("t7")), cb)

        assertThat(
            cb.done
                .single()
                .second.stopReason,
        ).isEqualTo("STOP_STRING")
    }

    @Test
    fun `a stop string is never streamed to the client`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(maxTokens = 8, stop = listOf("t7")), cb)

        assertThat(cb.tokenBatches.flatMap { it.toList() }.none { it.contains("t7") }).isTrue()
    }

    @Test
    fun `a generation closes every attachment descriptor`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val request = generateRequest(maxTokens = 4)

        engine.generate(request, RecordingCallback())

        assertThat(request.attachmentFds.all { isClosed(it.fd) }).isTrue()
    }

    // ---------------------------------------------------------------- busy

    @Test
    fun `a second concurrent generate is refused with BUSY`() {
        val second = RecordingCallback()
        var busyEngine: InferenceEngineState? = null
        val blocking =
            BlockingBackend { busyEngine?.generate(generateRequest(requestId = 2), second) }
        busyEngine = InferenceEngineState(blocking, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        busyEngine.onSessionUnlocked(epoch)
        busyEngine.load(loadRequest())

        busyEngine.generate(generateRequest(requestId = 1, maxTokens = 4), RecordingCallback())

        assertThat(second.errors.map { it.second }).containsExactly(ErrorCode.BUSY)
    }

    @Test
    fun `the first of two concurrent generates still completes`() {
        val first = RecordingCallback()
        var busyEngine: InferenceEngineState? = null
        val blocking =
            BlockingBackend { busyEngine?.generate(generateRequest(requestId = 2), RecordingCallback()) }
        busyEngine = InferenceEngineState(blocking, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        busyEngine.onSessionUnlocked(epoch)
        busyEngine.load(loadRequest())

        busyEngine.generate(generateRequest(requestId = 1, maxTokens = 4), first)

        assertThat(first.done).hasSize(1)
    }

    // -------------------------------------------------------------- native

    @Test
    fun `a native OOM during load maps to OOM`() {
        val oom =
            object : FakeLlamaBackend() {
                override fun newContext(
                    model: Long,
                    nCtx: Int,
                    nThreads: Int,
                    nBatch: Int,
                    embeddings: Boolean,
                ): Long = throw LlamaException(LlamaErrorCode.OUT_OF_MEMORY, "kv cache")
            }
        val engine = InferenceEngineState(oom, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)

        assertThat(engine.load(loadRequest())).isEqualTo(ErrorCode.OOM)
    }

    @Test
    fun `a failed context creation leaks no model handle`() {
        val oom =
            object : FakeLlamaBackend() {
                override fun newContext(
                    model: Long,
                    nCtx: Int,
                    nThreads: Int,
                    nBatch: Int,
                    embeddings: Boolean,
                ): Long = throw LlamaException(LlamaErrorCode.OUT_OF_MEMORY, "kv cache")
            }
        val engine = InferenceEngineState(oom, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        assertThat(oom.liveModels).isEmpty()
    }

    @Test
    fun `an OOM during decode reports OOM and unloads`() {
        val oom =
            object : FakeLlamaBackend() {
                override fun decodePrompt(
                    ctx: Long,
                    tokens: IntArray,
                    nPast: Int,
                ): Int = throw LlamaException(LlamaErrorCode.OUT_OF_MEMORY, "decode")
            }
        val engine = InferenceEngineState(oom, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(), cb)

        assertThat(cb.errors.single().second).isEqualTo(ErrorCode.OOM)
        assertThat(engine.status().state).isEqualTo("unloaded")
    }

    @Test
    fun `a native cancellation during decode ends with onDone CANCELLED`() {
        val cancelled =
            object : FakeLlamaBackend() {
                override fun decodePrompt(
                    ctx: Long,
                    tokens: IntArray,
                    nPast: Int,
                ): Int = throw LlamaException(LlamaErrorCode.CANCELLED, "aborted")
            }
        val engine = InferenceEngineState(cancelled, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val cb = RecordingCallback()

        engine.generate(generateRequest(), cb)

        assertThat(
            cb.done
                .single()
                .second.stopReason,
        ).isEqualTo("CANCELLED")
    }

    // ------------------------------------------------------------ fixtures

    private fun loadRequest(
        epoch: Long = this.epoch,
        mainSha256: String? = null,
        tokenizerSha256: String? = null,
        mainSize: Long? = null,
        mainRole: String = "main",
    ): LoadRequest {
        val main = fixture("model.gguf", MODEL_BYTES)
        val tokenizer = fixture("tokenizer.json", TOKENIZER_BYTES)
        return LoadRequest(
            binding =
                ManifestBinding(
                    manifestId = "fixture",
                    manifestVersion = 2,
                    files =
                        listOf(
                            ManifestFileRef(
                                role = mainRole,
                                fd = open(main),
                                expectedSha256 = mainSha256 ?: sha256(MODEL_BYTES),
                                expectedSizeBytes = mainSize ?: MODEL_BYTES.size.toLong(),
                            ),
                            ManifestFileRef(
                                role = "tokenizer",
                                fd = open(tokenizer),
                                expectedSha256 = tokenizerSha256 ?: sha256(TOKENIZER_BYTES),
                                expectedSizeBytes = TOKENIZER_BYTES.size.toLong(),
                            ),
                        ),
                    attestation = null,
                ),
            contextLength = 2048,
            threads = 4,
            gpuLayers = 0,
            embeddingMode = false,
            sessionEpoch = epoch,
        )
    }

    private fun generateRequest(
        requestId: Int = 1,
        maxTokens: Int = 4,
        stop: List<String> = emptyList(),
        epoch: Long = this.epoch,
    ) = GenerateRequest(
        requestId = requestId,
        messages = listOf(ChatMessageParcel(role = "user", content = "hello")),
        attachmentFds = emptyList(),
        sampling =
            SamplingParcel(
                temperature = 0.7f,
                topK = 40,
                topP = 0.95f,
                minP = 0.05f,
                repeatPenalty = 1.1f,
                maxTokens = maxTokens,
                seed = 1L,
                stop = stop,
            ),
        sessionEpoch = epoch,
    )

    private fun fixture(
        name: String,
        bytes: ByteArray,
    ): File = temp.newFile(name + "-" + (counter++)).also { it.writeBytes(bytes) }

    private fun open(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun isClosed(fd: ParcelFileDescriptor): Boolean =
        runCatching { fd.fd }.getOrNull().let {
            it == null ||
                it < 0
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Lets the test run something WHILE a generation is in flight on the worker. */
    private class BlockingBackend(
        private val duringDecode: () -> Unit,
    ) : FakeLlamaBackend() {
        private var fired = false

        override fun sampleNext(
            ctx: Long,
            sampler: Long,
        ): Int {
            if (!fired) {
                fired = true
                duringDecode()
            }
            return super.sampleNext(ctx, sampler)
        }
    }

    private companion object {
        val MODEL_BYTES = ByteArray(4_096) { (it % 251).toByte() }
        val TOKENIZER_BYTES = ByteArray(512) { (it % 97).toByte() }
        var counter = 0
    }
}
