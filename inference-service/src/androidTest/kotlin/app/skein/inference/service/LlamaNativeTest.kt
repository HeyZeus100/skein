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
import android.os.ParcelFileDescriptor
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
import java.util.function.BooleanSupplier
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
// Recorded from the CI x86_64 emulator (API 35, `emulator.yml` run
// 35845304360, 2026-09-23), which is where this test asserts. The value
// first pinned here was computed on a macOS arm64 host build of the same
// pinned llama.cpp (28, 284, 339, 5248, …) and diverged from the emulator at
// index 1: the two CPU backends use different matmul kernels, and for a Q2_K
// 135M model the second-token logits are close enough that the argmax flips.
// Greedy argmax is deterministic per backend (`sameSeedTwiceProducesTheSameIds`
// passed on the same run), so a mismatch here is never a flake: it is a
// backend, tokenizer, model-file or sampling change, and the commit that
// updates this array must say which.
//
// If this ever needs to change — a `third_party/llama.cpp` submodule bump
// that altered greedy sampling, tokenization, or the pinned model file — the
// commit message must say why.
private val GOLDEN_GREEDY_IDS =
    intArrayOf(28, 837, 260, 3372, 282, 260, 6128, 359, 7452, 12602, 284, 15289, 288, 260, 4340, 27485)

// bd skein-gg11.7: allowlist of CPU feature names reported by backendReport.
// Must mirror the names appended in native/llama/jni/skein_jni.cpp's backendReport.
private val CPU_FEATURES_ALLOWLIST =
    setOf(
        // x86
        "SSE3",
        "SSSE3",
        "AVX",
        "AVX2",
        "F16C",
        "FMA",
        "AVX512",
        // ARM
        "NEON",
        "ARM_FMA",
        "FP16_VA",
        "DOTPROD",
        "MATMUL_INT8",
        "SVE",
        "SME",
        "SME2",
    )

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

    @Test
    fun descriptorLoadCancellationFromProgressClosesOwnedStreamAndKeepsCallerFd() {
        val file = File(assumeModel())
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            var polls = 0
            val failure =
                runCatching {
                    model =
                        LlamaNative.loadModelFromFd(
                            descriptor.fd,
                            nGpuLayers = 0,
                            useMmap = true,
                            // Initial check passes. The second tensor callback
                            // aborts, proving this is cancellation IN the loader.
                            cancellation = BooleanSupplier { ++polls >= 3 },
                        )
                }.exceptionOrNull()

            assertThat(failure).isInstanceOf(LlamaException::class.java)
            assertThat((failure as LlamaException).code).isEqualTo(LlamaErrorCode.CANCELLED)
            assertThat(polls).isEqualTo(3)
            assertThat(LlamaNative.handleCount()).isEqualTo(0)
            assertThat(descriptor.fileDescriptor.valid()).isTrue()
            // The same caller-owned descriptor remains usable after native
            // cancellation, including its independent duplicated FILE* owner.
            model = LlamaNative.loadModelFromFd(descriptor.fd, nGpuLayers = 0, useMmap = true)
            assertThat(model).isNotEqualTo(0L)
        }
    }

    @Test
    fun pathLoadCancellationFromProgressRetainsNoNativeHandle() {
        val path = assumeModel()
        var polls = 0
        val failure =
            runCatching {
                model =
                    LlamaNative.loadModel(
                        path,
                        nGpuLayers = 0,
                        useMmap = true,
                        cancellation = BooleanSupplier { ++polls >= 3 },
                    )
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LlamaException::class.java)
        assertThat((failure as LlamaException).code).isEqualTo(LlamaErrorCode.CANCELLED)
        assertThat(polls).isEqualTo(3)
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

    // The model-backed half of skein-gg11.28. Unsupported templates are
    // refused by the JVM-tested service path; this fixture proves the real
    // renderer's content boundaries can be verified without a format fallback.
    @Test
    fun chatTemplatingRenderUsesTheModelsOwnTemplateAndMarkersAroundTheContent() {
        // Arrange
        loadTinyModel()
        val rendered =
            ChatTemplating.render(
                backend = NativeLlamaBackend,
                model = model,
                roles = arrayOf("user"),
                contents = arrayOf("Hello world"),
                addAssistantPrefix = true,
            )

        // Compare against the model's native render, not a generic format.
        assertThat(rendered.text).isEqualTo(
            LlamaNative.applyChatTemplate(model, arrayOf("user"), arrayOf("Hello world"), true),
        )
        assertThat(rendered.segments.joinToString("") { it.text }).isEqualTo(rendered.text)
        assertThat(rendered.text).contains("Hello world")
    }

    @Test
    fun safeTemplateTokensMatchNativeTokensForBenignSyntheticConversation() {
        loadTinyModel()
        val roles = arrayOf("system", "user", "assistant", "user")
        val contents = arrayOf("", "Hello world", "Hello", "Café 日本語 🧶")
        val rendered = ChatTemplating.render(NativeLlamaBackend, model, roles, contents, true)

        val actual = ChatTemplating.tokenize(NativeLlamaBackend, model, rendered.segments)
        // Safe only for this synthetic fixture: it contains no control-token
        // spellings. Production must NEVER tokenize arbitrary content this way.
        val reference = LlamaNative.tokenize(model, rendered.text, addBos = true, parseSpecial = true)

        assertThat(actual.toList()).isEqualTo(reference.toList())
    }

    @Test
    fun nativeWhitespaceAndUnicodeTokensMatchWholePromptReference() {
        loadTinyModel()
        val cases = listOf("  Café 日本語 🧶\n\n", "\t leading\n", "\n\n", " café  ")
        for (content in cases) {
            val layout =
                ChatTemplating.render(
                    NativeLlamaBackend,
                    model,
                    arrayOf("system", "user"),
                    arrayOf("Be precise.", content),
                    true,
                )
            val actual = ChatTemplating.tokenize(NativeLlamaBackend, model, layout.segments)
            val reference = LlamaNative.tokenize(model, layout.text, addBos = true, parseSpecial = true)
            assertThat(actual.toList()).isEqualTo(reference.toList())
        }
    }

    @Test
    fun controlsSpanningTemplateAndContentRemainLiteralInBothDirections() {
        loadTinyModel()
        val literal = "<|im_start|>"
        val plain = LlamaNative.tokenize(model, literal, addBos = true, parseSpecial = false)
        for (segments in listOf(
            listOf(Segment(SegmentKind.SCAFFOLD, "<|im_"), Segment(SegmentKind.CONTENT, "start|>")),
            listOf(Segment(SegmentKind.CONTENT, "<|im_"), Segment(SegmentKind.SCAFFOLD, "start|>")),
            listOf(
                Segment(SegmentKind.SCAFFOLD, "<|im_"),
                Segment(SegmentKind.CONTENT, "start"),
                Segment(SegmentKind.SCAFFOLD, "|>"),
            ),
        )) {
            assertThat(ChatTemplating.tokenize(NativeLlamaBackend, model, segments).toList()).isEqualTo(plain.toList())
        }
        val allowed = LlamaNative.tokenizeScaffold(model, literal, true, intArrayOf(0, literal.length))
        assertThat(allowed.toList()).isEqualTo(LlamaNative.tokenize(model, literal, true, true).toList())
        // Authorization is per call; a preceding allowed call cannot grant the next one permission.
        assertThat(LlamaNative.tokenizeScaffold(model, literal, true, intArrayOf()).toList()).isEqualTo(plain.toList())
    }

    @Test
    fun scaffoldTokenizerRejectsMalformedUnicodeAndInvalidByteRanges() {
        loadTinyModel()
        for (ranges in listOf(
            intArrayOf(0),
            intArrayOf(-1, 2),
            intArrayOf(0, 3),
            intArrayOf(0, 1),
            intArrayOf(0, 2, 0, 2),
        )) {
            val failure = runCatching { LlamaNative.tokenizeScaffold(model, "é", true, ranges) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(LlamaException::class.java)
            assertThat((failure as LlamaException).code).isEqualTo(LlamaErrorCode.INVALID_ARGUMENT)
        }
        val malformed =
            runCatching {
                LlamaNative.tokenizeScaffold(
                    model,
                    "\uD800",
                    true,
                    intArrayOf(),
                )
            }.exceptionOrNull()
        assertThat(malformed).isInstanceOf(LlamaException::class.java)
        assertThat((malformed as LlamaException).code).isEqualTo(LlamaErrorCode.INVALID_ARGUMENT)
    }

    @Test
    fun literalChatMlDelimiterCannotAddAnotherNativeControlToken() {
        loadTinyModel()
        val literal = "<|im_start|>"
        val special = LlamaNative.tokenize(model, literal, addBos = false, parseSpecial = true)
        // tools/models/test-model.lock pins a ChatML SmolLM2 instruct vocabulary.
        assertThat(special.size).isEqualTo(1)
        val rendered = ChatTemplating.render(NativeLlamaBackend, model, arrayOf("user"), arrayOf(literal), true)

        val actual = ChatTemplating.tokenize(NativeLlamaBackend, model, rendered.segments)
        val unsafeReference = LlamaNative.tokenize(model, rendered.text, addBos = true, parseSpecial = true)

        assertThat(actual.count { it == special.single() }).isEqualTo(2) // user and assistant headers only
        assertThat(unsafeReference.count { it == special.single() }).isEqualTo(3)
        assertThat(rendered.segments.single { it.kind == SegmentKind.CONTENT }.text).isEqualTo(literal)
    }

    @Test
    fun exactNativeCapacityAcceptsLastReservedPositionAndRefusesOneBeyond() {
        loadTinyModel()
        ctx = LlamaNative.newContext(model, nCtx = 64, nThreads = 2, nBatch = 64, embeddings = false)
        val actualCapacity = LlamaNative.contextLength(ctx)
        assertThat(actualCapacity).isAtLeast(64)
        val layout = ChatTemplating.render(NativeLlamaBackend, model, arrayOf("user"), arrayOf("Hello"), true)
        val ids = ChatTemplating.tokenize(NativeLlamaBackend, model, layout.segments)
        assertThat(ids.size).isLessThan(actualCapacity)
        val nPast = LlamaNative.decodePrompt(ctx, ids, 0)
        val reserve = actualCapacity - ids.size
        assertThat(LlamaNative.decodePrompt(ctx, IntArray(reserve) { ids.last() }, nPast)).isEqualTo(actualCapacity)
        val failure =
            runCatching {
                LlamaNative.decodePrompt(
                    ctx,
                    intArrayOf(ids.last()),
                    actualCapacity,
                )
            }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LlamaException::class.java)
        assertThat((failure as LlamaException).code).isEqualTo(LlamaErrorCode.CONTEXT_FULL)
    }

    @Test
    fun contextCapacityRejectsZeroWrongAndFreedHandles() {
        assertThat(
            runCatching {
                LlamaNative.contextLength(0L)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)
        loadTinyModel()
        assertThat(
            runCatching {
                LlamaNative.contextLength(model)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)
        val freed = LlamaNative.newContext(model, nCtx = 64, nThreads = 2, nBatch = 64, embeddings = false)
        LlamaNative.freeContextSecure(freed)
        assertThat(
            runCatching {
                LlamaNative.contextLength(freed)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)
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

    // ------------------------------------------------------ bd skein-gg11.2

    @Test
    fun backendReportWithNoModelReportsOnlyCompileTimeCpuFeatures() {
        // Act — 0/0 is the documented "nothing loaded" case, not a bad
        // handle: it must not throw, unlike zeroHandlesThrowIllegalStateException's list.
        val report = NativeBackendReport.parse(LlamaNative.backendReport(model = 0L, context = 0L, gpuLayers = 0))

        // Assert
        assertThat(report.devices).isEmpty()
        assertThat(report.cpuFeatures).isNotEmpty()
        // CPU features are reported on every ABI (x86 and ARM); all must be
        // allowlisted names from the native side's CPU_FEATURES_ALLOWLIST mirror.
        for (feature in report.cpuFeatures) {
            assertThat(feature).isIn(CPU_FEATURES_ALLOWLIST)
        }
        assertThat(listOf(report.nOutputsMax, report.nBatch, report.nUbatch)).isEqualTo(listOf(null, null, null))
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
