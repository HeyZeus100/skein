package app.skein.core.rag.prompt

import app.skein.core.model.AnswerPolicy
import app.skein.core.model.AnswerScope
import app.skein.core.model.AssembledPrompt
import app.skein.core.model.ChatMessage
import app.skein.core.model.Message
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.PromptAssembler
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.TokenBudget
import app.skein.security.prompt.PromptGuard

/**
 * Production [PromptAssembler]: the design spec §7.3 layout with
 * [PromptGuard.wrapRetrieved] fencing the retrieved-context segment and [AnswerPolicy] supplying the
 * trusted instruction segment. Retrieved passages are trimmed first, then
 * history, while the system and final user message are retained.
 *
 * Pure and deterministic, per the locked contract: no clock, no randomness,
 * no I/O, and — per spec §9 — no logging of [Retrieved.text], [Message.contentMd]
 * or [userQuery] at any point.
 */
public class PromptAssemblerImpl : PromptAssembler {
    override fun assemble(
        persona: Persona?,
        history: List<Message>,
        retrieved: List<Retrieved>,
        userQuery: String,
        budget: TokenBudget,
        countTokens: (String) -> Int,
        answerScope: AnswerScope,
    ): AssembledPrompt {
        val systemContent = AnswerPolicy.systemPrompt(persona, answerScope)

        val survivors =
            trimRetrievedToBudget(
                if (answerScope == AnswerScope.KNOWLEDGE) retrieved else emptyList(),
                budget.maxRetrievedTokens,
                countTokens,
            )
        val guardedBlock = PromptGuard.wrapRetrieved(survivors)
        val finalUserContent = finalUserMessage(guardedBlock, userQuery)

        val fixedCost = countTokens(systemContent) + countTokens(finalUserContent)
        val promptBudget = budget.contextLength - budget.reserveForAnswer
        val (kept, dropped) = dropOldestHistoryToFit(history, fixedCost, promptBudget, countTokens)

        val messages =
            buildList {
                add(ChatMessage(role = Role.SYSTEM, content = systemContent))
                kept.forEach { add(ChatMessage(role = historyRole(it.role), content = it.contentMd)) }
                add(ChatMessage(role = Role.USER, content = finalUserContent))
            }

        return AssembledPrompt(
            prompt = Prompt(messages = messages),
            citations = survivors.mapIndexed { index, item -> (index + 1) to item }.toMap(),
            droppedHistoryTurns = dropped,
            estimatedTokens = messages.sumOf { countTokens(it.content) },
        )
    }

    /**
     * Drops [retrieved] items from the end until [PromptGuard.wrapRetrieved]'s
     * rendering of the remainder costs at most [maxRetrievedTokens]. The
     * survivors are exactly [AssembledPrompt.citations]'s values, in order.
     */
    private fun trimRetrievedToBudget(
        retrieved: List<Retrieved>,
        maxRetrievedTokens: Int,
        countTokens: (String) -> Int,
    ): List<Retrieved> {
        var survivors = retrieved
        while (survivors.isNotEmpty() && countTokens(PromptGuard.wrapRetrieved(survivors)) > maxRetrievedTokens) {
            survivors = survivors.dropLast(1)
        }
        return survivors
    }

    /** The trailing USER message: the guarded retrieved block (if any) then `User: <query>`. */
    private fun finalUserMessage(
        guardedBlock: String,
        userQuery: String,
    ): String {
        val query = "$QUERY_PREFIX$userQuery"
        return if (guardedBlock.isEmpty()) query else "$guardedBlock\n\n$query"
    }

    /**
     * Drops [history] turns oldest-first until [fixedCost] plus the surviving
     * turns' cost is at most [promptBudget]. Never drops below zero turns;
     * the system and final-user messages ([fixedCost]) are never touched
     * here, matching the KDoc's "never dropped" invariant.
     */
    private fun dropOldestHistoryToFit(
        history: List<Message>,
        fixedCost: Int,
        promptBudget: Int,
        countTokens: (String) -> Int,
    ): Pair<List<Message>, Int> {
        var kept = history
        var dropped = 0
        while (kept.isNotEmpty() && fixedCost + kept.sumOf { countTokens(it.contentMd) } > promptBudget) {
            kept = kept.drop(1)
            dropped += 1
        }
        return kept to dropped
    }

    /**
     * The role a history [Message] is rendered with, per `Retrieval.kt`'s
     * locked "the instruction segment is the single leading `Role.SYSTEM`
     * message" invariant (skein-zh7o). A stored or imported (skein-ddpt)
     * transcript can carry a `Role.SYSTEM` turn — spec §5's `messages.role`
     * column allows it — but copying that role verbatim into history would
     * render it as a *second* instruction segment. It is re-roled to
     * [Role.USER] instead: rendered as data, exactly like every other
     * history turn, never dropped and never treated as an instruction.
     */
    private fun historyRole(role: Role): Role = if (role == Role.SYSTEM) Role.USER else role

    private companion object {
        private const val QUERY_PREFIX: String = "User: "
    }
}
