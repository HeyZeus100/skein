// The `E0.I10` contract suite: an abstract JUnit 4 test class that any
// `InferenceEngine` implementation — the `FakeInferenceEngine` here, the
// production `LlamaCppEngine` in `E4.I4`, and any future engine — must
// satisfy. The six semantic tests are lifted directly from the spec § 4.1
// "Semantics locked here" bullet list and the plan § 4.1 acceptance
// criteria for `E0.I10`.
//
// The task description called for JUnit 5; the surrounding codebase (see
// every test under `testing/src/test/kotlin/**`) is on JUnit 4 (4.13.2 via
// the version catalog) and the `:testing` module re-exports JUnit 4
// (`api(libs.junit)`). We match the codebase to keep the contract usable
// by downstream modules without forcing a per-module Vintage engine
// configuration.
//
// Kept in `src/main` (not `src/test`) so downstream modules can consume it
// as `testImplementation(project(":testing"))` — mirroring the pattern
// already used by `MainDispatcherRule` and the other JUnit rules in
// `app.skein.testing`. This module's own test sourceSet extends this
// class with `FakeInferenceEngineTest` to prove the contract passes on
// the JVM.

package us.aherrera.skein.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.ChatMessage
import us.aherrera.skein.core.model.InferenceEngine
import us.aherrera.skein.core.model.InferenceException
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.Prompt
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.core.model.SamplingParams
import us.aherrera.skein.core.model.Token

/**
 * Contract suite for `InferenceEngine` (spec § 4.1). Concrete subclasses
 * provide an engine and a text-capable model; each test then exercises one
 * semantic locked by the design.
 *
 * The suite is deliberately narrow — the six tests below map 1:1 to the
 * bullets in spec § 4.1 "Semantics locked here" and to the acceptance
 * criteria for `E0.I10`. Wider behavior (sampler tuning, thermal state,
 * KV-cache reuse) is out of scope for the contract; those are verified by
 * implementation-specific instrumented tests (`LlamaCppEngineTest` under
 * `E4.I4`).
 */
public abstract class InferenceEngineContractTest {
    /** The engine under test. Called once per test method. */
    protected abstract fun engine(): InferenceEngine

    /**
     * A text-capable model the engine can `load`. Must include
     * `Capability.TEXT` and must **not** include `Capability.EMBEDDING`
     * (the "embed without capability" test derives from this by leaving
     * embedding off).
     */
    protected abstract fun textModel(): Model

    /**
     * A convention: any `Model` whose `sha256` equals this value must fail
     * `load` with `Result.failure(InferenceException.HashMismatch)`. The
     * value is a 64-char run of zeros — no real corpus file hashes to this,
     * so both the fake and any real hashing engine can honor the same
     * sentinel without a bespoke abstract hook.
     */
    protected val badHashSentinel: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * A prompt with a single user turn; used by tests that need to trigger
     * `stream`. Subclasses may override if a specific script must be
     * matched, but the default is sufficient for the semantic tests here.
     */
    protected open fun samplePrompt(userMessage: String = "hi"): Prompt =
        Prompt(messages = listOf(ChatMessage(role = Role.USER, content = userMessage)))

    protected open fun samplingParams(): SamplingParams = SamplingParams(maxTokens = 8)

    // ------------------------------------------------------------------
    // §4.1 — "Exactly one `Token.Done` is emitted last on any non-
    // exceptional path."
    // ------------------------------------------------------------------

    @Test
    public fun stream_emits_done_last(): Unit =
        runTest {
            val engine = engine()
            engine.load(textModel()).getOrThrow()

            val tokens: List<Token> =
                engine.stream(samplePrompt(), samplingParams()).toList()

            assertTrue(
                "stream must emit at least a terminal Token.Done",
                tokens.isNotEmpty(),
            )
            assertTrue(
                "last emission must be Token.Done, was ${tokens.last()::class.simpleName}",
                tokens.last() is Token.Done,
            )
            assertEquals(
                "exactly one Token.Done must be emitted",
                1,
                tokens.count { it is Token.Done },
            )
        }

    // ------------------------------------------------------------------
    // §4.1 — "cancelling the collector cancels generation within 100 ms."
    // ------------------------------------------------------------------

    @Test
    public fun cancel_stops_within_100ms(): Unit =
        runTest {
            val engine = engine()
            engine.load(textModel()).getOrThrow()

            // Bound the whole test on real time — the assertion is about
            // wall-clock latency, not virtual delays.
            withTimeout(1_000L) {
                val job: Job =
                    launch {
                        try {
                            engine.stream(samplePrompt(), samplingParams()).collect {
                                // Suspend forever to guarantee we're mid-stream
                                // when cancel arrives.
                                awaitCancellation()
                            }
                        } catch (_: Throwable) {
                            // Any exception (including CancellationException)
                            // counts as "stopped".
                        }
                    }

                // Give the flow a chance to start.
                delay(10L)
                val startNs = System.nanoTime()
                job.cancelAndJoin()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                assertTrue(
                    "expected cancellation within 100 ms, took $elapsedMs ms",
                    elapsedMs < 100L,
                )
            }
        }

    // ------------------------------------------------------------------
    // §4.1 — "Only one `stream` may be active per engine; a second
    // concurrent collector fails with `Busy`."
    // ------------------------------------------------------------------

    @Test
    public fun second_stream_fails_busy(): Unit =
        runTest {
            val engine = engine()
            engine.load(textModel()).getOrThrow()

            val firstStarted = CompletableDeferred<Unit>()
            val holdFirst = CompletableDeferred<Unit>()
            val firstJob =
                launch {
                    try {
                        engine.stream(samplePrompt(), samplingParams()).collect { token ->
                            if (!firstStarted.isCompleted) firstStarted.complete(Unit)
                            // Suspend so the stream stays "in flight" while we
                            // try to collect a second one on this engine.
                            holdFirst.await()
                            @Suppress("UNUSED_EXPRESSION")
                            token
                        }
                    } catch (_: Throwable) {
                        // ignore
                    }
                }

            firstStarted.await()

            try {
                engine.stream(samplePrompt("second"), samplingParams()).first()
                fail("expected InferenceException.Busy")
            } catch (expected: InferenceException.Busy) {
                assertNotNull(expected)
            }

            // Let the first stream finish so runTest doesn't hang.
            holdFirst.complete(Unit)
            firstJob.cancelAndJoin()
        }

    // ------------------------------------------------------------------
    // §4.1 — "`embed` requires `Capability.EMBEDDING` on the loaded
    // model; otherwise throws `InvalidModel`."
    // ------------------------------------------------------------------

    @Test
    public fun embed_without_capability_throws_invalid_model(): Unit =
        runTest {
            val engine = engine()
            val textOnly = textModel()
            assertFalse(
                "textModel() must not carry EMBEDDING capability for this test",
                Capability.EMBEDDING in textOnly.capabilities,
            )
            engine.load(textOnly).getOrThrow()

            try {
                engine.embed("hello")
                fail("expected InferenceException.InvalidModel")
            } catch (expected: InferenceException.InvalidModel) {
                assertNotNull(expected)
            }
        }

    // ------------------------------------------------------------------
    // §4.1 — "`load` never throws; failure is `Result.failure(...)`."
    // Hash-mismatch surfaces as `InferenceException.HashMismatch`.
    // ------------------------------------------------------------------

    @Test
    public fun load_bad_hash_returns_failure(): Unit =
        runTest {
            val engine = engine()
            val bad = textModel().copy(sha256 = badHashSentinel)
            val result: Result<Unit> = engine.load(bad)
            assertTrue(
                "load with badHashSentinel must return Result.failure, was $result",
                result.isFailure,
            )
            val ex = result.exceptionOrNull()
            assertTrue(
                "expected HashMismatch, was ${ex?.let { it::class.simpleName }}",
                ex is InferenceException.HashMismatch,
            )
        }

    // ------------------------------------------------------------------
    // §4.1 — after `unload`, `stream` throws `ModelNotLoaded`.
    // ------------------------------------------------------------------

    @Test
    public fun unload_then_stream_throws_not_loaded(): Unit =
        runTest {
            val engine = engine()
            engine.load(textModel()).getOrThrow()
            engine.unload()

            try {
                // `first` triggers `flow { … }` execution up to the first emission.
                engine.stream(samplePrompt(), samplingParams()).first()
                fail("expected InferenceException.ModelNotLoaded")
            } catch (expected: InferenceException.ModelNotLoaded) {
                assertNotNull(expected)
            }
        }
}
