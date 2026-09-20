package app.skein.testing.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic, scripted stand-in for the (not-yet-landed) `InferenceEngine`
 * contract (plan §4.1, `E0.I10`). Maps a prompt string to a canned list of
 * response chunks; an unscripted prompt yields [defaultResponse]. This is a
 * stub for unit tests — it has no tokenizer, no sampling, and no notion of
 * context length or thermal state. Do not use it to assert anything about
 * real model behavior; that belongs in `E0.I10`'s contract suite.
 *
 * `E10.I2` (skein-0j1) re-targets this against the real `InferenceEngine`
 * interface (and its `Prompt`/`SamplingParams`/`Token` types) once `E0.I10`
 * lands; the simplified `String`-based signatures here are a placeholder.
 */
class FakeInferenceEngine(
    private val scriptedResponses: Map<String, List<String>> = emptyMap(),
    private val defaultResponse: List<String> = listOf("(no scripted response)"),
) {
    var loadCallCount: Int = 0
        private set
    var lastPrompt: String? = null
        private set
    var cancelled: Boolean = false
        private set

    fun load(): Result<Unit> {
        loadCallCount++
        return Result.success(Unit)
    }

    /** Emits the scripted chunks for [prompt] (falling back to [defaultResponse]) as a cold flow. */
    fun stream(prompt: String): Flow<String> {
        lastPrompt = prompt
        cancelled = false
        val chunks = scriptedResponses[prompt] ?: defaultResponse
        return flow {
            for (chunk in chunks) {
                emit(chunk)
            }
        }
    }

    fun cancel() {
        cancelled = true
    }
}

/** Builder mirroring the plan's `scriptedEngine("q" to listOf("a", "b"))` shape (`E10.I2`). */
fun scriptedEngine(vararg responses: Pair<String, List<String>>): FakeInferenceEngine =
    FakeInferenceEngine(scriptedResponses = responses.toMap())
