// Test-only fixture shared by this module's tests. `FakePromptAssembler`
// (`:testing`) is faithful to design spec §7.3's exact layout — a leading
// "Retrieved context:" block, `[N] <title> · <kind>` headers, etc. — which
// means the *final* USER message's content is never simply the raw query
// text whenever any item is retrieved (or even when none are: it renders
// `"User: $userQuery"`, not `userQuery`). `FakeInferenceEngine`'s `script`
// is keyed by that exact final-message content, so a chat-level test that
// wants `scriptedEngine("q" to listOf(...))` to match the query it actually
// sends needs an assembler that keeps the final message *exactly*
// `userQuery` — this one. What SendPipeline/ChatViewModel do with the
// assembled prompt (retrieve -> assemble -> stream -> parse -> persist) is
// this module's own concern; §7.3's exact layout is `PromptAssemblerImplTest`
// / `PromptAssemblerContractTest`'s job, not this one's.
package app.skein.feature.chat

import app.skein.core.model.AssembledPrompt
import app.skein.core.model.ChatMessage
import app.skein.core.model.Message
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.PromptAssembler
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.TokenBudget

internal class SimplePromptAssembler : PromptAssembler {
    override fun assemble(
        persona: Persona?,
        history: List<Message>,
        retrieved: List<Retrieved>,
        userQuery: String,
        budget: TokenBudget,
        countTokens: (String) -> Int,
    ): AssembledPrompt {
        val messages =
            buildList {
                add(ChatMessage(role = Role.SYSTEM, content = persona?.systemPrompt ?: ""))
                history.forEach {
                    add(
                        ChatMessage(
                            role =
                                if (it.role ==
                                    Role.SYSTEM
                                ) {
                                    Role.USER
                                } else {
                                    it.role
                                },
                            content = it.contentMd,
                        ),
                    )
                }
                add(ChatMessage(role = Role.USER, content = userQuery))
            }
        val citations = retrieved.mapIndexed { index, item -> (index + 1) to item }.toMap()
        return AssembledPrompt(
            prompt = Prompt(messages = messages),
            citations = citations,
            droppedHistoryTurns = 0,
            estimatedTokens = messages.sumOf { countTokens(it.content) },
        )
    }
}
