// skein-5oi (E4.I6), app-side half — per-family sampling defaults, with the
// persona override layered on top.
//
// WHERE THE NUMBERS COME FROM, AND WHERE THEY DO NOT
// ============================================================================
//
// Plan `E4.I6` (docs/superpowers/plans/2026-09-19-skein-v1-plan.md, the
// `SamplingDefaults.forModel` sentence) is the ONLY place in this repository
// that states them:
//
//   "`SamplingDefaults.forModel(model)` app-side returns spec-consistent
//    defaults (Qwen: temp 0.7/top-p 0.8/rep 1.05; Gemma: temp 1.0/top-k
//    64/top-p 0.95 per the model cards recorded in `models/MANIFEST.md`) and
//    persona overrides layer on top."
//
// That sentence cites `models/MANIFEST.md`. **That file does not exist**:
// `E0.I4`, the bead that writes it (one row per artifact: sha256, size, source
// URL, HF revision, licence), is unstarted, and there is no `models/`
// directory in the tree. So the per-family numbers below are pinned to the
// PLAN LINE, not to a model card, and `SamplingDefaultsTest` says so in the
// same words. When `E0.I4` lands, whoever writes `models/MANIFEST.md` should
// check these four-plus-three numbers against the cards it records and correct
// them here if the cards disagree — that is a one-line change plus the test.
//
// Everything the plan does NOT state per family (top-k for Qwen, min-p,
// repeat penalty for Gemma, max tokens, seed, stop strings) falls back to
// `SamplingParams`' own defaults, which are spec §4.1 verbatim and are the
// one numeric baseline this codebase already committed to. Inventing a
// per-family value for a field the plan is silent about would be exactly the
// invented measurement `InferenceConfig`'s header refuses to make.
//
// FAMILY DETECTION
// ============================================================================
//
// `Model` carries no architecture or family field — it has `id` and `name`
// (`:core:model`, locked). `ModelInspection.architecture` (H1) would be a
// stronger discriminator, but it is read from the GGUF, which is
// attacker-controlled (`Parcels.kt`: "A hostile GGUF can lie in every field
// below; none of them is a gate"), and letting an imported file pick its own
// sampler is a worse trade than a name match. `id` and `name` are Core-derived
// — the id comes from the manifest the importer accepted and the registry
// row — so matching on them keeps the decision on this side of the boundary.
//
// An unrecognised model gets `SamplingParams()`. That is not a failure: an
// imported GGUF the user chose is a first-class case (spec §3.1, "user can
// import any GGUF"), and the spec's own defaults are the right answer for one.

package app.skein.core.inference.engine

import app.skein.core.model.Model
import app.skein.core.model.SamplingParams

/**
 * The sampling parameters to use for a model when the caller has none.
 *
 * `LlamaCppEngine` applies these when `stream` is handed the contract's own
 * `SamplingParams()` — see `LlamaCppEngine.stream`'s KDoc for the exact rule.
 */
public object SamplingDefaults {
    /**
     * Qwen 2.5 family (`qwen-2.5-3b-abliterated-q4km` is the v1 default —
     * spec §3.1). temp 0.7 / top-p 0.8 / repeat 1.05, plan `E4.I6`.
     *
     * `temperature = 0.7f` coincides with `SamplingParams`' baseline; it is
     * restated rather than omitted so that changing the baseline cannot
     * silently change what Qwen runs with.
     */
    public val QWEN: SamplingParams =
        SamplingParams(
            temperature = 0.7f,
            topP = 0.8f,
            repeatPenalty = 1.05f,
        )

    /**
     * Gemma family (`gemma-4-e4b-it-q4km` is the other v1 default —
     * spec §3.1). temp 1.0 / top-k 64 / top-p 0.95, plan `E4.I6`.
     */
    public val GEMMA: SamplingParams =
        SamplingParams(
            temperature = 1.0f,
            topK = 64,
            topP = 0.95f,
        )

    /** Spec §4.1's baseline, for a model neither family claims. */
    public val FALLBACK: SamplingParams = SamplingParams()

    /**
     * The defaults for [model], by family.
     *
     * Matching is case-insensitive and substring-based over the model's id and
     * display name, because both carry the family in practice
     * (`qwen-2.5-3b-abliterated-q4km` / "Qwen2.5 3B Instruct (abliterated)
     * Q4_K_M"; `gemma-4-e4b-it-q4km` / "Gemma 4 E4B Instruct Q4_K_M"). A model
     * whose name matches neither gets [FALLBACK].
     */
    public fun forModel(model: Model): SamplingParams {
        val haystack = "${model.id} ${model.name}".lowercase()
        return when {
            QWEN_MARKER in haystack -> QWEN
            GEMMA_MARKER in haystack -> GEMMA
            else -> FALLBACK
        }
    }

    /**
     * Plan `E4.I6`'s `persona.sampling ?: defaults`, made explicit.
     *
     * The override REPLACES the family defaults rather than merging field by
     * field, exactly as the acceptance criterion writes it. A partial merge
     * would mean a persona that set only `temperature` silently inherited
     * Gemma's `topK = 64` when the user switched models, which is not
     * something a user could reason about.
     *
     * **`Persona` has no `sampling` field yet.** `app.skein.core.model.Persona`
     * (`:core:model`, locked, `E0.I13`) is `(id, name, systemPrompt,
     * defaultModel, createdAt)`; plan `E6.I12` describes a persona sheet with
     * "optional sampling overrides temperature/top-p", so the field is
     * expected but nobody has added it, and adding it means touching a locked
     * contract plus a vault migration. This function takes the override as a
     * parameter so that the merge rule is written down and tested NOW, and the
     * call site becomes `forPersona(model, persona.sampling)` with a
     * one-character change once that decision is taken. Recorded on `skein-5oi`.
     */
    public fun forPersona(
        model: Model,
        override: SamplingParams?,
    ): SamplingParams = override ?: forModel(model)

    private const val QWEN_MARKER = "qwen"
    private const val GEMMA_MARKER = "gemma"
}
