// `E3.I10` AC5: "`PromptAssemblerContractTest` (`E0.I12`) still passes with
// `PromptGuard` applied in `E5.I15`."
//
// `E5.I15` (`skein-82g`, the production `PromptAssemblerImpl`) is downstream
// of this issue, so the claim is proved here instead of asserted on trust:
// [GuardedReferenceAssembler] below is the §7.3 reference assembly with
// `PromptGuard.wrapRetrieved` in the one place `E5.I15` will put it, and the
// locked `E0.I12` suite runs against it unmodified. If a future change to the
// guard's framing broke the contract — the `Retrieved context:` opener, the
// `[N] <title> · <kind>\n    <text>` item shape, or verbatim pass-through —
// this class goes red before `E5.I15` ever compiles.
//
// This is a test double, not a shipping assembler: it does not cache token
// counts (`E4.I7`) and its truncation is the same greedy loop
// `FakePromptAssembler` uses.

package app.skein.security.prompt

import app.skein.core.model.AssembledPrompt
import app.skein.core.model.ChatMessage
import app.skein.core.model.Message
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.PromptAssembler
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.TokenBudget
import app.skein.testing.PromptAssemblerContractTest

class GuardedPromptAssemblerContractTest : PromptAssemblerContractTest() {
    override fun assembler(): PromptAssembler = GuardedReferenceAssembler()
}

/** The §7.3 layout with the `E3.I10` guard applied exactly where `E5.I15` will apply it. */
internal class GuardedReferenceAssembler : PromptAssembler {
    override fun assemble(
        persona: Persona?,
        history: List<Message>,
        retrieved: List<Retrieved>,
        userQuery: String,
        budget: TokenBudget,
        countTokens: (String) -> Int,
    ): AssembledPrompt {
        val systemContent = persona?.systemPrompt ?: ""

        var survivors = retrieved
        while (survivors.isNotEmpty() &&
            countTokens(PromptGuard.wrapRetrieved(survivors)) > budget.maxRetrievedTokens
        ) {
            survivors = survivors.dropLast(1)
        }

        val guarded = PromptGuard.wrapRetrieved(survivors)
        val query = "User: $userQuery"
        val finalUserContent = if (guarded.isEmpty()) query else guarded + "\n\n" + query
        val fixedCost = countTokens(systemContent) + countTokens(finalUserContent)
        val promptBudget = budget.contextLength - budget.reserveForAnswer

        var kept = history
        var dropped = 0
        while (kept.isNotEmpty() && fixedCost + kept.sumOf { countTokens(it.contentMd) } > promptBudget) {
            kept = kept.drop(1)
            dropped += 1
        }

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
     * Mirrors `PromptAssemblerImpl.historyRole` (skein-zh7o): a stored or
     * imported `Role.SYSTEM` history turn is re-roled to [Role.USER] so it
     * is rendered as data, never as a second instruction segment.
     */
    private fun historyRole(role: Role): Role = if (role == Role.SYSTEM) Role.USER else role
}
