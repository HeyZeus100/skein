-- Skein vault DB migration 009: model registry + provenance columns
-- (bead skein-cyq, H0-amended per docs/design/SKEIN_HUB.md §3.6/§9;
-- provenance columns specified by the not-yet-started skein-cwsl, "H3").
--
-- MIGRATION-NUMBER DEVIATION (recorded on skein-cyq and skein-cwsl per bd
-- note; docs/ARCHITECTURE.md §3.3 / docs/VAULT_FORMAT.md §7 own the
-- reservation ledger this deviates from): `post_mmap_blake3` was planned
-- for its own reserved migration 004. skein-cyq's own coordinator note
-- states the rule: fold `post_mmap_blake3` into 009 UNLESS skein-p8rn's
-- applied-migrations ledger (the fix for "a reserved migration landing
-- after a higher number is silently skipped by `Migrator`, which only
-- applies `version > current user_version`") had already merged to `main`
-- when this bead started. As of this migration, skein-p8rn is still
-- IN_PROGRESS — its ledger table is uncommitted in its own worktree
-- (confirmed against `Migrator.kt` on this branch: it still reads
-- `PRAGMA user_version` only, no `schema_migrations` table). Adding a
-- `004_post_mmap_blake3.sql` file now, with 007/008 already shipped, would
-- be silently skipped on every already-migrated database — precisely the
-- hazard skein-p8rn exists to fix. So: migration number 004 STAYS RESERVED
-- for `post_mmap_blake3` per the ledger (it is not reused for anything
-- else), and the `post_mmap_blake3` COLUMN lands here, in 009, instead.
-- `skein-cwsl` does not need a second migration for the columns it
-- specifies — they are all added below.
--
-- Columns added to `models` (`001_initial.sql`):
--   display_name     — backs the registry's `Model.name`. `001` never
--                       added a name column at all (see that file's
--                       `CREATE TABLE models`). NOT NULL DEFAULT '' so
--                       every pre-existing row stays valid; `ModelManager`
--                       backfills a real name for any row imported before
--                       this migration on next read.
--   context_length   — backs `Model.contextLength`. Defaults to
--                       `ModelManifest.DEFAULT_CONTEXT_LENGTH` (16384).
--   companions       — JSON array of `{role, path, sha256}`, the same
--                       style as the pre-existing `capabilities` JSON
--                       column.
--   post_mmap_blake3 — see the deviation note above. Nullable: a row
--                       written before this migration has no recorded
--                       BLAKE3; `ModelVerifier` computes one at next load
--                       when `expectedBlake3` is unavailable
--                       (`docs/ARCHITECTURE.md` §3.3, `MODEL_STORE.md` §4).
--   origin           — `ModelOrigin.db` (`app.skein.core.model.ModelOrigin`).
--                       NOT NULL DEFAULT 'document_picker' because every
--                       model imported before this column existed arrived
--                       through the document picker — there was no Hub and
--                       no bundled-default provenance path yet.
--   source_url,
--   source_revision,
--   license_spdx     — Hub-hint provenance (`docs/design/SKEIN_HUB.md`
--                       §3.4's "Hub hint" row). All nullable: absent for
--                       every non-Hub import, and never trusted for
--                       anything load-bearing — display/audit only.
--
-- Applied by `Migrator` under `PRAGMA user_version = 9`.

ALTER TABLE models ADD COLUMN display_name TEXT NOT NULL DEFAULT '';--;

ALTER TABLE models ADD COLUMN context_length INTEGER NOT NULL DEFAULT 16384;--;

ALTER TABLE models ADD COLUMN companions TEXT;--;

ALTER TABLE models ADD COLUMN post_mmap_blake3 TEXT;--;

ALTER TABLE models ADD COLUMN origin TEXT NOT NULL DEFAULT 'document_picker';--;

ALTER TABLE models ADD COLUMN source_url TEXT;--;

ALTER TABLE models ADD COLUMN source_revision TEXT;--;

ALTER TABLE models ADD COLUMN license_spdx TEXT;--;
