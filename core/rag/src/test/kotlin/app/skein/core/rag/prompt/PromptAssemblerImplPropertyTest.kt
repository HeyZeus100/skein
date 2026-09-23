// skein-82g (E5.I15) AC3 / plan `E5.I15`: "`estimatedTokens ≤ contextLength -
// reserveForAnswer` for 100 random histories" (property test). A seeded
// `kotlin.random.Random`, per this repo's existing property-test convention
// (`core/markdown/.../MarkdownAstRoundTripPropertyTest.kt`) — no new test
// library.
//
// For each of 100 trials this generates a random history (0-50 turns),
// random retrieved chunks (0-8, including role-marker/fence-lookalike text
// so the guarded rendering is exercised too), a random persona, and a random
// `maxRetrievedTokens`. The prompt budget (`contextLength - reserveForAnswer`)
// is then chosen so it is *always* achievable — at or above the token cost of
// assembling with the same inputs and zero history — because `PromptAssembler`
// (`E0.I12`'s KDoc) never drops the system or final-user message: a budget
// too small for those alone is a contract violation of the budget itself
// (`E4.I7`'s job to avoid), not something `assemble` can fix by truncation.
// Within that always-achievable range, the budget is still randomized so
// most trials do force some history to be dropped.
package app.skein.core.rag.prompt

import app.skein.core.model.DocumentKind
import app.skein.core.model.Message
import app.skein.core.model.Persona
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.TokenBudget
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PromptAssemblerImplPropertyTest {
    @Test
    fun estimated_tokens_never_exceed_the_prompt_budget_for_100_random_histories() {
        val random = Random(SEED)
        val assembler = PromptAssemblerImpl()

        repeat(TRIALS) { trial ->
            val persona = randomPersona(random, trial)
            val history = randomHistory(random)
            val retrieved = randomRetrieved(random)
            val userQuery = randomText(random, 1, 10)
            val maxRetrievedTokens = random.nextInt(0, MAX_RETRIEVED_TOKENS_CEILING + 1)

            fun assembleWith(
                effectiveHistory: List<Message>,
                contextLength: Int,
            ) = assembler.assemble(
                persona = persona,
                history = effectiveHistory,
                retrieved = retrieved,
                userQuery = userQuery,
                budget =
                    TokenBudget(
                        contextLength = contextLength,
                        reserveForAnswer = RESERVE_FOR_ANSWER,
                        maxRetrievedTokens = maxRetrievedTokens,
                    ),
                countTokens = ::countTokens,
            )

            // The floor: the cost of the system + final-user message alone, with all history
            // dropped. Any valid budget must be at least this, or the invariant is unsatisfiable
            // by construction (see file header).
            val floor = assembleWith(emptyList(), ROOMY_CONTEXT_LENGTH)
            val roomy = assembleWith(history, ROOMY_CONTEXT_LENGTH)

            val minPromptBudget = floor.estimatedTokens
            val maxPromptBudget = roomy.estimatedTokens + PROMPT_BUDGET_SLACK
            val promptBudget =
                if (minPromptBudget >= maxPromptBudget) {
                    minPromptBudget
                } else {
                    random.nextInt(minPromptBudget, maxPromptBudget + 1)
                }
            val contextLength = RESERVE_FOR_ANSWER + promptBudget

            val result = assembleWith(history, contextLength)

            assertTrue(
                "trial $trial: estimatedTokens ${result.estimatedTokens} exceeds prompt budget " +
                    "$promptBudget (history=${history.size} turns, retrieved=${retrieved.size} items, " +
                    "maxRetrievedTokens=$maxRetrievedTokens)",
                result.estimatedTokens <= promptBudget,
            )
        }
    }

    private fun randomPersona(
        random: Random,
        trial: Int,
    ): Persona? =
        when {
            random.nextInt(THREE) == 0 -> null
            random.nextBoolean() ->
                Persona(
                    id = "persona-$trial",
                    name = "Trial $trial",
                    systemPrompt = null,
                    defaultModel = null,
                    createdAt = 0L,
                )
            else ->
                Persona(
                    id = "persona-$trial",
                    name = "Trial $trial",
                    systemPrompt = randomText(random, 1, 20),
                    defaultModel = null,
                    createdAt = 0L,
                )
        }

    private fun randomHistory(random: Random): List<Message> {
        val turns = random.nextInt(0, MAX_HISTORY_TURNS + 1)
        return (1..turns).map { i ->
            Message(
                id = "msg-$i",
                chatDocId = CHAT_DOC_ID,
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT,
                contentMd = randomText(random, 1, 60),
                modelId = null,
                retrievedChunks = emptyList(),
                createdAt = i.toLong(),
            )
        }
    }

    private fun randomRetrieved(random: Random): List<Retrieved> {
        val count = random.nextInt(0, MAX_RETRIEVED_ITEMS + 1)
        return (1..count).map { i ->
            Retrieved(
                chunkId = i.toLong(),
                docId = "doc-$i",
                docTitle = randomText(random, 1, 6),
                text = randomText(random, 1, 80),
                score = 1.0 - i * SCORE_STEP,
                sourceKind = DocumentKind.entries[random.nextInt(DocumentKind.entries.size)],
                recalledBy = setOf(RecallSource.entries[random.nextInt(RecallSource.entries.size)]),
            )
        }
    }

    private fun randomText(
        random: Random,
        minWords: Int,
        maxWords: Int,
    ): String {
        val count = random.nextInt(minWords, maxWords + 1)
        return (1..count).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] }
    }

    private companion object {
        /** Deterministic per repo convention (`MarkdownAstRoundTripPropertyTest`'s `Random(42)`). */
        private const val SEED: Long = 20_260_921L
        private const val TRIALS: Int = 100
        private const val MAX_HISTORY_TURNS: Int = 50
        private const val MAX_RETRIEVED_ITEMS: Int = 8
        private const val MAX_RETRIEVED_TOKENS_CEILING: Int = 3_072
        private const val RESERVE_FOR_ANSWER: Int = 1_024
        private const val ROOMY_CONTEXT_LENGTH: Int = 10_000_000
        private const val PROMPT_BUDGET_SLACK: Int = 200
        private const val THREE: Int = 3
        private const val SCORE_STEP: Double = 0.01
        private const val CHAT_DOC_ID: String = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4b5c"

        private fun countTokens(text: String): Int = text.length / 4

        /** Includes role-marker/fence lookalikes so `PromptGuard`'s neutralization is exercised too. */
        private val WORDS =
            listOf(
                "alpha",
                "beta",
                "gamma",
                "note",
                "vault",
                "graph",
                "skein",
                "wiki",
                "system:",
                "assistant:",
                "user:",
                "<<<DATA 1>>>",
                "<<<END 1>>>",
                "</s>",
                "[INST]",
            )
    }
}
