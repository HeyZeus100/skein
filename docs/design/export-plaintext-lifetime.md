# Export plaintext lifetime — how `E2.I10`'s output relates to `skein-7ki2`'s sweeper

**Status:** Design note (not implementation)
**Date:** 2026-09-20
**Context:** written while implementing `skein-90d` (`E2.I10`, Markdown export)

## 1. Why this note exists

`skein-90d`'s task brief asks Markdown export to land its output "under a
controlled, bounded-lifetime staging directory" per
`docs/design/POST_REVIEW_RESOLUTIONS.md` §4. This note records why
`ExportServiceImpl` (this bead) does **not** itself stage plaintext on disk,
and what the still-open follow-up (`skein-7ki2`, plus the new
implementation bead filed alongside this note) needs to build for the
export paths that *do*.

## 2. What `ExportServiceImpl` actually does

The `ExportService` contract locked by `E0.I14` (`core/model/.../Transfer.kt`,
skein-18j — not modified by this bead) shapes both methods as:

```kotlin
public suspend fun exportMarkdown(docId: DocId, out: OutputStream)
public suspend fun exportVaultZip(out: OutputStream, onProgress: ... = { _, _ -> })
```

The caller owns `out` and decides where its bytes land.
`ExportServiceImpl.exportMarkdown`/`exportVaultZip` (this bead,
`core/vault/.../export/ExportServiceImpl.kt`) write directly into whatever
stream they're handed — they never open a `File` of their own.

Per `POST_REVIEW_RESOLUTIONS.md` §4.2, the v1 file-backed export flow for
Markdown/zip is `ACTION_CREATE_DOCUMENT`: the user picks a destination via
the system picker, the app calls `contentResolver.openOutputStream(uri)`,
and that stream is handed straight to `exportMarkdown`/`exportVaultZip`.
The plaintext bytes go straight from the (decrypted, in-memory) vault
content to the user-chosen SAF location — **no intermediate file in our
storage at all**, so there is nothing for a sweeper to clean up on this
path. This matches spec's "user owns the vault format" stance and §4.2's
explicit design decision ("routes plaintext to a user-chosen SAF location
outside our storage entirely — no staging in our cache").

## 3. Where staging genuinely is needed (not this bead)

§4.2 calls out the one place v1 does write plaintext to app-private storage
before it's known where it's finally going: **`PrintManager`-based PDF
export** (`E2.I11`) spools its rendered pages to a platform-managed
temporary file, and any future DOCX flow that needs to build up a full
in-memory-too-large document for the same reason. Those are the exports
this note (and the bounded-lifetime discipline `skein-90d`'s brief asked
about) actually apply to — not the Markdown/zip export this bead
implements.

## 4. The sketch (already designed, not yet built)

`POST_REVIEW_RESOLUTIONS.md` §4.3 already specifies the concrete artifacts
for that staging case in full:

- **Migration 005** — `export_stages` table (`stage_id`, `path`, `origin`,
  `document_id`, `revision_hash`, `created_at`, `expires_at`, `swept`),
  indexed on `expires_at`.
- **`StagedPlaintextSweeper`** — a `CoroutineWorker` enqueued via
  `OneTimeWorkRequestBuilder<StagedPlaintextSweeper>().setInitialDelay(10, MINUTES)`,
  keyed `sweep-<stageId>` with `ExistingWorkPolicy.REPLACE`; deletes the
  file and marks the row `swept = 1` once `expiresAt` has passed.
- **`BootReceiver`** — `RECEIVE_BOOT_COMPLETED` +
  `LOCKED_BOOT_COMPLETED`; on cold start, rescans `export_stages` for
  unswept rows and either sweeps them immediately (already expired) or
  re-enqueues the sweeper with the residual delay (crash/kill recovery —
  the "orphaned stage" case).
- **On-lock sweep** — `UnlockManager.lock()` gets an `onLocked` hook that
  calls `ExportStageRepository.sweepAll()` synchronously, so a device lock
  is zero-tolerance for staged plaintext regardless of the 10-minute timer.
- Staging directory: `cache/staging_export/`, excluded from both
  `data_extraction_rules.xml` (API 31+) and `backup_rules_legacy.xml`
  (API 30) per §4.3.

None of this exists in-tree yet — `skein-7ki2` (open, "Implementation
pending" per its own notes) is the design-reconciliation bead that produced
this sketch. `skein-0m1z` is filed alongside this note as the
narrowly-scoped implementation bead to actually build the migration,
`StagedPlaintextSweeper`, `BootReceiver`, and `ExportStageRepository`, since
`skein-7ki2` also covers the (larger, separate) `DocumentsProvider`
grant-URI-permission and backup-rules work.

## 5. Consequence for `skein-90d`'s acceptance criteria

`skein-90d`'s own acceptance criteria (`bd show skein-90d`) — round-trip via
`importText`, whole-vault zip structure/manifest, byte-identical repeat
exports, chat transcript export — are all about `ExportServiceImpl`'s
output *content*, not about a staging directory, and are verified by
`MarkdownExportTest`/`VaultZipTest`. The "bounded-lifetime staging area"
requirement remains real, but it belongs to the `ACTION_CREATE_DOCUMENT`
UI wiring (out of scope for a `core/vault` library module) and to the
PDF/DOCX exports that do spool to local disk — tracked by the new
implementation bead, not by this one.
