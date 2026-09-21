// The `E0.I11` in-memory `IndexStore` fake. JVM-only, cosine-over-int8 knn,
// substring bm25 (documented as a fake), plain-old bounded-BFS neighborhood
// expansion. Not intended for perf work; intended for feature tests that
// need "the right chunks come back for the right query" semantics.

package us.aherrera.skein.testing

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.aherrera.skein.core.model.Chunk
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.Entity
import us.aherrera.skein.core.model.IndexChange
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.ScoredChunk
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * In-memory `IndexStore`.
 *
 * `bm25` is a **substring fallback**, not real BM25 — see the file header
 * on `InMemoryVaultRepository` and the plan (`E0.I11` step 3): "BM25
 * approximated by term-frequency count — it is a fake, documented as
 * such". The intent is that consumers testing lexical recall can observe
 * "an exact term in the corpus surfaces in bm25 results" without booting
 * SQLite / FTS5.
 */
public class InMemoryIndexStore : IndexStore {
    private val lock: Mutex = Mutex()

    private val nextChunkId: AtomicLong = AtomicLong(1L)
    private val chunks: MutableMap<ChunkId, Chunk> = linkedMapOf()
    private val embeddings: MutableMap<ChunkId, ByteArray> = linkedMapOf()

    // Kind-scoped bucket over (srcId -> edges) is more expensive to
    // maintain but makes `replaceEdges` a simple filter.
    private val edges: MutableList<Edge> = mutableListOf()

    private val entities: MutableMap<Long, Entity> = linkedMapOf()
    private val entitiesByKey: MutableMap<String, Entity> = linkedMapOf()
    private val nextEntityId: AtomicLong = AtomicLong(1L)

    /**
     * Mirror of `IndexStoreImpl`'s invalidation stream (bd `skein-rkxi`) —
     * same `replay = 0` + `DROP_OLDEST` shape, for the same reason: a
     * mutating call must never block on, or fail because of, a slow
     * collector.
     *
     * This fake has no transactions, so "after the outermost commit" maps
     * onto "after [lock] is released and the mutation is visible to any
     * subsequent read" — every emission site below therefore sits outside
     * its `withLock` block but before the function returns, which is what
     * `IndexStore.observeChanges` promises.
     */
    private val changes: MutableSharedFlow<IndexChange> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun observeChanges(): Flow<IndexChange> = changes.asSharedFlow()

    // ------------------------------------------------------------------
    // Chunks + embeddings
    // ------------------------------------------------------------------

    override suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
    ): List<ChunkId> {
        val ids =
            lock.withLock {
                // Delete old chunks for the document (real DDL's ON DELETE
                // CASCADE + `chunks_ad` trigger analog).
                val oldIds =
                    this.chunks.values
                        .filter { it.docId == docId }
                        .map { it.id }
                for (id in oldIds) {
                    this.chunks.remove(id)
                    this.embeddings.remove(id)
                }
                // Insert new chunks in `ord` order.
                val out = ArrayList<ChunkId>(chunks.size)
                for (c in chunks.sortedBy { it.ord }) {
                    val newId = nextChunkId.getAndIncrement()
                    this.chunks[newId] =
                        Chunk(
                            id = newId,
                            docId = docId,
                            ord = c.ord,
                            text = c.text,
                            tokenCount = c.tokenCount,
                            embedderId = embedderId,
                            embedderVersion = embedderVersion,
                        )
                    out += newId
                }
                out
            }
        // Emitted even for an empty `chunks`: the delete above is itself a
        // change to what a reader sees.
        changes.tryEmit(IndexChange.ChunksReplaced(docId))
        return ids
    }

    override suspend fun putEmbeddings(embeddings: List<Pair<ChunkId, ByteArray>>) {
        if (embeddings.isEmpty()) return
        lock.withLock {
            // Validate the whole batch before writing any of it, as
            // `IndexStoreImpl` does — so a rejected batch leaves no
            // half-written state and, consequently, publishes nothing.
            for ((id, vec) in embeddings) {
                require(id in chunks) { "putEmbeddings: unknown chunk id=$id" }
                require(vec.size == INT8_DIM) {
                    "putEmbeddings: expected $INT8_DIM int8 values, got ${vec.size} for chunk id=$id"
                }
            }
            for ((id, vec) in embeddings) {
                this.embeddings[id] = vec.copyOf()
            }
        }
        changes.tryEmit(IndexChange.EmbeddingsUpdated(embeddings.map { it.first }))
    }

    override suspend fun knn(
        queryInt8: ByteArray,
        k: Int,
    ): List<ScoredChunk> {
        require(queryInt8.size == INT8_DIM) {
            "knn: expected $INT8_DIM int8 values, got ${queryInt8.size}"
        }
        // Cosine of unit-normalized int8 vectors ≈ dot product / (‖q‖‖v‖).
        val qNorm = norm(queryInt8)
        val scored =
            embeddings.entries.map { (id, vec) ->
                val denom = qNorm * norm(vec)
                val score = if (denom == 0.0) 0.0 else dot(queryInt8, vec) / denom
                ScoredChunk(chunkId = id, score = score)
            }
        return scored.sortedByDescending { it.score }.take(k)
    }

    override suspend fun bm25(
        query: String,
        k: Int,
    ): List<ScoredChunk> {
        // Fake BM25 — count case-insensitive term occurrences in the chunk
        // text. Returned scores are `count.toDouble()`; higher is better,
        // matching the real signature ("score = -bm25()"). Chunks that
        // don't contain any term are dropped.
        val terms =
            query
                .lowercase()
                .split(WORD_SPLIT)
                .filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val results = mutableListOf<ScoredChunk>()
        for (chunk in chunks.values) {
            val hay = chunk.text.lowercase()
            var score = 0.0
            for (t in terms) {
                score += countOccurrences(hay, t).toDouble()
            }
            if (score > 0.0) results += ScoredChunk(chunkId = chunk.id, score = score)
        }
        return results.sortedByDescending { it.score }.take(k)
    }

    override suspend fun getChunks(ids: Collection<ChunkId>): Map<ChunkId, Chunk> {
        val out = LinkedHashMap<ChunkId, Chunk>(ids.size)
        for (id in ids) {
            chunks[id]?.let { out[id] = it }
        }
        return out
    }

    override suspend fun chunksForDocs(
        docIds: Collection<DocId>,
        limitPerDoc: Int,
    ): List<Chunk> {
        val bucket = LinkedHashMap<DocId, MutableList<Chunk>>()
        for (c in chunks.values) {
            if (c.docId in docIds) {
                bucket.getOrPut(c.docId) { mutableListOf() } += c
            }
        }
        return bucket.values.flatMap { list -> list.sortedBy { it.ord }.take(limitPerDoc) }
    }

    // ------------------------------------------------------------------
    // Edges
    // ------------------------------------------------------------------

    override suspend fun replaceEdges(
        srcId: String,
        kinds: Set<EdgeKind>,
        edges: List<Edge>,
    ) {
        // Mirrors `IndexStoreImpl`: an empty `kinds` nominates nothing to
        // rewrite, so it is a no-op and publishes nothing.
        if (kinds.isEmpty()) return
        lock.withLock {
            this.edges.removeAll { it.srcId == srcId && it.kind in kinds }
            this.edges += edges
        }
        changes.tryEmit(IndexChange.EdgesReplaced(srcId, kinds))
    }

    override suspend fun edgesFrom(srcId: String): List<Edge> = edges.filter { it.srcId == srcId }

    override suspend fun edgesTo(
        dstId: String,
        kind: EdgeKind?,
    ): List<Edge> = edges.filter { it.dstId == dstId && (kind == null || it.kind == kind) }

    override suspend fun neighborhood(
        seeds: Set<String>,
        hops: Int,
        maxNodes: Int,
    ): List<Edge> {
        // Undirected BFS from seeds. Stop as soon as the visited-node set
        // reaches `maxNodes` — the last accepted edge may push visited over
        // the cap only up to +1 (an edge introduces at most one new node),
        // which we tolerate because the invariant "stops when [maxNodes]
        // reached" matches the real impl (plan `E2.I15`).
        val visited = mutableSetOf<String>().apply { addAll(seeds) }
        val out = mutableListOf<Edge>()
        var frontier: Set<String> = seeds
        var depth = 0
        while (depth < hops && frontier.isNotEmpty() && visited.size < maxNodes) {
            val nextFrontier = mutableSetOf<String>()
            for (node in frontier) {
                for (e in edges) {
                    val other =
                        when (node) {
                            e.srcId -> e.dstId
                            e.dstId -> e.srcId
                            else -> continue
                        }
                    if (out.contains(e)) continue
                    out += e
                    if (other !in visited) {
                        visited += other
                        nextFrontier += other
                        if (visited.size >= maxNodes) break
                    }
                }
                if (visited.size >= maxNodes) break
            }
            frontier = nextFrontier
            depth++
        }
        return out
    }

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    override suspend fun upsertEntity(
        canonicalName: String,
        entityType: String,
        firstSeen: Long,
    ): Entity =
        lock.withLock {
            val key = entityKey(canonicalName, entityType)
            entitiesByKey[key]?.let { return@withLock it }
            val e =
                Entity(
                    id = nextEntityId.getAndIncrement(),
                    canonicalName = canonicalName,
                    entityType = entityType,
                    firstSeen = firstSeen,
                )
            entities[e.id] = e
            entitiesByKey[key] = e
            e
        }

    override suspend fun findEntitiesByName(names: Collection<String>): List<Entity> {
        val lower = names.map { it.lowercase() }.toSet()
        return entities.values.filter { it.canonicalName.lowercase() in lower }
    }

    private companion object {
        /** Spec §5 vec0 dimension. Not a measurement-derived choice; see `001_initial.sql`. */
        const val INT8_DIM: Int = 256

        /** Matches `IndexStoreImpl.CHANGE_BUFFER_CAPACITY` / `ChangeBus.EXTRA_BUFFER_CAPACITY`. */
        const val CHANGE_BUFFER_CAPACITY: Int = 64

        val WORD_SPLIT: Regex = Regex("[^A-Za-z0-9]+")

        fun entityKey(
            name: String,
            type: String,
        ): String = "${type.lowercase()}::${name.lowercase()}"

        fun countOccurrences(
            hay: String,
            needle: String,
        ): Int {
            if (needle.isEmpty()) return 0
            var i = 0
            var n = 0
            while (true) {
                val at = hay.indexOf(needle, startIndex = i)
                if (at < 0) return n
                n++
                i = at + needle.length
            }
        }

        fun dot(
            a: ByteArray,
            b: ByteArray,
        ): Double {
            var s = 0.0
            for (i in a.indices) s += a[i].toInt() * b[i].toInt()
            return s
        }

        fun norm(a: ByteArray): Double {
            var s = 0.0
            for (x in a) s += (x.toInt() * x.toInt()).toDouble()
            return sqrt(s)
        }
    }
}
