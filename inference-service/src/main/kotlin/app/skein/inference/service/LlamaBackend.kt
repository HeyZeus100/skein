// skein-nxk (E4.I3): a thin interface over `LlamaNative`, so the service's
// policy — batching, stop strings, cancellation, the load gate, the lock gate —
// is testable on the JVM against a fake instead of only on a device with a real
// GGUF.
//
// `LlamaNative` itself is UNCHANGED and deliberately does not implement this
// interface. `tools/ci/jni-symbols.sh` derives the expected JNI symbol set by
// grepping `^\s*external fun <name>` out of `LlamaNative.kt` and requires exact
// set equality against `nm -D` on `libskein_llama.so`; writing
// `override external fun` would stop those lines matching and silently empty
// the expected set. [NativeLlamaBackend] is therefore a forwarding object, and
// the JNI surface stays exactly what E4.I1 declared.

package app.skein.inference.service

/** One entry of [NativeBackendReport.devices] — `ggml_backend_dev_type`/name, both allowlisted. */
data class NativeBackendDevice(
    val type: Int,
    val name: String,
)

/**
 * The parsed shape of `LlamaNative.backendReport`'s wire line (bd
 * skein-gg11.2, OL-05). [NativeLlamaBackend] parses the raw native string;
 * `FakeLlamaBackend` returns one of these directly, so
 * `InferenceEngineState.backendReport` is testable without a device.
 *
 * @param devices empty when no model is loaded.
 * @param cpuFeatures the compile-time ARM feature names this build has —
 *   always populated, even with nothing loaded.
 * @param gpuLayersOffloaded `0` when no model is loaded or the load was
 *   CPU-only.
 * @param nOutputsMax null when there is no live context.
 * @param nBatch null when there is no live context.
 * @param nUbatch null when there is no live context.
 */
data class NativeBackendReport(
    val devices: List<NativeBackendDevice>,
    val cpuFeatures: List<String>,
    val gpuLayersOffloaded: Int,
    val nOutputsMax: Int?,
    val nBatch: Int?,
    val nUbatch: Int?,
) {
    companion object {
        /**
         * Parses `LlamaNative.backendReport`'s `key=value;key=value` line.
         * `-1` is the native side's "absent" sentinel for the context fields
         * (see that function's KDoc) and becomes `null` here.
         */
        fun parse(raw: String): NativeBackendReport {
            val fields =
                raw.split(';').associate { field ->
                    val eq = field.indexOf('=')
                    if (eq < 0) field to "" else field.substring(0, eq) to field.substring(eq + 1)
                }
            val devices =
                fields["devices"].orEmpty().split(',').filter { it.isNotEmpty() }.map { entry ->
                    val colon = entry.indexOf(':')
                    NativeBackendDevice(
                        type = entry.substring(0, colon).toInt(),
                        name = entry.substring(colon + 1),
                    )
                }
            val cpuFeatures = fields["cpu_features"].orEmpty().split(',').filter { it.isNotEmpty() }

            fun presentInt(key: String): Int? = fields[key]?.toIntOrNull()?.takeIf { it >= 0 }

            return NativeBackendReport(
                devices = devices,
                cpuFeatures = cpuFeatures,
                gpuLayersOffloaded = fields["gpu_layers_offloaded"]?.toIntOrNull() ?: 0,
                nOutputsMax = presentInt("n_outputs_max"),
                nBatch = presentInt("n_batch"),
                nUbatch = presentInt("n_ubatch"),
            )
        }
    }
}

/**
 * Every native call the service makes. One-to-one with `LlamaNative`; see that
 * file for each call's semantics, threading rules and failure modes.
 */
interface LlamaBackend {
    fun backendInit()

    fun setLogCallback()

    /**
     * Loads a GGUF from an ALREADY-OPEN descriptor.
     *
     * Not a path. `skein-lnp2` established that an isolated process cannot open
     * `/proc/self/fd/<n>`: that is a fresh `open(2)` re-checked against the
     * isolated uid, the model file is `0400` owned by the app uid, and AOSP's
     * `isolated_app` policy denies `app_data_file` opens outright. The
     * descriptor the service was handed over Binder is the only way in.
     */
    fun loadModelFromFd(
        fd: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
    ): Long

    fun freeModel(model: Long)

    fun newContext(
        model: Long,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        embeddings: Boolean,
    ): Long

    /** Zeroes the KV cache before freeing — LOCK_POLICY_INDEXING.md §4.5. */
    fun freeContextSecure(ctx: Long)

    fun newSampler(
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Long,
    ): Long

    fun freeSampler(sampler: Long)

    fun tokenize(
        model: Long,
        text: String,
        addBos: Boolean,
        parseSpecial: Boolean,
    ): IntArray

    fun tokenToPieceBytes(
        model: Long,
        id: Int,
    ): ByteArray

    fun applyChatTemplate(
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String

    fun isEog(
        model: Long,
        token: Int,
    ): Boolean

    fun decodePrompt(
        ctx: Long,
        tokens: IntArray,
        nPast: Int,
    ): Int

    fun sampleNext(
        ctx: Long,
        sampler: Long,
    ): Int

    fun embed(
        ctx: Long,
        tokens: IntArray,
    ): FloatArray

    fun kvClear(ctx: Long)

    fun setCancelFlag(
        ctx: Long,
        flag: Boolean,
    )

    fun modelMeta(
        model: Long,
        key: String,
    ): String?

    fun modelHasVision(model: Long): Boolean

    fun modelNEmbd(model: Long): Int

    fun secureFreeCount(): Int

    /** bd skein-gg11.2 — see `NativeBackendReport` and `LlamaNative.backendReport`. */
    fun backendReport(
        model: Long,
        context: Long,
        gpuLayers: Int,
    ): NativeBackendReport

    /** bd skein-gg11.2 (OL-19) — see `LlamaNative.beginLoadLogCapture`. */
    fun beginLoadLogCapture()

    /** bd skein-gg11.2 (OL-19) — see `LlamaNative.drainLoadLogLines`. RAW; callers must redact. */
    fun drainLoadLogLines(): List<String>
}

/** The production backend: pure forwarding to `LlamaNative`. */
object NativeLlamaBackend : LlamaBackend {
    override fun backendInit() = LlamaNative.backendInit()

    override fun setLogCallback() = LlamaNative.setLogCallback()

    override fun loadModelFromFd(
        fd: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
    ): Long = LlamaNative.loadModelFromFd(fd, nGpuLayers, useMmap)

    override fun freeModel(model: Long) = LlamaNative.freeModel(model)

    override fun newContext(
        model: Long,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        embeddings: Boolean,
    ): Long = LlamaNative.newContext(model, nCtx, nThreads, nBatch, embeddings)

    override fun freeContextSecure(ctx: Long) = LlamaNative.freeContextSecure(ctx)

    override fun newSampler(
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Long,
    ): Long = LlamaNative.newSampler(temp, topK, topP, minP, repeatPenalty, seed)

    override fun freeSampler(sampler: Long) = LlamaNative.freeSampler(sampler)

    override fun tokenize(
        model: Long,
        text: String,
        addBos: Boolean,
        parseSpecial: Boolean,
    ): IntArray = LlamaNative.tokenize(model, text, addBos, parseSpecial)

    override fun tokenToPieceBytes(
        model: Long,
        id: Int,
    ): ByteArray = LlamaNative.tokenToPieceBytes(model, id)

    override fun applyChatTemplate(
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String = LlamaNative.applyChatTemplate(model, roles, contents, addAssistant)

    override fun isEog(
        model: Long,
        token: Int,
    ): Boolean = LlamaNative.isEog(model, token)

    override fun decodePrompt(
        ctx: Long,
        tokens: IntArray,
        nPast: Int,
    ): Int = LlamaNative.decodePrompt(ctx, tokens, nPast)

    override fun sampleNext(
        ctx: Long,
        sampler: Long,
    ): Int = LlamaNative.sampleNext(ctx, sampler)

    override fun embed(
        ctx: Long,
        tokens: IntArray,
    ): FloatArray = LlamaNative.embed(ctx, tokens)

    override fun kvClear(ctx: Long) = LlamaNative.kvClear(ctx)

    override fun setCancelFlag(
        ctx: Long,
        flag: Boolean,
    ) = LlamaNative.setCancelFlag(ctx, flag)

    override fun modelMeta(
        model: Long,
        key: String,
    ): String? = LlamaNative.modelMeta(model, key)

    override fun modelHasVision(model: Long): Boolean = LlamaNative.modelHasVision(model)

    override fun modelNEmbd(model: Long): Int = LlamaNative.modelNEmbd(model)

    override fun secureFreeCount(): Int = LlamaNative.secureFreeCount()

    override fun backendReport(
        model: Long,
        context: Long,
        gpuLayers: Int,
    ): NativeBackendReport = NativeBackendReport.parse(LlamaNative.backendReport(model, context, gpuLayers))

    override fun beginLoadLogCapture() = LlamaNative.beginLoadLogCapture()

    override fun drainLoadLogLines(): List<String> = LlamaNative.drainLoadLogLines()
}
