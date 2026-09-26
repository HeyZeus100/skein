package app.skein.testing.scenario

import app.skein.core.model.Capability
import app.skein.core.model.ChatMessage
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.Prompt
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.testing.corpus.Corpus
import app.skein.testing.scenario.ScenarioActivity.ModelReady
import app.skein.testing.scenario.ScenarioActivity.ModelStarting
import app.skein.testing.scenario.ScenarioActivity.PrefillProgress
import app.skein.testing.scenario.ScenarioActivity.RetrievalDone
import app.skein.testing.scenario.ScenarioActivity.RetrievalStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Virtual-time control (advanceTimeBy, runCurrent, currentTime) is still experimental API.
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioInferenceEngineTest {
    private val model =
        Model(
            id = "scenario-model",
            name = "Scenario model",
            path = "/dev/null/scenario.gguf",
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1L,
        )
    private val prompt = Prompt(listOf(ChatMessage(Role.USER, "hi")))
    private val params = SamplingParams()

    private fun texts(tokens: List<Token>): List<String> = tokens.filterIsInstance<Token.Text>().map { it.text }

    private fun done(tokens: List<Token>): Token.Done = tokens.last() as Token.Done

    /** Collects [engine]'s stream in the background so the test can stop it midway. */
    private fun TestScope.collectInBackground(
        engine: ScenarioInferenceEngine,
        into: MutableList<Token>,
    ) = launch { engine.stream(prompt, params).toList(into) }

    @Test
    fun `fast answer plays load, retrieval, prefill and 24 pieces in scripted time`() =
        runTest {
            val events = mutableListOf<ScenarioActivity>()
            val engine = ScenarioInferenceEngine(Scenarios.fastAnswer, Pacing.RealTime(), events::add)

            engine.load(model).getOrThrow()
            val retrieved = engine.retrievalService().retrieveContext("q")
            val tokens = engine.stream(prompt, params).toList()

            assertEquals(200L + 400L + 24 * 30L, currentTime)
            assertEquals(
                listOf(ModelStarting, ModelReady, RetrievalStarted, RetrievalDone(3, 2), PrefillProgress(1_024, 1_024)),
                events,
            )
            assertEquals(3, retrieved.size)
            assertEquals(24, texts(tokens).size)
            assertEquals(Token.Done(StopReason.EOS, 1_024, 24, 400L, 1_000f / 30), tokens.last())
        }

    @Test
    fun `slow prefill reports eight progress chunks`() =
        runTest {
            val events = mutableListOf<ScenarioActivity>()
            val engine = ScenarioInferenceEngine(Scenarios.slowPrefill, Pacing.RealTime(), events::add)

            engine.load(model).getOrThrow()
            assertEquals(3_400L, currentTime)
            val tokens = engine.stream(prompt, params).toList()

            val progress = events.filterIsInstance<PrefillProgress>()
            assertEquals((1..8).map { PrefillProgress(3_412 * it / 8, 3_412) }, progress)
            assertEquals(3_400L + 72_000L + 212 * 85L, currentTime)
            assertEquals(212, texts(tokens).size)
            assertEquals(StopReason.EOS, done(tokens).reason)
        }

    @Test
    fun `reasoning arrives as a think span ahead of the answer`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.reasoning, Pacing.RealTime())
            engine.load(model).getOrThrow()

            val pieces = texts(engine.stream(prompt, params).toList())

            assertEquals(1 + 40 + 1 + 60, pieces.size)
            assertEquals(ScenarioInferenceEngine.THINK_OPEN, pieces.first())
            assertEquals(ScenarioInferenceEngine.THINK_CLOSE, pieces[41])
            assertEquals(9_800L + 40 * 300L + 60 * 40L, currentTime)
        }

    @Test
    fun `reasoning exhausted ends LENGTH with the span still open`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.reasoningExhausted)
            engine.load(model).getOrThrow()

            val tokens = engine.stream(prompt, params).toList()

            assertEquals(StopReason.LENGTH, done(tokens).reason)
            assertEquals(ScenarioInferenceEngine.THINK_OPEN, texts(tokens).first())
            assertFalse(ScenarioInferenceEngine.THINK_CLOSE in texts(tokens))
        }

    @Test
    fun `stop mid stream lands after the stop latency and keeps the partial answer`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.stopMidStream, Pacing.RealTime())
            engine.load(model).getOrThrow()
            val tokens = mutableListOf<Token>()
            val job = collectInBackground(engine, tokens)

            advanceUntilIdle() // ten pieces out, then the stall waits for Stop
            assertEquals(10, tokens.size)
            engine.cancel()
            val stoppedAt = currentTime
            job.join()

            assertEquals(400L, currentTime - stoppedAt)
            assertEquals(10, texts(tokens).size)
            assertEquals(StopReason.CANCELLED, done(tokens).reason)
        }

    @Test
    fun `stop during prefill lands at the chunk boundary with no text`() =
        runTest {
            val events = mutableListOf<ScenarioActivity>()
            val engine = ScenarioInferenceEngine(Scenarios.stopDuringPrefill, Pacing.RealTime(), events::add)
            engine.load(model).getOrThrow()
            val tokens = mutableListOf<Token>()
            val job = collectInBackground(engine, tokens)

            advanceTimeBy(7_000L) // one 5 s chunk read, the second under way
            engine.cancel()
            val stoppedAt = currentTime
            job.join()

            assertEquals(5_000L, currentTime - stoppedAt)
            assertEquals(listOf(PrefillProgress(512, 3_072)), events)
            assertEquals(1, tokens.size)
            assertEquals(StopReason.CANCELLED, done(tokens).reason)
        }

    @Test
    fun `service died closes the stream with ServiceDied after three pieces`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.failServiceDied)
            engine.load(model).getOrThrow()
            val tokens = mutableListOf<Token>()

            val error = runCatching { engine.stream(prompt, params).toList(tokens) }.exceptionOrNull()

            assertTrue("was $error", error is InferenceException.ServiceDied)
            assertEquals(3, tokens.size)
        }

    @Test
    fun `out of memory on load fails load and leaves no model`() =
        runTest {
            val events = mutableListOf<ScenarioActivity>()
            val engine = ScenarioInferenceEngine(Scenarios.failOomOnLoad, Pacing.RealTime(), events::add)

            val result = engine.load(model)

            assertTrue(result.exceptionOrNull() is InferenceException.OutOfMemory)
            assertEquals(2_000L, currentTime)
            assertEquals(listOf(ModelStarting), events)
            val streamError = runCatching { engine.stream(prompt, params).toList() }.exceptionOrNull()
            assertTrue(streamError is InferenceException.ModelNotLoaded)
        }

    @Test
    fun `context full ends LENGTH`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.contextFull)
            engine.load(model).getOrThrow()

            val tokens = engine.stream(prompt, params).toList()

            assertEquals(Token.Done(StopReason.LENGTH, 15_800, 37, 6_000L, 1_000f / 40), tokens.last())
        }

    @Test
    fun `a gate holds model loading and each checkpoint until released`() =
        runTest {
            val gate = ScenarioGate()
            val engine = ScenarioInferenceEngine(Scenarios.modelLoading, Pacing.Gated(gate))

            val load = async { engine.load(model) }
            runCurrent()
            assertEquals(Checkpoint.LoadModel, gate.heldAt.value)
            assertFalse(load.isCompleted)
            gate.release()
            assertTrue(load.await().isSuccess)

            val tokens = mutableListOf<Token>()
            val job = collectInBackground(engine, tokens)
            runCurrent()
            assertEquals(Checkpoint.PrefillChunk(0), gate.heldAt.value)
            gate.release(4) // the prefill chunk and three pieces
            runCurrent()
            assertEquals(3, tokens.size)
            assertEquals(Checkpoint.Piece(3), gate.heldAt.value)

            gate.open()
            job.join()
            assertEquals(25, tokens.size)
            assertEquals(0L, currentTime) // gated pacing spends no time at all
        }

    @Test
    fun `markdown heavy streams a citation marker split across pieces`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.markdownHeavy)
            engine.load(model).getOrThrow()

            val pieces = texts(engine.stream(prompt, params).toList())

            assertTrue(pieces.joinToString("").startsWith(Corpus.mdMixed))
            assertTrue(pieces.windowed(3).contains(listOf("[", "1", "]")))
        }

    @Test
    fun `long answer streams 2000 pieces with no delay`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.longAnswer, Pacing.RealTime())
            engine.load(model).getOrThrow()

            val tokens = engine.stream(prompt, params).toList()

            assertEquals(2_000, texts(tokens).size)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `a cancel with no stream running is dropped`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.fastAnswer)
            engine.load(model).getOrThrow()

            engine.cancel()

            assertEquals(StopReason.EOS, done(engine.stream(prompt, params).toList()).reason)
        }

    @Test
    fun `cancelling the collector frees the engine for the next stream`() =
        runTest {
            val engine = ScenarioInferenceEngine(Scenarios.slowPrefill, Pacing.RealTime())
            engine.load(model).getOrThrow()
            val job = collectInBackground(engine, mutableListOf())
            advanceTimeBy(1_000L)

            job.cancel()
            job.join()

            assertEquals(StopReason.EOS, done(engine.stream(prompt, params).toList()).reason)
        }

    @Test
    fun `every scenario ends in exactly one Done or its documented failure`() =
        runTest {
            for (scenario in Scenarios.all) {
                val engine = ScenarioInferenceEngine(scenario, Pacing.RealTime())
                if (engine.load(model).isFailure) {
                    assertEquals(Scenarios.failOomOnLoad, scenario)
                    continue
                }
                val tokens = mutableListOf<Token>()
                var error: Throwable? = null
                val job =
                    launch {
                        error =
                            runCatching { engine.stream(prompt, params).toList(tokens) }.exceptionOrNull()
                    }
                advanceUntilIdle()
                if (job.isActive) engine.cancel() // the Stall scenarios wait for Stop
                job.join()

                if (scenario == Scenarios.failServiceDied) {
                    assertTrue(scenario.id, error is InferenceException.ServiceDied)
                } else {
                    assertEquals(scenario.id, null, error)
                    assertEquals(scenario.id, 1, tokens.count { it is Token.Done })
                    assertTrue(scenario.id, tokens.last() is Token.Done)
                }
            }
        }

    @Test
    fun `the catalogue carries the plan's scenario ids`() {
        assertEquals(
            listOf(
                "S-FAST-ANSWER",
                "S-SLOW-PREFILL",
                "S-REASONING",
                "S-REASONING-EXHAUSTED",
                "S-STOP-MID-STREAM",
                "S-STOP-DURING-PREFILL",
                "S-FAIL-SERVICE-DIED",
                "S-FAIL-OOM-ON-LOAD",
                "S-CONTEXT-FULL",
                "S-MODEL-LOADING",
                "S-MARKDOWN-HEAVY",
                "S-LONG-ANSWER",
            ),
            Scenarios.all.map { it.id },
        )
    }
}
