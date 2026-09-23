// skein-3aw (E4.I1) — instrumented acceptance tests for the llama.cpp JNI
// bindings. Every assertion in the bead's acceptance criteria lives here.
//
// The model: `androidTest/assets/tiny.gguf`, fetched by the `fetchTestModel`
// Gradle task that E4.I2 (bd skein-80p) adds and pinned by sha256 in
// `tools/models/test-model.lock`. It is NEVER committed. Until that task
// lands — and on any checkout where the fetch was skipped — every model-backed
// test here skips itself via [assumeModel] rather than failing, so this class
// is green in the lanes that have no asset and meaningful in the one that does.
//
// Where this runs: the `dev` x86_64 emulator lane (bd skein-80p) and the Fold
// runner (bd skein-k3b2). It is compiled unconditionally by
// `:inference-service:compileDevDebugAndroidTestKotlin` on every CI run, which
// is what keeps the `external fun` surface and the `Java_…` symbols from
// drifting apart between device runs.

package app.skein.inference.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

private const val TINY_GGUF_ASSET = "tiny.gguf"

// skein-80p (E4.I2): the exact 16-token id sequence a greedy decode of
// "Hello world" produces on the pinned tiny model
// (bartowski/SmolLM2-135M-Instruct-GGUF, Q2_K — tools/models/test-model.lock)
// with the params `goldenGreedySequenceIsPinned` uses below (nCtx = 512,
// nThreads = 2, nBatch = 256, newSampler(temp = 0f, ...)). temp <= 0f makes
// `newSampler` build a chain of ONLY `llama_sampler_init_greedy()`
// (native/llama/jni/skein_jni.cpp), which is pure argmax with no RNG, so
// [seed] does not affect this sequence.
//
// Computed off-device: no emulator/adb is available in this dispatch's
// environment, so this was produced by a throwaway host harness that mirrors
// LlamaNative's decodePrompt/sampleNext/newSampler call sequence exactly,
// linked against `third_party/llama.cpp` at the pinned commit
// (native/llama/PINNED_COMMIT) built for the host CPU backend, and confirmed
// identical across three independent runs. It has NOT been confirmed against
// the CI x86_64 emulator's own CPU backend — greedy argmax is expected to
// agree across CPU SIMD implementations for a model this size (no near-ties
// observed), but if the first `emulator.yml` run after this lands disagrees,
// that is the confirming run: update this array to match it, in a commit
// explaining that specifically (cross-arch float divergence), not "test
// flaked" — this chain has no randomness to flake.
//
// If this ever needs to change for a DIFFERENT reason — a `third_party/
// llama.cpp` submodule bump that altered greedy sampling, tokenization, or
// the pinned model file — the commit message must say why.
private val GOLDEN_GREEDY_IDS =
    intArrayOf(28, 284, 339, 5248, 441, 915, 5348, 563, 260, 905, 28, 339, 5248, 5348, 563, 260)

@RunWith(AndroidJUnit4::class)
class LlamaNativeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var model: Long = 0L
    private var ctx: Long = 0L
    private var sampler: Long = 0L

    @Before
    fun setUp() {
        LlamaNative.backendInit()
        LlamaNative.setLogCallback()
    }

    @After
    fun tearDown() {
        if (sampler != 0L) LlamaNative.freeSampler(sampler)
        if (ctx != 0L) LlamaNative.freeContext(ctx)
        if (model != 0L) LlamaNative.freeModel(model)
        sampler = 0L
        ctx = 0L
        model = 0L
        assertThat(LlamaNative.handleCount()).isEqualTo(0)
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    fun tokenizeReturnsAtLeastTwoIds() {
        // Arrange
        loadTinyModel()

        // Act
        val ids = LlamaNative.tokenize(model, "Hello world", addBos = false, parseSpecial = false)

        // Assert
        assertThat(ids.size).isAtLeast(2)
    }

    @Test
    fun tokenToPieceConcatenatesBackToTheInput() {
        // Arrange
        loadTinyModel()
        val text = "Hello world"
        val ids = LlamaNative.tokenize(model, text, addBos = false, parseSpecial = false)

        // Act — the byte-level accessor is the UTF-8-safe one: a single piece
        // may end mid-sequence, so the bytes are concatenated before decoding.
        val bytes = ids.fold(ByteArray(0)) { acc, id -> acc + LlamaNative.tokenToPieceBytes(model, id) }
        val roundTripped = String(bytes, Charsets.UTF_8)

        // Assert
        assertThat(roundTripped.trim()).isEqualTo(text)
    }

    @Test
    fun applyChatTemplateContainsTheUserMessage() {
        // Arrange
        loadTinyModel()

        // Act
        val prompt =
            LlamaNative.applyChatTemplate(
                model,
                roles = arrayOf("user"),
                contents = arrayOf("Hello world"),
                addAssistant = true,
            )

        // Assert
        assertThat(prompt).contains("Hello world")
    }

    @Test
    fun decodePromptThenEightSamplesYieldsEightIds() {
        // Arrange
        loadTinyModel()
        ctx = LlamaNative.newContext(model, nCtx = 512, nThreads = 2, nBatch = 256, embeddings = false)
        sampler = LlamaNative.newSampler(0.0f, 1, 1.0f, 0.0f, 1.0f, seed = 1234L)
        val prompt = LlamaNative.tokenize(model, "Hello world", addBos = true, parseSpecial = false)

        // Act
        var nPast = LlamaNative.decodePrompt(ctx, prompt, nPast = 0)
        val sampled = IntArray(8)
        for (i in 0 until 8) {
            val id = LlamaNative.sampleNext(ctx, sampler)
            sampled[i] = id
            nPast = LlamaNative.decodePrompt(ctx, intArrayOf(id), nPast)
        }

        // Assert
        assertThat(sampled.size).isEqualTo(8)
        assertThat(nPast).isEqualTo(prompt.size + 8)
    }

    @Test
    fun embedIsUnitLengthAndModelWidth() {
        // Arrange
        loadTinyModel()
        ctx = LlamaNative.newContext(model, nCtx = 512, nThreads = 2, nBatch = 256, embeddings = true)
        val tokens = LlamaNative.tokenize(model, "Hello world", addBos = true, parseSpecial = false)

        // Act — a causal text model has no pooling layer and reports
        // EMBEDDINGS_UNAVAILABLE; that is the "skipped otherwise" branch of
        // the acceptance criterion, not a failure.
        val attempt = runCatching { LlamaNative.embed(ctx, tokens) }
        val unavailable = (attempt.exceptionOrNull() as? LlamaException)?.code == LlamaErrorCode.EMBEDDINGS_UNAVAILABLE
        assumeTrue("not an embedding model", !unavailable)
        val vector = attempt.getOrThrow()

        // Assert
        assertThat(vector.size).isEqualTo(LlamaNative.modelNEmbd(model))
        val norm = sqrt(vector.fold(0.0) { acc, v -> acc + v.toDouble() * v.toDouble() })
        assertThat(abs(norm - 1.0)).isLessThan(1e-3)
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    fun cancelFlagAbortsADecodeWithin100Ms() {
        // Arrange
        loadTinyModel()
        ctx = LlamaNative.newContext(model, nCtx = 1024, nThreads = 2, nBatch = 512, embeddings = false)
        val one = LlamaNative.tokenize(model, "Hello world", addBos = true, parseSpecial = false).first()
        val tokens = IntArray(512) { one }
        val started = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)

        // Act — a 512-token decode on a worker; the flag is set from the test
        // thread (setCancelFlag's documented caller), and the worker must come
        // back with CANCELLED promptly.
        val worker =
            thread(name = "decode-512") {
                started.countDown()
                try {
                    LlamaNative.decodePrompt(ctx, tokens, nPast = 0)
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }
        started.await(5, TimeUnit.SECONDS)
        LlamaNative.setCancelFlag(ctx, true)
        val deadlineMs = 100L
        val joinStart = System.nanoTime()
        worker.join(5_000L)
        val elapsedMs = (System.nanoTime() - joinStart) / 1_000_000L

        // Assert
        assertThat(worker.isAlive).isFalse()
        val error = thrown.get()
        assertThat(error).isInstanceOf(LlamaException::class.java)
        assertThat((error as LlamaException).code).isEqualTo(LlamaErrorCode.CANCELLED)
        assertThat(elapsedMs).isAtMost(deadlineMs)
        LlamaNative.setCancelFlag(ctx, false)
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    fun loadingANonGgufFileFailsWithInvalidModelAndLeaksNoHandle() {
        // Arrange — a real file that is definitely not a GGUF.
        val notAModel = File(context.cacheDir, "not-a-model.bin")
        notAModel.writeBytes(ByteArray(4096) { 0x7f })

        // Act
        val failure =
            runCatching {
                LlamaNative.loadModel(notAModel.absolutePath, nGpuLayers = 0, useMmap = true)
            }.exceptionOrNull()

        // Assert
        assertThat(failure).isInstanceOf(LlamaException::class.java)
        assertThat((failure as LlamaException).code).isEqualTo(LlamaErrorCode.INVALID_MODEL)
        assertThat(LlamaNative.handleCount()).isEqualTo(0)
    }

    @Test
    fun errorMessagesCarryNoPromptText() {
        // Arrange — a path whose *name* is the only "prompt-like" string we
        // control; the point is that nothing the caller passed as content is
        // echoed back. Handle validation is the cheapest place to assert it.
        val secret = "the-secret-prompt-text"

        // Act
        val failure =
            runCatching {
                LlamaNative.tokenize(
                    0L,
                    secret,
                    addBos = false,
                    parseSpecial = false,
                )
            }.exceptionOrNull()

        // Assert
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).doesNotContain(secret)
    }

    // ---------------------------------------------------------- handle guard

    @Test
    fun zeroHandlesThrowIllegalStateException() {
        // Arrange / Act / Assert — every entry point that takes a handle.
        assertThrowsIllegalState { LlamaNative.freeModel(0L) }
        assertThrowsIllegalState { LlamaNative.newContext(0L, 128, 1, 128, false) }
        assertThrowsIllegalState { LlamaNative.freeContext(0L) }
        assertThrowsIllegalState { LlamaNative.freeContextSecure(0L) }
        assertThrowsIllegalState { LlamaNative.freeSampler(0L) }
        assertThrowsIllegalState { LlamaNative.tokenToPieceBytes(0L, 1) }
        assertThrowsIllegalState { LlamaNative.applyChatTemplate(0L, arrayOf("user"), arrayOf("hi"), true) }
        assertThrowsIllegalState { LlamaNative.decodePrompt(0L, intArrayOf(1), 0) }
        assertThrowsIllegalState { LlamaNative.sampleNext(0L, 0L) }
        assertThrowsIllegalState { LlamaNative.embed(0L, intArrayOf(1)) }
        assertThrowsIllegalState { LlamaNative.kvClear(0L) }
        assertThrowsIllegalState { LlamaNative.setCancelFlag(0L, true) }
        assertThrowsIllegalState { LlamaNative.modelMeta(0L, "general.architecture") }
        assertThrowsIllegalState { LlamaNative.modelHasVision(0L) }
        assertThrowsIllegalState { LlamaNative.modelNEmbd(0L) }
        assertThrowsIllegalState { LlamaNative.isEog(0L, 1) }
    }

    @Test
    fun secureFreeZeroesTheKvCacheBeforeFreeing() {
        // Arrange — LOCK_POLICY_INDEXING.md §4.5 / E4.I3's lock path.
        loadTinyModel()
        val before = LlamaNative.secureFreeCount()
        val victim = LlamaNative.newContext(model, nCtx = 256, nThreads = 1, nBatch = 128, embeddings = false)

        // Act
        LlamaNative.freeContextSecure(victim)

        // Assert
        assertThat(LlamaNative.secureFreeCount()).isEqualTo(before + 1)
        assertThat(LlamaNative.handleCount()).isEqualTo(1) // the model only
    }

    // ---------------------------------------------------------- AC 4 (skein-80p)

    @Test
    fun goldenGreedySequenceIsPinned() {
        // Arrange
        loadTinyModel()
        ctx = LlamaNative.newContext(model, nCtx = 512, nThreads = 2, nBatch = 256, embeddings = false)
        sampler = LlamaNative.newSampler(0.0f, 1, 1.0f, 0.0f, 1.0f, seed = 1234L)
        val prompt = LlamaNative.tokenize(model, "Hello world", addBos = true, parseSpecial = false)

        // Act
        var nPast = LlamaNative.decodePrompt(ctx, prompt, nPast = 0)
        val sampled = IntArray(GOLDEN_GREEDY_IDS.size)
        for (i in sampled.indices) {
            val id = LlamaNative.sampleNext(ctx, sampler)
            sampled[i] = id
            nPast = LlamaNative.decodePrompt(ctx, intArrayOf(id), nPast)
        }

        // Assert
        assertThat(sampled).isEqualTo(GOLDEN_GREEDY_IDS)
    }

    @Test
    fun sameSeedTwiceProducesTheSameIds() {
        // Arrange
        loadTinyModel()
        val prompt = LlamaNative.tokenize(model, "Hello world", addBos = true, parseSpecial = false)

        // Act — two independent contexts + samplers over the same loaded
        // model, identical params, the greedy chain (temp = 0f) newSampler
        // documents. handleCount() bookkeeping for these two is closed out
        // inside decodeAndSample so tearDown only sees the model handle.
        val first = decodeAndSample(prompt, seed = 1234L, count = 16)
        val second = decodeAndSample(prompt, seed = 1234L, count = 16)

        // Assert
        assertThat(second).isEqualTo(first)
    }

    // -------------------------------------------------------------- helpers

    private fun assertThrowsIllegalState(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    /** Decodes [prompt] then samples [count] ids, freeing its own context/sampler when done. */
    private fun decodeAndSample(
        prompt: IntArray,
        seed: Long,
        count: Int,
    ): IntArray {
        val localCtx = LlamaNative.newContext(model, nCtx = 512, nThreads = 2, nBatch = 256, embeddings = false)
        val localSampler = LlamaNative.newSampler(0.0f, 1, 1.0f, 0.0f, 1.0f, seed)
        try {
            var nPast = LlamaNative.decodePrompt(localCtx, prompt, nPast = 0)
            val ids = IntArray(count)
            for (i in ids.indices) {
                val id = LlamaNative.sampleNext(localCtx, localSampler)
                ids[i] = id
                nPast = LlamaNative.decodePrompt(localCtx, intArrayOf(id), nPast)
            }
            return ids
        } finally {
            LlamaNative.freeSampler(localSampler)
            LlamaNative.freeContext(localCtx)
        }
    }

    /**
     * Copies `tiny.gguf` out of the instrumentation assets and loads it, or
     * skips the test when the asset was not fetched (see this file's header).
     */
    private fun loadTinyModel() {
        val path = assumeModel()
        model = LlamaNative.loadModel(path, nGpuLayers = 0, useMmap = true)
        assertThat(model).isNotEqualTo(0L)
    }

    private fun assumeModel(): String {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val available = runCatching { assets.list("")?.contains(TINY_GGUF_ASSET) == true }.getOrDefault(false)
        assumeTrue("androidTest asset $TINY_GGUF_ASSET absent (fetched by E4.I2 / skein-80p)", available)
        val out = File(context.cacheDir, TINY_GGUF_ASSET)
        if (!out.exists() || out.length() == 0L) {
            assets.open(TINY_GGUF_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out.absolutePath
    }
}
