// JVM unit tests for `IndexSql` (E2.I15). The SQL-string builder is a
// pure function of an integer count; these tests pin the shape of the
// generated query surface so a reviewer diffing changes to the SQL
// vocabulary sees the affected callers explicitly.

package app.skein.core.vault.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class IndexSqlTest {
    @Test
    public fun `placeholders builds a comma-joined run of question marks`() {
        assertThat(IndexSql.placeholders(1)).isEqualTo("?")
        assertThat(IndexSql.placeholders(3)).isEqualTo("?, ?, ?")
    }

    @Test
    public fun `placeholders rejects zero because SQLite forbids IN parentheses`() {
        // SQLite's parser rejects `IN ()` with a syntax error, so the
        // callers must short-circuit an empty IN-list. The require()
        // here converts a caller bug into a diagnostic exception.
        try {
            IndexSql.placeholders(0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (ex: IllegalArgumentException) {
            assertThat(ex.message).contains("must be > 0")
        }
    }

    @Test
    public fun `selectChunksByIds embeds the placeholder run inside an IN-list`() {
        val sql = IndexSql.selectChunksByIds(2)
        assertThat(sql).contains("WHERE id IN (?, ?)")
        assertThat(sql).contains("FROM chunks")
    }

    @Test
    public fun `selectChunksForDocs uses ROW_NUMBER window with a limitPerDoc bind`() {
        val sql = IndexSql.selectChunksForDocs(3)
        assertThat(sql).contains("ROW_NUMBER()")
        assertThat(sql).contains("PARTITION BY doc_id")
        // The trailing bind is `WHERE rn <= ?`.
        assertThat(sql).contains("WHERE rn <= ?")
    }

    @Test
    public fun `deleteEdgesForSrcKinds binds src plus each kind`() {
        val sql = IndexSql.deleteEdgesForSrcKinds(2)
        assertThat(sql).contains("src_id = ?")
        assertThat(sql).contains("kind IN (?, ?)")
    }

    @Test
    public fun `edgesTouchingNodes binds each node twice to cover both directions`() {
        val sql = IndexSql.edgesTouchingNodes(2)
        assertThat(sql).contains("src_id IN (?, ?)")
        assertThat(sql).contains("dst_id IN (?, ?)")
    }

    @Test
    public fun `knn query uses vec_int8 constructor and k predicate`() {
        assertThat(IndexSql.KNN_BY_INT8).contains("vec_int8(?)")
        assertThat(IndexSql.KNN_BY_INT8).contains("k = ?")
        assertThat(IndexSql.KNN_BY_INT8).contains("ORDER BY distance")
    }

    @Test
    public fun `bm25 query orders by bm25 rank ascending`() {
        assertThat(IndexSql.BM25_QUERY).contains("bm25(chunks_fts)")
        assertThat(IndexSql.BM25_QUERY).contains("MATCH ?")
        assertThat(IndexSql.BM25_QUERY).contains("LIMIT ?")
    }

    @Test
    public fun `INSERT_CHUNK binds an explicit id instead of relying on rowid auto-assignment`() {
        // skein-x0ro: SQLite's default rowid rule ("largest existing
        // ROWID + 1, or 1 if the table is empty") reuses an id the moment
        // `chunks` empties out — exactly what `replaceChunks` does when it
        // deletes a document's only chunks. `IndexStoreImpl` binds `id`
        // itself from its own monotonic counter, so `id` must be the
        // FIRST column/placeholder here, and there must be no `RETURNING`
        // clause for the store to depend on instead.
        assertThat(IndexSql.INSERT_CHUNK).contains("INSERT INTO chunks(id, doc_id,")
        assertThat(IndexSql.INSERT_CHUNK).doesNotContain("RETURNING")
    }

    @Test
    public fun `SELECT_MAX_CHUNK_ID seeds the chunk-id counter from every id already on disk`() {
        assertThat(IndexSql.SELECT_MAX_CHUNK_ID).contains("MAX(id)")
        assertThat(IndexSql.SELECT_MAX_CHUNK_ID).contains("FROM chunks")
        // COALESCE(..., 0) so an empty table seeds the counter at 0, not
        // NULL — `nextChunkId.incrementAndGet()` needs a real Long.
        assertThat(IndexSql.SELECT_MAX_CHUNK_ID).contains("COALESCE(MAX(id), 0)")
    }

    @Test
    public fun `UPSERT_EMBEDDING wraps the bound blob in vec_int8 for the int8 vec0 column`() {
        // skein-2hzi: `chunks_vec.embedding` is declared `int8[256]`
        // (001_initial.sql). A bare BLOB parameter is read by sqlite-vec
        // as 64 float32s and the int8 column rejects it ("expected int8,
        // but a float32 vector was provided") — the parameter must be
        // wrapped in the `vec_int8(?)` constructor. Locks this in because
        // the JVM fake driver cannot execute sqlite-vec to catch a
        // regression here at runtime (see `FakeSkeinSQLiteNative`).
        assertThat(IndexSql.UPSERT_EMBEDDING).contains("vec_int8(?)")
    }

    @Test
    public fun `vec int8 dimension matches migration DDL`() {
        // The vec0 table in `001_initial.sql` is `int8[256]`. Any drift
        // between the DDL and this constant would silently corrupt
        // knn/putEmbeddings — pin the value in code.
        assertThat(IndexSql.VEC_INT8_DIM).isEqualTo(256)
    }
}
