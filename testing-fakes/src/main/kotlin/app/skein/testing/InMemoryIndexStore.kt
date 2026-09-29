// The `E0.I11` in-memory `IndexStore` fake. JVM-only, cosine-over-int8 knn,
// substring bm25 (documented as a fake), plain-old bounded-BFS neighborhood
// expansion. Not intended for perf work; intended for feature tests that
// need "the right chunks come back for the right query" semantics.

package app.skein.testing

import app.skein.core.model.Chunk
import app.skein.core.model.ChunkId
import app.skein.core.model.DocId
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.Entity
import app.skein.core.model.IndexChange
import app.skein.core.model.IndexStore
import app.skein.core.model.LexicalQueryLimits
import app.skein.core.model.NewChunk
import app.skein.core.model.RevisionHash
import app.skein.core.model.ScoredChunk
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * The repository this index shares a vault with (LC-02), or null when the
     * index stands alone. Set once, by `InMemoryVaultRepository`'s constructor.
     */
    @Volatile
    private var vault: InMemoryVaultRepository? = null

    internal fun link(vault: InMemoryVaultRepository) {
        check(this.vault == null) { "an InMemoryIndexStore is linked to one repository only" }
        this.vault = vault
    }

    /** Only the drained repository's openNextSession may hand this store to its successor. */
    internal fun prepareNextSession(previous: InMemoryVaultRepository) {
        check(vault === previous) { "index belongs to a different vault" }
        vault = null
    }

    /**
     * True when [nodeId] names a document (no `:`) the linked repository has
     * no row for — `IndexStoreImpl.documentMissing`. A standalone index has
     * no documents table, so nothing is missing from it.
     */
    private fun documentMissing(nodeId: String): Boolean = ':' !in nodeId && vault?.hasDocument(nodeId) == false

    /**
     * The linked repository's orphan sweep (`VaultRepositoryImpl.sweepIndexOrphans`):
     * drops every edge with a missing document at either end and every vector
     * with no chunk. Returns how many went, and the live sources of the
     * dropped in-edges, which the repository re-queues.
     */
    internal suspend fun sweepOrphans(): Pair<Int, Set<String>> =
        lock.withLock {
            val orphans = edges.filter { documentMissing(it.srcId) || documentMissing(it.dstId) }
            edges.removeAll(orphans.toSet())
            val strays = embeddings.keys.filter { it !in chunks }
            strays.forEach(embeddings::remove)
            val sources = orphans.filter { documentMissing(it.dstId) && !documentMissing(it.srcId) }.map { it.srcId }
            (orphans.size + strays.size) to sources.toSet()
        }

    /**
     * The linked repository deleted [docId]: its chunks and their vectors go,
     * as `chunks.doc_id ON DELETE CASCADE` plus the `chunks_ad` trigger do on
     * the device. Publishes nothing — on the device the cascade runs on the
     * repository's connection, which never reaches [observeChanges].
     */
    internal suspend fun cascadeDelete(docId: DocId) {
        lock.withLock {
            val doomed = chunks.values.filter { it.docId == docId }.map { it.id }
            for (id in doomed) {
                chunks.remove(id)
                embeddings.remove(id)
            }
        }
    }

    /**
     * OBJECT_LIFECYCLE_SPEC.md §3.4 for the linked repository — the twin of
     * `VaultRepositoryImpl.detachEdges`: WIKILINK edges into [docId] become
     * edges to [sentinel] at the unresolved weight (collapsing into one the
     * source already holds); with [deleting], every edge out of [docId] and
     * every other edge into it goes too. Publishes nothing, like the device.
     */
    internal suspend fun detachEdges(
        docId: DocId,
        sentinel: String,
        deleting: Boolean,
        bindings: Map<DocId, Pair<Boolean, Boolean>>,
    ) {
        lock.withLock {
            if (deleting) edges.removeAll { it.srcId == docId }
            val detached =
                edges
                    .filter { it.dstId == docId && it.kind == EdgeKind.WIKILINK }
                    .flatMap { edge ->
                        val (idBound, titleBound) = bindings[edge.srcId] ?: (false to true)
                        buildList {
                            if (titleBound) add(edge.copy(dstId = sentinel, weight = UNRESOLVED_WIKILINK_WEIGHT))
                            if (idBound &&
                                deleting
                            ) {
                                add(
                                    edge.copy(
                                        dstId = "import:${edge.srcId}:${docId.lowercase()}",
                                        weight = UNRESOLVED_WIKILINK_WEIGHT,
                                    ),
                                )
                            }
                        }
                    }
            edges.removeAll {
                it.dstId == docId && (deleting || (it.kind == EdgeKind.WIKILINK && bindings[it.srcId]?.first != true))
            }
            for (edge in detached) {
                edges.removeAll { it.srcId == edge.srcId && it.dstId == edge.dstId && it.kind == edge.kind }
                edges += edge
            }
        }
    }

    // ------------------------------------------------------------------
    // Chunks + embeddings
    // ------------------------------------------------------------------

    override suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
        revisionHash: RevisionHash?,
    ): List<ChunkId> {
        val ids =
            lock.withLock {
                // §3.3 race rule (1): nothing, not even the delete below, for a gone document.
                if (documentMissing(docId)) return emptyList()
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
                            revisionHash = revisionHash,
                            byteStart = c.byteStart,
                            byteEnd = c.byteEnd,
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

    /**
     * Migration 008 (skein-zx15) helper, folded onto the real
     * [Chunk.revisionHash] by skein-g32i (it used to live in a side-table
     * here because `Chunk` had not yet been extended). Null for an unknown
     * [chunkId] too.
     */
    public fun revisionHashOf(chunkId: ChunkId): RevisionHash? = chunks[chunkId]?.revisionHash

    /**
     * Migration 008 (skein-zx15) helper, folded onto the real
     * [Chunk.byteStart]/[Chunk.byteEnd] by skein-g32i: `[byteStart,
     * byteEnd)`, or null for an unknown [chunkId] or one written with no
     * offsets to report.
     */
    public fun byteRangeOf(chunkId: ChunkId): Pair<Int, Int>? {
        val c = chunks[chunkId] ?: return null
        val start = c.byteStart
        val end = c.byteEnd
        return if (start == null || end == null) null else start to end
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

    // Explicit JVM approximation for fake tests, not unicode61 parity evidence.
    override suspend fun lexicalTerms(text: String): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size > LexicalQueryLimits.MAX_TEXT_UTF8_BYTES) return emptyList()
        return Regex("[\\p{L}\\p{N}\\p{M}\\p{Co}]+")
            .findAll(text)
            .map { it.value }
            .filter { it.toByteArray(Charsets.UTF_8).size <= LexicalQueryLimits.MAX_TERM_UTF8_BYTES }
            .take(LexicalQueryLimits.MAX_TERMS)
            .toList()
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

    override suspend fun hasLexicalMatch(
        chunkId: ChunkId,
        query: String,
    ): Boolean {
        // Same substring approximation as this fake's bm25; real posting
        // consistency is covered by the SQLite instrumentation tests.
        val text = chunks[chunkId]?.text?.lowercase() ?: return false
        return lexicalTerms(query).any { it.lowercase() in text }
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
            if (documentMissing(srcId)) return
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

        /** `EdgeUpserter.UNRESOLVED_WIKILINK_WEIGHT` (`:core:vault`). */
        const val UNRESOLVED_WIKILINK_WEIGHT: Double = 0.5

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
