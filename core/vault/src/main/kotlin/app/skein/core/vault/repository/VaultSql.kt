// Every SQL string used by `VaultRepositoryImpl` lives here so a reviewer
// can audit the query surface in one file (E2.I4 acceptance criterion).
// Column names and table names must match the DDL in
// `core/vault/src/main/resources/migrations/001_initial.sql` verbatim.
//
// Design notes:
//   • `documents.frontmatter` is a JSON column; this class stores the
//     `JsonObject` serialized via `kotlinx.serialization.json.Json`, never
//     hand-built strings, so quoting/escaping is never this file's problem.
//   • `SELECT_TIMELINE`'s three optional filters (persona, `before` paging,
//     tag) use the `(? IS NULL OR <predicate>)` idiom instead of building
//     the WHERE clause dynamically — one fixed prepared statement shape
//     for every call, at the cost of always binding all parameters. The
//     `kind IN (?, ?, ?, ?)` list is always exactly
//     `DocumentKind.entries.size` (4) placeholders for the same reason:
//     [VaultRepositoryImpl] pads a smaller requested kind set by repeating
//     one of its own values (harmless — `IN (x, x)` matches identically to
//     `IN (x)`) rather than building a variable-length IN-list here.
//   • `SEARCH_BODIES_BM25` joins through `chunks_fts`/`chunks` — the vault
//     schema has no document-level FTS table, only the chunk-level one
//     `IndexStoreImpl` (`E2.I15`) populates during ingest (spec §5,
//     `E2.I4` acceptance criterion: "searchBodies("quantum") ... snippet
//     from snippet(chunks_fts, 0, '[', ']', '…', 12)"). A `ROW_NUMBER()`
//     window keeps only each document's single best-ranked chunk so a
//     `DocumentHit` list reads as one row per matching document, matching
//     `InMemoryVaultRepository.searchBodies`'s one-hit-per-document shape.
//     `bm25()` is negative-good (FTS5 convention); [VaultRepositoryImpl]
//     negates it before handing it back as `DocumentHit.rank`, matching
//     the "higher is better" convention `IndexStoreImpl.bm25()` already
//     established for `ScoredChunk.score`.
//   • `SEARCH_BODIES_BM25`'s shape (skein-gg11.10): FTS5's `bm25()` and
//     `snippet()` may only be called from a SELECT whose FROM directly
//     names the FTS5 table alongside the `MATCH` constraint — nothing may
//     sit between that call and the live FTS5 cursor it reads. An earlier
//     version wrapped `bm25(chunks_fts)`/`snippet(chunks_fts, ...)` in a
//     `WITH ranked AS (...)` CTE that ALSO computed
//     `ROW_NUMBER() OVER (PARTITION BY ...)` in the same SELECT: the
//     window function stops SQLite from flattening that CTE into the
//     outer query, so by the time `bm25()`/`snippet()` would run, the
//     plan has already detached them from the top-level query context FTS5
//     requires — `SkeinSQLiteException: unable to use function bm25 in the
//     requested context` (real device only; the JVM fake driver runs no
//     SQL and cannot catch this). The fix nests two derived tables instead
//     of one CTE, so the aux-function call and the window function never
//     share a SELECT: the innermost `matched` subquery is bm25()/snippet()
//     alone against `chunks_fts MATCH ?` — textually identical in shape to
//     `IndexSql.BM25_QUERY`, which is proven to work on-device — with no
//     join, no window function, nothing else in scope. The middle `scored`
//     subquery only reads `matched`'s already-materialized `bm25_rank`/
//     `snippet` columns (plain REAL/TEXT by that point, no FTS5 cursor
//     involved) to join `chunks` (for `doc_id`) and rank with
//     `ROW_NUMBER()`. The outer SELECT joins `documents` and keeps `rn = 1`
//     exactly as before.
//   • Placeholders are always positional (`?`); the only text ever
//     concatenated into a query is this object's own literal SQL — no user
//     string is ever formatted into a query. FTS5 MATCH input goes through
//     `app.skein.core.vault.index.FtsQuerySanitizer` (E2.I15) before it
//     reaches [SEARCH_BODIES_BM25], same as `IndexStoreImpl.bm25()`.

package app.skein.core.vault.repository

internal object VaultSql {
    // ------------------------------------------------------------------
    // Documents
    // ------------------------------------------------------------------

    const val DOCUMENT_SELECT_COLUMNS: String =
        "id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, content_hash"

    const val INSERT_DOCUMENT: String =
        "INSERT INTO documents(id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, " +
            "content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

    const val INSERT_ATTACHMENT_DOCUMENT: String =
        "INSERT INTO documents(id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, " +
            "content_hash, mime_type, blob_size) VALUES (?, 'attachment', ?, NULL, ?, ?, NULL, ?, ?, ?, ?)"

    const val SELECT_DOCUMENT_BY_ID: String =
        "SELECT id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, content_hash " +
            "FROM documents WHERE id = ?"

    const val SELECT_DOCUMENT_BY_TITLE_EXACT: String =
        "SELECT id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, content_hash " +
            "FROM documents WHERE title = ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1"

    // SQLite's built-in LIKE is already case-insensitive for ASCII; `ESCAPE
    // '\'` lets the caller neutralize a literal `%`/`_`/`\` in the prefix
    // (see VaultRepositoryImpl.escapeLikePattern) instead of it being
    // treated as a wildcard.
    const val SEARCH_TITLES_PREFIX: String =
        "SELECT id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, content_hash " +
            "FROM documents WHERE title LIKE ? ESCAPE '\\' ORDER BY updated_at DESC LIMIT ?"

    const val SEARCH_BODIES_BM25: String =
        // Outer: documents joined onto the already-deduped `scored` rows.
        "SELECT d.id, d.kind, d.title, d.body_md, d.created_at, d.updated_at, d.persona_id, d.frontmatter, " +
            "d.content_hash, scored.bm25_rank, scored.snippet " +
            "FROM (" +
            // Middle: ROW_NUMBER() partitions by doc_id over `matched`'s
            // already-materialized bm25_rank column — no FTS5 aux-function
            // call at this level, so the window function is safe here.
            "SELECT chunks.doc_id AS doc_id, matched.bm25_rank AS bm25_rank, matched.snippet AS snippet, " +
            "ROW_NUMBER() OVER (PARTITION BY chunks.doc_id ORDER BY matched.bm25_rank ASC) AS rn " +
            "FROM (" +
            // Innermost: bm25()/snippet() alone against `chunks_fts MATCH
            // ?` — same flat shape as IndexSql.BM25_QUERY, proven to work
            // on-device. No join, no window function shares this SELECT.
            "SELECT rowid, bm25(chunks_fts) AS bm25_rank, snippet(chunks_fts, 0, '[', ']', '…', 12) AS snippet " +
            "FROM chunks_fts " +
            "WHERE chunks_fts MATCH ?" +
            ") AS matched " +
            "JOIN chunks ON chunks.id = matched.rowid" +
            ") AS scored " +
            "JOIN documents d ON d.id = scored.doc_id " +
            "WHERE scored.rn = 1 " +
            "ORDER BY scored.bm25_rank ASC " +
            "LIMIT ?"

    // Bind order: 1,2 = personaId (nullable text, bound twice); 3,4 =
    // `before` (nullable long, bound twice); 5,6 = tag (nullable text,
    // bound twice — the second occurrence is lower-cased in SQL); 7-10 =
    // exactly 4 `kind` values (padded, see file header); 11 = limit.
    const val SELECT_TIMELINE: String =
        "SELECT id, kind, title, body_md, created_at, updated_at, persona_id, frontmatter, content_hash " +
            "FROM documents " +
            "WHERE (? IS NULL OR persona_id = ?) " +
            "AND (? IS NULL OR updated_at < ?) " +
            "AND (? IS NULL OR EXISTS (" +
            "SELECT 1 FROM edges WHERE src_id = documents.id AND kind = 'tag' AND dst_id = 'tag:' || lower(?)" +
            ")) " +
            "AND kind IN (?, ?, ?, ?) " +
            "ORDER BY updated_at DESC " +
            "LIMIT ?"

    const val UPDATE_DOCUMENT_BODY: String =
        "UPDATE documents SET title = ?, body_md = ?, updated_at = ?, content_hash = ? WHERE id = ?"

    const val UPDATE_CHAT_BODY: String =
        "UPDATE documents SET body_md = ?, updated_at = ?, content_hash = ? WHERE id = ?"

    // `content_hash` is set here as well as in [UPDATE_DOCUMENT_BODY]
    // because it now holds the document's `RevisionHash`, which covers the
    // frontmatter too (migration 003 / POST_REVIEW_RESOLUTIONS.md §1.3). The
    // `documents_au_ingest` trigger is `AFTER UPDATE OF body_md, title`, so
    // this statement still does not enqueue a re-index — a frontmatter-only
    // edit changes the revision address without re-chunking anything.
    const val UPDATE_DOCUMENT_FRONTMATTER: String =
        "UPDATE documents SET frontmatter = ?, updated_at = ?, content_hash = ? WHERE id = ?"

    const val DELETE_DOCUMENT: String =
        "DELETE FROM documents WHERE id = ?"

    // ------------------------------------------------------------------
    // Document revisions (migration 003, POST_REVIEW_RESOLUTIONS.md §1.3)
    // ------------------------------------------------------------------

    private const val REVISION_COLUMNS: String =
        "document_id, revision_hash, revision_ord, body_md_snapshot, frontmatter_snapshot, captured_at, reason"

    const val SELECT_MAX_REVISION_ORD: String =
        "SELECT COALESCE(MAX(revision_ord), -1) FROM document_revisions WHERE document_id = ?"

    // Upsert rather than `INSERT OR IGNORE`: `revision_hash` is a content
    // address, so re-writing content the document has held before must reuse
    // the existing row (that is §1.4's "newRevision is idempotent when
    // content is unchanged") — but it must also move that row back to the
    // head of the history, or a document edited A -> B -> A would leave
    // `MAX(revision_ord)` naming B while the document's content is A.
    const val UPSERT_DOCUMENT_REVISION: String =
        "INSERT INTO document_revisions($REVISION_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(document_id, revision_hash) DO UPDATE SET " +
            "revision_ord = excluded.revision_ord, captured_at = excluded.captured_at, reason = excluded.reason"

    // The current revision is the one whose hash IS the document's current
    // `content_hash` (§1.2 step 3) — authoritative by construction, and
    // immune to any drift between `revision_ord` and the document row.
    const val SELECT_CURRENT_REVISION: String =
        "SELECT r.document_id, r.revision_hash, r.revision_ord, r.body_md_snapshot, r.frontmatter_snapshot, " +
            "r.captured_at, r.reason FROM document_revisions r " +
            "JOIN documents d ON d.id = r.document_id AND d.content_hash = r.revision_hash " +
            "WHERE r.document_id = ?"

    const val SELECT_REVISION_BY_HASH: String =
        "SELECT $REVISION_COLUMNS FROM document_revisions WHERE document_id = ? AND revision_hash = ?"

    /** The whole of §1's replay check: one indexed row lookup, two hex strings compared by SQLite. */
    const val SELECT_REVISION_MATCHES: String =
        "SELECT 1 FROM documents WHERE id = ? AND content_hash = ?"

    // `documentRevisions_gc` (skein-a2yr, POST_REVIEW_RESOLUTIONS.md §1.2 step
    // 4). SQLite cannot express "referenced from inside a JSON column" as a
    // constraint (003's own header explains why), and the reference must be
    // decoded via `CitationRecordJson.decode` rather than matched as a
    // substring — so this is two plain queries plus an in-Kotlin set
    // difference in `VaultRepositoryImpl.sweepUnreferencedRevisions`, not a
    // single DELETE. `SELECT_SWEEPABLE_REVISIONS` finds every revision that
    // is not its document's current one (candidates only — cited-but-
    // superseded rows are filtered back out in Kotlin against the set built
    // from `SELECT_ALL_RETRIEVED_CHUNKS`); `DELETE_DOCUMENT_REVISION` removes
    // one row the Kotlin side decided is truly orphaned.
    const val SELECT_SWEEPABLE_REVISIONS: String =
        "SELECT r.document_id, r.revision_hash FROM document_revisions r " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM documents d WHERE d.id = r.document_id AND d.content_hash = r.revision_hash" +
            ")"

    const val DELETE_DOCUMENT_REVISION: String =
        "DELETE FROM document_revisions WHERE document_id = ? AND revision_hash = ?"

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    const val INSERT_MESSAGE: String =
        "INSERT INTO messages(id, chat_doc_id, role, content_md, model_id, retrieved_chunks, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"

    const val SELECT_MESSAGES_FOR_CHAT: String =
        "SELECT id, chat_doc_id, role, content_md, model_id, retrieved_chunks, created_at " +
            "FROM messages WHERE chat_doc_id = ? ORDER BY created_at ASC"

    /** Every message's citation payload, for `sweepUnreferencedRevisions`'s referenced-revision scan. No ordering needed. */
    const val SELECT_ALL_RETRIEVED_CHUNKS: String =
        "SELECT retrieved_chunks FROM messages"

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    const val SELECT_ATTACHMENT_MIME_TYPE: String =
        "SELECT mime_type FROM documents WHERE id = ? AND kind = 'attachment'"

    // ------------------------------------------------------------------
    // Ingest queue
    // ------------------------------------------------------------------

    const val SELECT_INGEST_QUEUE: String =
        "SELECT doc_id, reason, queued_at, attempts FROM ingest_queue ORDER BY queued_at ASC LIMIT ?"

    // Deletes the row only if `queued_at` still equals the value the
    // caller last observed — the "no-op when re-queued" acceptance
    // criterion falls straight out of this WHERE clause; no read-modify-
    // write race window because the DELETE's own WHERE is the check.
    const val DELETE_INGEST_ITEM_IF_UNCHANGED: String =
        "DELETE FROM ingest_queue WHERE doc_id = ? AND queued_at = ?"

    const val ENQUEUE_REEMBED_ALL: String =
        "INSERT OR REPLACE INTO ingest_queue(doc_id, reason, queued_at) " +
            "SELECT id, 'reembed', ? FROM documents WHERE kind != 'attachment'"

    // Migration 008 (skein-zx15): the persisted retry counter behind
    // `VaultRepository.recordIngestFailure`. `RETURNING attempts` in the
    // same statement as the increment avoids a read-modify-write race
    // window — the UPDATE's own WHERE is what makes "no row" (the entry
    // completed or the document was deleted concurrently) observable as
    // "no row returned" rather than a silent no-op.
    const val INCREMENT_INGEST_ATTEMPTS: String =
        "UPDATE ingest_queue SET attempts = attempts + 1 WHERE doc_id = ? RETURNING attempts"

    // ------------------------------------------------------------------
    // Export stages (migration 005, skein-0m1z)
    // ------------------------------------------------------------------

    const val EXPORT_STAGE_COLUMNS: String =
        "stage_id, path, origin, document_id, revision_hash, created_at, expires_at, swept"

    // `INSERT OR REPLACE`: re-recording the same stage id (a retried export
    // that reuses its id) must overwrite rather than throw, and the row it
    // replaces described the same file anyway.
    const val INSERT_EXPORT_STAGE: String =
        "INSERT OR REPLACE INTO export_stages($EXPORT_STAGE_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

    const val SELECT_EXPORT_STAGE: String =
        "SELECT $EXPORT_STAGE_COLUMNS FROM export_stages WHERE stage_id = ?"

    // Oldest expiry first so a caller sweeping in order clears the most
    // overdue plaintext first. Uses idx_export_stages_expires.
    const val SELECT_UNSWEPT_EXPORT_STAGES: String =
        "SELECT $EXPORT_STAGE_COLUMNS FROM export_stages WHERE swept = 0 ORDER BY expires_at ASC"

    // `AND swept = 0` is what makes "there was no such unswept row"
    // observable through `changes()` rather than silently counting an
    // already-swept row as freshly swept.
    const val MARK_EXPORT_STAGE_SWEPT: String =
        "UPDATE export_stages SET swept = 1 WHERE stage_id = ? AND swept = 0"

    const val MARK_ALL_EXPORT_STAGES_SWEPT: String =
        "UPDATE export_stages SET swept = 1 WHERE swept = 0"

    /** `changes()` for the statement just executed on this connection — how a bare UPDATE reports its row count. */
    const val SELECT_CHANGES: String = "SELECT changes()"

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    const val BEGIN_IMMEDIATE: String = "BEGIN IMMEDIATE"
    const val COMMIT: String = "COMMIT"
    const val ROLLBACK: String = "ROLLBACK"
}
