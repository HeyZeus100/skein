// skein-nxk (E4.I3): a scriptable [LlamaBackend] for the JVM tests.
//
// Every call is a `Handle`-shaped no-op by default and every interesting one is
// overridable, so a test overrides only the calls it is about. The point of
// `LlamaBackend` existing at all is that the service's policy — the load gate,
// the lock gate, batching, stop strings, cancellation, warm swap — can be
// driven here rather than only on a device with a real GGUF.

package app.skein.inference.service

/** Records handles handed out so a test can assert nothing leaked. */
open class FakeLlamaBackend : LlamaBackend {
    var backendInitCalls: Int = 0
        private set
    var logCallbackCalls: Int = 0
        private set

    val liveModels = mutableSetOf<Long>()
    val liveContexts = mutableSetOf<Long>()
    val liveSamplers = mutableSetOf<Long>()

    /** fds this backend was asked to load from, in order. */
    val loadedFds = mutableListOf<Int>()

    /** Contexts freed through the zero-then-free path. */
    val secureFrees = mutableListOf<Long>()

    /** Cancel-flag transitions, as (ctx, flag). */
    val cancelFlags = mutableListOf<Pair<Long, Boolean>>()

    /**
     * GGUF metadata this fake's models declare, by key. Empty means every
     * `modelMeta` answers null, which is what a GGUF stating nothing does.
     */
    val meta = mutableMapOf<String, String>()

    /**
     * The KV-cache and decode calls, in the order they were made — the two
     * whose ORDER is load-bearing (E-4: a request's cache must be cleared
     * before its first decode, not after).
     */
    val cacheAndDecodeCalls = mutableListOf<String>()

    private var nextHandle = 1L

    override fun backendInit() {
        backendInitCalls++
    }

    override fun setLogCallback() {
        logCallbackCalls++
    }

    override fun loadModelFromFd(
        fd: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
    ): Long {
        loadedFds += fd
        return (nextHandle++).also { liveModels += it }
    }

    override fun freeModel(model: Long) {
        liveModels -= model
    }

    /**
     * How many contexts were ever asked for — not how many are live. `inspect`
     * must create none at all, and a context created and freed again would
     * leave [liveContexts] empty just the same.
     */
    var newContextCalls: Int = 0
        private set

    override fun newContext(
        model: Long,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        embeddings: Boolean,
    ): Long {
        newContextCalls++
        return (nextHandle++).also { liveContexts += it }
    }

    override fun freeContextSecure(ctx: Long) {
        liveContexts -= ctx
        secureFrees += ctx
    }

    override fun newSampler(
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Long,
    ): Long = (nextHandle++).also { liveSamplers += it }

    override fun freeSampler(sampler: Long) {
        liveSamplers -= sampler
    }

    override fun tokenize(
        model: Long,
        text: String,
        addBos: Boolean,
        parseSpecial: Boolean,
    ): IntArray = IntArray(text.length.coerceAtMost(8)) { it + 1 }

    override fun tokenToPieceBytes(
        model: Long,
        id: Int,
    ): ByteArray = "t$id".toByteArray(Charsets.UTF_8)

    override fun applyChatTemplate(
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String =
        buildString {
            for (i in roles.indices) {
                append("<|im_start|>").append(roles[i]).append('\n')
                append(contents[i])
                append("<|im_end|>\n")
            }
            if (addAssistant) append("<|im_start|>assistant\n")
        }

    override fun isEog(
        model: Long,
        token: Int,
    ): Boolean = false

    override fun decodePrompt(
        ctx: Long,
        tokens: IntArray,
        nPast: Int,
    ): Int {
        cacheAndDecodeCalls += DECODE_PROMPT
        return nPast + tokens.size
    }

    override fun sampleNext(
        ctx: Long,
        sampler: Long,
    ): Int = 7

    override fun embed(
        ctx: Long,
        tokens: IntArray,
    ): FloatArray = FloatArray(4) { 0.5f }

    override fun kvClear(ctx: Long) {
        cacheAndDecodeCalls += KV_CLEAR
    }

    override fun setCancelFlag(
        ctx: Long,
        flag: Boolean,
    ) {
        cancelFlags += ctx to flag
    }

    override fun modelMeta(
        model: Long,
        key: String,
    ): String? = meta[key]

    override fun modelHasVision(model: Long): Boolean = false

    override fun modelNEmbd(model: Long): Int = 4

    override fun secureFreeCount(): Int = secureFrees.size

    companion object {
        const val KV_CLEAR = "kvClear"
        const val DECODE_PROMPT = "decodePrompt"
    }
}
