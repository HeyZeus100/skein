// SQL-backed `IndexStore` implementation (E2.I15, plan §4.2).
//
// This is the on-device implementation that backs the in-memory
// `us.aherrera.skein.testing.InMemoryIndexStore` fake used by unit tests
// in the rest of the codebase. Its behaviour is verified by the shared
// `IndexStoreContractTest` (in `:testing`) via the instrumented
// `IndexStoreImplContractTest` subclass in `src/androidTest/`.
//
// Design summary:
//   • One `SQLiteConnection` per instance, guarded by a single coroutine
//     `Mutex`. WAL is enabled on every connection produced by
//     `SkeinSQLiteDriver` (`E2.I1`), so a reader pool + single writer is
//     the eventual optimisation; the M1 shape here is a single-connection
//     serial pipe that meets the contract without racing FTS5/vec0.
//   • Every SQL string is centralised in `IndexSql` (E2.I4 review pattern).
//   • The `bm25()`, `knn`, `neighborhood`, and IN-list queries all take
//     user-controlled shape (query text, k, IN-list size). Placeholders
//     are always positional; user text is quoted and joined by
//     `FtsQuerySanitizer` before it reaches FTS5.
//   • Exception messages never carry raw user text — a query that fails
//     surfaces as the underlying `SkeinSQLiteException`, whose message
//     already elides user data because the driver builds it from the
//     SQLite error code, not the bindings.
//
// Not implemented here (yet, filed as follow-ups):
//   • The reader-pool + single-writer split. `IndexStoreImpl(pool, bus)`
//     in the plan takes a `ConnectionPool` — the plan's `ConnectionPool`
//     type does not yet exist in-tree; this constructor takes a single
//     `SQLiteConnection` so the impl can land and downstream RAG code
//     (`E5.I6`, `E5.I7`, `E5.I8`) can compile. Swapping to the pool is a
//     mechanical change once `ConnectionPool` lands.
//   • The `ChangeBus` param, likewise — reserved for the reactive
//     invalidation stream that `SearchViewModel` will subscribe to
//     (E7.I5).
//
// The instrumented tests use a fresh in-memory `:memory:` DB per test;
// unit tests exercise only pure-Kotlin pieces (`FtsQuerySanitizer`,
// `IndexSql`) because a `SQLiteConnection` requires the native `.so`.

package app.skein.core.vault.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.aherrera.skein.core.model.Chunk
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.Entity
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.ScoredChunk

public class IndexStoreImpl(
    private val connection: SQLiteConnection,
) : IndexStore,
    AutoCloseable {
    private val mutex: Mutex = Mutex()

    override suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
    ): List<ChunkId> =
        mutex.withLock {
            transaction {
                connection.prepare(IndexSql.DELETE_CHUNKS_FOR_DOC).use { stmt ->
                    stmt.bindText(1, docId)
                    stmt.step()
                }
                if (chunks.isEmpty()) return@transaction emptyList()
                val ids = ArrayList<ChunkId>(chunks.size)
                connection.prepare(IndexSql.INSERT_CHUNK_RETURNING_ID).use { stmt ->
                    for (c in chunks.sortedBy { it.ord }) {
                        stmt.reset()
                        stmt.clearBindings()
                        stmt.bindText(1, docId)
                        stmt.bindLong(2, c.ord.toLong())
                        stmt.bindText(3, c.text)
                        stmt.bindLong(4, c.tokenCount.toLong())
                        stmt.bindText(5, embedderId)
                        stmt.bindLong(6, embedderVersion.toLong())
                        check(stmt.step()) {
                            "INSERT ... RETURNING id yielded no row for doc chunk"
                        }
                        ids += stmt.getLong(0)
                    }
                }
                ids
            }
        }

    override suspend fun putEmbeddings(embeddings: List<Pair<ChunkId, ByteArray>>) {
        // Validate lengths up front so a bad caller does not leave a
        // half-written vec0 table — the transaction only wraps the writes
        // themselves, but the require() runs before we begin.
        for ((id, vec) in embeddings) {
            require(vec.size == IndexSql.VEC_INT8_DIM) {
                "putEmbeddings: expected ${IndexSql.VEC_INT8_DIM} int8 values for chunk id=$id, got ${vec.size}"
            }
        }
        mutex.withLock {
            transaction {
                connection.prepare(IndexSql.UPSERT_EMBEDDING).use { stmt ->
                    for ((id, vec) in embeddings) {
                        stmt.reset()
                        stmt.clearBindings()
                        stmt.bindLong(1, id)
                        stmt.bindBlob(2, vec)
                        stmt.step()
                    }
                }
            }
        }
    }

    override suspend fun knn(
        queryInt8: ByteArray,
        k: Int,
    ): List<ScoredChunk> {
        require(queryInt8.size == IndexSql.VEC_INT8_DIM) {
            "knn: expected ${IndexSql.VEC_INT8_DIM} int8 values, got ${queryInt8.size}"
        }
        if (k <= 0) return emptyList()
        return mutex.withLock {
            connection.prepare(IndexSql.KNN_BY_INT8).use { stmt ->
                stmt.bindBlob(1, queryInt8)
                stmt.bindLong(2, k.toLong())
                val out = ArrayList<ScoredChunk>(k)
                while (stmt.step()) {
                    val id: ChunkId = stmt.getLong(0)
                    val distance: Double = stmt.getDouble(1)
                    // vec0 cosine distance ∈ [0, 2] → similarity ∈ [-1, 1].
                    out += ScoredChunk(chunkId = id, score = 1.0 - distance)
                }
                out
            }
        }
    }

    override suspend fun bm25(
        query: String,
        k: Int,
    ): List<ScoredChunk> {
        if (k <= 0) return emptyList()
        val fts = FtsQuerySanitizer.sanitize(query)
        if (fts.isEmpty()) return emptyList()
        return mutex.withLock {
            connection.prepare(IndexSql.BM25_QUERY).use { stmt ->
                stmt.bindText(1, fts)
                stmt.bindLong(2, k.toLong())
                val out = ArrayList<ScoredChunk>()
                while (stmt.step()) {
                    val id: ChunkId = stmt.getLong(0)
                    val bm25: Double = stmt.getDouble(1)
                    // FTS5 `bm25()` returns a negative-good value; the
                    // interface contract is "score = -bm25()" so callers
                    // rank by score DESC uniformly across knn and bm25.
                    out += ScoredChunk(chunkId = id, score = -bm25)
                }
                out
            }
        }
    }

    override suspend fun getChunks(ids: Collection<ChunkId>): Map<ChunkId, Chunk> {
        if (ids.isEmpty()) return emptyMap()
        return mutex.withLock {
            connection.prepare(IndexSql.selectChunksByIds(ids.size)).use { stmt ->
                var i = 1
                for (id in ids) {
                    stmt.bindLong(i++, id)
                }
                val out = LinkedHashMap<ChunkId, Chunk>(ids.size)
                while (stmt.step()) {
                    val chunk = readChunk(stmt)
                    out[chunk.id] = chunk
                }
                out
            }
        }
    }

    override suspend fun chunksForDocs(
        docIds: Collection<DocId>,
        limitPerDoc: Int,
    ): List<Chunk> {
        if (docIds.isEmpty() || limitPerDoc <= 0) return emptyList()
        return mutex.withLock {
            connection.prepare(IndexSql.selectChunksForDocs(docIds.size)).use { stmt ->
                var i = 1
                for (id in docIds) {
                    stmt.bindText(i++, id)
                }
                stmt.bindLong(i, limitPerDoc.toLong())
                val out = ArrayList<Chunk>()
                while (stmt.step()) {
                    out += readChunk(stmt)
                }
                out
            }
        }
    }

    override suspend fun replaceEdges(
        srcId: String,
        kinds: Set<EdgeKind>,
        edges: List<Edge>,
    ) {
        if (kinds.isEmpty()) return
        mutex.withLock {
            transaction {
                // Delete only the kinds the caller nominates — an unrelated
                // ENTITY edge on the same source is preserved. This is the
                // acceptance criterion `replaceEdges(src, kinds={WIKILINK},
                // …) does not touch ENTITY edges of the same source`.
                connection.prepare(IndexSql.deleteEdgesForSrcKinds(kinds.size)).use { stmt ->
                    stmt.bindText(1, srcId)
                    var i = 2
                    for (kind in kinds) {
                        stmt.bindText(i++, kind.db)
                    }
                    stmt.step()
                }
                if (edges.isNotEmpty()) {
                    connection.prepare(IndexSql.INSERT_EDGE).use { stmt ->
                        for (edge in edges) {
                            stmt.reset()
                            stmt.clearBindings()
                            stmt.bindText(1, edge.srcId)
                            stmt.bindText(2, edge.dstId)
                            stmt.bindText(3, edge.kind.db)
                            stmt.bindDouble(4, edge.weight)
                            stmt.bindLong(5, edge.createdAt)
                            stmt.step()
                        }
                    }
                }
            }
        }
    }

    override suspend fun edgesFrom(srcId: String): List<Edge> =
        mutex.withLock {
            connection.prepare(IndexSql.EDGES_FROM).use { stmt ->
                stmt.bindText(1, srcId)
                collectEdges(stmt)
            }
        }

    override suspend fun edgesTo(
        dstId: String,
        kind: EdgeKind?,
    ): List<Edge> =
        mutex.withLock {
            val sql = if (kind == null) IndexSql.EDGES_TO_ANY_KIND else IndexSql.EDGES_TO_ONE_KIND
            connection.prepare(sql).use { stmt ->
                stmt.bindText(1, dstId)
                if (kind != null) stmt.bindText(2, kind.db)
                collectEdges(stmt)
            }
        }

    override suspend fun neighborhood(
        seeds: Set<String>,
        hops: Int,
        maxNodes: Int,
    ): List<Edge> {
        if (seeds.isEmpty() || hops <= 0 || maxNodes <= 0) return emptyList()
        return mutex.withLock {
            // Iterative BFS: at each hop the frontier is queried against
            // `edges` in a single SELECT covering both directions (src or
            // dst matches). The seen-edge set dedupes; visited stops
            // expansion at `maxNodes`. Two prepared statements are cached
            // in [SQLiteStatement] scope naturally because we build one
            // fresh per frontier size — the frontier shrinks rapidly, so
            // preparing per hop is not a hot path.
            val visited = LinkedHashSet<String>(maxNodes)
            visited.addAll(seeds)
            val seenEdges = LinkedHashSet<EdgeKey>()
            val out = ArrayList<Edge>()
            var frontier: Set<String> = seeds
            var depth = 0
            while (depth < hops && frontier.isNotEmpty() && visited.size < maxNodes) {
                val nextFrontier = LinkedHashSet<String>()
                val fList = frontier.toList()
                connection.prepare(IndexSql.edgesTouchingNodes(fList.size)).use { stmt ->
                    // Bind the frontier twice — once for `src_id IN (…)`,
                    // once for `dst_id IN (…)`. Positional bind indices
                    // increment through both runs.
                    var i = 1
                    for (node in fList) stmt.bindText(i++, node)
                    for (node in fList) stmt.bindText(i++, node)
                    while (stmt.step()) {
                        val edge = readEdge(stmt) ?: continue
                        val key = EdgeKey(edge.srcId, edge.dstId, edge.kind)
                        if (!seenEdges.add(key)) continue
                        out += edge
                        val other =
                            when {
                                edge.srcId in frontier && edge.dstId !in visited -> edge.dstId
                                edge.dstId in frontier && edge.srcId !in visited -> edge.srcId
                                else -> null
                            }
                        if (other != null) {
                            visited += other
                            nextFrontier += other
                            if (visited.size >= maxNodes) return@use
                        }
                    }
                }
                frontier = nextFrontier
                depth++
            }
            out
        }
    }

    override suspend fun upsertEntity(
        canonicalName: String,
        entityType: String,
        firstSeen: Long,
    ): Entity =
        mutex.withLock {
            transaction {
                connection.prepare(IndexSql.INSERT_ENTITY_IGNORE).use { stmt ->
                    stmt.bindText(1, canonicalName)
                    stmt.bindText(2, entityType)
                    stmt.bindLong(3, firstSeen)
                    stmt.step()
                }
                connection.prepare(IndexSql.SELECT_ENTITY_BY_KEY).use { stmt ->
                    stmt.bindText(1, canonicalName)
                    stmt.bindText(2, entityType)
                    check(stmt.step()) {
                        "upsertEntity: entity row missing after INSERT OR IGNORE"
                    }
                    Entity(
                        id = stmt.getLong(0),
                        canonicalName = stmt.getText(1),
                        entityType = stmt.getText(2),
                        firstSeen = stmt.getLong(3),
                    )
                }
            }
        }

    override suspend fun findEntitiesByName(names: Collection<String>): List<Entity> {
        if (names.isEmpty()) return emptyList()
        val lowered = names.map { it.lowercase() }
        return mutex.withLock {
            connection.prepare(IndexSql.findEntitiesByLowerName(lowered.size)).use { stmt ->
                var i = 1
                for (n in lowered) stmt.bindText(i++, n)
                val out = ArrayList<Entity>(lowered.size)
                while (stmt.step()) {
                    out +=
                        Entity(
                            id = stmt.getLong(0),
                            canonicalName = stmt.getText(1),
                            entityType = stmt.getText(2),
                            firstSeen = stmt.getLong(3),
                        )
                }
                out
            }
        }
    }

    override fun close() {
        connection.close()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Execute [block] inside a `BEGIN IMMEDIATE ... COMMIT` (rollback on
     * throw). SQLite allows nested savepoints but the current callers
     * only ever call `transaction` at the top of a single method, so a
     * plain BEGIN/COMMIT is safe and cheaper than a savepoint.
     */
    private inline fun <T> transaction(block: () -> T): T {
        connection.prepare("BEGIN IMMEDIATE").use { it.step() }
        try {
            val out = block()
            connection.prepare("COMMIT").use { it.step() }
            return out
        } catch (t: Throwable) {
            try {
                connection.prepare("ROLLBACK").use { it.step() }
            } catch (_: Throwable) {
                // Best-effort rollback; propagate the original failure.
            }
            throw t
        }
    }

    private fun readChunk(stmt: SQLiteStatement): Chunk {
        val embedderId =
            if (stmt.isNull(5)) null else stmt.getText(5)
        val embedderVersion =
            if (stmt.isNull(6)) null else stmt.getLong(6).toInt()
        return Chunk(
            id = stmt.getLong(0),
            docId = stmt.getText(1),
            ord = stmt.getLong(2).toInt(),
            text = stmt.getText(3),
            tokenCount = stmt.getLong(4).toInt(),
            embedderId = embedderId,
            embedderVersion = embedderVersion,
        )
    }

    private fun readEdge(stmt: SQLiteStatement): Edge? {
        val kindDb = stmt.getText(2)
        val kind =
            try {
                EdgeKind.fromDb(kindDb)
            } catch (_: NoSuchElementException) {
                return null
            }
        val weight = if (stmt.isNull(3)) kind.weight else stmt.getDouble(3)
        val createdAt = if (stmt.isNull(4)) 0L else stmt.getLong(4)
        return Edge(
            srcId = stmt.getText(0),
            dstId = stmt.getText(1),
            kind = kind,
            weight = weight,
            createdAt = createdAt,
        )
    }

    private fun collectEdges(stmt: SQLiteStatement): List<Edge> {
        val out = ArrayList<Edge>()
        while (stmt.step()) {
            readEdge(stmt)?.let { out += it }
        }
        return out
    }

    private data class EdgeKey(
        val src: String,
        val dst: String,
        val kind: EdgeKind,
    )
}
