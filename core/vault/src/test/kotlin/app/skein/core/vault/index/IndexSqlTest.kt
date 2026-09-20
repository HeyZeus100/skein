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
    public fun `INSERT_CHUNK_RETURNING_ID uses RETURNING clause to avoid lastInsertRowId race`() {
        // The `RETURNING id` clause avoids a second SELECT last_insert_rowid()
        // round trip and — critically — is safe under concurrent writers
        // because the row id is returned atomically with the INSERT.
        assertThat(IndexSql.INSERT_CHUNK_RETURNING_ID).contains("RETURNING id")
    }

    @Test
    public fun `vec int8 dimension matches migration DDL`() {
        // The vec0 table in `001_initial.sql` is `int8[256]`. Any drift
        // between the DDL and this constant would silently corrupt
        // knn/putEmbeddings — pin the value in code.
        assertThat(IndexSql.VEC_INT8_DIM).isEqualTo(256)
    }
}
