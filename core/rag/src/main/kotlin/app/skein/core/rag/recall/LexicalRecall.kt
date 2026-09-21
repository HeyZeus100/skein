// `LexicalRecall` (skein-3rj3, gap on E5.I6, spec §7.2 recall step 2). Thin
// wrapper over `IndexStore.bm25` — the FTS5-backed lexical recall source
// that `RetrievalServiceImpl` (E5.I13) fuses alongside `VectorRecall`
// (E5.I7) and `GraphRecall` (E5.I11, skein-7tw) in `PprRanker` (E5.I12).
//
// Pure Kotlin over the `IndexStore` interface from `:core:model` — no SQL
// of its own, no dependency on `:core:vault`, mirroring `GraphRecall`'s
// package (`app.skein.core.rag.recall`) and shape. Tests exercise it against
// the JVM `InMemoryIndexStore` fake in `:testing`; a real-FTS5 companion
// lives as a compile-only androidTest in `:core:vault` (gated on
// skein-k3b2, the same CI-emulator follow-up that gates
// `IndexStoreImplAcceptanceTest`).
//
// Scope note (skein-3rj3 vs. skein-b8v): `skein-b8v`'s description also
// mentions "term de-duplication" and "a fallback to prefix matching for
// single-term queries". Both are already satisfied one layer down by
// `FtsQuerySanitizer` (`core/vault/.../index/FtsQuerySanitizer.kt`): it
// tokenizes the raw query, OR-joins every token (duplicate tokens just
// produce a redundant, harmless `OR` clause — FTS5 MATCH is unaffected by
// repeats), and appends a trailing `*` to the *last* token regardless of
// how many tokens the query has, which is exactly "prefix matching for a
// single-term query" when there is only one token. `LexicalRecall` does not
// re-implement either behaviour; it delegates query shaping to the store
// entirely, per `IndexStoreImpl.bm25`'s own header ("user text is quoted
// and joined by `FtsQuerySanitizer` before it reaches FTS5"). What
// `skein-3rj3` actually gates on `skein-b8v` closing is narrower: the
// wrapper class itself, `[0, 1]` score normalization, and deterministic
// ordering — see the bead for the authoritative AC.
//
// Adversarial-safety note: this class does not catch exceptions from
// `index.bm25`. `FtsQuerySanitizer.sanitize` never throws (empty/whitespace
// input sanitizes to `""`, which `IndexStoreImpl.bm25` short-circuits to
// `emptyList()` before ever touching FTS5), so the store's own contract is
// already adversarial-safe — see `IndexStoreImplAcceptanceTest`'s 20-string
// suite, reused verbatim by `LexicalRecallTest` here (JVM, against the
// fake) and by the androidTest companion (real FTS5). Nothing in the
// skein-3rj3 bead calls for swallowing a *typed* failure the store does
// raise (e.g. a lower-level `SkeleSQLiteException`/`IllegalStateException`
// for a genuinely broken connection) into an empty result, so this wrapper
// lets any such exception propagate rather than masking a real failure as
// "no results".

package app.skein.core.rag.recall

import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.ScoredChunk

/**
 * Lexical (FTS5/BM25) recall (spec §7.2 recall step 2). See file header for
 * scope and the adversarial-safety contract.
 *
 * @param index backs the underlying `bm25` query. Sanitization of [query]
 *   is entirely the store's responsibility (`FtsQuerySanitizer`).
 */
public class LexicalRecall(
    private val index: IndexStore,
) {
    /**
     * Returns at most [k] chunks, scores normalized to `[0, 1]` with the
     * top-scoring chunk exactly `1.0`, ordered by descending score and then
     * ascending `chunkId` (deterministic tie-break, matching `GraphRecall`'s
     * convention).
     *
     * Normalization is `score / maxScore` over the (already positive-good,
     * per `IndexStore.bm25`'s "score = -bm25()" contract) scores `bm25`
     * returns. Two edge cases:
     *   - an empty [query], or one for which `bm25` finds no matching
     *     chunk, returns `emptyList()` without dividing by anything;
     *   - if every returned score is exactly `0.0` (a degenerate tie with
     *     no `maxScore` to divide by), every chunk normalizes to `1.0`
     *     rather than producing `NaN` from `0.0 / 0.0` — they are, by
     *     definition, all tied for best.
     *
     * A non-positive [k] short-circuits to `emptyList()` without calling
     * the store, mirroring `IndexStore.bm25`'s and `GraphRecall.recall`'s
     * own `k <= 0` guard.
     */
    public suspend fun recall(
        query: String,
        k: Int = DEFAULT_K,
    ): List<ScoredChunk> {
        if (k <= 0) return emptyList()

        val raw = index.bm25(query, k)
        if (raw.isEmpty()) return emptyList()

        val maxScore = raw.maxOf { it.score }
        val normalized =
            if (maxScore == 0.0) {
                raw.map { it.copy(score = 1.0) }
            } else {
                raw.map { it.copy(score = it.score / maxScore) }
            }

        return normalized
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunkId })
            .take(k)
    }

    public companion object {
        /** Spec §7.2 default fan-in per recall source. Matches `GraphRecall.DEFAULT_K`. */
        public const val DEFAULT_K: Int = 30
    }
}
