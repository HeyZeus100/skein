// skein-3aw (E4.I1) — the whole Kotlin side of the llama.cpp JNI boundary.
//
// Design spec §6: "JNI bindings written from scratch (thin wrapper; no
// third-party JNI dependencies)". Thin means: every function here is one
// `external fun` over a handful of llama.cpp calls, all arguments and results
// are primitives / `Long` handles / `IntArray` / `FloatArray` / `ByteArray` /
// `String`, and no policy (batching, stop strings, UTF-8 stream buffering,
// sampling defaults, thermal backoff) lives below this line. Policy is
// `E4.I3`'s (`InferenceService`) and `E4.I6`'s.
//
// PACKAGE DEVIATION (recorded in bd skein-3aw): the plan's Files list
// originally said `us.aherrera.skein.inference.service`. This module's
// namespace has been `app.skein.inference.service` since E1.I2 — the repo
// convention at the time was that *contracts* lived under
// `us.aherrera.skein.*` and *implementation modules* under `app.skein.*`
// (cf. `:core:model`, which holds both). Following the module won over the
// plan's path string; skein-376c later renamed every contract module onto
// `app.skein.*` too, so the deviation is now moot.
//
// HANDLES. A handle is an opaque, never-reused `Long` token minted by the
// native registry in `native/llama/jni/handles.h`, not a pointer. A stale or
// forged value therefore cannot be dereferenced — it simply is not in the
// registry, and the call throws [IllegalStateException] like a `0` would.
// [handleCount] is the debug accessor the bead's acceptance criteria name.
//
// THREADING. Every function documents the thread it may be called from. The
// short version, which `E4.I3` implements:
//   * one worker thread (`InferenceWorker`'s `HandlerThread`) owns a context
//     and is the ONLY thread that may call [decodePrompt], [sampleNext],
//     [embed] or [kvClear] on it;
//   * [setCancelFlag] is the one call deliberately made from *another* thread
//     (a binder thread) while that worker is inside `llama_decode`;
//   * model-level reads ([tokenize], [tokenToPieceBytes], [applyChatTemplate],
//     [modelMeta], [modelHasVision], [modelNEmbd], [isEog]) are safe from any
//     thread — llama.cpp documents its tokenizer API as thread-safe and these
//     touch only immutable model state;
//   * [backendInit] and [setLogCallback] mutate process-global llama.cpp state
//     and must be called once, before any worker starts.
//
// LOGGING. [setLogCallback] installs the native `llama_log_set` sink that
// routes every ggml/llama log line through [LlamaLogRedactor] (E1.I11) and
// then `SkeinLog`. Until it is called, llama.cpp writes to stderr, which on
// Android is swallowed rather than logged — so nothing leaks either way, but
// the service calls it first thing regardless.

package app.skein.inference.service

import app.skein.core.model.SkeinLog

private const val LOG_TAG = "llama.cpp"

/**
 * Thin `external` surface over `libskein_llama.so`.
 *
 * Failure modes, uniform across every function:
 *  * a `0` or unknown handle throws [IllegalStateException] — a caller bug;
 *  * anything llama.cpp rejects throws [LlamaException] carrying a
 *    [LlamaErrorCode] and a message that never contains prompt or token text;
 *  * a C++ exception escaping llama.cpp is caught at the JNI frame and
 *    rethrown as `LlamaException(UNKNOWN, …)` — it never unwinds through JNI.
 */
object LlamaNative {
    init {
        System.loadLibrary("skein_llama")
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * `llama_backend_init()`. Idempotent on the native side.
     *
     * Thread: any, but exactly once and before any other call — it initialises
     * process-global ggml state. `E4.I3` calls it from `Service.onCreate`.
     */
    external fun backendInit()

    /**
     * Installs the `llama_log_set` sink that forwards to [LlamaLogRedactor].
     *
     * Thread: any, once, alongside [backendInit]. llama.cpp documents the
     * logger state as global and *not* thread-safe, so this must not race with
     * an in-flight decode. The callback itself fires on whichever ggml thread
     * logged and attaches to the JVM as a daemon thread when needed.
     */
    external fun setLogCallback()

    /**
     * Loads a GGUF from an ALREADY-OPEN descriptor. **This is the entry point
     * the isolated service uses**; [loadModel] is for dev harnesses only.
     *
     * `skein-lnp2` (M0.5 adversarial review) established that the isolated
     * process cannot load by path at all — not the store path (app-private,
     * `0400`, owned by the app uid) and not `/proc/self/fd/<n>` either, because
     * opening that is a fresh `open(2)` whose DAC and SELinux checks run against
     * the isolated uid, and AOSP's `isolated_app` policy denies `app_data_file`
     * opens outright. Only a descriptor inherited over Binder works.
     *
     * The native side `dup`s [fd] and wraps the duplicate in a `FILE*` for
     * `llama_model_load_from_file_ptr`, so the caller keeps ownership of its own
     * descriptor — `PinnedModelFile` holds it for the model's lifetime and
     * closes it on unload. llama.cpp maps the stream through `fileno()`; no path
     * is resolved anywhere.
     *
     * @param fd a readable descriptor positioned anywhere; the loader seeks.
     * @return a model handle, never `0` on success.
     * @throws LlamaException [LlamaErrorCode.INVALID_MODEL] if the descriptor
     *   does not contain a loadable GGUF, [LlamaErrorCode.INVALID_ARGUMENT] if
     *   it cannot be duplicated.
     *
     * Thread: the inference worker thread. Blocks for seconds on a large model.
     */
    external fun loadModelFromFd(
        fd: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
    ): Long

    /**
     * Loads a GGUF from [path].
     *
     * **Dev harnesses and the JNI instrumented tests only.** The isolated
     * `:inference` process cannot open any path it would want to load — see
     * [loadModelFromFd], which is what `E4.I3` calls.
     *
     * @param nGpuLayers layers to offload; `0` is CPU-only, negative means all.
     * @param useMmap maps to `LLAMA_LOAD_MODE_MMAP` vs `LLAMA_LOAD_MODE_NONE`
     *   (llama.cpp v0.4.1 replaced the old `use_mmap` bool with `load_mode`).
     * @return a model handle, never `0` on success.
     * @throws LlamaException [LlamaErrorCode.INVALID_MODEL] if the file is not
     *   a loadable GGUF, [LlamaErrorCode.OUT_OF_MEMORY] if the mapping fails.
     *   No handle is registered on failure ([handleCount] is unchanged).
     *
     * Thread: the inference worker thread. Blocks for seconds on a large model.
     */
    external fun loadModel(
        path: String,
        nGpuLayers: Int,
        useMmap: Boolean,
    ): Long

    /**
     * `llama_model_free`. Frees every context created from [model] first —
     * that is the caller's job, and freeing a model with live contexts is
     * undefined in llama.cpp.
     *
     * Thread: the inference worker thread.
     */
    external fun freeModel(model: Long)

    /**
     * Creates a context over [model].
     *
     * @param nCtx KV-cache size in tokens (`0` = the model's training context).
     * @param nThreads threads for both generation and batch processing.
     * @param nBatch logical batch size; [decodePrompt] chunks to it.
     * @param embeddings `true` to extract pooled embeddings instead of logits.
     * @throws LlamaException [LlamaErrorCode.OUT_OF_MEMORY] if the KV cache
     *   cannot be allocated.
     *
     * Thread: the inference worker thread.
     */
    external fun newContext(
        model: Long,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        embeddings: Boolean,
    ): Long

    /**
     * Frees a context, zeroing its KV cache first — see [freeContextSecure],
     * which this is an alias for. There is deliberately **no** non-zeroing
     * free path: every context in this process has held decrypted prompt and
     * retrieved-context tokens in its KV cache
     * (`docs/design/LOCK_POLICY_INDEXING.md` §4.5).
     *
     * Thread: the inference worker thread, with no decode in flight.
     */
    external fun freeContext(ctx: Long)

    /**
     * `skein_ctx_free_secure`: `llama_memory_clear(mem, data = true)` — which
     * `ggml_backend_buffer_clear(buf, 0)`s every KV buffer — and only then
     * `llama_free(ctx)`.
     *
     * This is the entry point `docs/design/LOCK_POLICY_INDEXING.md` §4.5 and
     * `E4.I3`'s `onLocked` name explicitly, because "freed" is not "zeroed":
     * `llama_free` returns the KV pages to the allocator with the session's
     * plaintext token state still in them. [secureFreeCount] is the dev-only
     * counter `E4.I3`'s `LockDuringGenerateTest` asserts on.
     *
     * Thread: the binder thread handling `onLocked`, **after** the worker has
     * been cancelled and has left `llama_decode` — the zeroing races an
     * in-flight decode otherwise.
     */
    external fun freeContextSecure(ctx: Long)

    /**
     * `llama_sampler_chain_init` + penalties → top-k → top-p → min-p → temp →
     * dist, the order llama.cpp's own examples use.
     *
     * A [temp] of `0` (or below) builds a greedy chain instead, which is what
     * `E4.I2`'s golden-sequence test pins.
     *
     * @param seed `-1` for a random seed, any other value for a reproducible one.
     *
     * Thread: the inference worker thread. A sampler carries mutable history
     * (the repetition-penalty ring buffer) and must not be shared.
     */
    external fun newSampler(
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Long,
    ): Long

    /** `llama_sampler_free`. Thread: the inference worker thread. */
    external fun freeSampler(sampler: Long)

    // ------------------------------------------------------------ vocabulary

    /**
     * `llama_tokenize` against the model's vocabulary.
     *
     * @param addBos let the model add BOS/EOS if it is configured to.
     * @param parseSpecial tokenize control/special tokens instead of treating
     *   them as plaintext. **`false` for anything user-authored** — `true`
     *   would let a document containing `<|im_start|>` forge a chat turn.
     *
     * Thread: any.
     */
    external fun tokenize(
        model: Long,
        text: String,
        addBos: Boolean,
        parseSpecial: Boolean,
    ): IntArray

    /**
     * The raw bytes of one token's piece — **the UTF-8-safe accessor**, and
     * the one a streaming caller must use.
     *
     * A single token's piece is frequently a *fragment* of a UTF-8 sequence
     * (any CJK or emoji token is split across two or three of them). Decoding
     * each piece on its own replaces those fragments with U+FFFD, so the
     * boundary hands over bytes and `E4.I3`'s `Utf8Buffer` holds an incomplete
     * tail until the next token completes it.
     *
     * Thread: any.
     */
    external fun tokenToPieceBytes(
        model: Long,
        id: Int,
    ): ByteArray

    /**
     * Convenience decode of [tokenToPieceBytes] for whole-piece uses (tests,
     * diagnostics, stop-string literals). **Not** for streaming output: see
     * [tokenToPieceBytes] for why a per-token `String` is lossy.
     *
     * Deliberately not an `external fun` — the acceptance criterion "`nm -D |
     * grep Java_` lists exactly the declared externals" is checked by
     * `tools/ci/jni-symbols.sh`, and a JNI `NewStringUTF` here would have to
     * mangle exactly the bytes this layer exists to preserve.
     *
     * Thread: any.
     */
    fun tokenToPiece(
        model: Long,
        id: Int,
    ): String = String(tokenToPieceBytes(model, id), Charsets.UTF_8)

    /**
     * Applies the GGUF's embedded chat template via
     * `llama_chat_apply_template(llama_model_chat_template(model), …)`.
     *
     * @param roles and [contents] are parallel arrays; they must be the same
     *   length and non-empty ([LlamaErrorCode.INVALID_ARGUMENT] otherwise).
     * @param addAssistant append the assistant-turn opener.
     * @throws LlamaException [LlamaErrorCode.TEMPLATE_UNSUPPORTED] when the
     *   GGUF embeds no template, or one llama.cpp's non-Jinja renderer does
     *   not know. `E4.I6` owns the per-model fallback.
     *
     * Thread: any.
     */
    external fun applyChatTemplate(
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String

    /** `llama_vocab_is_eog` — end-of-generation, for `E4.I3`'s `StopReason.EOS`. Thread: any. */
    external fun isEog(
        model: Long,
        token: Int,
    ): Boolean

    // --------------------------------------------------------------- compute

    /**
     * Decodes [tokens] into the context's KV cache starting at position
     * [nPast], in `n_batch`-sized chunks, requesting logits for the final
     * token only.
     *
     * @return the new `nPast` (`nPast + tokens.size`).
     * @throws LlamaException [LlamaErrorCode.CANCELLED] if [setCancelFlag] was
     *   set — checked both by the `ggml_abort_callback` inside a chunk and
     *   between chunks, so the abort latency is bounded even on a backend
     *   where the callback is not honoured;
     *   [LlamaErrorCode.CONTEXT_FULL] if the KV cache has no slot;
     *   [LlamaErrorCode.OUT_OF_MEMORY] / [LlamaErrorCode.DECODE_FAILED] otherwise.
     *
     * Thread: the inference worker thread that owns [ctx], exclusively.
     */
    external fun decodePrompt(
        ctx: Long,
        tokens: IntArray,
        nPast: Int,
    ): Int

    /**
     * `llama_sampler_sample(sampler, ctx, -1)` over the logits of the last
     * decoded token; the sampler accepts the token internally, so the
     * repetition-penalty history advances with it.
     *
     * Thread: the inference worker thread that owns [ctx], exclusively, and
     * only after a [decodePrompt] that requested logits.
     */
    external fun sampleNext(
        ctx: Long,
        sampler: Long,
    ): Int

    /**
     * Mean-pooled, L2-normalised embedding of [tokens], as llama.cpp's
     * `embedding` example computes it. The context must have been created with
     * `embeddings = true`.
     *
     * When the model pools internally (`llama_pooling_type != NONE`) the
     * sequence embedding is taken as-is and normalised; otherwise the
     * per-token embeddings are averaged first. A causal text model that
     * exposes neither throws [LlamaErrorCode.EMBEDDINGS_UNAVAILABLE].
     *
     * Clears the KV cache before and after: an embedding pass must not leave
     * the generation context polluted, and must not leave the embedded text
     * resident afterwards.
     *
     * Thread: the inference worker thread that owns [ctx], exclusively.
     */
    external fun embed(
        ctx: Long,
        tokens: IntArray,
    ): FloatArray

    /**
     * `llama_memory_clear(mem, data = true)` — drops the KV metadata *and*
     * zeroes the buffers, which is what makes it usable as the between-turns
     * hygiene step as well as a reset.
     *
     * Thread: the inference worker thread that owns [ctx], exclusively.
     */
    external fun kvClear(ctx: Long)

    /**
     * Raises or clears the context's abort flag. Raising it makes the
     * in-flight [decodePrompt] fail with [LlamaErrorCode.CANCELLED]; the flag
     * stays raised until cleared, so the caller must clear it before reusing
     * the context.
     *
     * Thread: **any thread, concurrently with a decode on the worker** — that
     * is the entire point. The flag is a `std::atomic<bool>` owned by the
     * context's registry entry, so this is safe while the worker is inside
     * `llama_decode`.
     */
    external fun setCancelFlag(
        ctx: Long,
        flag: Boolean,
    )

    // -------------------------------------------------------------- metadata

    /**
     * One GGUF metadata value by key (`general.architecture`,
     * `tokenizer.chat_template`, …), or `null` when the key is absent.
     *
     * Thread: any.
     */
    external fun modelMeta(
        model: Long,
        key: String,
    ): String?

    /**
     * Whether the GGUF carries a vision tower — any metadata key matching
     * `clip.*` or `*.vision.*`.
     *
     * `E4.I11` adds the `mtmd*` entry points that actually *use* it; this is
     * the cheap capability probe `E4.I4`'s `ModelManager` needs to decide
     * whether an mmproj companion is required.
     *
     * Thread: any.
     */
    external fun modelHasVision(model: Long): Boolean

    /** `llama_model_n_embd` — the embedding width [embed] returns. Thread: any. */
    external fun modelNEmbd(model: Long): Int

    // ----------------------------------------------------------------- debug

    /**
     * Live handles in the native registry (models + contexts + samplers).
     *
     * The bead's acceptance criterion "loading a non-GGUF file … leaves no
     * leaked handle" is this returning `0`. Cheap (one mutex + `map.size()`),
     * so it is compiled into release builds too — a leak check that only
     * exists in debug is a leak check that never runs on the Fold.
     *
     * Thread: any.
     */
    external fun handleCount(): Int

    /**
     * How many times the zero-then-free path of [freeContextSecure] has run
     * since process start. The hook `E4.I3`'s lock tests assert on
     * (`LOCK_POLICY_INDEXING.md` §7.7).
     *
     * Thread: any.
     */
    external fun secureFreeCount(): Int

    /**
     * `skein-gg11.2` (OL-05, `docs/design/SKEIN_HUB.md` §12): a privacy-safe
     * diagnostic of which backend devices and CPU features this build
     * actually has, packed into one semicolon-delimited line —
     * [NativeBackendReport.parse] (`LlamaBackend.kt`) is the other half of
     * this format. One external rather than several structured return types,
     * per the bead's own instruction; every field is either a count/number or
     * a name matched against a fixed allowlist in `skein_jni.cpp`'s
     * `AllowlistedBackendName` — never a raw GGUF string, a path, or a byte
     * dump.
     *
     * @param model `0` for the compile-time-only report (no model loaded):
     *   the CPU feature list is still populated, `devices` is empty.
     * @param context `0` when no context exists (a bare `inspect`, or nothing
     *   loaded): `n_outputs_max`/`n_batch`/`n_ubatch` are absent.
     * @param gpuLayers the value [model] was ACTUALLY loaded with — the
     *   report re-derives the device list via the same rule
     *   [loadModelFromFd] applied, and is only truthful if this matches.
     *   Ignored when [model] is `0`.
     *
     * Thread: any when [context] is `0`; the inference worker thread
     * otherwise (same rule as every other context-touching call).
     */
    external fun backendReport(
        model: Long,
        context: Long,
        gpuLayers: Int,
    ): String

    // ------------------------------------------------ called from native code

    /**
     * The `llama_log_set` sink's Kotlin half, invoked from
     * `skein_jni.cpp`'s `ggml_log_callback`. Public only because JNI needs a
     * resolvable method id; nothing in Kotlin should call it.
     *
     * @param level a raw `ggml_log_level`: 1 DEBUG, 2 INFO, 3 WARN, 4 ERROR.
     *   `0` (NONE) and `5` (CONT, a continuation line carrying no level of its
     *   own) are mapped to DEBUG and therefore dropped — a continuation of a
     *   warning is not worth the risk of forwarding an unlevelled fragment.
     *
     * Thread: whichever ggml/llama thread emitted the line, attached to the
     * JVM as a daemon thread by the native side if it was not already.
     */
    @JvmStatic
    fun onNativeLog(
        level: Int,
        message: String,
    ) {
        val mapped =
            when (level) {
                3 -> LlamaLogLevel.WARN
                4 -> LlamaLogLevel.ERROR
                else -> LlamaLogLevel.DEBUG
            }
        captureIfLoading(mapped, message)
        val safe = LlamaLogRedactor.forward(mapped, message) ?: return
        when (mapped) {
            LlamaLogLevel.ERROR -> SkeinLog.e(LOG_TAG, safe)
            else -> SkeinLog.w(LOG_TAG, safe)
        }
    }

    // -------------------------------------------------- load-error capture

    // `skein-gg11.2` (OL-19, `JNI_ANALYSIS.md` §5): the first few WARN/ERROR
    // lines llama.cpp logs during a load attempt are the informative ones —
    // llama.cpp's own errors cascade specific ("missing tensor blk.0…") to
    // generic ("model load failed"). Every such line already crosses into
    // Kotlin through [onNativeLog] above; this is a small ring buffer over
    // that existing callback, not a second native log path. Capturing is
    // GATED by [loadLogCapturing] so a WARN/ERROR from an unrelated call
    // (a decode, an embed) between two loads never contaminates the next
    // failure's detail — only `InferenceEngineState.load`/`readInspection`
    // toggle it, immediately before a load attempt.
    private val loadLogLock = Any()
    private var loadLogCapturing = false
    private val loadLogLines = mutableListOf<String>()

    private const val MAX_CAPTURED_LOAD_LOG_LINES = 4
    private const val MAX_CAPTURED_LOAD_LOG_LINE_CHARS = 160

    private fun captureIfLoading(
        level: LlamaLogLevel,
        message: String,
    ) {
        if (level < LlamaLogLevel.WARN) return
        synchronized(loadLogLock) {
            if (loadLogCapturing && loadLogLines.size < MAX_CAPTURED_LOAD_LOG_LINES) {
                loadLogLines += message.take(MAX_CAPTURED_LOAD_LOG_LINE_CHARS)
            }
        }
    }

    /**
     * Clears any lines left over from a previous attempt and starts
     * recording. Call immediately before [loadModelFromFd]/[loadModel] on the
     * inference worker thread.
     */
    fun beginLoadLogCapture() {
        synchronized(loadLogLock) {
            loadLogCapturing = true
            loadLogLines.clear()
        }
    }

    /**
     * Stops recording and returns what was captured since
     * [beginLoadLogCapture], oldest first, RAW — callers MUST run each line
     * through [LlamaLogRedactor.redact] before it reaches an exception
     * message or a log line (spec §9's no-raw-content rule applies to a load
     * failure's detail exactly as it applies to everything else this process
     * logs). Already capped in count and per-line length, so a hostile GGUF
     * that spams warnings during a load cannot grow this without bound.
     */
    fun drainLoadLogLines(): List<String> =
        synchronized(loadLogLock) {
            loadLogCapturing = false
            loadLogLines.toList()
        }
}
