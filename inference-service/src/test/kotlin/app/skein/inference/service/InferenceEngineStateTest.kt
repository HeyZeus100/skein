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
import app.skein.core.model.SkeinLog
import app.skein.ipc.ChatMessageParcel
import app.skein.ipc.ErrorCode
import app.skein.ipc.GenerateRequest
import app.skein.ipc.InspectRequest
import app.skein.ipc.LoadRequest
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import app.skein.ipc.ModelInspection
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

    // ------------------------------------------------- KV cache hygiene (E-4)
    //
    // OfflineLLM review finding E-4. Every request decodes its prompt from
    // position 0, so whatever the previous request left in the cache is stale;
    // correctness used to rest on llama.cpp purging a cell when a later decode
    // overwrites its position — an unasserted invariant of somebody else's
    // implementation, whose failure mode is the previous conversation bleeding
    // into this answer.

    @Test
    fun `a generation clears the KV cache exactly once`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        engine.generate(generateRequest(maxTokens = 4), RecordingCallback())

        assertThat(backend.cacheAndDecodeCalls.count { it == FakeLlamaBackend.KV_CLEAR }).isEqualTo(1)
    }

    @Test
    fun `a generation clears the KV cache before it decodes anything`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        engine.generate(generateRequest(maxTokens = 4), RecordingCallback())

        assertThat(backend.cacheAndDecodeCalls.first()).isEqualTo(FakeLlamaBackend.KV_CLEAR)
    }

    @Test
    fun `a second generation clears the first generation's cache`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        engine.generate(generateRequest(requestId = 1, maxTokens = 4), RecordingCallback())
        engine.generate(generateRequest(requestId = 2, maxTokens = 4), RecordingCallback())

        assertThat(backend.cacheAndDecodeCalls.count { it == FakeLlamaBackend.KV_CLEAR }).isEqualTo(2)
    }

    // ------------------------------------------------------------- inspect
    //
    // H1 (skein-91yy), docs/design/SKEIN_HUB.md §3.3. `inspect` exists so the
    // ACCEPTANCE decision about an untrusted GGUF is made inside the isolated
    // process, without `:app` parsing a byte and without paying for a
    // `llama_context` on a model nobody has agreed to use yet.

    @Test
    fun `a cold service refuses inspect`() {
        assertThat(engine.inspect(inspectRequest()).errorCode).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun `a cold service closes the descriptors an inspect was refused with`() {
        val request = inspectRequest()

        engine.inspect(request)

        assertThat(request.binding.files.all { isClosed(it.fd) }).isTrue()
    }

    @Test
    fun `an inspect for the wrong epoch is refused`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest(epoch = epoch + 1)).errorCode)
            .isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun `an unlocked service inspects`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).errorCode).isEqualTo(ErrorCode.OK)
    }

    @Test
    fun `an inspection reports the architecture the file declares`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["general.architecture"] = ARCHITECTURE

        assertThat(engine.inspect(inspectRequest()).architecture).isEqualTo(ARCHITECTURE)
    }

    @Test
    fun `an inspection renders the file type as a quantisation name`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["general.file_type"] = "15"

        assertThat(engine.inspect(inspectRequest()).quantization).isEqualTo("Q4_K_M")
    }

    @Test
    fun `an inspection reports the context length the architecture declares`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["general.architecture"] = ARCHITECTURE
        backend.meta["$ARCHITECTURE.context_length"] = "8192"

        assertThat(engine.inspect(inspectRequest()).contextLength).isEqualTo(8192)
    }

    @Test
    fun `an inspection has no context length when the file declares none`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["general.architecture"] = ARCHITECTURE

        assertThat(engine.inspect(inspectRequest()).contextLength).isNull()
    }

    @Test
    fun `an inspection reports the embedding width llama cpp computes`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).embeddingWidth).isEqualTo(4)
    }

    @Test
    fun `an inspection reports that a chat template is present`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["tokenizer.chat_template"] = "{{ messages }}"

        assertThat(engine.inspect(inspectRequest()).hasChatTemplate).isTrue()
    }

    @Test
    fun `an inspection reports a missing chat template without refusing`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).hasChatTemplate).isFalse()
    }

    @Test
    fun `an inspection reports that the chat template applies`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).chatTemplateOk).isTrue()
    }

    @Test
    fun `a template llama cpp cannot apply is reported, not refused`() {
        val unsupported =
            object : FakeLlamaBackend() {
                override fun applyChatTemplate(
                    model: Long,
                    roles: Array<String>,
                    contents: Array<String>,
                    addAssistant: Boolean,
                ): String = throw LlamaException(LlamaErrorCode.TEMPLATE_UNSUPPORTED, "no template")
            }
        val engine = InferenceEngineState(unsupported, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)

        val inspection = engine.inspect(inspectRequest())

        assertThat(listOf(inspection.errorCode, inspection.chatTemplateOk))
            .isEqualTo(listOf(ErrorCode.OK, false))
    }

    @Test
    fun `an inspection reports the tokenizer the file declares`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["tokenizer.ggml.model"] = "gpt2"

        assertThat(engine.inspect(inspectRequest()).tokenizerModel).isEqualTo("gpt2")
    }

    @Test
    fun `an inspection reports vision metadata`() {
        val vision =
            object : FakeLlamaBackend() {
                override fun modelHasVision(model: Long): Boolean = true
            }
        val engine = InferenceEngineState(vision, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).hasVision).isTrue()
    }

    // The GGUF keys llama.cpp's own writer emits carry no parameter count and
    // `llama_model_n_params` has no JNI entry point, so this field is null for
    // every file the project's tooling produces. Recorded on skein-91yy.
    @Test
    fun `an inspection has no parameter count when the file declares none`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).parameterCount).isNull()
    }

    @Test
    fun `an inspection reports a parameter count a file does declare`() {
        engine.onSessionUnlocked(epoch)
        backend.meta["general.parameter_count"] = "4300000000"

        assertThat(engine.inspect(inspectRequest()).parameterCount).isEqualTo(4_300_000_000L)
    }

    @Test
    fun `an inspection never creates a context`() {
        engine.onSessionUnlocked(epoch)

        engine.inspect(inspectRequest())

        assertThat(backend.newContextCalls).isEqualTo(0)
    }

    @Test
    fun `an inspection loads the model from a descriptor, never a path`() {
        engine.onSessionUnlocked(epoch)

        engine.inspect(inspectRequest())

        assertThat(backend.loadedFds).hasSize(1)
    }

    @Test
    fun `an inspection frees the model it loaded`() {
        engine.onSessionUnlocked(epoch)

        engine.inspect(inspectRequest())

        assertThat(backend.liveModels).isEmpty()
    }

    @Test
    fun `an inspection closes every descriptor it received`() {
        engine.onSessionUnlocked(epoch)
        val request = inspectRequest()

        engine.inspect(request)

        assertThat(request.binding.files.all { isClosed(it.fd) }).isTrue()
    }

    @Test
    fun `an inspection leaves a loaded model loaded`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        engine.inspect(inspectRequest())

        assertThat(engine.status().state).isEqualTo("ready")
    }

    @Test
    fun `an inspection leaves the loaded model's handles alone`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())
        val loadedModel = backend.liveModels.single()

        engine.inspect(inspectRequest())

        assertThat(backend.liveModels).containsExactly(loadedModel)
    }

    @Test
    fun `an inspection does not free the loaded context`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        engine.inspect(inspectRequest())

        assertThat(backend.secureFrees).isEmpty()
    }

    @Test
    fun `an inspection during a generation is refused with BUSY`() {
        var busyEngine: InferenceEngineState? = null
        var inspected: ModelInspection? = null
        val blocking = BlockingBackend { inspected = busyEngine?.inspect(inspectRequest()) }
        busyEngine = InferenceEngineState(blocking, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        busyEngine.onSessionUnlocked(epoch)
        busyEngine.load(loadRequest())

        busyEngine.generate(generateRequest(maxTokens = 4), RecordingCallback())

        assertThat(inspected?.errorCode).isEqualTo(ErrorCode.BUSY)
    }

    @Test
    fun `an inspection refused as BUSY still closes its descriptors`() {
        var busyEngine: InferenceEngineState? = null
        val request = inspectRequest()
        val blocking = BlockingBackend { busyEngine?.inspect(request) }
        busyEngine = InferenceEngineState(blocking, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        busyEngine.onSessionUnlocked(epoch)
        busyEngine.load(loadRequest())

        busyEngine.generate(generateRequest(maxTokens = 4), RecordingCallback())

        assertThat(request.binding.files.all { isClosed(it.fd) }).isTrue()
    }

    // ----------------------------------------- inspect: the same load gate

    @Test
    fun `an inspection of a mismatched main file refuses with HASH_MISMATCH`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest(mainSha256 = "00".repeat(32))).errorCode)
            .isEqualTo(ErrorCode.HASH_MISMATCH)
    }

    @Test
    fun `an inspection of a mismatched main file never reaches the engine`() {
        engine.onSessionUnlocked(epoch)

        engine.inspect(inspectRequest(mainSha256 = "00".repeat(32)))

        assertThat(backend.loadedFds).isEmpty()
    }

    @Test
    fun `an inspection of a mismatched companion refuses with COMPANION_HASH_MISMATCH`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest(tokenizerSha256 = "00".repeat(32))).errorCode)
            .isEqualTo(ErrorCode.COMPANION_HASH_MISMATCH)
    }

    @Test
    fun `an inspection of a mis-sized file refuses with INVALID_MODEL`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest(mainSize = 99_999L)).errorCode)
            .isEqualTo(ErrorCode.INVALID_MODEL)
    }

    @Test
    fun `an inspection of an unknown role refuses with INVALID_MODEL`() {
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest(mainRole = "not-a-role")).errorCode)
            .isEqualTo(ErrorCode.INVALID_MODEL)
    }

    @Test
    fun `a refused inspection closes every descriptor it received`() {
        engine.onSessionUnlocked(epoch)
        val request = inspectRequest(mainSha256 = "00".repeat(32))

        engine.inspect(request)

        assertThat(request.binding.files.all { isClosed(it.fd) }).isTrue()
    }

    @Test
    fun `a refused inspection leaves a loaded model loaded`() {
        engine.onSessionUnlocked(epoch)
        engine.load(loadRequest())

        engine.inspect(inspectRequest(mainSha256 = "00".repeat(32)))

        assertThat(engine.status().state).isEqualTo("ready")
    }

    @Test
    fun `a file llama cpp will not load reports INVALID_MODEL`() {
        val invalid =
            object : FakeLlamaBackend() {
                override fun loadModelFromFd(
                    fd: Int,
                    nGpuLayers: Int,
                    useMmap: Boolean,
                ): Long = throw LlamaException(LlamaErrorCode.INVALID_MODEL, "not a gguf")
            }
        val engine = InferenceEngineState(invalid, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).errorCode).isEqualTo(ErrorCode.INVALID_MODEL)
    }

    @Test
    fun `an allocation failure during an inspection reports OOM`() {
        val oom =
            object : FakeLlamaBackend() {
                override fun loadModelFromFd(
                    fd: Int,
                    nGpuLayers: Int,
                    useMmap: Boolean,
                ): Long = throw LlamaException(LlamaErrorCode.OUT_OF_MEMORY, "mmap")
            }
        val engine = InferenceEngineState(oom, InlineTaskRunner(), CallbackDispatcher(InlineTaskRunner()))
        engine.onSessionUnlocked(epoch)

        assertThat(engine.inspect(inspectRequest()).errorCode).isEqualTo(ErrorCode.OOM)
    }

    // The service is the one process holding an untrusted GGUF's own strings.
    // `general.architecture` is attacker-controlled, so an inspection that
    // logged what it read would be a way to write chosen text into logcat.
    @Test
    fun `an inspection never logs what it read`() {
        val logged = mutableListOf<String>()
        SkeinLog.testHook = { _, message, _ -> logged += message }
        try {
            engine.onSessionUnlocked(epoch)
            backend.meta["general.architecture"] = ARCHITECTURE

            engine.inspect(inspectRequest())
        } finally {
            SkeinLog.testHook = null
        }

        assertThat(logged.none { it.contains(ARCHITECTURE) }).isTrue()
    }

    @Test
    fun `a refused inspection never logs a digest`() {
        val logged = mutableListOf<String>()
        SkeinLog.testHook = { _, message, _ -> logged += message }
        try {
            engine.onSessionUnlocked(epoch)
            engine.inspect(inspectRequest(mainSha256 = "00".repeat(32)))
        } finally {
            SkeinLog.testHook = null
        }

        assertThat(logged.none { it.contains("00".repeat(32)) }).isTrue()
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
    ): LoadRequest =
        LoadRequest(
            binding = binding(mainSha256, tokenizerSha256, mainSize, mainRole),
            contextLength = 2048,
            threads = 4,
            gpuLayers = 0,
            embeddingMode = false,
            sessionEpoch = epoch,
        )

    /**
     * The same binding `load` is given, so an `inspect` test and a `load` test
     * exercising the same refusal really are exercising the same input.
     */
    private fun inspectRequest(
        epoch: Long = this.epoch,
        mainSha256: String? = null,
        tokenizerSha256: String? = null,
        mainSize: Long? = null,
        mainRole: String = "main",
    ): InspectRequest =
        InspectRequest(
            binding = binding(mainSha256, tokenizerSha256, mainSize, mainRole),
            sessionEpoch = epoch,
        )

    private fun binding(
        mainSha256: String? = null,
        tokenizerSha256: String? = null,
        mainSize: Long? = null,
        mainRole: String = "main",
    ): ManifestBinding {
        val main = fixture("model.gguf", MODEL_BYTES)
        val tokenizer = fixture("tokenizer.json", TOKENIZER_BYTES)
        return ManifestBinding(
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

        /**
         * Distinctive on purpose: the logging tests assert this exact string
         * never reaches a log line, which a plausible value like "llama" could
         * pass by coincidence.
         */
        const val ARCHITECTURE = "arch-from-an-untrusted-file"
    }
}
