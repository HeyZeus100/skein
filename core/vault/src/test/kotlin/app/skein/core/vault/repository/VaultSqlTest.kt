// JVM unit tests for `VaultSql` (skein-gg11.10). The JVM fake driver
// (`FakeSkeinSQLiteNative`) executes no real SQL — it cannot catch a query
// that only fails against real SQLite/FTS5 — so these tests pin the SQL
// *text shape* itself: a reviewer diffing `SEARCH_BODIES_BM25` sees
// immediately if a change reintroduces the CTE-plus-window-function shape
// that broke `bm25()`/`snippet()` on the real driver
// (`SkeinSQLiteException: unable to use function bm25 in the requested
// context`, caught by the emulator lane's
// `searchBodies_quantum_returns_bm25_ranked_hit_with_bracket_snippet`).
//
// See `VaultSql.kt`'s header comment (`SEARCH_BODIES_BM25`'s shape note)
// for the full explanation: FTS5's `bm25()`/`snippet()` may only be called
// from a SELECT whose FROM directly names the FTS5 table alongside its
// `MATCH` constraint, with nothing else — in particular no window function
// — sharing that same SELECT (a window function blocks SQLite from
// flattening a wrapping CTE/subquery into the outer query, which detaches
// the aux-function call from the live FTS5 cursor it needs).

package app.skein.core.vault.repository

import app.skein.core.vault.index.IndexSql
import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class VaultSqlTest {
    /**
     * The exact flat shape `bm25()`/`snippet()` must be called from:
     * `SELECT ... bm25(chunks_fts) ... snippet(chunks_fts, ...) FROM
     * chunks_fts WHERE chunks_fts MATCH ?` — textually the same query
     * `IndexSql.BM25_QUERY` runs (proven to work on-device, `IndexStoreImpl`),
     * with `snippet(...)` added alongside `bm25(...)` in the same SELECT.
     * Nothing — no JOIN, no ROW_NUMBER(), no other column — sits between
     * this SELECT's result columns and its own `WHERE chunks_fts MATCH ?`.
     */
    private val expectedFlatFtsSubquery =
        "SELECT rowid, bm25(chunks_fts) AS bm25_rank, snippet(chunks_fts, 0, '[', ']', '…', 12) AS snippet " +
            "FROM chunks_fts " +
            "WHERE chunks_fts MATCH ?"

    @Test
    public fun `SEARCH_BODIES_BM25 calls bm25 and snippet only from a flat MATCH-bound FTS5 query`() {
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains(expectedFlatFtsSubquery)
    }

    @Test
    public fun `SEARCH_BODIES_BM25's flat FTS5 subquery matches IndexSql BM25_QUERY's proven shape`() {
        // IndexSql.BM25_QUERY is IndexStoreImpl's bm25 query, confirmed to
        // execute on the real driver. Pin that the aux-function-bearing
        // subquery here is structurally the same query (rowid + bm25(),
        // flat FROM/WHERE MATCH, no join, no window function) — the
        // `snippet(...)` column is the only addition.
        assertThat(IndexSql.BM25_QUERY).isEqualTo(
            "SELECT rowid, bm25(chunks_fts) FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts) LIMIT ?",
        )
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("SELECT rowid, bm25(chunks_fts) AS bm25_rank")
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("FROM chunks_fts ")
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("WHERE chunks_fts MATCH ?")
    }

    @Test
    public fun `SEARCH_BODIES_BM25's ROW_NUMBER window never shares a SELECT with bm25 or snippet`() {
        // The bug this regresses: `WITH ranked AS (SELECT ... bm25(chunks_fts)
        // ... ROW_NUMBER() OVER (...) ... FROM chunks_fts JOIN chunks ...
        // WHERE chunks_fts MATCH ?)` put the window function in the SAME
        // SELECT as the aux-function calls, which blocks CTE flattening and
        // detaches bm25()/snippet() from the live FTS5 cursor.
        //
        // `expectedFlatFtsSubquery` (pinned verbatim above) is itself a
        // complete, self-contained SELECT statement — from its own `SELECT`
        // to its own `MATCH ?` — with no `ROW_NUMBER` anywhere in it. Pinning
        // that this EXACT literal is immediately followed by `) AS matched `
        // proves that SELECT's column list and FROM/WHERE close right there,
        // with nothing (no window function, no extra column) inserted before
        // the closing paren — i.e. bm25()/snippet() and ROW_NUMBER() cannot
        // be sharing this SELECT.
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("$expectedFlatFtsSubquery) AS matched ")
        assertThat(expectedFlatFtsSubquery).doesNotContain("ROW_NUMBER")

        // The window function is still present overall (doc-level dedup is
        // unchanged behaviour) — in the middle layer, reading `matched`'s
        // already-materialized bm25_rank column rather than calling bm25()
        // itself.
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains(
            "ROW_NUMBER() OVER (PARTITION BY chunks.doc_id ORDER BY matched.bm25_rank ASC) AS rn",
        )
    }

    @Test
    public fun `SEARCH_BODIES_BM25 has no top-level CTE`() {
        // The original broken query opened with `WITH ranked AS (...)`. The
        // fix uses nested derived-table subqueries instead — pin that no
        // `WITH` clause reappears (a CTE around a window function is what
        // broke bm25()/snippet() in the first place, see the class KDoc).
        assertThat(VaultSql.SEARCH_BODIES_BM25.trimStart()).doesNotContainMatch("^WITH\\b")
    }

    @Test
    public fun `SEARCH_BODIES_BM25 still dedupes to one row per document and joins documents`() {
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("PARTITION BY chunks.doc_id")
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("WHERE scored.rn = 1")
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("JOIN documents d ON d.id = scored.doc_id")
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("ORDER BY scored.bm25_rank ASC")
        assertThat(VaultSql.SEARCH_BODIES_BM25).contains("LIMIT ?")
    }

    @Test
    public fun `SEARCH_BODIES_BM25 binds exactly two placeholders, fts text first then limit`() {
        // VaultRepositoryImpl.searchBodies binds 1 = the sanitized FTS
        // query text, 2 = limit, in that order — positional binding is by
        // order of occurrence in the compiled statement text.
        val placeholderCount = VaultSql.SEARCH_BODIES_BM25.count { it == '?' }
        assertThat(placeholderCount).isEqualTo(2)
        val matchPlaceholder = VaultSql.SEARCH_BODIES_BM25.indexOf("MATCH ?")
        val limitPlaceholder = VaultSql.SEARCH_BODIES_BM25.indexOf("LIMIT ?")
        assertThat(matchPlaceholder).isGreaterThan(-1)
        assertThat(limitPlaceholder).isGreaterThan(matchPlaceholder)
    }

    @Test
    public fun `SEARCH_BODIES_BM25 result columns keep documents first then bm25_rank then snippet`() {
        // VaultRepositoryImpl.searchBodies reads columns 0..8 via
        // readDocument(stmt) (DOCUMENT_COLUMN_COUNT = 9), then
        // stmt.getDouble(9) for bm25_rank and stmt.getText(10) for
        // snippet — the result-column order must not drift.
        val selectClause =
            VaultSql.SEARCH_BODIES_BM25.substringAfter("SELECT ").substringBefore(" FROM (")
        assertThat(selectClause).isEqualTo(
            "d.id, d.kind, d.title, d.body_md, d.created_at, d.updated_at, d.persona_id, d.frontmatter, " +
                "d.content_hash, scored.bm25_rank, scored.snippet",
        )
    }
}
