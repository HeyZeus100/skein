// M0.5 contract file (`E0.I12`): lands the design plan §4.3 interfaces —
// `RetrievalService` and `PromptAssembler` — plus the value types they speak
// in. The declarations below are the plan's §4.3 code block verbatim; the
// only additions are (a) KDoc, (b) two nullable, defaulted fields on
// [Retrieved] mandated by `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3's
// "Retrieval → citation flow" step 1, and (c) [RecallSource.citationSourceKind],
// the bridge to the already-locked persisted enum. Every addition carries a
// default, so the §4.3 positional constructor shape is unchanged.
//
// `:core:model` is pure Kotlin/JVM (no Android imports) so every process
// (`:app`, `:inference`, `:embedder`, `:testing`) can consume the same
// types. This file needs no runtime dependency at all beyond the module's
// existing ones; `Prompt`, `ChatMessage` and `Role` come from `Inference.kt`
// (`E0.I10`), and `ChunkId`, `DocId`, `PersonaId`, `DocumentKind`, `Message`,
// `Persona`, `RevisionHash`, `Locator` and `CitationSourceKind` from
// `Vault.kt` (`E0.I11`).
//
// ## Measurement-derived numerics
//
// None are baked in. `TokenBudget` deliberately declares no default values:
// its three fields are supplied by `ContextBudget` (`E4.I7`), which derives
// them from the loaded model and `MEASUREMENTS.md`. The spec-default values
// the plan's §4.3 comments name — 16 384 for `contextLength` (design spec
// §4.1, already locked as `Model.contextLength = 16_384` in `Inference.kt`),
// `SamplingParams.maxTokens = 1024` for `reserveForAnswer` (also
// `Inference.kt`), and 3 072 for `maxRetrievedTokens` (plan §4.3 comment,
// restated by `E4.I7` as `min(3072, 40 %)`) — are documented on each field
// rather than defaulted, so no implementation silently inherits a number the
// measurement milestone has not confirmed.
//
// ## The prompt-injection non-negotiable
//
// Design spec §2 principle 10 / §7.3 / §9 require CaMeL-style separation:
// retrieved vault text is **data, never instructions**. This contract encodes
// that structurally, not by convention — see [PromptAssembler] for the three
// mechanisms and the invariants `PromptAssemblerContractTest` (in `:testing`)
// enforces on every implementation.
//
// The file name is pinned to `Retrieval.kt` by plan §4.3 / `E0.I12`'s file
// list; it holds several top-level declarations, so ktlint's `filename` rule
// is satisfied without a suppression.

package us.aherrera.skein.core.model

// -----------------------------------------------------------------------------
// Recall provenance (plan §4.3)
// -----------------------------------------------------------------------------

/**
 * Which of design spec §7.2's three parallel recall stages surfaced a chunk.
 *
 * Distinct from [CitationSourceKind] (`Vault.kt`, from
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3), which is the *persisted*
 * enum in the `citation-record-v1` payload and additionally carries
 * `RERANK` — a stage that reorders recall output rather than producing it.
 * [citationSourceKind] is the one-way bridge between them.
 */
public enum class RecallSource {
    VECTOR,
    LEXICAL,
    GRAPH,
    ;

    /** The `citation-record-v1` spelling of this recall stage. */
    public val citationSourceKind: CitationSourceKind
        get() =
            when (this) {
                VECTOR -> CitationSourceKind.VECTOR
                LEXICAL -> CitationSourceKind.LEXICAL
                GRAPH -> CitationSourceKind.GRAPH
            }
}

/**
 * One hydrated retrieval hit — design spec §7.2 step 3's
 * `{chunk_id, doc_id, doc_title, text, score, source_kind}` plus the recall
 * provenance the context panel (`E6.I5`) renders.
 *
 * [text] is the chunk body exactly as stored. It is **untrusted document
 * content**: nothing in this type, and nothing in [PromptAssembler], may
 * treat it as an instruction. See [PromptAssembler] for how it is framed.
 *
 * [sourceKind] is the *document* kind (`note`/`chat`/`attachment`/`aiout`),
 * which is what design spec §7.3's `[N] <title> · <source_kind>` header line
 * renders; [recalledBy] is the *recall* provenance. The two are deliberately
 * different axes.
 *
 * [revisionHash] and [locator] are additive over plan §4.3, required by
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 ("`RetrievalService.retrieveContext`
 * resolves each retrieved chunk to `(document_id, current_revision_hash,
 * byte_start, byte_end)`"). They are nullable with a `null` default because
 * Migration 003 (`document_revisions` + `chunks.revision_hash`) is not in the
 * v1 DDL: an implementation running against a pre-003 vault returns `null`
 * and the citation record it feeds degrades to a non-anchored citation.
 */
public data class Retrieved(
    val chunkId: ChunkId,
    val docId: DocId,
    val docTitle: String,
    val text: String,
    val score: Double,
    val sourceKind: DocumentKind,
    val recalledBy: Set<RecallSource>,
    /** `document_revisions.revision_hash` of the revision [text] was cut from; null before Migration 003. */
    val revisionHash: RevisionHash? = null,
    /** Byte range of [text] within that revision's `body_md`; null before Migration 003. */
    val locator: Locator? = null,
)

// -----------------------------------------------------------------------------
// RetrievalService (plan §4.3)
// -----------------------------------------------------------------------------

/**
 * Design spec §7.2's recall → rank → return pipeline, behind one call.
 *
 * Implementations: `RetrievalServiceImpl` (`E5.I13`, `:core:rag`) and
 * `us.aherrera.skein.testing.FakeRetrievalService`. Both must satisfy
 * `us.aherrera.skein.testing.RetrievalServiceContractTest`.
 */
public interface RetrievalService {
    /** Spec §7.2. Returns at most k results ordered by descending score. Empty vault → empty list, never throws. */
    public suspend fun retrieveContext(
        query: String,
        k: Int = 8,
        personaId: PersonaId? = null,
    ): List<Retrieved>
}

// -----------------------------------------------------------------------------
// PromptAssembler (plan §4.3)
// -----------------------------------------------------------------------------

/**
 * The three token allowances [PromptAssembler] assembles within. Produced by
 * `ContextBudget` (`E4.I7`), which folds the safety margin into
 * [contextLength] before constructing this value.
 *
 * The *prompt budget* — the number [AssembledPrompt.estimatedTokens] must
 * never exceed — is `contextLength - reserveForAnswer`.
 */
public data class TokenBudget(
    /** From `MEASUREMENTS.md`; 16 384 unless revised (design spec §4.1, `Model.contextLength`). */
    val contextLength: Int,
    /** Headroom kept for the answer; equals `SamplingParams.maxTokens` (default 1024, `Inference.kt`). */
    val reserveForAnswer: Int,
    /** Ceiling on the retrieved-context block; 3 072 by default (plan §4.3, `E4.I7`'s `min(3072, 40 %)`). */
    val maxRetrievedTokens: Int,
)

/**
 * The result of [PromptAssembler.assemble].
 *
 * [citations] is keyed by the 1-based `[N]` marker as it literally appears in
 * [prompt], and its values are exactly the [Retrieved] items that survived
 * truncation — never the items that were trimmed. `E5.I16`'s citation parser
 * and `PromptGuard.citationsAllowed` (`E3.I10`) treat `citations.keys` as the
 * allow-list: a `[N]` the model emits outside that set is rendered as inert
 * text, never as a chip.
 */
public data class AssembledPrompt(
    val prompt: Prompt,
    /** 1-based citation index → source, exactly the [N] markers present in the prompt. */
    val citations: Map<Int, Retrieved>,
    /** How many of [PromptAssembler.assemble]'s `history` turns were dropped to fit the budget. */
    val droppedHistoryTurns: Int,
    /** Sum of `countTokens` over every assembled message; never exceeds `contextLength - reserveForAnswer`. */
    val estimatedTokens: Int,
)

/**
 * Builds the design spec §7.3 prompt layout.
 *
 * ## Data, not instructions (design spec §2 principle 10, §7.3, §9)
 *
 * Retrieved vault text is attacker-controlled: any note, any imported file,
 * any pasted clipping can contain "ignore previous instructions". The
 * separation is structural and has three parts, all of which
 * `PromptAssemblerContractTest` enforces:
 *
 * 1. **Typed role segments.** The output is a [Prompt] of role-tagged
 *    [ChatMessage]s, never one concatenated string. The instruction segment
 *    is the single leading [Role.SYSTEM] message, whose content is exactly
 *    `persona?.systemPrompt ?: ""` — nothing derived from `retrieved` is ever
 *    placed there, and no assembler may append to it.
 * 2. **A labelled data segment.** Every retrieved chunk lives in one
 *    [Role.USER] message — the last one — that begins with the literal line
 *    `Retrieved context:`, so the model reads the block as quoted material.
 * 3. **Verbatim pass-through plus downstream fencing.** The assembler copies
 *    [Retrieved.text] byte-for-byte; it performs no escaping and no
 *    re-indentation. Neutralizing delimiters and role-marker lines is
 *    `PromptGuard.wrapRetrieved`'s job (`E3.I10`), applied by
 *    `PromptAssemblerImpl` (`E5.I15`) around each item's text. Because the
 *    guard only fences, the item header/body framing below survives it.
 *
 * ## Layout (design spec §7.3)
 *
 * ```text
 * SYSTEM     persona.systemPrompt, or "" when absent
 * USER/ASST  recent chat turns, oldest first, bounded by the budget
 * USER       Retrieved context:
 *            [1] <docTitle> · <sourceKind.db>
 *                <text>
 *            [2] …
 *
 *            User: <userQuery>
 * ```
 *
 * Framing rules, locked so `E5.I15` and every fake agree:
 * - Items are numbered from 1 in the order given, joined by a single `\n`.
 * - An item is `"[" + N + "] " + docTitle + " · " + sourceKind.db + "\n    " + text`.
 *   The four spaces are a separator before verbatim [Retrieved.text]; the
 *   assembler does not indent the text's subsequent lines.
 * - The item block is followed by a blank line and then `User: <userQuery>`.
 * - When `retrieved` is empty there is no `Retrieved context:` block at all:
 *   the final [Role.USER] message is exactly `User: <userQuery>` and
 *   [AssembledPrompt.citations] is empty.
 *
 * ## Truncation (plan `E5.I15`, `E4.I7`)
 *
 * Retrieved items are trimmed from the end until the retrieved block costs at
 * most [TokenBudget.maxRetrievedTokens]; only the survivors appear in
 * [AssembledPrompt.citations]. Then history turns are dropped oldest-first
 * until [AssembledPrompt.estimatedTokens] is at most
 * `contextLength - reserveForAnswer`, and
 * [AssembledPrompt.droppedHistoryTurns] counts exactly those turns. The
 * system message and the final user message are never dropped.
 */
public interface PromptAssembler {
    /**
     * Builds spec §7.3 layout. Retrieved text is wrapped as DATA (see PromptGuard, E3.I10) and never
     * concatenated into the system prompt. History is truncated oldest-first to fit the budget.
     *
     * Pure and deterministic: the same arguments always produce an equal
     * [AssembledPrompt]. `countTokens` is supplied by the caller (backed by
     * `IInferenceService.tokenCount` plus `E4.I7`'s LRU cache) so this stays
     * free of any engine dependency.
     */
    public fun assemble(
        persona: Persona?,
        history: List<Message>,
        retrieved: List<Retrieved>,
        userQuery: String,
        budget: TokenBudget,
        countTokens: (String) -> Int,
    ): AssembledPrompt
}
