-- Skein vault DB migration 003: content-addressed `document_revisions`
-- (citation stability across re-ingestion). skein-uo5n.
--
-- Sources of truth:
--   • docs/design/POST_REVIEW_RESOLUTIONS.md §1.3 — THE spec for this file.
--     The DDL below is §1.3's block with the two deviations called out
--     under "Deviations from §1.3" at the bottom of this header.
--   • skein-voys — migration numbering. 003 is reserved for this file by
--     POST_REVIEW_RESOLUTIONS §1.3 and by 001_initial.sql's own header
--     ("NOT included in this migration (deliberately): document_revisions —
--     Migration 003"). The v1 plan's own `003_ingest_attempts.sql` is the
--     one that gets renumbered; skein-voys tracks that.
--   • core/model/.../Revisions.kt — `RevisionHashing`, the normative
--     definition of the `revision_hash` value this table stores.
--   • docs/VAULT_FORMAT.md §2 (schema) and its migration changelog,
--     updated alongside this file.
--
-- Applied by `Migrator` under `PRAGMA user_version = 3`. Statements are
-- separated by the `--;` sentinel, per 001_initial.sql's loader convention.
--
-- Ordering note: migrations apply in ascending numeric order, so on a fresh
-- database 003 runs BEFORE 007_drop_attachment_master_key.sql. Nothing here
-- references `attachment_master_key` or `attachment_keys` (the tables 007
-- drops), so the two are independent in either direction; a device that
-- installed at v1 runs 003 then 007 and reaches the same schema as a fresh
-- install.
--
-- 001's DDL is not edited by this migration. The only pre-existing object
-- touched is `chunks`, via ALTER TABLE ADD COLUMN (§1.3's own instruction),
-- which appends a nullable column and rewrites no rows.
--
-- ===== What `revision_hash` is =====
--
-- BLAKE3-256, lowercase hex, over the canonicalized `body_md` plus the
-- normalized frontmatter, exactly as §1.3 states. The canonical form is
-- normative and lives in ONE place — `RevisionHashing` in `:core:model`
-- (see its KDoc): CRLF/CR line endings folded to LF; frontmatter emitted as
-- compact JSON with object keys sorted recursively and the `id` key removed
-- (it always equals `documents.id`, and keeping it would make two
-- byte-identical notes hash differently); the two parts length-prefixed and
-- domain-separated before hashing so the concatenation is injective.
--
-- Because the hash is a pure function of content, it is also what
-- `documents.content_hash` now holds for every non-attachment document —
-- which is what makes §1.2 step 3's replay check ("compare the cited
-- revision_hash against the document's current content_hash") a literal
-- string comparison with no join and no re-hashing. Attachments keep
-- SHA-256-over-plaintext-bytes in `content_hash`: they have no `body_md`,
-- are never chunked, and are never the source of a citation, so they get no
-- rows here.
--
-- ===== Retention =====
--
-- §1.2 step 4: a revision row lives as long as any
-- `messages.retrieved_chunks` entry references it. That reference lives
-- inside a JSON payload, which SQLite cannot express as a foreign key, so
-- retention is enforced by `VaultRepository.sweepUnreferencedRevisions()`
-- (skein-a2yr) rather than by a constraint here — it decodes every
-- message's citation-record-v1 payload via `CitationRecordJson.decode`
-- (never a string match on the JSON text) and deletes every row that is
-- neither a document's current revision nor named by one of those decoded
-- citations. Nothing in this migration's own DDL deletes a revision;
-- `ON DELETE CASCADE` from `documents` is the only *automatic* removal
-- (fires only when the whole document goes away), and the sweep is invoked
-- from the app layer at most once per unlocked session, inside the same
-- authorized-unlock maintenance pass ingest runs in
-- (`docs/design/LOCK_POLICY_INDEXING.md`) — never at lock time.
--
-- A `messages.retrieved_chunks` excerpt is a *second, independent* copy of
-- cited text (the JSON `excerpt` field, up to 1024 chars) that lives inside
-- the `messages` row itself, not in this table — the sweep above never
-- touches it, so deleting or editing away the source document does not
-- scrub the excerpt out of past chat citation records. That gap is real
-- (raised as skein-koda) and is intentionally left to a follow-up: purging
-- it needs a user-visible action ("purge history for this note"), which is
-- out of this migration's and this sweep's scope.
--
-- ===== Deviations from §1.3 (both forced, both narrow) =====
--
-- 1. `chunks.revision_hash` carries NO `REFERENCES document_revisions(
--    revision_hash)` clause. SQLite requires a foreign key's parent key to
--    be a PRIMARY KEY or UNIQUE index; `document_revisions`' primary key is
--    the pair `(document_id, revision_hash)`, so `revision_hash` alone is
--    not a valid parent key and every INSERT into `chunks` would fail at
--    DML time with "foreign key mismatch" under `PRAGMA foreign_keys = ON`
--    (which SkeinSQLiteDriver sets on every open). The alternative —
--    `UNIQUE(revision_hash)` — is worse: it would forbid two documents from
--    ever holding identical content, contradicting the content-addressing
--    property §1.3's own primary key chooses. A composite
--    `FOREIGN KEY (doc_id, revision_hash)` is not expressible either,
--    because SQLite's ALTER TABLE ADD COLUMN accepts only column
--    constraints, never table-level ones. The column plus its index is
--    therefore an unenforced pointer, exactly as `edges.src_id`/`dst_id`
--    already are in 001. Retrieval (E5.I13, a separate bead) reads it
--    alongside `documents.content_hash`, which IS authoritative.
-- 2. `frontmatter_snapshot` is declared `TEXT NOT NULL`, not `JSON NOT
--    NULL`. SQLite has no JSON type; `JSON` in 001 (`documents.frontmatter`,
--    `messages.retrieved_chunks`, `models.capabilities`) is an
--    unrecognized type name that falls back to BLOB affinity. Those columns
--    are nullable so the affinity never mattered; this one is NOT NULL and
--    is read back as text, so it is declared with the affinity it actually
--    wants. The stored bytes are the same canonical JSON either way.
--
-- ===== Chat snapshot bound (skein-a2yr; resolves the cost noted below) =====
--
-- `body_md_snapshot` stores the whole body, per §1.3 ("Not the excerpt; the
-- whole body at revision time") — for every kind EXCEPT `chat`. A chat
-- document's `body_md` is re-materialized from `messages` on every append,
-- which would make snapshot storage quadratic in the number of turns if
-- archived in full on every turn (the cost this header used to describe as
-- "tracked separately"). `VaultRepositoryImpl.captureRevision` (and its
-- `InMemoryVaultRepository` twin) resolves this by storing the empty string
-- for a `kind = 'chat'` document's `body_md_snapshot` instead of the
-- transcript — the row still exists (so `currentRevision`/`getRevision`
-- return non-null and RAG can still stamp `chunks.revision_hash`) and
-- `revision_hash` is still the exact hash of the real transcript (computed
-- by the caller before this row is written), so a citation into a chat turn
-- still resolves correctly via `revisionMatches`'s direct
-- `documents.content_hash` comparison, which never reads this table. What is
-- lost is only the archived *diff-view* copy: reading an old chat revision's
-- `bodyMdSnapshot` back returns "", not its old text (no UI reads it today).
-- See `VaultRepository.currentRevision`'s KDoc and `docs/VAULT_FORMAT.md`'s
-- Retention section for the full rationale.
--
-- ===== `messages.retrieved_chunks` =====
--
-- No column change is needed — it is already JSON (001_initial.sql), and
-- §1.3 says so explicitly. What changed is its *shape*: citation-record-v1
-- per core/model/.../CitationRecordJson.kt, which also reads the legacy
-- bare-chunk-id-array shape as `record_version: 0` (§1.5). Enforcement is
-- in that Kotlin codec; SQLite enforces no JSON schema. Note also that this
-- file must not end with a comment-only chunk: `Migrator` prepares every
-- `--;`-separated chunk as a statement, and a chunk with no SQL in it has
-- nothing to prepare.

CREATE TABLE document_revisions (
  document_id          TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  revision_hash        TEXT NOT NULL,
  revision_ord         INTEGER NOT NULL,
  body_md_snapshot     TEXT NOT NULL,
  frontmatter_snapshot TEXT NOT NULL,
  captured_at          INTEGER NOT NULL,
  reason               TEXT NOT NULL,
  PRIMARY KEY (document_id, revision_hash)
);--;

CREATE INDEX idx_document_revisions_doc ON document_revisions(document_id, revision_ord DESC);--;

-- Chunks stay ephemeral (E2.I2's `replaceChunks` still deletes and
-- re-inserts them); this is the durable reverse pointer that lets retrieval
-- emit a `(revision_hash, locator)` tuple without re-hashing the document.
-- See deviation 1 in the header for why there is no FK clause.
ALTER TABLE chunks ADD COLUMN revision_hash TEXT;--;

CREATE INDEX idx_chunks_revision ON chunks(revision_hash);--;
