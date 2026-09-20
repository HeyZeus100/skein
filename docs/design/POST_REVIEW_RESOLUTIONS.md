# Post-Review Resolutions — coordinated design pass

**Status:** Design (not implementation)
**Date:** 2026-09-19
**Author:** Coordinator agent (worktree `agent-af3ff9258730af0bc`)
**Scope:** Resolves the four architectural questions left open by the 2026-09-19 orchestrator
review: `skein-uo5n` (citation stability), `skein-st1r` (model verification),
`skein-pn1l` (Binder aggregate byte limits + real IPC), `skein-7ki2` (export
flow, grants, backup, plaintext lifetime).

**Locked prior decisions this design must honor** (do not contradict):

- `skein-wa1l` — enforced immutable-write per attachment UUID; deterministic
  IV = `attachment_uuid[:12]`; retries create a new UUID.
- `skein-3xyu` — ingest runs only during authorized unlocked sessions; lock
  triggers immediate cancellation across `:app`/`:inference`/`:embedder`.
- `skein-4pqj` — RC production (E0.I23a) is split from RC validation (E0.I23b);
  E10.I15 becomes device-smoke of the validated RC.
- `skein-vhtu` — SQLCipher-primary; `DocumentsProvider` exposes vault content
  **as Markdown files** to other apps; no plaintext-Markdown-on-disk representation.

**Non-negotiables** (spec §2): no `INTERNET` permission, no cleartext-at-rest,
isolated inference/embedder processes stay isolated, model files remain hash-verified
before mmap, no Play Services, no telemetry.

---

## Table of contents

- [Cross-cutting: how the four designs fit together](#cross-cutting-how-the-four-designs-fit-together)
- [§1 — `skein-uo5n` Citation stability across re-ingestion](#1--skein-uo5n-citation-stability-across-re-ingestion)
- [§2 — `skein-st1r` Model verification and companion coverage](#2--skein-st1r-model-verification-and-companion-coverage)
- [§3 — `skein-pn1l` Binder aggregate byte limits and IPC contract](#3--skein-pn1l-binder-aggregate-byte-limits-and-ipc-contract)
- [§4 — `skein-7ki2` Export flow — provider, grants, plaintext lifetime, backup](#4--skein-7ki2-export-flow--provider-grants-plaintext-lifetime-backup)
- [§5 — Plan amendments needed](#5--plan-amendments-needed)

---

## Cross-cutting: how the four designs fit together

The four issues share three couplings that must be respected across sections;
each section calls out where its design closes on the shared surface.

1. **The `:app ↔ :inference` / `:app ↔ :embedder` IPC contract (§3)** carries
   the `LoadRequest` used by the model-load flow (§2) and the retrieval payload
   that produces citations (§1). All three sections converge on the same
   parcelable shapes; §2 tightens `LoadRequest` with companion fds and post-mmap
   digest fields, §1 adds retained excerpt bytes to the retrieval payload
   (bounded so §3's aggregate byte budget is not violated).
2. **The vault schema.** §1's `document_revisions` table is populated by the
   ingest pipeline; §4's `DocumentsProvider` reads the current revision when
   materializing Markdown; the export-staging record for a share URI (§4) is
   keyed by the same `(document_id, revision_hash)` tuple §1 introduces.
3. **The unlock lifecycle** (already locked by `skein-3xyu`). Lock cancels
   in-flight IPC (§3), aborts a pending model verify/load (§2), invalidates
   short-lived share grants (§4), and freezes citation resolution to the last
   known revision (§1).

Every Android-semantics claim below carries a `developer.android.com` URL.

---

## §1 — `skein-uo5n` Citation stability across re-ingestion

### 1.1 Restatement of the defect

Spec §5 defines `messages.retrieved_chunks JSON` as "chunk ids used as RAG
context". `chunks.id` is `INTEGER PRIMARY KEY`, and E2.I2's `replaceChunks`
migration deletes and re-inserts rows on re-ingestion (documents update on
edit, on paste-back import, on re-embed migration `E5.I18`). This means:

- A message stored last week with `retrieved_chunks: [147, 812]` — where 147
  was "the third paragraph of `note-a.md`" — will, after the user edits
  `note-a.md`, silently point at whatever new row happens to receive rowid
  147 (an unrelated document, or a wholly new chunk of the same document
  with different text). Nothing in the UI tells the reader that the source
  of a citation has moved out from under it.

This is a silent-corruption class bug: the user believed they saw a citation
"anchored" to particular text; on replay they see different text under the
same `[N]` marker with no warning. It also blocks any future v2 sync design
that assumes replayed chats are content-addressed.

### 1.2 Design decision

Adopt **option 3 from the issue** (retain excerpt + tuple). Concretely:

1. Introduce a new `document_revisions` table that records a content-addressed
   revision hash for every state a document has ever been in that was cited or
   retrieved. Revisions are created by the ingest pipeline when a document's
   `content_hash` changes; old revisions are retained as long as any
   `messages.retrieved_chunks` entry references them.
2. Replace `messages.retrieved_chunks` (opaque chunk-id JSON) with a JSON blob
   whose shape is a stable **citation record** carrying the excerpt at the time
   of the turn plus a `(document_id, revision_hash, locator)` tuple.
3. At chat-replay time, the UI compares the cited `revision_hash` against the
   document's current `content_hash`. Match → cite marker is "live"
   (click opens the current text at the recorded locator). Mismatch → cite
   marker shows a "source changed" badge; click opens a diff (cited excerpt on
   the left, current text at that locator on the right) inside the same
   preview tab surface used elsewhere.
4. Retention rule: a `document_revisions` row is kept as long as at least one
   `messages.retrieved_chunks` (or, later, an outbound share record — §4)
   still references it. Sweep via a foreign-key `ON DELETE RESTRICT` plus a
   nightly-during-unlock `documentRevisions_gc` that drops orphans.

Rationale:

- Bounded storage cost. Excerpts in messages are already unavoidable (RAG
  cited context needs to survive re-ingestion for audit anyway); the
  incremental cost is per-revision metadata rows, not per-chunk copies.
- The tuple `(document_id, revision_hash, locator)` is what "go to source"
  needs: the locator resolves against any revision (current or archived),
  and the diff view uses two revisions.
- No cross-turn re-verification cost. The revision hash is precomputed at
  ingest time; the chat surface only compares two hex strings.
- Compatible with `skein-3xyu`'s "ingest during authorized unlock only":
  revision creation is inside `IngestWorker` (which is gated by unlock),
  and the GC job is scheduled inside the same authorized-unlock window.
- Compatible with `skein-vhtu`'s "SQLCipher-primary": revisions live inside
  the encrypted DB alongside everything else. The `DocumentsProvider` (§4)
  serves the *current* revision of a note; a follow-up "export historical
  version" is deferred to v2.

### 1.3 Concrete architectural artifacts

#### Migration 003 — `document_revisions` + `messages.retrieved_chunks` shape

The excerpt in the citation record is truncated (target 512 chars, up to
1024) to protect §3's Binder aggregate byte budget. Longer answers cite
multiple record entries rather than one very long excerpt.

```sql
-- core/vault/src/main/resources/migrations/003_document_revisions.sql
-- Applied under PRAGMA user_version = 3. See plan §4.9.

CREATE TABLE document_revisions (
  document_id      TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  revision_hash    TEXT NOT NULL,          -- BLAKE3-256 hex over the canonicalized body_md + normalized frontmatter
  revision_ord     INTEGER NOT NULL,       -- monotonic within document_id; assigned by ingest
  body_md_snapshot TEXT NOT NULL,          -- exact bytes hashed. Not the excerpt; the whole body at revision time.
  frontmatter_snapshot JSON NOT NULL,
  captured_at      INTEGER NOT NULL,       -- epoch millis, from clock()
  reason           TEXT NOT NULL,          -- 'ingest' | 'reembed' | 'import' | 'share'
  PRIMARY KEY (document_id, revision_hash)
);
CREATE INDEX idx_document_revisions_doc ON document_revisions(document_id, revision_ord DESC);

-- chunks are still ephemeral; add a durable reverse pointer for the current revision
-- so retrieval can produce (revision_hash, locator) tuples cheaply.
ALTER TABLE chunks ADD COLUMN revision_hash TEXT
    REFERENCES document_revisions(revision_hash);
CREATE INDEX idx_chunks_revision ON chunks(revision_hash);

-- messages.retrieved_chunks now stores a citation-record JSON per spec §5.
-- SQLite has no JSON schema enforcement; enforcement is in the Kotlin serializer.
-- No column change is needed (already JSON), but a comment migration is applied
-- so tooling knows the new shape. Old rows are readable via a legacy adapter
-- (§1.5 migration/impact) that treats missing 'record_version' as 0.
```

Locators are stable across re-chunking because they anchor to byte offsets
into the revision's `body_md_snapshot`:

```json
// core/rag/src/main/resources/schema/citation-record-v1.schema.json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://aherrera.us/skein/schema/citation-record-v1.json",
  "title": "Skein retrieved_chunks payload v1",
  "type": "object",
  "additionalProperties": false,
  "required": ["record_version", "retrieved", "cited"],
  "properties": {
    "record_version": { "const": 1 },
    "retrieved": {
      "type": "array",
      "maxItems": 30,
      "items": { "$ref": "#/$defs/citation" }
    },
    "cited": {
      "description": "Subset of retrieved that the model actually cited via [N] markers.",
      "type": "array",
      "maxItems": 30,
      "items": { "type": "integer", "minimum": 1 }
    }
  },
  "$defs": {
    "citation": {
      "type": "object",
      "additionalProperties": false,
      "required": ["marker", "document_id", "revision_hash", "locator", "excerpt", "source_kind"],
      "properties": {
        "marker":         { "type": "integer", "minimum": 1, "maximum": 30 },
        "document_id":    { "type": "string", "pattern": "^[0-9a-f-]{36}$" },
        "revision_hash":  { "type": "string", "pattern": "^[a-f0-9]{64}$" },
        "locator": {
          "type": "object",
          "additionalProperties": false,
          "required": ["byte_start", "byte_end"],
          "properties": {
            "byte_start": { "type": "integer", "minimum": 0 },
            "byte_end":   { "type": "integer", "minimum": 0 },
            "chunk_ord":  { "type": "integer", "minimum": 0 }
          }
        },
        "excerpt":     { "type": "string", "maxLength": 1024 },
        "excerpt_hash":{ "type": "string", "pattern": "^[a-f0-9]{64}$" },
        "source_kind": { "enum": ["vector", "lexical", "graph", "rerank"] }
      }
    }
  }
}
```

#### Kotlin surfaces (illustrative)

```kotlin
// core/rag/src/main/kotlin/us/aherrera/skein/rag/cite/CitationRecord.kt
// PURELY illustrative — not code to compile in this branch.

data class Locator(val byteStart: Int, val byteEnd: Int, val chunkOrd: Int?)

data class Citation(
    val marker: Int,
    val documentId: DocId,
    val revisionHash: String,
    val locator: Locator,
    val excerpt: String,             // <= 1024 chars, UTF-8 safe truncation
    val excerptHash: String,         // BLAKE3-256(excerpt) for tamper detection at replay
    val sourceKind: SourceKind,      // vector | lexical | graph | rerank
)

data class CitationRecord(
    val recordVersion: Int = 1,
    val retrieved: List<Citation>,
    val cited: List<Int>,
)

sealed interface CitationResolution {
    data class Live(val current: RevisionRef, val cited: Citation) : CitationResolution
    data class SourceChanged(
        val cited: Citation,               // the excerpt shown at citation time
        val current: RevisionRef?,         // null when document deleted
        val currentSliceAtLocator: String?,// what the same byte range says now
    ) : CitationResolution
}

interface CitationResolver {
    /** O(1) per citation: two hash lookups + one string slice. */
    suspend fun resolve(record: CitationRecord, marker: Int): CitationResolution
}
```

#### Retrieval → citation flow (concise)

1. `RetrievalService.retrieveContext` (plan `E5.I13`) resolves each retrieved
   chunk to `(document_id, current_revision_hash, byte_start, byte_end)` using
   the new `chunks.revision_hash` column and `document_revisions`.
2. `PromptAssembler` (plan `E5.I15`) numbers them `[1..k]` and hands them to
   the inference IPC (§3). The excerpt travels through the assistant response
   parser (plan `E5.I16`) unchanged.
3. On `onDone`, the chat layer persists `CitationRecord` (retrieved list +
   cited markers) into `messages.retrieved_chunks`.
4. On chat replay, `CitationRenderer` runs `CitationResolver.resolve` for each
   `[N]` marker. Match → underline, no badge. Mismatch → underline + amber
   "source changed" badge, and the click opens a side-by-side view.

### 1.4 Tests required

Unit tests (JVM, no Android):

- `CitationRecordJsonTest` — round-trips every `record_version: 1` example
  fixture; rejects `record_version: 0`, unknown fields, oversized excerpts.
- `LocatorTest` — `body_md.substring(byte_start, byte_end)` handles multi-byte
  UTF-8 correctly (round-trip on Cyrillic, CJK, emoji fixtures).
- `Blake3RevisionHashTest` — canonicalization (LF-only line endings,
  frontmatter key sort) produces identical hashes for cosmetic-only edits.

Integration tests (Robolectric or `androidx.sqlite` `BundledSQLiteDriver`
in-memory):

- `DocumentRevisionsRepositoryTest` — `newRevision(docId, body_md, frontmatter)`
  is idempotent when content is unchanged; deleting a document cascades
  revisions; GC preserves any revision still referenced by a message.
- `CitationResolverTest` — after `updateBody` (which changes `content_hash`),
  a message's citations resolve as `SourceChanged` and the current-slice
  matches the new locator.
- `RetrievedChunkMigrationTest` — legacy rows (chunk-id-only JSON, treated as
  `record_version: 0`) render as "source unknown" badges without crashing.

Adversarial tests:

- `RevisionCollisionTest` — attempt to insert two revisions with the same
  `revision_hash` but different bodies; PK conflict blocks it (hash IS the
  content address).
- `ExcerptTamperTest` — mutate `excerpt` after persistence; `excerpt_hash`
  mismatch surfaces a distinct badge ("stored citation altered").

### 1.5 Migration / impact

Spec:

- Update §5 `messages.retrieved_chunks` prose to reference
  `citation-record-v1.schema.json`. **The spec DDL block does not need to
  change** — the column is already `JSON` — but the paragraph above it does.

Plan:

- `E0.I11` (migration 001) — no change; the new schema is migration 003.
- `E2.I2` / `E2.I4` — add `newRevision` + `getRevision` to `VaultRepository`;
  extend `replaceChunks` to accept a `revisionHash` argument.
- `E5.I13` (`RetrievalServiceImpl`) — populate the new `Citation` fields.
- `E5.I15` (`PromptAssembler`) — carry `(revision_hash, locator, excerpt)`
  through prompt assembly.
- `E5.I16` (citation parser) — emits `CitationRecord`; add the migration test
  for `record_version: 0`.
- New plan item `E5.I16b` (or renumbered): `CitationResolver` + UI badge.
- `E6.I8` (chat surface) — render "source changed" badge, wire diff sheet.
- `E10.I5` (retrieval eval) — gold set gets a re-ingestion case that asserts
  the badge shows.

`bd` items: the design is `docs`-labelled; implementation stays under
`skein-uo5n` until the code lands.

---

## §2 — `skein-st1r` Model verification and companion coverage

### 2.1 Restatement of the defect

Plan `E3.I5` verifies a model by streaming SHA-256 over a `ParcelFileDescriptor`
inside `:inference`, then hands the same fd to llama.cpp via
`/proc/self/fd/<n>`. The review flagged three separate holes:

1. **mmap is not immutability.** `mmap` with `MAP_PRIVATE` gives a copy-on-write
   snapshot **at write time to the mapping's own pages**, not a permanent
   snapshot of the file's contents. A `MAP_SHARED` mapping, or any read from
   an un-touched `MAP_PRIVATE` page after another writer modified the file,
   reflects the new bytes (Linux `mmap(2)`). Holding a `dup`'d fd preserves the
   inode identity of the file we hashed, but does not by itself prevent another
   process (or another `openat` from `:app`) from writing to the same inode.
2. **Companion-file hashes are advisory today.** `ModelManifest.companions`
   lists `tokenizer` / `config` / `mmproj` files with their own SHA-256, but
   `EmbedderLoadRequest` accepts each companion as a bare `ParcelFileDescriptor`
   with only the main-model hash guaranteed. A tampered `tokenizer.json` is
   used without verification.
3. **Digest match ≠ origin trust.** Even a matched hash proves only that the
   file bytes equal what the manifest asserts. Origin trust is a separate
   property that `skein-2zag`-adjacent sigstore verification is meant to
   provide, but the plan does not clearly separate the two checks in the load
   flow.

### 2.2 Design decision

Three interlocking rules:

1. **Import-into-immutable-store.** Model bytes never live at a user-writable
   SAF path once the app is done with them. `ModelManager.import` streams the
   SAF source into `filesDir/models/<id>/<id>.gguf` (already app-private per
   plan `E4.I5`), then sets the directory read-only for the app itself
   (`chmod 500`) and each imported file read-only (`chmod 400`) after import
   completes. `filesDir` is app-private by default; other apps and other users
   have no access without root (Android storage semantics). The imported
   directory is called the *immutable model store*; nothing but `ModelManager`
   (and only in dedicated re-import operations) can write into it.
2. **Verify-before-mmap AND verify-after-mmap, of the same bytes.** In addition
   to the streaming SHA-256 over the fd (plan `E3.I5`), the isolated service
   runs a second BLAKE3-256 digest over the actual mmap'd `MappedByteBuffer`
   *after* `mmap` returns and *before* it hands control to llama.cpp. If the
   bytes differ from what the fd digest saw, the load aborts with
   `ErrorCode.HASH_MISMATCH_POST_MMAP` and the service unloads. BLAKE3 is used
   for the post-mmap pass because it is faster than SHA-256 on aarch64 and
   because a different algorithm exposes any bit-flip a common `MessageDigest`
   bug would hide. This closes the TOCTOU-plus-inplace-write hole that
   `MAP_SHARED` or `MAP_PRIVATE`-before-touch reads permit.
3. **Every file loaded is manifest-covered.** `LoadRequest` /
   `EmbedderLoadRequest` (§3) carry an aggregate `ManifestBinding` — the
   manifest ID, the main sha256, the companion list with per-file sha256, and
   an `attestation_status` enum. The service verifies each fd against its
   declared sha256 before use. A companion whose sha256 is missing from the
   manifest is a hard load failure — no silent "trust adjacent files."

`skein-2zag`-adjacent sigstore attestation stays as it was: `E3.I6` verifies
the bundle offline against the pinned trust root and produces an
`AttestationStatus` (`Verified` / `Failed` / `Expired` / `Unavailable`). The
digest gate remains the *hard gate*; the attestation gate is a separate
`origin_trust: enum` that the UI surfaces as a badge ("verified by
`admin@aherrera.us` on 2026-08-14") and that policies may require for the
default-model slot.

Preventing in-place mutation while a model is loaded:

- The isolated service holds a `FileChannel.tryLock(0, Long.MAX_VALUE, shared=true)`
  advisory read lock on the imported file for the model's lifetime. Advisory
  locks are cooperative, so this alone does not stop a hostile actor; combined
  with the read-only permission and the app-private path it is a defense in
  depth: any code inside our app that tries to `openat(WRONLY)` this file
  during load fails at the permission check (EACCES), and any re-import call
  hitting `ModelManager` while the file is locked receives `ModelInUse`.
- Re-import over an in-use path is refused: `ModelManager.import` must be
  called with a fresh `id` (a new UUID) if the old model is still loaded.
  Delete-then-import is refused too — the delete call returns `ModelInUse`
  until `unload` completes.

References (Android):

- Isolated processes and IPC:
  https://developer.android.com/reference/android/R.attr#isolatedProcess ,
  https://developer.android.com/guide/components/aidl
- App-private storage:
  https://developer.android.com/training/data-storage/app-specific
- `ParcelFileDescriptor` semantics:
  https://developer.android.com/reference/android/os/ParcelFileDescriptor

### 2.3 Concrete architectural artifacts

#### Extended `ModelManifest` schema (delta on plan §4.8)

```json
// core/model/src/main/resources/schema/model-manifest-v2.schema.json (delta)
{
  "properties": {
    "manifest_version": { "const": 2 },
    "companions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["role", "file", "sha256", "size_bytes"],
        "additionalProperties": false,
        "properties": {
          "role":       { "enum": ["mmproj", "tokenizer", "tokenizer_config",
                                    "config", "generation_config", "license",
                                    "special_tokens_map"] },
          "file":       { "type": "string" },
          "sha256":     { "type": "string", "pattern": "^[a-f0-9]{64}$" },
          "size_bytes": { "type": "integer", "minimum": 1 },
          "required":   { "type": "boolean", "default": true }
        }
      }
    },
    "attestation": {
      "properties": {
        "covers": {
          "type": "array",
          "description": "Files whose sha256 is inside the attested manifest. When absent, defaults to main only.",
          "items": { "enum": ["main", "companions", "all"] }
        }
      }
    }
  }
}
```

Every file the service loads (main + each companion where `required: true` or
pulled by a runtime path) MUST appear in `companions` with sha256, otherwise
the manifest is rejected at import. `LICENSE` files are included in the
manifest for provenance even though they are not "loaded" in a technical
sense; the audit surface benefits from them being hashed.

#### `ManifestBinding` and expanded `LoadRequest` (Parcelable)

```kotlin
// core/ipc/src/main/kotlin/us/aherrera/skein/ipc/ManifestBinding.kt

@Parcelize data class ManifestFileRef(
    val role: String,                       // 'main' | 'mmproj' | 'tokenizer' | ...
    val fd: ParcelFileDescriptor,           // opened read-only by :app from the immutable store
    val expectedSha256: String,             // 64 hex, lowercase
    val expectedSizeBytes: Long,            // sanity + resource planning
) : Parcelable

@Parcelize data class ManifestBinding(
    val manifestId: String,                 // ties this load to a specific models row
    val manifestVersion: Int,               // 2 for the schema in §2.3
    val files: List<ManifestFileRef>,       // includes 'main' + every companion
    val attestation: AttestationRefParcel?, // sigstore bundle fd + covers
) : Parcelable
```

#### Load flow inside `:inference` (illustrative pseudocode)

```kotlin
override fun load(req: LoadRequest): Int {
    val binding = req.binding
    // 1. Verify each fd BEFORE mmap. Streaming SHA-256, constant-time compare.
    for (f in binding.files) {
        val actual = ModelVerifier.sha256(FileInputStream(f.fd.fileDescriptor))
        if (!constantTimeEqual(actual, f.expectedSha256)) return ErrorCode.HASH_MISMATCH
    }
    // 2. Read-lock the main file for the duration of the load.
    val mainFd = binding.files.first { it.role == "main" }.fd
    val ch = FileInputStream(mainFd.fileDescriptor).channel
    val lock = ch.tryLock(0L, Long.MAX_VALUE, /* shared = */ true)
        ?: return ErrorCode.MODEL_IN_USE
    // 3. mmap and re-digest the mapped bytes with a DIFFERENT algorithm.
    val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, ch.size())
    val postHash = Blake3.digestOf(mapped)
    if (!constantTimeEqual(Blake3.of(mainFd, binding.files.mainSha256), postHash))
        return ErrorCode.HASH_MISMATCH_POST_MMAP
    // 4. Hand the same fd to llama.cpp via /proc/self/fd/<dup>.
    val procPath = "/proc/self/fd/${dupFd(mainFd)}"
    ctx = LlamaNative.load(procPath, req.contextLength, req.threads, req.gpuLayers)
    // 5. Record the lock + mapped buffer as owned state; unload() releases both.
}
```

`Blake3.of(fd, expectedSha)` deterministically maps the SHA-256 known at
`ModelManifest` time to the BLAKE3 known at build time. The default-model
manifest files (`app/src/main/assets/models/*.skein.json`) ship both digests
so a well-formed manifest can be checked without pre-loading. A user-imported
model's post-mmap check computes both digests once at import and stores the
BLAKE3 in a new `models.post_mmap_blake3` column (migration 004).

#### Manifest-004 companion coverage of default models

The two default manifests (Qwen 2.5 3B Instruct Abliterated Q4_K_M, Gemma 4
E4B Q4_K_M) declare every companion the service ever opens: the `.gguf` main
file; the mmproj (Gemma with vision) if present; the tokenizer if used in an
ONNX embedder path; the LICENSE text. `E4.I5` refuses to register a model
whose manifest lacks a companion for a file that the load flow would need.

### 2.4 Tests required

Unit tests (JVM):

- `ManifestValidatorTest` — rejects manifest missing companion for a file the
  loader will need; accepts complete manifest; rejects unknown roles.
- `ConstantTimeCompareTest` — asserts the routine used is
  `MessageDigest.isEqual`, not `String.equals`.
- `Blake3VectorTest` — cross-check with a bundled test vector; ensures we do
  not silently switch algorithms.

Instrumented tests on emulator (dev tiny fixtures):

- `PostMmapDigestTest` — an in-tree malicious module writes a dev-only
  `writeAfterLock(fd, offset, byte)` shim between the pre-mmap hash and the
  mmap. The service must fail with `HASH_MISMATCH_POST_MMAP`. This test only
  compiles in the `dev` build type so the shim is not shipped.
- `CompanionMismatchTest` — swap a byte in `tokenizer.json` after fd open;
  expect `HASH_MISMATCH` with `role: "tokenizer"` in the error.
- `AdvisoryLockTest` — while `:inference` holds the read lock, `ModelManager`
  in `:app` cannot open the model for write; `delete` returns `ModelInUse`.
- `PermissionEnforcementTest` — after `import`, the model directory is `500`
  and files are `400`; attempts to `openat(O_WRONLY)` from the same UID fail
  with `EACCES`. (This test uses `Os.stat` and `Os.open`.)

Adversarial tests:

- `ToctouExtendedTest` (extends plan `E10.I16`) — the classic swap-file-under
  path scenario, plus a variant where a second fd is opened after the lock
  and used to try to `pwrite` new bytes (`EACCES`, blocked by permission),
  and a variant where the `MAP_PRIVATE` mapping is expected to isolate reads
  from later inode writes but does not (asserts our post-mmap digest catches
  this class).
- `AttestationDegradationTest` — a Sigstore bundle with an expired trust
  root produces `AttestationStatus.Expired` but the model still loads
  (the digest gate is the hard gate). A tampered-digest model refuses to
  load regardless of attestation state.

### 2.5 Migration / impact

Spec:

- §2.5 wording clarification: "All model files hash-verified before AND after
  mmap; sigstore attestation supported and preferred; **origin trust
  (attestation) and content trust (digest) are separate gates and both are
  surfaced in the model manager UI**."
- §6 wording: "Model file `mmap`'d read-only; hash verified against
  `models.sha256` before mmap **and re-verified over the mapped bytes with a
  distinct algorithm before use**; sigstore attestation checked when
  `attestation_url` is set."
- §9 threat model: add "in-place file mutation between hash check and use" as
  a mitigated attack path, referencing this section.

Plan:

- `E0.I15` (`ModelManifest` v1) — bump to v2 with the expanded `companions`
  and required-sha256 rule; parser must accept both versions in a transitional
  window and refuse to LOAD a v1 manifest that references any companion
  outside the manifest.
- `E0.I16` (AIDL contract) — `LoadRequest` and `EmbedderLoadRequest` gain a
  `ManifestBinding` field (§3 shows the exact shape after the IPC design lands).
- `E3.I5` (`ModelVerifier`) — split into `ModelVerifier.fdSha256` (existing)
  and `ModelVerifier.postMmapBlake3` (new). Add companion-verify helper.
- `E3.I6` (Sigstore) — no behavioral change; documentation clarification only.
- `E4.I3` / `E5.I1` — implement the new load flow. Both services enforce the
  read-only lock and the post-mmap check.
- `E4.I5` (`ModelManager.import`) — sets the immutable-store permissions and
  computes the BLAKE3 digest at import.
- Migration `004_post_mmap_blake3.sql` adds the column on `models`.
- `E10.I16` (TOCTOU test) — expanded per §2.4.

---

## §3 — `skein-pn1l` Binder aggregate byte limits and IPC contract

### 3.1 Restatement of the defect

Android's Binder driver enforces a **per-process transaction buffer of 1 MiB**
(Binder documentation and `TransactionTooLargeException`), shared across every
in-flight `oneway` and blocking transaction that a process is involved in:
https://developer.android.com/reference/android/os/TransactionTooLargeException
. The plan's current text (§4.7 and §4.7 note "clients batch ≤ 32 texts per
`embed`; the inference client never sends more than one image per message"),
combined with `GenerateRequest.images: List<ByteArray>` up to ~4 MiB inline,
means:

- A single request can exceed the buffer on its own if images push past 1 MiB.
- Two concurrent requests (e.g., a streaming chat + an ingest embedding call
  targeting `:embedder`) can each be individually under the limit and *still*
  fail with `TransactionTooLargeException` because the buffer is shared.
- Some Binder callbacks (`oneway` token batches from `:inference`) can be
  starved during backpressure with no visible signal.

The Parcelable round-trip tests in the plan do not exercise real IPC and will
not catch this class of failure.

### 3.2 Design decision

Lock ONE transport contract with four rules:

1. **Inline payload cap per transaction.** The sum of `Parcel` bytes in any
   single AIDL call (arguments AND response) is bounded to **32 KiB defensive
   (hard error above 128 KiB)**. This is at least an order of magnitude below
   the 1 MiB total, so N=8 concurrent transactions still fit. The cap is
   enforced at the client boundary before every call.
2. **Large payloads travel via `ParcelFileDescriptor` handles to
   `ashmem`-backed shared memory (`MemoryFile`) or app-private tmpfiles.**
   The Parcel carries only the fd (a few bytes) plus a size field. The service
   `mmap`s the region read-only, consumes it, and closes the fd. Image bytes,
   long text arrays for `embed`, and generation output for long tail cases
   all move over shared memory when they exceed the 32 KiB inline budget.
3. **Descriptor ownership is explicit.** The AIDL javadoc says: "The service
   OWNS every fd it receives and MUST `close()` after processing, success or
   failure." Callback fds (rare in this design; only `debugDumpStats` uses one)
   invert the rule. The client `dup`s only when it wants to hold on past the
   call; otherwise a receiving service is the last reference.
4. **Cancellation, backpressure, and death.** `cancel(requestId)` is
   oneway; the service acknowledges via `onDone(stopReason = CANCELLED)`.
   Backpressure on `oneway` `onTokens` callbacks: the callback buffer holds
   ≤ 8 batches; if the client's binder thread pool cannot keep up, the
   service drops the *oldest* batches and includes a `dropped: Int` field
   on the following `onTokens` so the UI can show a "output truncated for
   speed" note. `linkToDeath` on both directions: on service death, in-flight
   flows fail with `ServiceDied`; on client death, the service cancels every
   request owned by that client and unloads if no clients remain.

Aggregate byte budget:

- `:app` runs at most **one active generate** and **one active embed batch**
  at a time by design. The `RetrievalService` and `IngestPipeline` serialize
  their calls through a `Mutex` per-service (already implied by plan
  `E5.I13`).
- Under those constraints, worst-case concurrent traffic on `:app`'s
  binder buffer is:
  - `generate`: 32 KiB (prompt + messages inline) + one shared-memory image fd
    + streaming `onTokens` callbacks at ≤ 16 KiB each, ≤ 8 in flight.
  - `embed`: 32 KiB inline text array OR shared-memory fd + response byte[]
    (256 int8 × 32 texts = 8 KiB inline).
  - Total ≈ 200 KiB, well below 1 MiB with headroom for `:embedder` on the
    other service.

References (Android):

- Binder transaction limits and `TransactionTooLargeException`:
  https://developer.android.com/reference/android/os/TransactionTooLargeException
- `ParcelFileDescriptor` and its `MODE_READ_ONLY`, `dup`, and
  `createReliablePipe` semantics:
  https://developer.android.com/reference/android/os/ParcelFileDescriptor
- `MemoryFile` for ashmem:
  https://developer.android.com/reference/android/os/MemoryFile
- Bound service lifecycle and `linkToDeath`:
  https://developer.android.com/guide/components/bound-services
  and
  https://developer.android.com/reference/android/os/IBinder#linkToDeath(android.os.IBinder.DeathRecipient,%20int)

### 3.3 Concrete architectural artifacts

#### AIDL contract (v2 — supersedes plan §4.7 v1)

```aidl
// core/ipc/src/main/aidl/us/aherrera/skein/ipc/IInferenceService.aidl
package us.aherrera.skein.ipc;
import us.aherrera.skein.ipc.IInferenceCallback;
import us.aherrera.skein.ipc.LoadRequest;
import us.aherrera.skein.ipc.GenerateRequest;
import us.aherrera.skein.ipc.EngineStatus;

interface IInferenceService {
    /**
     * Sync. Verifies EVERY fd in req.binding.files against its expected sha256,
     * pre-mmap and post-mmap (see §2 of POST_REVIEW_RESOLUTIONS.md).
     * Returns ErrorCode.OK or an ErrorCode. Aggregate inline payload <= 32 KiB
     * (fds carry the bytes).
     */
    int load(in LoadRequest req);

    /**
     * Async. cb must be a fresh IInferenceCallback (client-owned).
     * req.messages+sampling <= 32 KiB inline; images/audio travel via
     * req.attachmentFds (shared-memory-backed).
     * Exactly one onDone or onError per requestId.
     */
    void generate(in GenerateRequest req, in IInferenceCallback cb);

    /** oneway. Service acknowledges by emitting onDone(stopReason=CANCELLED). */
    oneway void cancel(int requestId);

    /** Sync. Releases mmap, fd locks, KV cache. Idempotent. */
    void unload();

    /** Sync. Small. Never call while a generate is in flight (returns BUSY). */
    EngineStatus status();
}
```

```aidl
// IInferenceCallback.aidl
package us.aherrera.skein.ipc;
import us.aherrera.skein.ipc.GenStats;

oneway interface IInferenceCallback {
    /**
     * Batched. dropped > 0 iff the service dropped earlier batches under
     * backpressure. Client should update the visible "output truncated" state.
     * A single call is <= 16 KiB of pieces + ids.
     */
    void onTokens(int requestId, in String[] pieces, in int[] ids, int dropped);

    void onDone(int requestId, in GenStats stats);

    /** code is an ErrorCode. */
    void onError(int requestId, int code, String message);
}
```

```aidl
// IEmbedderService.aidl
package us.aherrera.skein.ipc;
import us.aherrera.skein.ipc.EmbedderLoadRequest;
import us.aherrera.skein.ipc.EmbedRequest;
import us.aherrera.skein.ipc.EmbedResult;

interface IEmbedderService {
    int load(in EmbedderLoadRequest req);

    /**
     * Texts either inline (<=32 KiB total) or in req.inputFd (shared memory).
     * Returns 256 int8 per text as one byte[] (<=8 KiB for the 32-batch max).
     * isQuery selects the "search_query: " prefix per model card.
     */
    EmbedResult embed(in EmbedRequest req);

    // extractEntities and rerank follow the same "inline or shared-memory" rule.
    // ...

    void unload();
}
```

#### Parcelable shapes (with shared-memory attachment refs)

```kotlin
// core/ipc/src/main/kotlin/us/aherrera/skein/ipc/Parcels.kt (v2)

@Parcelize data class SharedMemRef(
    val fd: ParcelFileDescriptor,   // MemoryFile.getFileDescriptor().dup() -> PFD
    val sizeBytes: Long,
    val mimeHint: String,           // 'image/png' | 'image/jpeg' | 'text/plain; charset=utf-8' | 'application/octet-stream'
    val role: String,               // 'image' | 'audio' | 'input-texts' | ...
) : Parcelable

@Parcelize data class LoadRequest(
    val binding: ManifestBinding,   // §2 — every fd + expected sha256
    val contextLength: Int,
    val threads: Int,
    val gpuLayers: Int,
    val embeddingMode: Boolean,
) : Parcelable

@Parcelize data class GenerateRequest(
    val requestId: Int,
    val messages: List<ChatMessageParcel>,      // capped by client; overflow -> attachmentFds
    val attachmentFds: List<SharedMemRef>,      // images, audio, oversized text
    val sampling: SamplingParcel,
) : Parcelable

@Parcelize data class EmbedRequest(
    val texts: List<String>,                    // when <=32 texts AND total serialized <=28 KiB
    val inputFd: SharedMemRef?,                 // else, populated instead of texts
    val isQuery: Boolean,
) : Parcelable

@Parcelize data class EmbedResult(
    val flat: ByteArray,                        // rowMajor int8[texts*256]; <= 8 KiB per call
    val droppedInputs: Int,                     // 0 in normal operation
) : Parcelable

object ErrorCode {
    const val OK = 0
    const val HASH_MISMATCH = 1
    const val HASH_MISMATCH_POST_MMAP = 7
    const val INVALID_MODEL = 2
    const val OOM = 3
    const val NOT_LOADED = 4
    const val BUSY = 5
    const val CANCELLED = 6
    const val TX_TOO_LARGE = 8
    const val MODEL_IN_USE = 9
    const val COMPANION_HASH_MISMATCH = 10
    const val INTERNAL = 99
}
```

#### Client-side transport rules (illustrative)

```kotlin
// core/inference/src/main/kotlin/us/aherrera/skein/inference/TransportRules.kt

object TransportRules {
    /** Hard cap; anything above must go through SharedMemRef. */
    const val INLINE_HARD_LIMIT_BYTES: Int = 32 * 1024
    /** Defensive per-call ceiling before we refuse to marshal (throws IllegalArgumentException). */
    const val INLINE_REFUSE_LIMIT_BYTES: Int = 128 * 1024
    /** Max token batches queued via oneway before we start dropping oldest. */
    const val MAX_INFLIGHT_TOKEN_BATCHES: Int = 8
    /** Max texts per embed call, regardless of transport. */
    const val MAX_TEXTS_PER_EMBED: Int = 32
}

fun buildEmbedRequest(client: EmbedderClient, texts: List<String>, isQuery: Boolean): EmbedRequest {
    require(texts.size <= TransportRules.MAX_TEXTS_PER_EMBED)
    val inlineBytes = estimateSerializedSize(texts) // conservative UTF-8 sizing
    return if (inlineBytes <= TransportRules.INLINE_HARD_LIMIT_BYTES) {
        EmbedRequest(texts = texts, inputFd = null, isQuery = isQuery)
    } else {
        val ref = client.writeSharedMemory(texts.joinToLengthPrefixedUtf8Bytes(),
            mimeHint = "text/plain; charset=utf-8", role = "input-texts")
        EmbedRequest(texts = emptyList(), inputFd = ref, isQuery = isQuery)
    }
}
```

#### Service-side ownership + cancellation

```kotlin
// inference-service/src/main/kotlin/.../InferenceWorker.kt (illustrative)

override fun generate(req: GenerateRequest, cb: IInferenceCallback) {
    val id = req.requestId
    require(current.get() == null) { onError(cb, id, ErrorCode.BUSY, "one active request"); return }
    val slot = RequestSlot(id, cb, cb.asBinder().linkToDeath({ cancel(id) }, 0))
    current.set(slot)

    // Attachments — service OWNS these fds.
    val attachments = req.attachmentFds.map { ref ->
        LoadedAttachment(role = ref.role, bytes = readSharedMemory(ref)).also {
            ref.fd.close()
        }
    }

    worker.submit {
        try {
            runInference(req.messages, req.sampling, attachments,
                onBatch = { pieces, ids, dropped ->
                    if (slot.aborted) throw CancellationException()
                    cb.onTokens(id, pieces.toTypedArray(), ids.toIntArray(), dropped)
                },
                onEnd = { stats -> cb.onDone(id, stats) })
        } catch (c: CancellationException) {
            cb.onDone(id, GenStats(stopReason = "CANCELLED", /* ... */))
        } catch (t: Throwable) {
            cb.onError(id, mapError(t), t.message.orEmpty())
        } finally {
            current.compareAndSet(slot, null)
        }
    }
}
```

### 3.4 Tests required

Unit tests (JVM):

- `TransportRulesTest` — `buildEmbedRequest` picks inline vs shared-memory
  correctly at every boundary; refuses `> 128 KiB` inline.
- `SharedMemWriterTest` — writes a payload, reads it back through a
  `ParcelFileDescriptor` opened read-only in the same process.
- `ErrorMappingTest` (extends plan `E4.I4`) — every new `ErrorCode` maps to a
  distinct `InferenceException` subclass.

Instrumented tests on emulator (real IPC across service processes):

- `AggregateByteBudgetTest` — six concurrent `embed` calls, each with a
  payload sized just below the inline cap, all succeed (previously would
  push aggregate past the 1 MiB buffer if inline). This is a direct
  regression test for the `pn1l` defect.
- `LargePayloadFdTest` — a 4 MiB image sent as `SharedMemRef` succeeds;
  the same 4 MiB sent inline is refused by the client library
  (`IllegalArgumentException` at the `TransportRules` guard), never reaching
  Binder.
- `TokenBackpressureTest` — the client callback thread pool is blocked
  artificially; the service drops the oldest batches and the next `onTokens`
  arrives with `dropped > 0` and correct pieces.
- `CallbackDeathTest` — client's `IInferenceCallback` binder proxy is killed
  mid-generation; service observes `DeathRecipient`, cancels the request,
  releases the model lock, unloads if last client.
- `ServiceDeathRecoveryTest` — kill `:inference` via `Process.killProcess`
  in dev; active `stream` fails with `ServiceDied`; next `load` succeeds.

Adversarial tests:

- `CrossServiceIsolationTest` — `:inference` cannot invoke any AIDL entry
  point in `:embedder` (they are only reachable from `:app`), asserted by
  attempting `bindService` from the isolated process and observing
  `SecurityException`. Confirms process topology.
- `FdOwnershipTest` — service does not close a fd on the error path? The
  test kills the request mid-processing and checks that `SharedMemRef.fd`
  ends up closed (leak check via `Os.stat("/proc/<pid>/fd")`).

Real IPC is exercised by binding the actual `:inference` and `:embedder`
services under `androidTest`, not by round-tripping Parcelables in-process.

### 3.5 Migration / impact

Spec:

- §4.1 process topology paragraph — add a sentence noting the aggregate
  Binder buffer is 1 MiB per process, and that large payloads travel via
  shared-memory fds. Point at this document.

Plan:

- `E0.I16` (AIDL contract) — bump to v2; regenerate parcels; both service
  modules move to `SharedMemRef` payloads. Update §4.7 in the plan doc
  wholesale (see §5 amendments).
- `E4.I3` / `E4.I4` — implement `TransportRules`, `onTokens(dropped)`,
  `linkToDeath` on both directions.
- `E5.I1` (`:embedder`) — accept `EmbedRequest.inputFd`; `MemoryFile` reader.
- `E5.I3` — batch chunker respects `MAX_TEXTS_PER_EMBED`.
- Add plan item under `E10` (testing): `E10.I18 — Real-IPC transport tests`
  containing the `AggregateByteBudgetTest` etc.

---

## §4 — `skein-7ki2` Export flow — provider, grants, plaintext lifetime, backup

### 4.1 Restatement of the defect

Spec §2.9/§9 promises DocumentsProvider-only exposure with tight grants;
plan `E2.I6` implements a permission-guarded DocumentsProvider with
`grantUriPermissions="false"`; plan `E6.I16` (share source) then adds a
FileProvider under `<cache-path>` for writing staged plaintext for
system-picker share flows. The review flagged four problems:

1. Two providers is one too many for v1. `DocumentsProvider` + `FileProvider`
   duplicates the surface without a stated boundary; either the spec is
   wrong or one is unnecessary.
2. `android:grantUriPermissions="false"` disables ad-hoc client grants
   entirely unless the manifest declares explicit
   `<grant-uri-permission android:pathPrefix="..." />` subsets:
   https://developer.android.com/guide/topics/manifest/provider-element
   . The current design does neither, so a real system-picker share flow
   would fail on some paths.
3. `E6.I16`'s "clean cache on next launch" is not a guaranteed 10-minute
   plaintext lifetime: an uninstall or a device that never wakes leaves the
   plaintext for days.
4. `dataExtractionRules` (plan `E3.I7`) must cover the export staging dir
   on every supported API level (30..latest) or a Seedvault D2D pass will
   grab plaintext.

### 4.2 Design decision

**Pick DocumentsProvider as the single canonical export provider in v1.** No
`FileProvider` in the shipped app. Rationale:

- `skein-vhtu` already locks the vault to SQLCipher-primary with
  `DocumentsProvider` as the outward face; `DocumentsProvider` gives
  system-picker semantics, persistable and non-persistable grants, and MIME
  routing without a second surface.
- `FileProvider` is designed for cache-backed one-off shares. Its features
  (Uri grants, `<cache-path>`, `<paths>` config) are all reachable via
  `DocumentsProvider` when we author the provider ourselves.
- Removing `FileProvider` also removes the "share/cache dir with plaintext"
  problem entirely, because the DocumentsProvider streams from the vault
  through a `ParcelFileDescriptor.createReliablePipe()` and never lands
  plaintext on disk *unless* a specific "hard export" (offline copy to
  Downloads via the system file picker) is invoked. Section 4.3 defines the
  staged plaintext lifetime for that narrow case only.

**Deferred to v1.1 (not v1):** an `Intent.ACTION_SEND` "share as file"
outward flow that has to hand another app a Uri whose backing bytes exist
somewhere the receiver can read after our activity is gone. Two candidates,
both deferred:

- (a) DocumentsProvider `content://` URIs granted read-only to the receiver
  via `FLAG_GRANT_READ_URI_PERMISSION` on the outgoing intent (allowed even
  with `grantUriPermissions="false"` if a `<grant-uri-permission>` subset is
  declared and covers the path — see 4.3 for the exact rules v1.1 will use).
- (b) Copy to Downloads via the framework `ACTION_CREATE_DOCUMENT` picker
  (user-driven; no persistent grant leakage).

For v1, spec §3.1 "Share source: any note/chat/output out to any app"
narrows to text-and-clipboard flows plus `ACTION_CREATE_DOCUMENT` for
file-backed exports, which routes plaintext to a user-chosen SAF location
outside our storage entirely (no staging in our cache). The v1.1 provider
share design is prototyped, not shipped.

**Grant subsets in the manifest.** DocumentsProvider stays with
`grantUriPermissions="false"` and `permission="android.permission.MANAGE_DOCUMENTS"`.
A `<grant-uri-permission>` subset is declared for read-only child paths under
`Notes/` and `Attachments/` so v1.1 can add ad-hoc grants without a manifest
change. Persistable grants remain refused (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
is never offered; the provider never advertises `Root.FLAG_SUPPORTS_RECENTS`
in a way that would tempt a client to persist).

**Plaintext lifetime for the narrow staging window we do use.** The one place
plaintext lands on disk during v1 is `ACTION_CREATE_DOCUMENT` (user picks a
save location; we `openOutputStream` on their URI and stream from the vault).
This is user-authorized; the file lives at the user-chosen location and
Skein does not track it. That is intentional and matches the spec:
"user owns the vault format".

For any *internal* staging (e.g., PDF export via `PrintManager`, which spools
to a temporary file managed by the platform), the timers below apply:

- WorkManager job `StagedPlaintextSweeper` scheduled at import via
  `OneTimeWorkRequest.Builder(...).setInitialDelay(10, MINUTES).build()`
  keyed to a unique stage id. Docs:
  https://developer.android.com/reference/androidx/work/WorkManager
  and
  https://developer.android.com/reference/androidx/work/OneTimeWorkRequest
- Persisted state in `export_stages` (SQLCipher table) records
  `(stage_id, path, created_at, expires_at)`.
- `BootReceiver` (`RECEIVE_BOOT_COMPLETED`) rescans `export_stages` on cold
  start and deletes any expired plaintext files, then re-schedules any
  unexpired stages with the residual delay. Docs:
  https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED
- On lock (`UnlockManager` → `Locked`), an immediate sweep runs regardless
  of `expires_at` (locked = zero tolerance for staged plaintext).
- On lock invalidation (`KeyPermanentlyInvalidatedException`), the sweep
  also runs.

**Backup exclusions covering every API level 30..latest.** Manifest uses
`android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+) *and*
retains `android:fullBackupContent="@xml/backup_rules_legacy"` (API 30) so
older SDKs still exclude the staging dir. Both files list the export staging
directory (`cache/staging_export/`) as excluded, along with the paths plan
`E3.I7` already excludes.

References (Android):

- `<provider>` element and `<grant-uri-permission>`:
  https://developer.android.com/guide/topics/manifest/provider-element
- DocumentsProvider:
  https://developer.android.com/reference/android/provider/DocumentsProvider
  and
  https://developer.android.com/guide/topics/providers/document-provider
- `dataExtractionRules`:
  https://developer.android.com/guide/topics/data/autobackup
  and specifically the `data_extraction_rules.xml` reference:
  https://developer.android.com/about/versions/12/backup-restore#xml-changes
- WorkManager `OneTimeWorkRequest.Builder.setInitialDelay`:
  https://developer.android.com/reference/androidx/work/OneTimeWorkRequest.Builder#setInitialDelay(long,%20java.util.concurrent.TimeUnit)
- Direct Boot and `RECEIVE_BOOT_COMPLETED`:
  https://developer.android.com/training/articles/direct-boot
- `ACTION_CREATE_DOCUMENT`:
  https://developer.android.com/training/data-storage/shared/documents-files#create-file

### 4.3 Concrete architectural artifacts

#### Manifest snippet — provider + grants + boot receiver

```xml
<!-- app/src/main/AndroidManifest.xml (illustrative delta) -->
<application
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules_legacy"
    android:hasFragileUserData="true">

    <provider
        android:name="us.aherrera.skein.vault.provider.VaultDocumentsProvider"
        android:authorities="us.aherrera.skein.documents"
        android:exported="true"
        android:permission="android.permission.MANAGE_DOCUMENTS"
        android:grantUriPermissions="false">
        <intent-filter>
            <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
        </intent-filter>
        <!-- v1.1 ad-hoc grants will target these path prefixes. -->
        <grant-uri-permission android:pathPrefix="/document/note:" />
        <grant-uri-permission android:pathPrefix="/document/att:" />
        <!-- Persistable grants are still refused programmatically. -->
    </provider>

    <receiver
        android:name="us.aherrera.skein.export.stage.BootReceiver"
        android:enabled="true"
        android:exported="false"
        android:directBootAware="false">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        </intent-filter>
    </receiver>
</application>
```

Notes:

- `directBootAware="false"` is deliberate: the vault is credential-encrypted
  and cannot be unlocked in direct-boot mode, so we run only after the user
  reaches the Unlock screen. Files in `cache/staging_export/` are also
  credential-encrypted and inaccessible in direct-boot mode by default.
- No FileProvider entry. Removing it is a v1 acceptance criterion.

#### `data_extraction_rules.xml` and legacy fallback

```xml
<!-- app/src/main/res/xml/data_extraction_rules.xml (API 31+) -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" />
        <exclude domain="file"     path="keys/" />
        <exclude domain="file"     path="models/" />
        <exclude domain="file"     path="attachments/" />
        <exclude domain="file"     path="vault.db" />
        <exclude domain="file"     path="vault.db-wal" />
        <exclude domain="file"     path="vault.db-shm" />
        <exclude domain="file"     path="vault.db-journal" />
        <exclude domain="external" path="." />
        <exclude domain="root"     path="." />
        <exclude domain="sharedpref" path="unlock_state.xml" />
        <!-- Export staging is under cache; cache is not backed up by default,
             but we exclude it explicitly for defense in depth. -->
        <exclude domain="cache"    path="staging_export/" />
    </cloud-backup>
    <device-transfer>
        <!-- Same set as cloud-backup for Seedvault D2D coverage. -->
        <exclude domain="database" />
        <exclude domain="file"     path="keys/" />
        <exclude domain="file"     path="models/" />
        <exclude domain="file"     path="attachments/" />
        <exclude domain="file"     path="vault.db" />
        <exclude domain="file"     path="vault.db-wal" />
        <exclude domain="file"     path="vault.db-shm" />
        <exclude domain="file"     path="vault.db-journal" />
        <exclude domain="external" path="." />
        <exclude domain="root"     path="." />
        <exclude domain="cache"    path="staging_export/" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<!-- app/src/main/res/xml/backup_rules_legacy.xml (API 30 fallback) -->
<full-backup-content>
    <exclude domain="database" />
    <exclude domain="file"     path="keys/" />
    <exclude domain="file"     path="models/" />
    <exclude domain="file"     path="attachments/" />
    <exclude domain="file"     path="vault.db" />
    <exclude domain="file"     path="vault.db-wal" />
    <exclude domain="file"     path="vault.db-shm" />
    <exclude domain="file"     path="vault.db-journal" />
    <exclude domain="external" path="." />
    <exclude domain="root"     path="." />
    <exclude domain="cache"    path="staging_export/" />
</full-backup-content>
```

Both files are shipped; the platform picks by API level automatically.

#### Migration 005 — `export_stages` and WorkManager pairing

```sql
-- core/vault/src/main/resources/migrations/005_export_stages.sql
CREATE TABLE export_stages (
    stage_id     TEXT PRIMARY KEY,          -- UUIDv7
    path         TEXT NOT NULL,             -- absolute inside cache/staging_export
    origin       TEXT NOT NULL,             -- 'pdf_export' | 'docx_export' | 'markdown_export'
    document_id  TEXT REFERENCES documents(id) ON DELETE CASCADE,
    revision_hash TEXT REFERENCES document_revisions(revision_hash) ON DELETE CASCADE, -- §1 tie-in
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,          -- created_at + 10 min by default
    swept        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_export_stages_expires ON export_stages(expires_at);
```

#### Kotlin surfaces — sweeper and boot receiver (illustrative)

```kotlin
// feature/shell/src/main/kotlin/us/aherrera/skein/export/stage/StagedPlaintextSweeper.kt

class StagedPlaintextSweeper(
    context: Context,
    params: WorkerParameters,
    private val repo: ExportStageRepository,
    private val clock: () -> Long,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val stageId = inputData.getString(KEY_STAGE_ID) ?: return Result.failure()
        val stage = repo.get(stageId) ?: return Result.success() // already gone
        // Fixed 10-minute cap unless already elapsed.
        if (clock() >= stage.expiresAt) {
            deleteAndMark(stage)
            return Result.success()
        }
        // Reschedule with residual delay (paranoid path — should not usually run).
        return Result.retry()
    }

    companion object {
        const val KEY_STAGE_ID = "stage_id"
        fun enqueue(context: Context, stageId: String, delayMs: Long) {
            val req = OneTimeWorkRequestBuilder<StagedPlaintextSweeper>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_STAGE_ID to stageId))
                .addTag("staged-plaintext")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sweep-$stageId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

// feature/shell/src/main/kotlin/us/aherrera/skein/export/stage/BootReceiver.kt

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo: ExportStageRepository = get(context)
                val now = System.currentTimeMillis()
                repo.listUnswept().forEach { stage ->
                    if (now >= stage.expiresAt) repo.deleteAndFile(stage)
                    else StagedPlaintextSweeper.enqueue(context, stage.stageId,
                        delayMs = stage.expiresAt - now)
                }
            } finally { pending.finish() }
        }
    }
}
```

`UnlockManager.lock()` (plan `E3.I3`) adds an `onLocked` hook that invokes
`ExportStageRepository.sweepAll()` synchronously before the master key
zeroes.

### 4.4 Tests required

Unit tests (JVM):

- `DataExtractionRulesTest` — parse both `data_extraction_rules.xml` and
  `backup_rules_legacy.xml`; assert the same exclude paths appear in each
  and that `staging_export/` is present in both.
- `ExportStageRepositoryTest` — inserts, updates, sweeps by expiry, cascades
  when a `document_revisions` row is deleted (§1 tie-in).
- `StagedPlaintextSweeperTest` — runs with a `FakeClock`; deletes when
  expired; retries when premature.

Robolectric / integration tests:

- `ManifestPolicyTest` (extends plan `E3.I1`) — exported set is exactly
  `{ MainActivity, VaultDocumentsProvider, SkeinVoiceInteractionService,
    BootReceiver (not exported: verify) }`; no `FileProvider` present;
  `grantUriPermissions="false"` on the DocumentsProvider; two
  `<grant-uri-permission>` children with the correct path prefixes.
- `BootReceiverTest` — a receiver invoked with `LOCKED_BOOT_COMPLETED`
  and then `BOOT_COMPLETED` triggers exactly one sweep pass.

Instrumented tests (emulator on API 30, 33, 35 to hit both rules files):

- `SystemPickerFlowTest` — a companion test APK issues the intent
  `ACTION_OPEN_DOCUMENT` with our provider's tree; the picker returns a
  URI; the caller reads bytes; a subsequent
  `takePersistableUriPermission` call throws (proving persistent grants
  refused). This is the "real system-picker flow" the review demanded.
- `ExportPdfPlaintextLifetimeTest` — trigger a PDF export, confirm a
  `staging_export/*.pdf` file exists, advance WorkManager clock
  (`TestDriver.setInitialDelayMet`) past 10 minutes, confirm the file is
  gone and the row is `swept = 1`.
- `LockDuringExportTest` — a `StagedPlaintextSweeper` is scheduled;
  `UnlockManager.lock()` is invoked before its delay elapses; the file is
  deleted immediately.
- `BackupExfiltrationTest` (extends plan `E10.I8`) — writes a marker string
  through the export path and asserts it does not appear in `bmgr` backup
  output nor in the Seedvault manual-checklist artifact.

Adversarial:

- `CrashDuringExportTest` — begin an export, kill the app process before
  the sweeper is scheduled; on next boot the receiver picks up the
  orphaned stage and schedules it or sweeps it if expired.
- `PicklePersistabilityTest` — a co-installed test app requests
  `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` on our tree URI and calls
  `takePersistableUriPermission`; the call throws `SecurityException`.

### 4.5 Migration / impact

Spec:

- §2.9 — refine to: "All data is app-private, encrypted at rest with
  StrongBox-backed keys, exposed to other apps only via a `DocumentsProvider`.
  No `FileProvider` in v1."
- §3.1 share source — narrow to text and `ACTION_CREATE_DOCUMENT` for v1;
  cross-app URI sharing deferred to v1.1.

Plan:

- `E2.I6` — restated with the manifest snippet in §4.3 (grants subset,
  persistable refusal, MIME rules).
- `E6.I15` (share target receiver) — unchanged; still an inbound path.
- `E6.I16` (share source) — **substantially rewritten**: remove FileProvider;
  keep in-memory text sharing; replace file/PDF/DOCX outward flows with
  `ACTION_CREATE_DOCUMENT` (user-driven) plus staged plaintext under
  `cache/staging_export/` with the 10-minute sweeper.
- `E3.I7` (`dataExtractionRules`) — replaced by the two-file variant in
  §4.3; adds `backup_rules_legacy.xml` and asserts both are shipped in the
  APK.
- `E3.I3` (`UnlockManager`) — add `onLocked` hook that sweeps
  `ExportStageRepository`.
- `E10.I8` (backup exfiltration) — test set expanded per §4.4.
- `E10.I9` (`DocumentsProvider` grant tests) — persistable-refusal test
  moves into instrumented flow with the companion test APK.
- New plan item `E6.I16b` (or renumbered): `BootReceiver` +
  `StagedPlaintextSweeper` implementation and tests.

---

## §5 — Plan amendments needed

The plan is not edited by this design pass. The next reconciliation pass
should apply these deltas verbatim:

1. **§4.7 (AIDL contract)** — replace v1 AIDL with the v2 shapes in §3.3
   (this doc), including `ManifestBinding`, `SharedMemRef`, `onTokens`
   `dropped` field, new `ErrorCode` values, and `TransportRules` cap.
2. **§4.8 (`ModelManifest`)** — bump to `manifest_version: 2`; add
   `companions.size_bytes` and `companions.required`; add
   `attestation.covers`. Note both v1 and v2 accepted by parser during
   the transitional window.
3. **§4.9 (SQLite DDL)** — add migrations 003 (document revisions), 004
   (`models.post_mmap_blake3`), 005 (`export_stages`).
4. **E0.I15** — parser accepts v1 and v2; refuses to LOAD a v1 manifest
   that references companions.
5. **E0.I16** — dependency stays under it; test targets updated to v2.
6. **E2.I2 / E2.I4** — `VaultRepository` gains `newRevision`,
   `getRevision`, and revision-aware `replaceChunks`.
7. **E2.I6** — manifest snippet in §4.3 is the authoritative form.
8. **E3.I5** — split into fd-hash and post-mmap-hash halves; add
   companion-verify helper; add `read_lock` via `FileChannel.tryLock`.
9. **E3.I6** — clarify hard-gate vs origin-trust separation in text only.
10. **E3.I7** — replace with the two-file rules variant in §4.3.
11. **E3.I3** — `onLocked` sweeps `ExportStageRepository`; documented in
    the acceptance criteria as a new bullet.
12. **E4.I3 / E4.I4** — implement TransportRules + backpressure; wire
    post-mmap check; `linkToDeath` both directions.
13. **E4.I5** — `ModelManager.import` moves imports into
    `filesDir/models/<id>/`, chmod 400 files + 500 dir, records
    `post_mmap_blake3` at import.
14. **E5.I1** — accept `EmbedRequest.inputFd`; `MemoryFile` reader; use
    v2 AIDL.
15. **E5.I13** — populate `Citation` shape with locator + excerpt.
16. **E5.I16** — persists `CitationRecord` (§1 schema); parser test
    updated for legacy record migration.
17. **E6.I16** — rewritten per §4.5.
18. **New E6.I16b** — `BootReceiver` + `StagedPlaintextSweeper`.
19. **E10.I5** — retrieval eval adds "re-ingestion causes source-changed
    badge" case.
20. **E10.I8** — expand backup exfiltration to include the staging dir.
21. **E10.I9** — companion test APK exercises real system-picker flow;
    persistable refusal asserted from a client APK.
22. **E10.I16** — expand TOCTOU test with the post-mmap-modification
    variant.
23. **New E10.I18** — Real-IPC transport tests (aggregate byte budget,
    shared-mem fds, backpressure, callback death).

All amendments are compatible with the four already-locked decisions
(`wa1l`, `3xyu`, `4pqj`, `vhtu`).

---

**End of document.**
