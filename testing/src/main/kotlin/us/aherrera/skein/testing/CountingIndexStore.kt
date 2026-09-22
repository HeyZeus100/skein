// `E10.I2` (skein-0j1): a call-counting `IndexStore` decorator so a
// retrieval/ingest test can assert *how many times* (and, via [counts], with
// what method-name breakdown) a pipeline touched the index — e.g. "recall
// called `bm25` exactly once per query, `knn` not at all" — without the
// index itself growing any recording behaviour of its own (`docs/TESTING.md`:
// "fakes must never grow real behaviour").
//
// Deliberately NOT a fake in its own right: every call is forwarded
// unchanged to [CountingIndexStore.delegate] (normally an
// [InMemoryIndexStore]) after being counted. Whatever [delegate] is
// faithful or approximate about (see its own KDoc) applies unchanged here —
// this class adds only bookkeeping, never behaviour.

package us.aherrera.skein.testing

import kotlinx.coroutines.flow.Flow
import us.aherrera.skein.core.model.Chunk
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.Entity
import us.aherrera.skein.core.model.IndexChange
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.RevisionHash
import us.aherrera.skein.core.model.ScoredChunk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps [delegate] (any [IndexStore] — typically [InMemoryIndexStore]),
 * recording one call per invocation of every [IndexStore] member, keyed by
 * method name (e.g. `"bm25"`, `"knn"`, `"replaceChunks"`). Thread-safe: the
 * counters are a [ConcurrentHashMap] of [AtomicInteger], so concurrent
 * collectors/coroutines counting against the same instance don't race.
 *
 * @param delegate the real [IndexStore] every call is forwarded to,
 *   unchanged and uncounted-twice — [CountingIndexStore] never touches its
 *   internal state directly.
 */
public class CountingIndexStore(
    private val delegate: IndexStore,
) : IndexStore {
    private val callCounts: MutableMap<String, AtomicInteger> = ConcurrentHashMap()

    private fun record(method: String) {
        callCounts.computeIfAbsent(method) { AtomicInteger(0) }.incrementAndGet()
    }

    /** Snapshot of how many times each [IndexStore] method has been called. A method never called is absent, not zero. */
    public val counts: Map<String, Int> get() = callCounts.mapValues { (_, v) -> v.get() }

    /** How many times [method] (its [IndexStore] member name, e.g. `"bm25"`) has been called. `0` if never. */
    public fun countOf(method: String): Int = callCounts[method]?.get() ?: 0

    /** Clears every recorded count. Does not affect [delegate]'s state. */
    public fun reset() {
        callCounts.clear()
    }

    override fun observeChanges(): Flow<IndexChange> {
        record("observeChanges")
        return delegate.observeChanges()
    }

    override suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
        revisionHash: RevisionHash?,
    ): List<ChunkId> {
        record("replaceChunks")
        return delegate.replaceChunks(docId, chunks, embedderId, embedderVersion, revisionHash)
    }

    override suspend fun putEmbeddings(embeddings: List<Pair<ChunkId, ByteArray>>) {
        record("putEmbeddings")
        delegate.putEmbeddings(embeddings)
    }

    override suspend fun knn(
        queryInt8: ByteArray,
        k: Int,
    ): List<ScoredChunk> {
        record("knn")
        return delegate.knn(queryInt8, k)
    }

    override suspend fun bm25(
        query: String,
        k: Int,
    ): List<ScoredChunk> {
        record("bm25")
        return delegate.bm25(query, k)
    }

    override suspend fun getChunks(ids: Collection<ChunkId>): Map<ChunkId, Chunk> {
        record("getChunks")
        return delegate.getChunks(ids)
    }

    override suspend fun chunksForDocs(
        docIds: Collection<DocId>,
        limitPerDoc: Int,
    ): List<Chunk> {
        record("chunksForDocs")
        return delegate.chunksForDocs(docIds, limitPerDoc)
    }

    override suspend fun replaceEdges(
        srcId: String,
        kinds: Set<EdgeKind>,
        edges: List<Edge>,
    ) {
        record("replaceEdges")
        delegate.replaceEdges(srcId, kinds, edges)
    }

    override suspend fun edgesFrom(srcId: String): List<Edge> {
        record("edgesFrom")
        return delegate.edgesFrom(srcId)
    }

    override suspend fun edgesTo(
        dstId: String,
        kind: EdgeKind?,
    ): List<Edge> {
        record("edgesTo")
        return delegate.edgesTo(dstId, kind)
    }

    override suspend fun neighborhood(
        seeds: Set<String>,
        hops: Int,
        maxNodes: Int,
    ): List<Edge> {
        record("neighborhood")
        return delegate.neighborhood(seeds, hops, maxNodes)
    }

    override suspend fun upsertEntity(
        canonicalName: String,
        entityType: String,
        firstSeen: Long,
    ): Entity {
        record("upsertEntity")
        return delegate.upsertEntity(canonicalName, entityType, firstSeen)
    }

    override suspend fun findEntitiesByName(names: Collection<String>): List<Entity> {
        record("findEntitiesByName")
        return delegate.findEntitiesByName(names)
    }
}
