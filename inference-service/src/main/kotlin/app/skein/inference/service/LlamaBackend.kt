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
}
