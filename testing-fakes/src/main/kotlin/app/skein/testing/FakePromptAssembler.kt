// The `E0.I12` `FakePromptAssembler`: a JVM-only, dependency-free stand-in
// for the `PromptAssembler` contract locked in `core/model/…/Retrieval.kt`.
// It exists so `PromptAssemblerContractTest` is executed — and therefore
// proven satisfiable — at M0.5, before `PromptAssemblerImpl` (`E5.I15`)
// lands, and so `E10.I2`'s chat/editor unit tests can build prompts without
// pulling `:core:rag` or `:core:security` onto their classpath.
//
// Faithful to the contract:
//   • Emits the design spec §7.3 layout exactly as `Retrieval.kt`'s
//     `PromptAssembler` KDoc locks it: one leading SYSTEM message holding
//     `persona?.systemPrompt ?: ""`, then the surviving history turns
//     oldest-first, then one USER message opening with `Retrieved context:`
//     and closing with `User: <query>`.
//   • Retrieved text is copied verbatim into the data segment and never
//     reaches the SYSTEM message.
//   • Trims retrieved items from the end to `maxRetrievedTokens`, then drops
//     history oldest-first until `estimatedTokens` fits
//     `contextLength - reserveForAnswer`, and reports both counts.
//   • Pure and deterministic — no clock, no randomness, no I/O.
//
// NOT faithful (approximation only — see `skein-0j1`'s KDoc mandate):
//   • NO `PromptGuard` (`E3.I10`). Delimiters, `<<<` sequences and
//     `system:` / `assistant:` role-marker lines inside a chunk are NOT
//     neutralized. Never use this outside tests: `E5.I15` is the assembler
//     that applies the guard, and only its output is safe to feed a model.
//   • Token accounting is whatever `countTokens` says, applied to the
//     already-rendered strings. The real assembler caches counts per
//     `E4.I7`; this one recounts on every truncation step, which is O(n²)
//     in the number of turns and fine only at test sizes.
//   • Truncation is greedy and linear, not the packing search a production
//     assembler could use: it never re-adds a small later item after
//     dropping a large earlier one.

package app.skein.testing

import app.skein.core.model.AssembledPrompt
import app.skein.core.model.ChatMessage
import app.skein.core.model.Message
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.PromptAssembler
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.TokenBudget

/** Deterministic reference rendering of the design spec §7.3 prompt layout. */
public class FakePromptAssembler : PromptAssembler {
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
        while (survivors.isNotEmpty() && countTokens(renderBlock(survivors)) > budget.maxRetrievedTokens) {
            survivors = survivors.dropLast(1)
        }

        val finalUserContent = renderFinalUserMessage(survivors, userQuery)
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

    private fun renderFinalUserMessage(
        survivors: List<Retrieved>,
        userQuery: String,
    ): String {
        val query = "User: $userQuery"
        return if (survivors.isEmpty()) query else renderBlock(survivors) + "\n\n" + query
    }

    /**
     * Mirrors `PromptAssemblerImpl.historyRole` (skein-zh7o): a stored or
     * imported `Role.SYSTEM` history turn is re-roled to [Role.USER] so it
     * is rendered as data, never as a second instruction segment.
     */
    private fun historyRole(role: Role): Role = if (role == Role.SYSTEM) Role.USER else role

    /** `Retrieved context:` followed by one `[N] <title> · <kind>` header per item and its verbatim text. */
    private fun renderBlock(survivors: List<Retrieved>): String =
        survivors
            .mapIndexed { index, item ->
                "[${index + 1}] ${item.docTitle} · ${item.sourceKind.db}\n    ${item.text}"
            }.joinToString(separator = "\n", prefix = "Retrieved context:\n")
}
