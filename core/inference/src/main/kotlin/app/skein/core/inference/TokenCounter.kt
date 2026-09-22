// skein-4c7 (E4.I7) — coordinator decision, 2026-09-21: a small seam over
// token counting, declared here rather than added to the locked
// `us.aherrera.skein.core.model.InferenceEngine` contract, because no engine
// implementation exists yet in this codebase (that's `E4.I4`/skein-1uw's
// territory, not this bead's). `skein-1uw`'s `LlamaCppEngine` is expected to
// implement this over `IInferenceService.tokenCount`; tests here use a
// deterministic fake.

package app.skein.core.inference

/**
 * Counts the number of model tokens [text] renders to.
 *
 * A `fun interface` so callers (and tests) can pass a plain lambda:
 * `TokenCounter { text -> text.length }`.
 */
public fun interface TokenCounter {
    public suspend fun count(text: String): Int
}
