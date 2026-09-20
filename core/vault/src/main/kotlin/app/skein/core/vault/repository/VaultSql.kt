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
        "WITH ranked AS (" +
            "SELECT chunks.doc_id AS doc_id, " +
            "bm25(chunks_fts) AS bm25_rank, " +
            "snippet(chunks_fts, 0, '[', ']', '…', 12) AS snippet, " +
            "ROW_NUMBER() OVER (PARTITION BY chunks.doc_id ORDER BY bm25(chunks_fts) ASC) AS rn " +
            "FROM chunks_fts JOIN chunks ON chunks.id = chunks_fts.rowid " +
            "WHERE chunks_fts MATCH ?" +
            ") " +
            "SELECT d.id, d.kind, d.title, d.body_md, d.created_at, d.updated_at, d.persona_id, d.frontmatter, " +
            "d.content_hash, ranked.bm25_rank, ranked.snippet " +
            "FROM ranked JOIN documents d ON d.id = ranked.doc_id " +
            "WHERE ranked.rn = 1 " +
            "ORDER BY ranked.bm25_rank ASC " +
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

    const val UPDATE_DOCUMENT_FRONTMATTER: String =
        "UPDATE documents SET frontmatter = ?, updated_at = ? WHERE id = ?"

    const val DELETE_DOCUMENT: String =
        "DELETE FROM documents WHERE id = ?"

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    const val INSERT_MESSAGE: String =
        "INSERT INTO messages(id, chat_doc_id, role, content_md, model_id, retrieved_chunks, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"

    const val SELECT_MESSAGES_FOR_CHAT: String =
        "SELECT id, chat_doc_id, role, content_md, model_id, retrieved_chunks, created_at " +
            "FROM messages WHERE chat_doc_id = ? ORDER BY created_at ASC"

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    const val SELECT_ATTACHMENT_MIME_TYPE: String =
        "SELECT mime_type FROM documents WHERE id = ? AND kind = 'attachment'"

    // ------------------------------------------------------------------
    // Ingest queue
    // ------------------------------------------------------------------

    const val SELECT_INGEST_QUEUE: String =
        "SELECT doc_id, reason, queued_at FROM ingest_queue ORDER BY queued_at ASC LIMIT ?"

    // Deletes the row only if `queued_at` still equals the value the
    // caller last observed — the "no-op when re-queued" acceptance
    // criterion falls straight out of this WHERE clause; no read-modify-
    // write race window because the DELETE's own WHERE is the check.
    const val DELETE_INGEST_ITEM_IF_UNCHANGED: String =
        "DELETE FROM ingest_queue WHERE doc_id = ? AND queued_at = ?"

    const val ENQUEUE_REEMBED_ALL: String =
        "INSERT OR REPLACE INTO ingest_queue(doc_id, reason, queued_at) " +
            "SELECT id, 'reembed', ? FROM documents WHERE kind != 'attachment'"

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    const val BEGIN_IMMEDIATE: String = "BEGIN IMMEDIATE"
    const val COMMIT: String = "COMMIT"
    const val ROLLBACK: String = "ROLLBACK"
}
