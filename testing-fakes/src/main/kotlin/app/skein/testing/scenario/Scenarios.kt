package app.skein.testing.scenario

import app.skein.core.model.InferenceException
import app.skein.core.model.StopReason
import app.skein.testing.corpus.Corpus
import app.skein.testing.scenario.Step.Answer
import app.skein.testing.scenario.Step.Fail
import app.skein.testing.scenario.Step.Finish
import app.skein.testing.scenario.Step.LoadModel
import app.skein.testing.scenario.Step.Prefill
import app.skein.testing.scenario.Step.Reason
import app.skein.testing.scenario.Step.Retrieve
import app.skein.testing.scenario.Step.Stall

/**
 * The scenario catalogue of UX_TEST_PLAN.md §6.3. Ids are stable: previews
 * and screenshot tests reference them; the states each one drives are named
 * in CHAT_UX_SPEC.md §26.
 */
public object Scenarios {
    // Declared first: object properties initialise in declaration order.
    private val answerWords =
        (
            "Straw colonises in about two weeks at 22 °C [1], hardwood takes closer to three [2]. Keep the " +
                "humidity above 85 % while pinning, give the block fresh air twice a day, and harvest when the " +
                "caps begin to flatten."
        ).split(" ")

    private val reasoningWords =
        (
            "Note [2] logs a 14-day colonisation on straw versus 21 on hardwood, so the answer depends on " +
                "which substrate they mean. The question says logs, so hardwood, but the journal is about straw."
        ).split(" ")

    /** A populated chat; the calm rule (AC-11). */
    public val fastAnswer: Scenario =
        Scenario(
            "S-FAST-ANSWER",
            listOf(
                LoadModel(0),
                Retrieve(200, 3, 2),
                Prefill(400, 1_024),
                Answer(answer(24), 30),
                Finish(StopReason.EOS),
            ),
        )

    /** `chat-activity-starting`, `-searching`, `-reading-5s`, `-reading-3m`, `-reading-progress`, `-writing`; AC-05. */
    public val slowPrefill: Scenario =
        Scenario(
            "S-SLOW-PREFILL",
            listOf(
                LoadModel(3_400),
                Retrieve(400, 7, 3),
                Prefill(72_000, 3_412, chunks = 8),
                Answer(answer(212), 85),
                Finish(StopReason.EOS),
            ),
        )

    /** `chat-reasoning-live`, `chat-reasoning-done`; AC-07. 40 reasoning pieces over 12 s. */
    public val reasoning: Scenario =
        Scenario(
            "S-REASONING",
            listOf(
                Prefill(9_800, 2_048, chunks = 4),
                Reason(cycle(reasoningWords, 40), 300),
                Answer(answer(60), 40),
                Finish(StopReason.EOS),
            ),
        )

    /** `chat-reasoning-exhausted`: thinks until the budget runs out, never answers. */
    public val reasoningExhausted: Scenario =
        Scenario(
            "S-REASONING-EXHAUSTED",
            listOf(
                Prefill(4_000, 1_024, chunks = 2),
                Reason(cycle(reasoningWords, 240), 50, closed = false),
                Finish(StopReason.LENGTH),
            ),
        )

    /** `chat-stopping`, `chat-stopped-partial`: ten pieces, then waits for Stop; the stop takes 400 ms to land. */
    public val stopMidStream: Scenario =
        Scenario("S-STOP-MID-STREAM", listOf(Answer(answer(10), 60), Stall), stopLatencyMs = 400)

    /** `chat-stopped-empty`; AC-19: Stop while reading the prompt lands at the chunk boundary (5 s). */
    public val stopDuringPrefill: Scenario =
        Scenario("S-STOP-DURING-PREFILL", listOf(Prefill(30_000, 3_072, chunks = 6), Stall), stopLatencyMs = 5_000)

    /** `chat-error-servicedied`; AC-20: three pieces, then the service dies. */
    public val failServiceDied: Scenario =
        Scenario("S-FAIL-SERVICE-DIED", listOf(Answer(answer(3), 60), Fail(InferenceException.ServiceDied())))

    /** `chat-model-failed`: `load()` fails with `OutOfMemory` after 2 s. */
    public val failOomOnLoad: Scenario =
        Scenario("S-FAIL-OOM-ON-LOAD", listOf(LoadModel(2_000, failWith = InferenceException.OutOfMemory())))

    /** The out-of-room card: a nearly full context, then `LENGTH` mid-sentence. */
    public val contextFull: Scenario =
        Scenario(
            "S-CONTEXT-FULL",
            listOf(Prefill(6_000, 15_800, chunks = 4), Answer(answer(37), 40), Finish(StopReason.LENGTH)),
        )

    /** `chat-model-starting`: a 20 s model start (hold it with a gate at [Checkpoint.LoadModel]). */
    public val modelLoading: Scenario =
        Scenario(
            "S-MODEL-LOADING",
            listOf(LoadModel(20_000), Prefill(400, 1_024), Answer(answer(24), 30), Finish(StopReason.EOS)),
        )

    /** `chat-content-stress`: F-MD-MIXED, then F-MD-CITE's split markers (`"["`, `"1"`, `"]"`). */
    public val markdownHeavy: Scenario =
        Scenario(
            "S-MARKDOWN-HEAVY",
            listOf(Answer(split(Corpus.mdMixed) + "\n\n" + Corpus.mdCitePieces, 20), Finish(StopReason.EOS)),
        )

    /** Streaming cost and the 2,000-token budget (§12): 2,000 pieces, no delay. */
    public val longAnswer: Scenario =
        Scenario("S-LONG-ANSWER", listOf(Answer(answer(2_000), 0), Finish(StopReason.EOS)))

    /** Every scenario above, in catalogue order. */
    public val all: List<Scenario> =
        listOf(
            fastAnswer,
            slowPrefill,
            reasoning,
            reasoningExhausted,
            stopMidStream,
            stopDuringPrefill,
            failServiceDied,
            failOomOnLoad,
            contextFull,
            modelLoading,
            markdownHeavy,
            longAnswer,
        )

    /** [count] answer pieces: one word each, a leading space on all but the first, cycling the answer text. */
    private fun answer(count: Int): List<String> = cycle(answerWords, count)

    private fun cycle(
        words: List<String>,
        count: Int,
    ): List<String> = List(count) { i -> (if (i == 0) "" else " ") + words[i % words.size] }

    /** [text] as word pieces that keep their leading whitespace, so the pieces join back to the text. */
    private fun split(text: String): List<String> = Regex("""\s*\S+""").findAll(text).map { it.value }.toList()
}
