-- Skein vault DB, initial migration (v1). E0.I11.
--
-- Sources of truth:
--   • Design spec §5 (data model) — reproduced verbatim below.
--   • Plan §4.9 (`E0.I11`, this issue) — plan additions marked below.
--   • docs/design/ATTACHMENT_ENCRYPTION.md §3.4 — attachment key hierarchy
--     tables. Live in `vault.db` (SQLCipher-encrypted under the separate
--     vault-open key hierarchy, no circular dependency).
--
-- Applied by `E2.I2` (`Migrator`) under `PRAGMA user_version = 1`.
--
-- NOT included in this migration (deliberately):
--   • `document_revisions` — Migration 003 per POST_REVIEW_RESOLUTIONS.md §1.3
--     (citation stability). The v1 vault ships without content-addressed
--     revisions; the payload shape (`CitationRecord` v1, in
--     `core/model/.../Vault.kt`) is locked at M0.5 so RAG code compiles
--     against a stable Kotlin type before 003 lands.
--   • `recovery_drafts` — a later migration per LOCK_POLICY_INDEXING.md §7
--     (background indexing safety); intentionally out of scope for §4.9.
--   • Migration 005 `export_stages` (POST_REVIEW_RESOLUTIONS.md §4.3) —
--     future migration, not v1.
--
-- Loader convention: `SkeinSQLiteDriver` (E2.I1 / skein-e2ki) executes each
-- SQL statement below individually. Statements are separated by a bare `;`
-- at end of line + newline; comment lines (`--`) and blank lines are ignored.
-- Any string literal or trigger body that would contain a `;` on its own
-- line MUST use the dash-dash-semicolon sentinel convention (`E0.I11` plan
-- step 4; the sentinel is spelled out here rather than written literally so
-- that a splitter cutting on the bare sequence cannot cut this comment) — the
-- current file does not need it because every trigger body is a single-line
-- statement.
--
-- No measurement-derived constants are baked in:
--   • The vec0 dimension `256` matches spec §5 line 156 (`vec0(embedding
--     int8[256])`). The dimension is a spec default and matches
--     nomic-embed-text-v1.5's Matryoshka 256-slice (spec §7.1 step 3). If
--     `MEASUREMENTS.md` (`skein-5hr`) later picks a different slice, the
--     new dimension ships as Migration 002 or as an alternate vec0 table,
--     not by editing this file.
--   • Distance metric `cosine` is a plan §4.9 correction: vec0's default is
--     L2; spec §7.2 requires cosine.
--   • Trigger bodies use `INSERT OR REPLACE INTO ingest_queue` so a queued
--     row is bumped when the same doc is edited again — enables the
--     "completeIngest is a no-op when queuedAt advanced" semantic tested
--     by `VaultRepositoryContractTest.completeIngest_noop_when_requeued`.

-- ===== Spec §5, verbatim =====

CREATE TABLE documents (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  body_md TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  persona_id TEXT REFERENCES personas(id),
  frontmatter JSON,
  content_hash TEXT
);--;

CREATE INDEX idx_documents_updated ON documents(updated_at DESC);--;

CREATE INDEX idx_documents_persona ON documents(persona_id);--;

CREATE TABLE chunks (
  id INTEGER PRIMARY KEY,
  doc_id TEXT REFERENCES documents(id) ON DELETE CASCADE,
  ord INTEGER NOT NULL,
  text TEXT NOT NULL,
  token_count INTEGER,
  embedder_id TEXT,
  embedder_version INTEGER
);--;

CREATE INDEX idx_chunks_doc ON chunks(doc_id);--;

CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='id');--;

-- PLAN ADDITION (§4.9): spec DDL is `vec0(embedding int8[256])`; §7.2 requires
-- cosine, and vec0 defaults to L2. Dimension 256 is spec default (Matryoshka
-- slice) — see file header note.
CREATE VIRTUAL TABLE chunks_vec USING vec0(embedding int8[256] distance_metric=cosine);--;

CREATE TABLE edges (
  src_id TEXT NOT NULL,
  dst_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  weight REAL DEFAULT 1.0,
  created_at INTEGER,
  PRIMARY KEY (src_id, dst_id, kind)
);--;

CREATE INDEX idx_edges_dst ON edges(dst_id, kind);--;

CREATE TABLE entities (
  id INTEGER PRIMARY KEY,
  canonical_name TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  first_seen INTEGER,
  UNIQUE(canonical_name, entity_type)
);--;

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  chat_doc_id TEXT REFERENCES documents(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  content_md TEXT NOT NULL,
  model_id TEXT,
  retrieved_chunks JSON,
  created_at INTEGER NOT NULL
);--;

CREATE INDEX idx_messages_chat ON messages(chat_doc_id, created_at);--;

CREATE TABLE personas (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  system_prompt TEXT,
  default_model TEXT,
  created_at INTEGER
);--;

CREATE TABLE models (
  id TEXT PRIMARY KEY,
  path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  attestation_url TEXT,
  format TEXT NOT NULL,
  capabilities JSON,
  size_bytes INTEGER,
  imported_at INTEGER
);--;

CREATE TABLE ingest_queue (
  doc_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
  reason TEXT,
  queued_at INTEGER
);--;

-- ===== Plan §4.9 additions =====

-- `models`: companions (mmproj/tokenizer) and display name — see
-- `ModelManifest` §4.8 (E0.I15). Split into separate ALTERs because SQLite's
-- ALTER TABLE only takes one column per statement.
ALTER TABLE models ADD COLUMN name TEXT NOT NULL DEFAULT '';--;

ALTER TABLE models ADD COLUMN context_length INTEGER NOT NULL DEFAULT 16384;--;

ALTER TABLE models ADD COLUMN companions JSON;--;

ALTER TABLE models ADD COLUMN license_spdx TEXT;--;

-- `documents`: mime type + blob size for `kind = 'attachment'` rows.
ALTER TABLE documents ADD COLUMN mime_type TEXT;--;

ALTER TABLE documents ADD COLUMN blob_size INTEGER;--;

-- ===== FTS5 sync triggers (plan §4.9) =====
-- External-content FTS tables do not update themselves (SQLite docs,
-- "External Content Tables"). vec0 rows are also cleaned up here.

CREATE TRIGGER chunks_ai AFTER INSERT ON chunks BEGIN
  INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
END;--;

CREATE TRIGGER chunks_ad AFTER DELETE ON chunks BEGIN
  INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES ('delete', old.id, old.text);
  DELETE FROM chunks_vec WHERE rowid = old.id;
END;--;

CREATE TRIGGER chunks_au AFTER UPDATE OF text ON chunks BEGIN
  INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES ('delete', old.id, old.text);
  INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
END;--;

-- ===== Ingest-queue triggers (spec §7.1 step 1, plan §4.9) =====
-- Attachments are indexed via their derived NOTE/AIOUT, not directly.

CREATE TRIGGER documents_ai_ingest AFTER INSERT ON documents WHEN new.kind != 'attachment' BEGIN
  INSERT OR REPLACE INTO ingest_queue(doc_id, reason, queued_at) VALUES (new.id, 'created', new.created_at);
END;--;

CREATE TRIGGER documents_au_ingest AFTER UPDATE OF body_md, title ON documents
WHEN new.kind != 'attachment' AND (new.body_md IS NOT old.body_md OR new.title IS NOT old.title) BEGIN
  INSERT OR REPLACE INTO ingest_queue(doc_id, reason, queued_at) VALUES (new.id, 'updated', new.updated_at);
END;--;

-- Title lookups for wikilink resolution (spec §7.1 step 5) and autocomplete
-- (`E7.I5`).
CREATE INDEX idx_documents_title_nocase ON documents(title COLLATE NOCASE);--;

CREATE INDEX idx_documents_kind_updated ON documents(kind, updated_at DESC);--;

-- ===== Attachment key hierarchy (ATTACHMENT_ENCRYPTION.md §3.4) =====
-- Layer 1 master material (wrapped under the biometric / credential
-- Keystore aliases) and Layer 2 per-attachment content keys. Both live
-- inside `vault.db` under SQLCipher's own key hierarchy — see
-- ATTACHMENT_ENCRYPTION.md §3.4 for the non-circularity argument.

CREATE TABLE attachment_master_key (
  key_version              INTEGER PRIMARY KEY,
  wrapped_bytes_biometric  BLOB,
  wrap_iv_biometric        BLOB,
  wrap_tag_biometric       BLOB,
  wrapped_bytes_credential BLOB,
  wrap_iv_credential       BLOB,
  wrap_tag_credential      BLOB,
  created_at               INTEGER NOT NULL,
  superseded_at            INTEGER
);--;

CREATE TABLE attachment_keys (
  attachment_uuid    TEXT PRIMARY KEY REFERENCES documents(id),
  wrapped_content_key BLOB NOT NULL,
  wrap_iv            BLOB NOT NULL,
  wrap_tag           BLOB NOT NULL,
  master_key_version INTEGER NOT NULL REFERENCES attachment_master_key(key_version),
  created_at         INTEGER NOT NULL
);--;

CREATE INDEX idx_attachment_keys_version ON attachment_keys(master_key_version);--;
