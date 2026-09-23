// `PromptAssemblerImpl` (skein-82g, E5.I15, plan
// `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` lines 3684-3701;
// design spec §7.3). The production `PromptAssembler` (`:core:model`,
// locked `E0.I12`/skein-x4f): the §7.3 layout — one leading SYSTEM message,
// then history oldest-first, then one trailing USER message holding the
// guarded retrieved-context block and the query — with `PromptGuard`
// (`:core:security`, `E3.I10`/skein-xhi) applied around the retrieved
// segment so a retrieved chunk is data, never an instruction (design spec §2
// principle 10, §7.3, §9).
//
// ## This is a composition, not a rewrite
//
// `core/security`'s `GuardedPromptAssemblerContractTest`/
// `GuardedReferenceAssembler` already proves — against the locked
// `PromptAssemblerContractTest` — that "wrap survivors with
// `PromptGuard.wrapRetrieved`, then greedily trim retrieved items from the
// end until the wrapped block fits `maxRetrievedTokens`, then drop history
// oldest-first until the whole prompt fits `contextLength - reserveForAnswer`"
// satisfies every §7.3 assertion, including the CaMeL-separation ones. This
// class is that exact composition, moved to its permanent home in
// `:core:rag` (skein-82g's `PromptAssemblerImplTest : PromptAssemblerContractTest()`
// runs the identical suite against it). Nothing here reimplements
// `PromptGuard`'s neutralization or fencing — see that file for the
// role-marker/fence rewriting this class relies on.
//
// ## Truncation order (plan `E5.I15`)
//
// 1. Retrieved items are trimmed from the *end* of the list until the
//    `PromptGuard.wrapRetrieved` rendering of the survivors costs at most
//    `budget.maxRetrievedTokens`. Only survivors appear in
//    [app.skein.core.model.AssembledPrompt.citations].
// 2. History turns are then dropped oldest-first until the assembled
//    prompt's total token cost is at most `contextLength - reserveForAnswer`.
//    The system message and the final (retrieved + query) user message are
//    never dropped — a budget too small for them alone still returns a
//    correctly-shaped prompt whose `estimatedTokens` may exceed the budget;
//    `E4.I7`'s `ContextBudget` is responsible for not handing out a budget
//    that tight in production.
//
// ## Token counting (`E4.I7`/skein-4c7 coordination)
//
// `PromptAssembler.assemble`'s locked signature (`:core:model`) already
// supplies `countTokens: (String) -> Int` as a synchronous callback — the
// caller (ultimately `ContextBudget`, backed by `IInferenceService.tokenCount`
// plus an LRU cache) owns the async/caching seam. Coordinator note
// (2026-09-21, skein-82g) additionally asks that estimation "go through the
// same `TokenCounter` seam skein-4c7 declares" (`fun interface TokenCounter {
// suspend fun count(text: String): Int }`, declared next to `ContextBudget`
// in `core/inference`). As of this bead, skein-4c7 has not merged (no
// `TokenCounter` type exists anywhere in the tree), but it also does not
// change what this class does: `TokenCounter` is `ContextBudget`'s seam for
// *producing* a `TokenBudget` and for caching repeated counts across turns —
// it sits entirely upstream of `assemble`'s already-synchronous `countTokens`
// parameter, which this class simply calls, exactly as `FakePromptAssembler`
// and `GuardedReferenceAssembler` already do. No local `TokenCounter`
// declaration is added here: there is no call site in this file that would
// use one, and declaring an unused seam type in production code would be
// dead weight the coordinator would have to delete at merge anyway. If
// skein-4c7 lands with a different shape for how its cache feeds
// `countTokens`, that is invisible to this class either way.
package app.skein.core.rag.prompt

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
 * [PromptGuard.wrapRetrieved] fencing the retrieved-context segment. See the
 * file header for the truncation order and the CaMeL-style data/instruction
 * separation this composition provides.
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
    ): AssembledPrompt {
        val systemContent = persona?.systemPrompt ?: ""

        val survivors = trimRetrievedToBudget(retrieved, budget.maxRetrievedTokens, countTokens)
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
