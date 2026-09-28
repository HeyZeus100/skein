package app.skein.core.rag.prompt

import app.skein.core.model.AnswerScope
import app.skein.core.model.AssembledPrompt
import app.skein.core.model.InferenceException
import app.skein.core.model.Message
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.PromptAssembler
import app.skein.core.model.PromptHistory
import app.skein.core.model.PromptMeasurement
import app.skein.core.model.Retrieved
import app.skein.core.model.SamplingParams
import app.skein.core.model.TokenBudget

/**
 * Fits the exact service-formatted prompt plus the answer reservation to the
 * actual loaded context. Content estimates only preselect retrieved passages;
 * they never authorize generation. Every accepted candidate is measured.
 * Failures propagate, with no estimated-count fallback and no prompt logging.
 */
public class ExactPromptAssembler(
    private val assembler: PromptAssembler,
    private val measure: suspend (Prompt, SamplingParams) -> PromptMeasurement,
) {
    public suspend fun assemble(
        persona: Persona?,
        history: List<Message>,
        retrieved: List<Retrieved>,
        userQuery: String,
        budget: TokenBudget,
        countTokens: (String) -> Int,
        answerScope: AnswerScope,
        params: SamplingParams,
    ): AssembledPrompt {
        // The pure assembler must not discard history before the exact check.
        val selectionBudget = budget.copy(contextLength = Int.MAX_VALUE, reserveForAnswer = 0)

        fun candidate(
            turns: List<Message>,
            sources: List<Retrieved>,
        ): AssembledPrompt =
            assembler.assemble(persona, turns, sources, userQuery, selectionBudget, countTokens, answerScope)

        val mandatory = candidate(emptyList(), emptyList())
        val initial = measure(mandatory.prompt, params)
        if (!fits(initial, params)) throw InferenceException.ContextFull()
        var keptHistory = PromptHistory.withoutLeadingReplies(history)
        var keptSources = candidate(emptyList(), retrieved).citations.values.toList()
        while (true) {
            val assembled = candidate(keptHistory, keptSources)
            val bound = assembled.prompt.copy(expectedModelSha256 = initial.modelSha256)
            val measured =
                try {
                    measure(bound, params)
                } catch (e: InferenceException.TransactionTooLarge) {
                    // A very long optional history may exceed Binder even with FD
                    // spilling. Removing whole exchanges is safe; acceptance still
                    // requires a successful exact measurement below.
                    if (keptHistory.isNotEmpty()) {
                        keptHistory = PromptHistory.dropOldestExchange(keptHistory)
                        continue
                    }
                    if (keptSources.isNotEmpty()) {
                        keptSources = keptSources.dropLast(1)
                        continue
                    }
                    throw e
                }
            if (measured.modelSha256 != initial.modelSha256) throw InferenceException.ModelChanged()
            if (fits(measured, params)) {
                return assembled.copy(
                    prompt = bound,
                    droppedHistoryTurns = history.size - keptHistory.size,
                    formattedTokens = measured.promptTokens,
                    contextLength = measured.contextLength,
                    droppedRetrievedItems = retrieved.size - assembled.citations.size,
                )
            }
            when {
                keptHistory.isNotEmpty() -> keptHistory = PromptHistory.dropOldestExchange(keptHistory)
                keptSources.isNotEmpty() -> keptSources = keptSources.dropLast(1)
                else -> throw InferenceException.ContextFull()
            }
        }
    }

    private fun fits(
        measured: PromptMeasurement,
        params: SamplingParams,
    ): Boolean =
        measured.promptTokens >= 0 &&
            measured.contextLength > 0 &&
            params.maxTokens >= 0 &&
            measured.promptTokens.toLong() + params.maxTokens <= measured.contextLength.toLong()
}
