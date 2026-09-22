// Every SQL string used by `IndexStoreImpl` lives here so that a reviewer
// can audit the query surface without hunting through the implementation
// (E2.I15, plan §4.2). The queries reference tables and triggers created
// by migration 001 (`core/vault/src/main/resources/migrations/001_initial.sql`);
// column names must match the DDL there verbatim.
//
// Design notes:
//   • The `chunks` FTS/vec companions are cleaned up by DDL triggers on
//     `DELETE FROM chunks` (see 001_initial.sql `chunks_ad`), so a
//     `replaceChunks` is just DELETE + INSERT on the `chunks` base table.
//   • `chunks_vec` rows are ONLY inserted here (there is no FTS-style
//     `_ai` trigger for vec0) — `putEmbeddings` writes to `chunks_vec`
//     directly via `INSERT OR REPLACE`.
//   • `vec_int8(?)` reifies a BLOB of exactly 256 int8 values (spec §5,
//     migration 001 line ~80) into a vec0-compatible vector. Binding
//     as a raw BLOB works only through that constructor call.
//   • `knn` selects `distance ASC` — cosine distance is in [0, 2]; the
//     caller in [IndexStoreImpl] converts to similarity `1 - distance`
//     to match the `ScoredChunk.score` "higher is better" contract.
//   • `bm25()` is negative-good; the caller returns `-bm25()` so the
//     score matches the `ScoredChunk.score` contract as well.
//   • Placeholders are always positional (`?`), never string-formatted,
//     with a single documented exception: dynamic IN-lists build their
//     placeholder run via [placeholders]. The values themselves are
//     always bound.

package app.skein.core.vault.index

internal object IndexSql {
    // Vec0 vector dimension. Not a measurement-derived choice — see
    // `001_initial.sql` header note ("Sources of truth: spec §5 line 156").
    const val VEC_INT8_DIM: Int = 256

    // ------------------------------------------------------------------
    // Chunks
    // ------------------------------------------------------------------

    const val DELETE_CHUNKS_FOR_DOC: String =
        "DELETE FROM chunks WHERE doc_id = ?"

    const val INSERT_CHUNK_RETURNING_ID: String =
        "INSERT INTO chunks(doc_id, ord, text, token_count, embedder_id, embedder_version, " +
            "revision_hash, byte_start, byte_end) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"

    const val SELECT_CHUNK_COLUMNS: String =
        "id, doc_id, ord, text, token_count, embedder_id, embedder_version, revision_hash, byte_start, byte_end"

    fun selectChunksByIds(count: Int): String =
        "SELECT $SELECT_CHUNK_COLUMNS FROM chunks WHERE id IN (${placeholders(count)})"

    fun selectChunksForDocs(count: Int): String =
        // ROW_NUMBER partitions each doc's chunks by ord ascending; the
        // outer WHERE keeps only the first `limitPerDoc` per doc. The
        // ORDER BY makes the row order deterministic for callers that
        // rely on ord within a doc.
        """
        WITH ranked AS (
          SELECT $SELECT_CHUNK_COLUMNS,
                 ROW_NUMBER() OVER (PARTITION BY doc_id ORDER BY ord) AS rn
          FROM chunks
          WHERE doc_id IN (${placeholders(count)})
        )
        SELECT $SELECT_CHUNK_COLUMNS FROM ranked WHERE rn <= ? ORDER BY doc_id, ord
        """.trimIndent()

    // ------------------------------------------------------------------
    // Embeddings (chunks_vec)
    // ------------------------------------------------------------------

    const val UPSERT_EMBEDDING: String =
        "INSERT OR REPLACE INTO chunks_vec(rowid, embedding) VALUES (?, vec_int8(?))"

    // The `k = ?` predicate is required by vec0 for the k-NN operator;
    // sqlite-vec parses it out of the WHERE clause at plan time.
    const val KNN_BY_INT8: String =
        "SELECT rowid, distance FROM chunks_vec " +
            "WHERE embedding MATCH vec_int8(?) AND k = ? " +
            "ORDER BY distance"

    // ------------------------------------------------------------------
    // FTS5 (chunks_fts)
    // ------------------------------------------------------------------

    const val BM25_QUERY: String =
        "SELECT rowid, bm25(chunks_fts) FROM chunks_fts " +
            "WHERE chunks_fts MATCH ? " +
            "ORDER BY bm25(chunks_fts) LIMIT ?"

    // ------------------------------------------------------------------
    // Edges
    // ------------------------------------------------------------------

    const val EDGE_COLUMNS: String = "src_id, dst_id, kind, weight, created_at"

    const val INSERT_EDGE: String =
        "INSERT OR REPLACE INTO edges($EDGE_COLUMNS) VALUES (?, ?, ?, ?, ?)"

    fun deleteEdgesForSrcKinds(kindCount: Int): String =
        "DELETE FROM edges WHERE src_id = ? AND kind IN (${placeholders(kindCount)})"

    const val EDGES_FROM: String =
        "SELECT $EDGE_COLUMNS FROM edges WHERE src_id = ?"

    const val EDGES_TO_ANY_KIND: String =
        "SELECT $EDGE_COLUMNS FROM edges WHERE dst_id = ?"

    const val EDGES_TO_ONE_KIND: String =
        "SELECT $EDGE_COLUMNS FROM edges WHERE dst_id = ? AND kind = ?"

    fun edgesTouchingNodes(count: Int): String =
        // A hop's frontier queries out-edges and in-edges together so a
        // single prepared statement covers both directions per node
        // batch. Callers dedupe (edge, node) pairs on the client side.
        "SELECT $EDGE_COLUMNS FROM edges " +
            "WHERE src_id IN (${placeholders(count)}) OR dst_id IN (${placeholders(count)})"

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    // Case-insensitive lookup key is `(canonical_name, entity_type)` per
    // migration 001's UNIQUE constraint. The upsert uses INSERT OR IGNORE
    // followed by a SELECT because sqlite-vec's ON CONFLICT support does
    // not extend to virtual tables; the plain SELECT after IGNORE keeps
    // the code path uniform across (entities, other virtual tables).
    const val INSERT_ENTITY_IGNORE: String =
        "INSERT OR IGNORE INTO entities(canonical_name, entity_type, first_seen) " +
            "VALUES (?, ?, ?)"

    const val SELECT_ENTITY_BY_KEY: String =
        "SELECT id, canonical_name, entity_type, first_seen FROM entities " +
            "WHERE canonical_name = ? AND entity_type = ?"

    fun findEntitiesByLowerName(count: Int): String =
        // Matches the fake's semantic (lowercase equality) — the DDL's
        // UNIQUE(canonical_name, entity_type) is case-sensitive, but the
        // interface contract is "by name" and callers compose canonical
        // forms; the impl still lowercases both sides for parity.
        "SELECT id, canonical_name, entity_type, first_seen FROM entities " +
            "WHERE lower(canonical_name) IN (${placeholders(count)})"

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Comma-joined placeholder run of length [count]. Used only for
     * variable-length IN-lists — no user string ever concatenates in.
     * Throws if [count] is zero because SQLite's `IN ()` is a syntax
     * error; callers short-circuit an empty collection.
     */
    fun placeholders(count: Int): String {
        require(count > 0) { "IN-list placeholder count must be > 0, was $count" }
        return List(count) { "?" }.joinToString(separator = ", ")
    }
}
