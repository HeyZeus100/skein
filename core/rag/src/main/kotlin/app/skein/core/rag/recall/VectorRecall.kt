// `VectorRecall` (skein-4uu, E5.I7, spec §7.2 recall step 1). One of the
// three recall sources `RetrievalServiceImpl` (E5.I13, once `RetrievalService`
// itself lands in `E0.I12` / skein-x4f) fuses via `PprRanker` (E5.I12),
// alongside `LexicalRecall` (E5.I6, skein-3rj3) and `GraphRecall` (E5.I11,
// skein-7tw).
//
// Pure Kotlin over the `IndexStore` / `EmbedderService` interfaces from
// `:core:model` — no SQL of its own, no dependency on `:core:vault`,
// mirroring `LexicalRecall`/`GraphRecall`'s package (`app.skein.core.rag.recall`)
// and shape. Tests exercise it against the JVM `InMemoryIndexStore` /
// `FakeEmbedderService` fakes in `:testing`; a real-vec companion (real int8
// vectors, real `IndexStoreImpl.knn`) lives as a compile-only androidTest in
// `:core:vault`, gated on skein-k3b2 like `LexicalRecallAcceptanceTest`.
//
// ## Score mapping chain (bd skein-4uu — verified against both `knn`
// implementations before writing this class, so the mapping below is
// applied exactly once)
//
// `IndexStore.knn`'s own kdoc (`core/model/.../Vault.kt`) already commits to
// the contract: "Cosine similarity in `[-1, 1]`. Higher is better." Both
// implementations honor that at the store layer, before this class ever
// sees a score:
//   - `IndexStoreImpl.knn` (`core/vault/.../IndexStoreImpl.kt`) reads
//     vec0's raw cosine *distance* (`∈ [0, 2]`) and converts it itself:
//     `score = 1.0 - distance`.
//   - `InMemoryIndexStore.knn` (`:testing`) computes the cosine directly —
//     `dot(q, v) / (‖q‖·‖v‖)` — which is already a similarity, not a
//     distance.
// So by the time a `ScoredChunk` reaches `VectorRecall`, `score` is always
// a cosine similarity in `[-1, 1]`; this class performs the *only*
// remaining step from the bead — `(s + 1) / 2` — to land it in `[0, 1]` for
// fusion. Neither `knn` implementation is touched here (both are
// off-limits per skein-4uu's non-negotiables), and this class must not
// re-derive a distance or re-apply `1 - x`, which would double-map.
//
// Determinism: `IndexStoreImpl.knn`'s underlying vec0 query and
// `InMemoryIndexStore.knn`'s plain `sortedByDescending` do not themselves
// promise a tie-break, so this class re-sorts every result itself —
// descending score, then ascending `chunkId` — matching `LexicalRecall`'s
// and `GraphRecall`'s documented convention.

package app.skein.core.rag.recall

import app.skein.core.model.EmbedderService
import app.skein.core.model.IndexStore
import app.skein.core.model.ScoredChunk

/**
 * Vector (sqlite-vec / cosine KNN) recall (spec §7.2 recall step 1). See
 * file header for scope and the score-mapping chain.
 *
 * @param index backs the underlying `knn` query.
 * @param embedder embeds [recall]'s `query` argument via `embedQuery`
 *   (applies the "search_query: " prefix — see `EmbedderService`'s kdoc).
 */
public class VectorRecall(
    private val index: IndexStore,
    private val embedder: EmbedderService,
) {
    /**
     * Returns at most [k] chunks, scores mapped from `IndexStore.knn`'s
     * cosine similarity (`[-1, 1]`) into `[0, 1]` via `(s + 1) / 2`, ordered
     * by descending score and then ascending `chunkId` (deterministic
     * tie-break, matching `LexicalRecall`'s and `GraphRecall`'s convention).
     *
     * A non-positive [k] short-circuits to `emptyList()` without calling
     * the embedder or the store, mirroring `LexicalRecall.recall`'s and
     * `GraphRecall.recall`'s own `k <= 0` guard. An empty index (no
     * embeddings written yet) likewise yields `emptyList()`, since `knn`
     * has nothing to rank; an empty [query] string is not special-cased —
     * `embedder.embedQuery` still returns a valid (if not semantically
     * meaningful) vector for it, and `knn` ranks against that vector like
     * any other.
     */
    public suspend fun recall(
        query: String,
        k: Int = DEFAULT_K,
    ): List<ScoredChunk> {
        if (k <= 0) return emptyList()

        val queryVector = embedder.embedQuery(query)
        val raw = index.knn(queryVector, k)
        if (raw.isEmpty()) return emptyList()

        val mapped = raw.map { it.copy(score = (it.score + 1.0) / 2.0) }

        return mapped
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunkId })
            .take(k)
    }

    public companion object {
        /** Spec §7.2 default fan-in per recall source. Matches `LexicalRecall.DEFAULT_K`/`GraphRecall.DEFAULT_K`. */
        public const val DEFAULT_K: Int = 30
    }
}
