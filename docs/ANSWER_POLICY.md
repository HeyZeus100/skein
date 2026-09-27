# Answer scope and evidence policy

`skein-gg11.31` introduces `AnswerPolicy.VERSION = "1"` and an explicit scope
for every chat turn. This implements the accuracy continuation plan and
amends the original prompt contract's empty/persona-only system message.

The sole system message contains the trusted app policy, the selected scope,
then optional Space preferences. Retrieved passages and imported conversation
turns remain data. `PromptGuard` still fences retrieved text, and stored
system-role turns are still re-roled as user data. The policy asks for direct
answers, uncertainty, exact quotations, supported citations and honest
handling of conflicts and current information. Its presence does not prove
that a model obeys it.

The chat frontmatter key `skein_knowledge_enabled` defaults to true when
absent or malformed. Updates run in a transaction and preserve other keys.
The pipeline captures the preference before warm-up, so changing it affects
the next turn rather than changing an answer already in progress.

| Scope | Retrieval and generation |
|---|---|
| Knowledge on | Search the vault, assemble the surviving passages, and ask the model to ground factual claims in them. If no passages survive, persist an explicit missing-evidence response without invoking generation. |
| Knowledge off | Skip automatic retrieval. The assembler also discards any accidentally supplied retrieval candidates. General knowledge answers must not claim vault search or live verification. |

The missing-evidence response also covers passages removed by the prompt
budget. It is an app response: `TurnOutcome.generationSkipped` records that
no model generated it. It must not be counted as a successful model answer
or assigned invented generation statistics in evaluation.

The budget calculation includes the same system-policy text used by the
assembler. Exact template-aware budgeting remains `skein-gg11.33`; this
change does not claim that content token counts cover template overhead.

Explicit attachment inclusion is not implemented by this scope switch.
The current import action adds content to Knowledge; inserting a wikilink
does not establish that the file is in the prompt. B9's eventual attached
source path must retain explicit attachments when automatic search is off
and distinguish those attachments in the policy and inspector.

Structural tests verify scope, retrieval bypass, policy placement,
missing-evidence persistence, citation offers and changes between turns.
Nonempty retrieval can still contain weak or irrelevant matches; relevance
rejection is `skein-gg11.32`. Claim support, false abstention and correctness
require the separate real-model evaluation in `skein-gg11.30`.
