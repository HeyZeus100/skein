// `GraphRecall` (skein-7tw, E5.I11, spec §7.2 recall step 3). One of the
// three recall sources `RetrievalServiceImpl` (E5.I13, once `RetrievalService`
// itself lands in `E0.I12` / skein-x4f) fuses via `PprRanker` (E5.I12).
//
// Pure Kotlin over the `IndexStore` / `VaultRepository` interfaces from
// `:core:model` — no SQL of its own, no dependency on `:core:vault`. Tests
// exercise it against the JVM `InMemoryIndexStore` / `InMemoryVaultRepository`
// fakes in `:testing`, inserting `entities` rows and `edges` directly (the
// plan's own words: "Entity rows are inserted directly by the tests here").
//
// Algorithm (plan `E5.I11`):
//   1. seeds = entities whose canonical name appears in the query (exact
//      match over `entities`, via `IndexStore.findEntitiesByName` on the
//      query's capitalized n-grams up to 3 — see `QueryNgrams`) plus
//      documents whose title appears in the query (`VaultRepository.findByTitle`
//      on the same n-grams).
//   2. `IndexStore.neighborhood(seeds, hops = 2, maxNodes = 60)`.
//   3. Documents in the neighborhood → their chunks
//      (`IndexStore.chunksForDocs`, capped at 2 per doc) scored
//      `1 / (1 + hopDistance)`, hop distance measured from the nearest seed
//      by BFS over the edges `neighborhood` returned.
//
// Node-id convention (see `Edge`'s kdoc on `core/model/.../Vault.kt` and
// `EdgeUpserter`'s companion, `core/vault/.../extract/EdgeUpserter.kt`):
// documents are bare UUIDv7 strings; entities are `"entity:<entities.id>"`;
// tags are `"tag:<lowercased-name>"`; an unresolved wikilink target is
// `"title:<lowercased-title>"`. `neighborhood` walks all of these
// indiscriminately (it is a plain string-keyed graph), so this class filters
// the visited set down to document ids before calling `chunksForDocs` —
// entity/tag/unresolved-title sentinel nodes are graph waypoints, never
// chunk sources.

package app.skein.core.rag.recall

import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.ScoredChunk
import us.aherrera.skein.core.model.VaultRepository

/**
 * Graph-seed recall (spec §7.2 step 3). See file header for the algorithm.
 *
 * @param index backs seed entity lookup, neighborhood expansion, and chunk
 *   hydration.
 * @param repo backs seed document-title lookup.
 * @param hops BFS depth passed to `IndexStore.neighborhood`. Plan default 2.
 * @param maxNodes node cap passed to `IndexStore.neighborhood`. Plan default 60.
 * @param maxChunksPerDoc `chunksForDocs`'s per-document cap. Plan default 2.
 */
public class GraphRecall(
    private val index: IndexStore,
    private val repo: VaultRepository,
    private val hops: Int = DEFAULT_HOPS,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    private val maxChunksPerDoc: Int = DEFAULT_MAX_CHUNKS_PER_DOC,
) {
    /**
     * Returns at most [k] chunks, ordered by descending score and then
     * ascending `chunkId` (deterministic tie-break). A [query] with no
     * capitalized n-gram, or one whose n-grams match no entity or document
     * title, returns immediately without ever calling
     * `IndexStore.neighborhood` or `IndexStore.chunksForDocs`.
     */
    public suspend fun recall(
        query: String,
        k: Int = DEFAULT_K,
    ): List<ScoredChunk> {
        if (k <= 0) return emptyList()

        val ngrams = QueryNgrams.extract(query)
        if (ngrams.isEmpty()) return emptyList()

        val seeds = findSeeds(ngrams)
        if (seeds.isEmpty()) return emptyList()

        val edges = index.neighborhood(seeds, hops = hops, maxNodes = maxNodes)
        val hopDistanceByNode = bfsHopDistances(seeds, edges)

        val hopDistanceByDoc: Map<DocId, Int> =
            hopDistanceByNode.filterKeys { isDocumentNode(it) }
        if (hopDistanceByDoc.isEmpty()) return emptyList()

        val chunks = index.chunksForDocs(hopDistanceByDoc.keys, limitPerDoc = maxChunksPerDoc)
        val scored =
            chunks.map { chunk ->
                val hop = hopDistanceByDoc.getValue(chunk.docId)
                ScoredChunk(chunkId = chunk.id, score = 1.0 / (1.0 + hop))
            }

        return scored
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunkId })
            .take(k)
    }

    /** Step 1: entity + title lookup over the query's n-grams, deduped into one seed-node set. */
    private suspend fun findSeeds(ngrams: Set<String>): Set<String> {
        val seeds = LinkedHashSet<String>()
        for (entity in index.findEntitiesByName(ngrams)) {
            seeds += entityNode(entity.id)
        }
        for (ngram in ngrams) {
            val doc = repo.findByTitle(ngram)
            if (doc != null) seeds += doc.id
        }
        return seeds
    }

    /**
     * BFS over the edge list `IndexStore.neighborhood` returned, undirected
     * (mirrors `neighborhood`'s own undirected expansion). Every [seeds]
     * node starts at distance 0 regardless of whether it has any edges;
     * nodes beyond [hops] hops away are never reached because `neighborhood`
     * did not return edges that far out.
     */
    private fun bfsHopDistances(
        seeds: Set<String>,
        edges: List<Edge>,
    ): Map<String, Int> {
        val adjacency = HashMap<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.srcId) { mutableListOf() } += edge.dstId
            adjacency.getOrPut(edge.dstId) { mutableListOf() } += edge.srcId
        }

        val distance = LinkedHashMap<String, Int>()
        val queue = ArrayDeque<String>()
        for (seed in seeds) {
            if (distance.putIfAbsent(seed, 0) == null) queue += seed
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val d = distance.getValue(node)
            if (d >= hops) continue
            for (next in adjacency[node].orEmpty()) {
                if (next !in distance) {
                    distance[next] = d + 1
                    queue += next
                }
            }
        }
        return distance
    }

    public companion object {
        public const val DEFAULT_K: Int = 30
        public const val DEFAULT_HOPS: Int = 2
        public const val DEFAULT_MAX_NODES: Int = 60
        public const val DEFAULT_MAX_CHUNKS_PER_DOC: Int = 2

        private const val ENTITY_PREFIX: String = "entity:"
        private const val TAG_PREFIX: String = "tag:"
        private const val UNRESOLVED_TITLE_PREFIX: String = "title:"

        private fun entityNode(entityId: Long): String = "$ENTITY_PREFIX$entityId"

        /**
         * `true` for a bare document id, `false` for the `entity:`/`tag:`/
         * `title:` sentinel node ids (see file header's node-id convention).
         */
        private fun isDocumentNode(nodeId: String): Boolean =
            !nodeId.startsWith(ENTITY_PREFIX) &&
                !nodeId.startsWith(TAG_PREFIX) &&
                !nodeId.startsWith(UNRESOLVED_TITLE_PREFIX)
    }
}
