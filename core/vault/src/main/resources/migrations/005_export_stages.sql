-- Skein vault DB migration 005: `export_stages`, the durable record of
-- plaintext spooled to `cache/staging_export/` so it can be swept on a
-- bounded timer, on lock, and after a crash. skein-0m1z.
--
-- Sources of truth:
--   • docs/design/POST_REVIEW_RESOLUTIONS.md §4.3, "Migration 005 —
--     `export_stages` and WorkManager pairing" — THE spec for this file.
--     The DDL below is §4.3's block with the one deviation called out under
--     "Deviation from §4.3" at the bottom of this header.
--   • docs/design/export-plaintext-lifetime.md §4 — why only the exports
--     that spool to app-private storage first (PDF via `PrintManager`,
--     E2.I11) need this table at all: `ExportServiceImpl`'s Markdown/zip
--     path streams to a caller-supplied `OutputStream` (an
--     `ACTION_CREATE_DOCUMENT` SAF destination) and never lands plaintext
--     in our storage, so it has nothing to register here.
--   • bd skein-voys — migration numbering. 005 is reserved for this table
--     by that bead's reservation ledger and by docs/VAULT_FORMAT.md §7
--     ("002 and 004-006 above are reserved but not yet in-tree").
--   • core/export/.../pdf/ExportStage.kt — `ExportStageFactory` already
--     builds exactly this row shape (stage_id, path, origin, document_id,
--     created_at, expires_at) for the staged PDF; this table is where that
--     record becomes durable.
--
-- Applied by `Migrator` under `PRAGMA user_version = 5`. Statements are
-- separated by the dash-dash-semicolon sentinel, per 001_initial.sql's loader
-- convention (spelled out so a naive splitter cannot cut this comment).
--
-- Ordering note: migrations apply in ascending numeric order, so on a fresh
-- database 005 runs after 001 and 003 and before 007 and 008. It references
-- only `documents` (created by 001), so it is independent of 003, 007 and
-- 008 in either direction; a fresh install reaches `PRAGMA user_version` 8
-- exactly as before this file existed.
--
-- !! UPGRADE-PATH NOTE (skein-0m1z; resolved by skein-p8rn) !!
-- This migration originally shipped with an UPGRADE-PATH CAVEAT here:
-- `Migrator.migrate` used to apply only migrations whose version was
-- strictly GREATER than the database's current `PRAGMA user_version`, so a
-- database that already reached user_version 8 before this file landed
-- would never apply 005, and `export_stages` would simply not exist there.
-- `Migrator` now maintains a `schema_migrations` ledger (skein-p8rn) that
-- tracks exactly which versions have run, seeded from `PRAGMA user_version`
-- plus a live-schema check for any database that predates the ledger, so a
-- gap-filler like this one is applied exactly once no matter how many
-- higher-numbered migrations (007, 008) already shipped ahead of it. See
-- `Migrator`'s KDoc ("Seeding a pre-ledger database") for the seeding
-- algorithm, and `docs/VAULT_FORMAT.md` §7 for the summary.
-- `SqlExportStageRepository` still probes for the table and fails loudly
-- rather than silently not recording a stage, as defense in depth: a
-- missing table must never be mistaken for "nothing to sweep".
--
-- ===== Columns =====
--
-- `stage_id` is the UUIDv7 that also prefixes the staged file's name
-- (`PdfStaging.stagedFile`), so a row and its file can be matched from
-- either direction without parsing a path.
--
-- `path` is the absolute path of the staged file inside
-- `cache/staging_export/`. It is content-free (a UUID plus a sanitized
-- title) but is still never logged above debug level, per spec §9.
--
-- `origin` is 'pdf_export' | 'docx_export' | 'markdown_export' per §4.3. No
-- CHECK constraint: 001's own `documents.kind` precedent keeps the enum in
-- Kotlin (`ExportStageFactory.ORIGIN_PDF`) rather than in the schema, so
-- adding an origin never needs a migration.
--
-- `document_id` cascades on document delete: a staged export of a deleted
-- document must not keep a row pointing at it. The FILE that row described
-- is still removed, because `StagedPlaintextSweep.sweepAll` sweeps the
-- staging DIRECTORY (every file in it), not just the rows — so a row
-- cascaded away from under the sweeper can never orphan plaintext on disk.
--
-- `expires_at` is `created_at + 10 minutes` by default
-- (`ExportStageFactory.DEFAULT_LIFETIME_MILLIS`); it is a stored column,
-- not a computed one, so a future per-origin lifetime needs no migration.
--
-- `swept` is 0/1 rather than a nullable `swept_at` timestamp because
-- nothing needs to know WHEN a sweep happened — only that the plaintext is
-- gone — and a timestamp would be one more piece of user-activity metadata
-- persisted for no consumer.
--
-- ===== Deviation from §4.3 =====
--
-- §4.3 writes `revision_hash TEXT REFERENCES document_revisions(revision_hash)
-- ON DELETE CASCADE`. That FK is not expressible: 003_document_revisions.sql
-- gives `document_revisions` the composite primary key
-- `(document_id, revision_hash)`, and SQLite requires an FK's parent columns
-- to be a PRIMARY KEY or carry a UNIQUE index — `revision_hash` alone is
-- neither, so the clause would raise "foreign key mismatch" at the first
-- INSERT rather than at CREATE TABLE. `revision_hash` is therefore a plain
-- nullable TEXT here, following the precedent 003 itself set for
-- `chunks.revision_hash` (added by that migration with no FK clause, for
-- exactly this reason — see 003's header, deviation 1).
--
-- Losing that FK loses nothing this table needs: `document_id`'s cascade
-- already removes a stage row when its document goes, and revision pruning
-- must NOT silently delete a stage row while its plaintext file survives.

CREATE TABLE export_stages (
  stage_id      TEXT PRIMARY KEY,
  path          TEXT NOT NULL,
  origin        TEXT NOT NULL,
  document_id   TEXT REFERENCES documents(id) ON DELETE CASCADE,
  revision_hash TEXT,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  swept         INTEGER NOT NULL DEFAULT 0
);--;

CREATE INDEX idx_export_stages_expires ON export_stages(expires_at);--;
