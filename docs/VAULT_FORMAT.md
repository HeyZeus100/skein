# Vault Format

On-disk layout, encryption, data model, and wire format for Skein vaults. This document describes the shipped surface as of 2026-09-21.

## Status

**[shipped]** where noted below — true of `main` today, verifiable by reading the source files listed. **[v1 design, in progress]** claims are locked into the design spec and plan but not yet landed in the codebase; they are tracked as open `bd` issues.

## Table of contents

1. [Layout and files](#layout-and-files)
2. [SQLCipher database](#sqlite-cipher-database)
3. [Frontmatter wire format](#frontmatter-wire-format)
4. [Document IDs (UUIDv7)](#document-ids-uuidv7)
5. [Attachment container (SKAT)](#attachment-container-skat)
6. [Vault-ZIP export format](#vault-zip-export-format)
7. [Migrations and versioning](#migrations-and-versioning)
8. [What is not stable in v1](#what-is-not-stable-in-v1)

---

## Layout and files

**[shipped]** A vault occupies app-private storage (`Context.getFilesDir()` on Android) and comprises these paths:

- `vault.db` — SQLCipher-encrypted database (see section 2)
- `attachments/` — directory of encrypted attachment blobs, named `<uuidv7>` with no extension
- `keys/key-envelope.v1` — **[shipped]** the vault master key, wrapped under the two `AndroidKeyStore` Layer-0 aliases (biometric-bound and device-credential-bound; StrongBox where available). It is read by `VaultKeyProvider` *before* `vault.db` can be opened — the same 32-byte master keys the database — so it lives beside the database rather than inside it (`docs/design/ATTACHMENT_ENCRYPTION.md` §3.4, 2026-09-20 amendment). Binary, big-endian: 8-byte magic `SKEINKEY`; `u16` format version (`1`); `u32 key_version`; `i64 created_at` (Unix ms); one flags byte (bit 0 = StrongBox-backed); a factor count (`2`); then one record per factor — factor id (`0x01` biometric, `0x02` device credential), alias, wrapped bytes, wrap IV and optional tag, each `u16`-length-prefixed, zero length meaning "this factor is currently dead" (the GCM tag rides inside the wrapped bytes in v1); and a trailing SHA-256 over everything before it. Written atomically (temp file in `keys/`, fsync, rename). It holds only Keystore-wrapped bytes, never the plaintext master. Source: `core/vault/src/main/kotlin/app/skein/core/vault/key/FileMasterKeyStorage.kt`. Excluded from cloud backup and device transfer by the `keys/` rule (`docs/BACKUP_EXCLUSIONS.md`).
- Other app-private files reserved for future use (model caches, etc.)

No user-readable Markdown or configuration files live on disk inside the vault. The on-disk layout is opaque; the Markdown representation is materialized on demand via `ExportService.exportMarkdown()` or `ExportService.exportVaultZip()` (see section 6).

**[v1 design, in progress]** App-private storage is enforced by the Android OS — no other app can read these paths without root. Export to user-accessible storage (Downloads, Documents) goes through `DocumentsProvider` or an explicit user-initiated export action.

---

## SQLCipher database

**[shipped]** The vault database uses SQLCipher (AES-256-CTR page-level encryption, via `sqlcipher://` URI). A fresh vault migrates to `PRAGMA user_version = 7` (migrations 001, 003 and 007 — see section 7).

### Database schema

Migration files: `core/vault/src/main/resources/migrations/001_initial.sql` (the v1 schema, locked by the design spec §5) plus `003_document_revisions.sql` and `007_drop_attachment_master_key.sql` (see section 7). The tables below describe the schema after all of them have applied.

#### `documents` (primary table)

| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT PRIMARY KEY | UUIDv7 (see section 4) — lowercase RFC 9562 format |
| `kind` | TEXT NOT NULL | One of `note`, `chat`, `attachment`, `aiout` |
| `title` | TEXT NOT NULL | User-facing name; used for archive filename when exported |
| `body_md` | TEXT | Markdown body; `NULL` for `kind='attachment'` |
| `created_at` | INTEGER NOT NULL | Unix milliseconds since epoch |
| `updated_at` | INTEGER NOT NULL | Unix milliseconds since epoch |
| `persona_id` | TEXT REFERENCES personas(id) | May be `NULL`; references the persona system prompt |
| `frontmatter` | JSON | YAML frontmatter serialized as JSON (see section 3) |
| `content_hash` | TEXT | Content address. For every non-`attachment` kind this **is** the document's current `revision_hash` — BLAKE3-256 over the canonicalized `body_md` plus normalized frontmatter (migration 003; see `document_revisions` below). For `attachment` it is SHA-256 over the blob's plaintext bytes. |
| `mime_type` | TEXT | Present only when `kind='attachment'`; e.g. `image/png`, `application/pdf` |
| `blob_size` | INTEGER | Present only when `kind='attachment'`; plaintext size in bytes |

Indexes: `documents_updated` (by `updated_at` DESC), `documents_persona` (by `persona_id`), `documents_title_nocase`, `documents_kind_updated`.

#### `chunks` (retrieved content, for RAG)

| Column | Type | Notes |
|--------|------|-------|
| `id` | INTEGER PRIMARY KEY | Internal row ID (not user-visible) |
| `doc_id` | TEXT REFERENCES documents(id) ON DELETE CASCADE | Parent document |
| `ord` | INTEGER NOT NULL | Ordinal within the document (0-based) |
| `text` | TEXT NOT NULL | Chunk plaintext (used for BM25 FTS5 and display) |
| `token_count` | INTEGER | Cached token count (model-dependent) |
| `embedder_id` | TEXT | Model ID that produced the embedding |
| `embedder_version` | INTEGER | Embedder version (for cache invalidation) |
| `revision_hash` | TEXT | Added by migration 003: the `document_revisions.revision_hash` this chunk was cut from, so retrieval can emit a `(revision_hash, locator)` citation tuple without re-hashing the document. Carries no foreign key — see migration 003's header for why. **[shipped]** as of migration 008 (`skein-zx15`): `IngestSteps.indexLexical` stamps it from `VaultRepository.currentRevision(docId)?.revisionHash` on every ingest pass. |
| `byte_start` | INTEGER | **[shipped]** Added by migration 008 (`skein-zx15`, folding in `skein-s9hm`): the chunk's UTF-8 byte offset into `documents.body_md`, derived from `core/rag`'s `Chunk.start` (a UTF-16 **char** offset, which disagrees with the byte offset for any non-ASCII body). Nullable — null exactly when `byte_end` is. |
| `byte_end` | INTEGER | **[shipped]** Added by migration 008; the matching UTF-8 byte offset for `Chunk.end`. `[byte_start, byte_end)` is the byte-anchored locator range `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 describes. |

Indexes: `chunks_doc` (by `doc_id`), `idx_chunks_revision` (by `revision_hash`).

Virtual tables:
- `chunks_fts` — FTS5 external-content table over `chunks.text` for keyword search (BM25)
- `chunks_vec` — vec0 (SQLite-vec) virtual table for similarity search; dimension 256 (int8 quantized), cosine distance metric

#### `messages` (chat history)

| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT PRIMARY KEY | UUIDv7 |
| `chat_doc_id` | TEXT REFERENCES documents(id) ON DELETE CASCADE | Parent chat document (`kind='chat'`) |
| `role` | TEXT NOT NULL | `user` or `assistant` |
| `content_md` | TEXT NOT NULL | Message body (Markdown) |
| `model_id` | TEXT | Model ID (for assistant messages) |
| `retrieved_chunks` | JSON | Citation record for the turn. Two shapes are readable: **citation-record-v1** (`record_version: 1`) — `{retrieved: [{marker, document_id, revision_hash, locator: {byte_start, byte_end, chunk_ord?}, excerpt, excerpt_hash, source_kind}], cited: [marker]}`, per `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 — and the legacy pre-003 shape, a bare array of `chunks.id` values, treated as `record_version: 0`. SQLite enforces no JSON schema; the codec `core/model/.../CitationRecordJson.kt` is the enforcement point. See also PRIVACY.md §1. |
| `created_at` | INTEGER NOT NULL | Unix milliseconds since epoch |

Indexes: `messages_chat` (by `chat_doc_id`, `created_at`).

#### `document_revisions` (citation stability, migration 003)

Content-addressed snapshots of every state a document has been in that could have been cited. Added by `003_document_revisions.sql` (`skein-uo5n`, design `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3).

| Column | Type | Notes |
|--------|------|-------|
| `document_id` | TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE | Parent document |
| `revision_hash` | TEXT NOT NULL | BLAKE3-256 hex over the canonicalized `body_md` + normalized frontmatter — the content address |
| `revision_ord` | INTEGER NOT NULL | Monotonic within `document_id`; the highest ord is the most recently captured revision |
| `body_md_snapshot` | TEXT NOT NULL | The canonical body that was hashed — the whole body, not an excerpt |
| `frontmatter_snapshot` | TEXT NOT NULL | The canonical frontmatter JSON that was hashed |
| `captured_at` | INTEGER NOT NULL | Unix milliseconds since epoch |
| `reason` | TEXT NOT NULL | One of `ingest`, `reembed`, `import`, `share` |

Primary key: `(document_id, revision_hash)` — the hash *is* the content address, so re-writing content a document has held before reuses its row rather than appending a duplicate. Indexes: `idx_document_revisions_doc` (by `document_id`, `revision_ord` DESC).

**Revision hash (normative).** BLAKE3-256, lowercase hex, over `"skein/revision/v1\0"` ‖ `u64le(len(frontmatter))` ‖ canonical frontmatter ‖ canonical body, all UTF-8, where the canonical body folds `\r\n` and `\r` to `\n` (a null body is the empty string) and the canonical frontmatter is compact JSON with object keys sorted recursively and the `id` key removed (it always equals `documents.id`, and keeping it would make two byte-identical notes hash differently). The single implementation is `RevisionHashing` in `core/model/src/main/kotlin/app/skein/core/model/Revisions.kt`; `Blake3` beside it is a pure-Kotlin BLAKE3-256 pinned to the published test vectors. Consequences: a CRLF↔LF-only edit, a frontmatter key reordering, a retitle, and a `persona_id` change all leave the revision — and therefore every citation into the document — untouched; a body or frontmatter-value edit re-addresses it.

**Write path.** `VaultRepositoryImpl` (and its `InMemoryVaultRepository` twin) captures a revision on `createDocument`, `updateBody`, `updateFrontmatter`, and on the transcript rewrite inside `appendMessage`, inside the same transaction as the `documents` write. Capture is skipped when the content address did not move. Attachments never get rows.

**Chat snapshot bound (`skein-a2yr`).** For a `kind = 'chat'` document, `body_md_snapshot` is stored as the empty string rather than the re-materialized transcript. `appendMessage` recomputes the whole transcript on every turn, so archiving it in full every turn would make `document_revisions` storage quadratic in the number of turns (`003_document_revisions.sql`'s "Chat snapshot bound" header note). The row is still captured — `currentRevision`/`getRevision` still return it, and `revision_hash` is still the exact BLAKE3-256 of the real transcript — so a citation into a chat turn still resolves correctly via `revisionMatches` (a direct `documents.content_hash` comparison that never reads this table). Only the archived diff-view copy is skipped: reading an *old* chat revision's `bodyMdSnapshot` back returns `""`, not its historical text. No UI reads an archived chat snapshot today (the "source changed" diff view, plan `E5.I16b`/`E6.I8`, is not yet landed), so nothing currently regresses; a future diff view over chat history would need to reconstruct the old transcript from `messages` by timestamp instead of reading this column, exactly as the design that motivated this bound intends.

**Read path.** `VaultRepository.currentRevision(id)` returns the row whose hash equals the document's current `content_hash`; `getRevision(id, hash)` returns an archived one for the diff view; `revisionMatches(citation)` is the replay check — two hex strings compared, no re-hashing. A mismatch is what the chat surface renders as the "source changed" badge (plan `E5.I16b`/`E6.I8`, not yet landed).

**Retention (`skein-a2yr`). [shipped]** A revision row lives as long as it is a document's current revision (its hash equals `documents.content_hash`) *or* it is referenced by at least one `messages.retrieved_chunks` citation-record-v1 entry (§1.2 step 4). `VaultRepository.sweepUnreferencedRevisions()` enforces this: it decodes every message's citation payload via `CitationRecordJson.decode` (never a string match against the JSON text — that reference lives inside a JSON payload, which SQLite cannot express as a foreign key) and deletes every row that is neither. It is idempotent, returns only a count (never content — spec §9), and is invoked by `app/.../ingest/IngestScheduler.runPending` at most once per unlocked session, inside the same authorized-unlock maintenance pass ingest runs in (`docs/design/LOCK_POLICY_INDEXING.md`) — never at lock time, and never a reason to extend the vault key's lifetime. `ON DELETE CASCADE` from `documents` remains the *immediate* removal path when a whole document is deleted; the sweep only ever cleans up revisions whose document still exists.

**Excerpt lifetime (not swept — `skein-koda`).** A citation-record-v1 entry's `excerpt` (up to 1024 chars of the cited document's text, §1.3) is a second, independent copy of that text living inside the `messages` row itself, not in `document_revisions`. The sweep above never touches `messages`, so deleting or editing away the source document does **not** scrub the excerpt out of past chat citation records — a user who removes a sensitive paragraph, or deletes the note entirely, will still find that paragraph's text quoted in any chat that once cited it. This is a known, documented gap (`docs/PRIVACY.md` §1), not an oversight: scrubbing it needs a user-visible action ("purge history for this note") that is out of this sweep's scope and tracked as a follow-up.

#### `personas`

| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT PRIMARY KEY | Persona identifier |
| `name` | TEXT NOT NULL | Display name |
| `system_prompt` | TEXT | LLM system prompt |
| `default_model` | TEXT | Default model ID for this persona |
| `created_at` | INTEGER | Unix milliseconds since epoch |

#### `models`

| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT PRIMARY KEY | Model identifier |
| `path` | TEXT NOT NULL | Filesystem path (`filesDir/models/<id>/model.gguf`) |
| `sha256` | TEXT NOT NULL | Content hash, verified on load |
| `attestation_url` | TEXT | Sigstore attestation URL (optional) |
| `format` | TEXT NOT NULL | `gguf` or `onnx` |
| `capabilities` | JSON | Model capabilities (e.g. `{"chat": true, "embedding": false}`) |
| `size_bytes` | INTEGER | Model file size |
| `imported_at` | INTEGER | Unix milliseconds since epoch |
| `name` | TEXT NOT NULL | Display name (added in v1) |
| `context_length` | INTEGER | Context window size; default 16384 |
| `companions` | JSON | Companion files (e.g. mmproj for vision models) |
| `license_spdx` | TEXT | SPDX identifier or free-text license |

#### `ingest_queue`

| Column | Type | Notes |
|--------|------|-------|
| `doc_id` | TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE | Document pending re-index |
| `reason` | TEXT | Why it was queued (`created`, `updated`, etc.) |
| `queued_at` | INTEGER | Unix milliseconds since epoch |
| `attempts` | INTEGER NOT NULL DEFAULT 0 | **[shipped]** Added by migration 008 (`skein-zx15`): consecutive mandatory-step (lexical/link) ingest failures, via `VaultRepository.recordIngestFailure`. `IngestPipeline` drops the entry after the 3rd. Every `INSERT OR REPLACE` into this table (the `documents_ai_ingest`/`documents_au_ingest` triggers, `ENQUEUE_REEMBED_ALL`) resets it to 0 — a fresh queue entry gets a fresh retry budget. |

#### `edges` (wikilinks, tags, entity graph)

| Column | Type | Notes |
|--------|------|-------|
| `src_id` | TEXT NOT NULL | Source document ID |
| `dst_id` | TEXT NOT NULL | Destination document ID |
| `kind` | TEXT NOT NULL | Edge type: `wikilink`, `tag`, `backlink`, etc. |
| `weight` | REAL | Edge weight for PageRank (default 1.0) |
| `created_at` | INTEGER | Unix milliseconds since epoch |

Primary key: `(src_id, dst_id, kind)`. Indexes: `edges_dst` (by `dst_id`, `kind`).

#### `entities` (extracted named entities)

| Column | Type | Notes |
|--------|------|-------|
| `id` | INTEGER PRIMARY KEY | Internal ID |
| `canonical_name` | TEXT NOT NULL | Entity text |
| `entity_type` | TEXT NOT NULL | GLiNER type (e.g. `PERSON`, `LOCATION`) |
| `first_seen` | INTEGER | Unix milliseconds since epoch |

Unique constraint: `(canonical_name, entity_type)`.

#### `attachment_master_key` and `attachment_keys` — **removed** (migration 007, `skein-7d0l`)

**[shipped]** Both tables were dropped by `007_drop_attachment_master_key.sql` (`PRAGMA user_version = 7`) and no longer exist as of that migration. They were the key-wrapping tables originally sketched for attachment content encryption (see section 5 and `docs/design/ATTACHMENT_ENCRYPTION.md` §3.4), superseded before either was ever populated:

- `attachment_master_key` became vestigial as of `skein-txrh`: the wrapped vault master it was designed to hold is persisted in `keys/key-envelope.v1` (section 1) instead, because the same master keys `vault.db` itself and a row inside the database could never be read before the database is opened.
- `attachment_keys` was never populated by any shipped code: the real attachment store, `FileAttachmentStore` (`core/vault/.../blob/FileAttachmentStore.kt`), derives a fresh per-write content key via HKDF-SHA256 from the vault master key and a random per-file salt stored in the container header itself (see "Attachment container (SKAT)" below), and persists no wrapped key material anywhere.

`attachment_keys.master_key_version`'s foreign key to `attachment_master_key(key_version)` was resolved by dropping the child table first, then the parent, inside 007's single transaction — SQLite only checks a foreign key against the *referencing* table's rows, so no `PRAGMA foreign_keys` toggling or table-rebuild dance was needed even with `PRAGMA foreign_keys = ON` (section "Encryption and locking" / §4.9).

### Encryption and locking

**[v1 design, in progress]** The SQLCipher passphrase is derived from a hardware-backed key (StrongBox) that is unwrapped only after biometric authentication and held in memory while the vault is unlocked. When the vault is locked, this key material is wiped from memory.

---

## Frontmatter wire format

**[shipped]** Every document (note, chat, attachment, aiout) serialized as Markdown carries a YAML frontmatter block with this wire format and canonical key order.

### Canonical keys

Defined in `core/model/src/main/kotlin/app/skein/core/model/Transfer.kt` (`FrontmatterKeys`):

| Key | Type | Required | Notes |
|-----|------|----------|-------|
| `id` | string | Yes | UUIDv7, lowercase RFC 9562 format (see section 4) |
| `kind` | string | Yes | One of `note`, `chat`, `attachment`, `aiout` |
| `title` | string | Yes | Document title |
| `created` | ISO-8601 timestamp | Yes | RFC 3339 UTC (e.g. `2026-09-20T12:00:00Z`), from `Instant.toString()` |
| `updated` | ISO-8601 timestamp | Yes | RFC 3339 UTC |
| `persona` | string | No | Persona name; omitted when the document has no persona |
| `tags` | YAML flow sequence | No | Lowercased tag names; e.g. `[example, fixture]` |
| `source` | string | No | Document ID (UUIDv7) of the source attachment (PDF, image) for AI-extracted notes (AIOUT/imported-ATTACHMENT); omitted for ordinary notes |

### Canonical order

When rendering frontmatter to Markdown, keys appear in this order: `id`, `kind`, `title`, `created`, `updated`, `persona`, `tags`, `source`. Any keys outside this set (from imported or user-edited documents) are sorted alphabetically after the canonical keys and replayed verbatim to preserve any complex YAML syntax not part of the codec's subset.

### Parser and codecs

**[shipped]** The codec is hand-rolled (no third-party YAML library, per supply-chain guardrails) and lives in `core/vault/src/main/kotlin/app/skein/core/vault/codec/Frontmatter.kt`. It handles:

- Scalar values: strings (quoted and unquoted), numbers, booleans, and ISO-8601 timestamps (no stripping of whitespace — `Instant.toString()` is parsed as-is)
- Flow lists: `tags: [a, b, c]` and block-style lists (`- a` / `- b`)
- Quoted strings: `"` delimiters with `\"` and `\\` escape sequences
- Opaque passthrough: any frontmatter outside the recognized subset (nested mappings, block scalars) is captured verbatim and replayed on re-render so no data is silently dropped

### Canonical example

**[shipped]** The following note represents every field from `FrontmatterKeys` except the optional `source` key:

```yaml
---
id: 018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b
kind: note
title: Example Note
created: 2026-09-20T12:00:00Z
updated: 2026-09-20T12:00:00Z
persona: default
tags: [example, fixture]
---
This is the body of the canonical example note. `docs/VAULT_FORMAT.md`
(`E9.I5`) reproduces this exact text as its worked frontmatter example.
```

(This example is pinned as `FrontmatterExamples.NOTE` in `core/model/src/main/kotlin/app/skein/core/model/Transfer.kt` and is CI-tested to match this document.)

### Obsidian compatibility

**[shipped]** The frontmatter subset (scalars, arrays, timestamps) is valid YAML and is readable by Obsidian when the vault directory is opened as an Obsidian vault. Obsidian understands `id` (as an internal link target), `tags` (for tag-based backlinks), and any other scalar/array key in the frontmatter. It does not understand `kind`, `persona`, or `source`, but does not break on them either — they are inert YAML fields from Obsidian's perspective.

---

## Document IDs (UUIDv7)

**[shipped]** Every document has a stable UUIDv7 (RFC 9562 §5.7) that is used as the primary key in the database and appears in the frontmatter `id` field and as the attachment filename. IDs never change for the lifetime of the document, even after edits.

### Bit layout

Generated and formatted by `core/vault/src/main/kotlin/app/skein/core/vault/id/Uuid7.kt`:

- **Bits 127–80 (48 bits):** Unix milliseconds since epoch
- **Bits 79–76 (4 bits):** Version (fixed `0111` = 7)
- **Bits 75–64 (12 bits):** Monotonic counter (sub-millisecond uniqueness within one process)
- **Bits 63–62 (2 bits):** Variant (fixed `10`)
- **Bits 61–0 (62 bits):** Random bits (from `SecureRandom`)

### Canonical string format

**[shipped]** Lowercase, 36-character `8-4-4-4-12` hexadecimal with hyphens, per RFC 9562:

```
018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b
```

### Monotonicity and collision resistance

**[shipped]** The counter ensures strict monotonicity even within a single millisecond and across calls in the same process. If the counter would overflow (>4095 in a single millisecond), `lastMs` is advanced by one and the counter resets — a "borrowed" future millisecond. This also transparently absorbs backward clock jumps: the emitted timestamp field never regresses, so the canonical hex string is always strictly greater than the previous call's output.

---

## Attachment container (SKAT)

**[shipped]** Each file in the `attachments/` directory is a streaming, chunked container named `SKAT` (Skein-Chunked-Attachment, defined in `core/vault/src/main/kotlin/app/skein/core/vault/blob/SkatFormat.kt`). **Layout updated v1 → v2 (2026-09-21, skein-yn8d/0nh8):** 48-byte salted header, per-chunk final flag in AAD, HKDF info string includes file salt, reader termination from authenticated flag only.

### File layout

A SKAT file is a sequence of:

1. **Header (48 bytes, fixed layout)**
2. **Chunks (repeating: final flag + plaintext length + encrypted plaintext + authentication tag)**

### Header

| Offset | Size | Field | Value |
|--------|------|-------|-------|
| 0 | 4 | Magic | `SKAT` (ASCII) |
| 4 | 1 | Version | 2 (current) |
| 5 | 4 | Chunk size (little-endian) | 1,048,576 (1 MiB) |
| 9 | 16 | File salt | Random per-write (SecureRandom), fresh on each write |
| 25 | 8 | Total plaintext bytes (little-endian) | Length of the original unencrypted file — **unauthenticated** |
| 33 | 15 | Reserved | All zeros |

**Static header (bytes 0–24):** The first 25 bytes (magic, version, chunk size, file salt) form the "static header" and are known before streaming begins. All 25 bytes are fed into every chunk's AAD. The file salt (offset 9–24) is random and fresh per write, making each key unique even if the same attachment ID is overwritten out-of-band (backup restore, file manager, filesystem write).

**Total plaintext bytes (offset 25–32):** Patched in after the attachment is closed, once the total is known. Deliberately placed *after* the static prefix because it cannot be authenticated by any chunk tag (the reader does not have it until reading completes). The field serves `size(id)` for O(1) length lookups only; the reader never consults it for framing or termination.

### Chunk framing

Plaintext is split into chunks of up to 1 MiB each:

- **Chunk index 0:** bytes 0–1,048,575 of plaintext
- **Chunk index 1:** bytes 1,048,576–2,097,151 of plaintext
- (and so on)

The last chunk may be shorter than 1 MiB. Every container ends with exactly one chunk carrying the final flag, including an empty attachment (a single final frame with `plain_len = 0`).

Each chunk frame is:

| Field | Size | Notes |
|-------|------|-------|
| Final flag | 1 byte | `0x00` interior chunk, `0xFF` final chunk |
| Plain length (LE) | 4 bytes | Plaintext length in this chunk (0 to `chunk_size`) |
| Ciphertext | variable | AES-256-GCM ciphertext |
| GCM tag | 16 bytes | Authentication tag (128 bits) |

Interior chunks always have `plain_len == chunk_size`. Only the final-flagged chunk may have `0 ≤ plain_len < chunk_size`.

### Per-attachment key derivation

**[shipped]** Each attachment write derives a unique content encryption key from the vault master key, the attachment UUID, and a fresh random file salt.

**Derivation (v2, skein-yn8d):**

```
file_salt = 16 fresh SecureRandom bytes (stored in header at offset 9)
info = "skein-attachment-v2" || file_salt (36 bytes total)
content_key = HKDF-SHA256(
  salt        = vault_master_key,
  ikm         = attachment_uuid.utf8Bytes,
  info        = info,
  L           = 32 bytes
)
```

The salt (master key) and content key are wiped immediately after derivation. The file salt is stored in the container header and is therefore authenticated by every chunk tag (via the static header in the AAD).

**Distinction from v1:** v1 derived the key deterministically from `(master, id)` alone, making any two writes to the same ID produce an identical key. v2's random per-write salt ensures that even two writes to the same ID derive distinct keys, closing a threat vector where backup restore or filesystem overwrite could land two containers with shared GCM `(key, nonce)` pairs.

**v1 compatibility:** Containers with version 1 are refused outright with `AttachmentException.UnsupportedVersion`. No released Skein vault exists, so no migration is needed. Carrying a v1 reader would keep both the old key derivation and the old termination semantics reachable from released code.

Implementation: `core/vault/src/main/kotlin/app/skein/core/vault/blob/FileAttachmentStore.kt`, using the hand-rolled `Hkdf.kt` (RFC 5869).

### AAD composition (v2, skein-0nh8)

The Additional Authenticated Data for chunk `i` is:

```
aad = static_header(25) || chunk_index_LE(8) || final_flag(1) || plain_len_LE(4)
```

(Total 38 bytes.)

All four components are authenticated together by the GCM tag, ensuring:
- The file salt (inside static header) cannot be swapped without breaking every tag.
- The chunk sequence cannot be reordered.
- The final flag is authenticated, so "this is the end of the attachment" is a cryptographic statement.
- The plaintext length in each chunk cannot be altered without causing tag verification to fail.

### Nonce (IV)

**[shipped]** The GCM nonce is 12 bytes: the chunk index (8 bytes, little-endian) zero-padded to 12 bytes. Chunk 0 has nonce `0x00000000_00000000_00000000`, chunk 1 has nonce `0x01000000_00000000_00000000`, etc.

```
nonce = chunk_index_LE(8) || 0x00000000
```

### Reader termination and error handling

**[shipped]** The reader terminates when it encounters a chunk frame with the final flag set to `0xFF`. Termination **always** comes from the authenticated final flag, never from the `totalPlaintextBytes` header field.

- **Normal termination:** A chunk with `finalFlag = 0xFF` is read and authenticated. The reader returns all decrypted bytes seen so far.
- **Data after final chunk:** If the file pointer is not at EOF after a final-flagged frame is authenticated, the file is corrupt → `AttachmentCorruptException("data after final chunk")`.
- **EOF without final flag:** If EOF is reached while reading a chunk header or before any chunk has the final flag set → `AttachmentTruncatedException`.
- **Truncated chunk:** If a frame header promises more ciphertext bytes than the file holds → `AttachmentTruncatedException`.
- **Tag verification failure:** If the GCM tag does not verify (including any bit flip in the AAD, frame header, or ciphertext) → `AttachmentCorruptException`.

### Write-once enforcement

**[shipped]** Once an attachment is written to `attachments/<uuid>`, it cannot be overwritten. Any attempt to write a second attachment with the same UUID raises `AttachmentException.AlreadyExists`. This is enforced by a TOCTOU-minimal check immediately before the atomic move.

Before v2, write-once was a critical defense against GCM `(key, nonce)` reuse. v2's random per-write salt makes the write-once check redundant for GCM safety; it is retained for data-loss prevention (an attachment ID identifies exactly one plaintext for its lifetime).

---

## Vault-ZIP export format

**[shipped]** `ExportService.exportVaultZip()` produces a ZIP archive that contains the entire vault in plaintext (for backup, transfer, or archival). The archive is byte-deterministic: two exports of an unchanged vault produce identical bytes (mod per-device timezone handling of fixed DOS-epoch timestamps).

### Layout

The ZIP archive contains:

```
<title_0>.md                   # First document (notes/chats in title-sorted order)
<title_1>.md
...
attachments/<uuid_0>.<ext>     # Attachment blobs (id-sorted)
attachments/<uuid_1>.<ext>
...
.skein/manifest.json           # Metadata manifest
```

Document filenames are derived from their `title` field, sanitized for safety, and de-duplicated with a `.1`, `.2` suffix if needed. Attachment filenames use the UUID and a file extension inferred from the MIME type (e.g. `.png`, `.pdf`).

### Manifest format

**[shipped]** The `.skein/manifest.json` file is a JSON object with these keys:

```json
{
  "schemaVersion": 1,
  "documents": [
    {
      "id": "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b",
      "kind": "note",
      "path": "Example_Note.md"
    },
    ...
  ],
  "attachments": [
    {
      "id": "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4b5c",
      "path": "attachments/018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4b5c.png",
      "mime": "image/png"
    },
    ...
  ],
  "personas": [
    "default",
    "research"
  ]
}
```

Fields:
- `schemaVersion`: Current version is 1
- `documents`: Array of exported documents with their IDs, kinds, and ZIP paths
- `attachments`: Array of exported attachments with their IDs, paths, and MIME types
- `personas`: Sorted list of distinct persona names used in the vault

### Entry timestamps

**[shipped]** All ZIP entry timestamps are set to `1980-01-01T00:00:00` (DOS-epoch floor, 315,532,800,000 milliseconds since Unix epoch). This ensures byte-identical output across exports of an unchanged vault, useful for diffing backups. The timestamp is set via `ZipEntry.setTime(millis)` and converted through the JVM/device's default timezone — byte-identical output holds per-device/per-CI-run rather than across differently-configured timezones globally.

---

## Migrations and versioning

**[shipped]** The vault database tracks its schema version via `PRAGMA user_version` (an unencrypted integer at the head of every SQLCipher file). The value is set to 1 on first initialization and is incremented with each migration.

### Migration discovery and loading

**[shipped]** Migrations are discovered and applied by `Migrator` (plan task `E2.I2`, not yet in the codebase but designed and specified in `core/vault/src/main/resources/migrations/`). The authoritative list of migrations is `INDEX.txt`:

```
# Migration manifest (E2.I2 / skein-5my)
# Add new migrations by appending a line here AND adding the file below.
001_initial.sql
003_document_revisions.sql
007_drop_attachment_master_key.sql
008_ingest_attempts.sql
```

Each migration is a file named `NNN_<description>.sql` where `NNN` is a zero-padded integer (001, 002, etc.). Migrations are applied in INDEX.txt order (sorted numerically by `NNN`, not by manifest line order). Statements within a migration are separated by the `--;` sentinel at end-of-line (not a bare `;`, which also terminates inner statements inside multi-line trigger bodies); comment lines (`--`) and blank lines are ignored.

### Current migrations

**[shipped]** Five migrations exist:

- `001_initial.sql` — v1 schema (see section 2)
- `003_document_revisions.sql` — adds `document_revisions` and `chunks.revision_hash` for citation stability across re-ingestion (`skein-uo5n`, design `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3; see section 2's `document_revisions` entry above)
- `005_export_stages.sql` — adds `export_stages` and `idx_export_stages_expires`, the durable record of plaintext spooled to `cache/staging_export/` that the `StagedPlaintextSweeper`/`BootReceiver`/on-lock sweep delete on a bounded timer (`skein-0m1z`, design `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.3). Filled a number that was RESERVED for it while 007 and 008 had already landed — see the ledger note below for why that no longer risks a silent skip.
- `007_drop_attachment_master_key.sql` — drops the vestigial `attachment_master_key` table and the never-populated `attachment_keys` table (`skein-7d0l`; see section 2's `attachment_master_key` entry above). `PRAGMA user_version` reaches 7, not 2, because migration numbers 002, 004 and 006 are reserved by landed plan/design docs (`skein-voys`) for not-yet-landed migrations (`002_attestation_status`, `004_post_mmap_blake3`, `006_recovery_drafts`) and taking one of them here would collide when those land.
- `008_ingest_attempts.sql` — adds `ingest_queue.attempts` (the persisted E5.I10 bounded-retry counter) and `chunks.byte_start`/`chunks.byte_end` (the UTF-8 byte offsets `skein-s9hm` flagged as missing from 003) — `skein-zx15`. This is the v1 plan's own `E5.I10` migration, originally slotted as `003_ingest_attempts.sql`; `skein-voys` tracked the renumbering once `skein-uo5n` took 003 for `document_revisions` first, and 008 is the first number free of every other reservation above.

Migrations apply in ascending numeric order, so on a fresh database 003 runs before 005, which runs before 007 and 008; a device that installed at v1 runs 003, 005, 007, then 008, and reaches the same schema. Future migrations are tracked in the plan and design docs; 002, 004 and 006 above are reserved but not yet in-tree.

**Gap-filling a reserved number: the `schema_migrations` ledger (`skein-0m1z`, `skein-p8rn`).** `Migrator` no longer judges a migration solely by `PRAGMA user_version` (a single high-water mark). It also maintains a `schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER)` ledger table — created by `Migrator` itself, not by a numbered migration, so it exists before any migration is judged — and treats a migration as pending iff its version is absent from the ledger. This is what a reserved number landing *after* a higher one has already shipped needs: under the old `version > user_version` rule, a reserved number like 005 (which landed with 007 and 008 already in-tree) would be applied on a fresh install but silently SKIPPED on any database that had already reached the higher `user_version` — 5 is never greater than 8. The ledger tracks exact membership instead, so a gap-filler is applied exactly once no matter how many higher-numbered migrations already shipped.

A database migrated entirely before this ledger existed has `user_version` set but no `schema_migrations` row. `Migrator` seeds the ledger for such a database on its first `migrate()` call under the new code — but not by naively marking every migration with `version <= user_version` as applied, which would wrongly mark a never-applied gap-filler as done (the two histories "005 ran, then 007, then 008" and "007 ran, then 008, with 005 never applied" both end at `user_version` 8 and are indistinguishable by that number alone). Instead, seeding checks each candidate migration's own schema effects (the table it creates, the table it drops, or the column it adds) against the database's live schema, and only marks it applied if those effects are actually observable — see `Migrator`'s KDoc for the full algorithm.

The ledger fixes *whether* a migration eventually runs, not *when* relative to higher-numbered migrations that already shipped ahead of it: a gap-filler discovered on an already-migrated database necessarily applies chronologically after them, regardless of its version number. `005_export_stages.sql` is safe under this (it references only `documents`, from 001, and is independent of 003/007/008 in either direction — see its header). A future reserved migration (002, 004 or 006) that needed to run *before* an already-shipped higher-numbered migration's schema change would not be safe here; that residual ordering hazard is tracked separately (`skein-ncdf`) and is not something the ledger alone can close.

### Migration changelog

- **003** (`skein-uo5n`) — adds `document_revisions` (content-addressed body/frontmatter snapshots) and the `chunks.revision_hash` reverse pointer; changes the *meaning* of `documents.content_hash` for non-attachment documents to the BLAKE3-256 revision hash, and the *shape* of `messages.retrieved_chunks` to citation-record-v1 (the legacy bare-chunk-id array stays readable as `record_version: 0`). No column is dropped and no 001 DDL is edited.
- **007** (`skein-7d0l`) — drops `attachment_master_key` and `attachment_keys` (superseded by the `keys/key-envelope.v1` file and `FileAttachmentStore`'s per-write HKDF derivation, respectively; neither table was ever populated by shipped code).
- **005** (`skein-0m1z`) — adds `export_stages` (`stage_id` PK, `path`, `origin`, nullable `document_id` cascading from `documents`, nullable `revision_hash`, `created_at`, `expires_at`, `swept`) and `idx_export_stages_expires`. `revision_hash` carries no foreign key, unlike `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.3's sketch: `document_revisions`'s primary key is the composite `(document_id, revision_hash)`, so `revision_hash` alone is neither a primary key nor UNIQUE and SQLite rejects an FK onto it — the same reason 003's own `chunks.revision_hash` has none. No column is dropped and no 001/003 DDL is edited.
- **008** (`skein-zx15`) — adds `ingest_queue.attempts NOT NULL DEFAULT 0` (`IngestPipeline`'s bounded-retry counter, previously an in-memory `IngestAttempts` stand-in that reset on every lock/unlock) and the nullable `chunks.byte_start`/`chunks.byte_end` (populated by `IngestSteps.indexLexical` from `core/rag`'s `Chunk.start`/`Chunk.end` UTF-16 char offsets, converted to UTF-8 byte offsets). No column is dropped and no 001/003/007 DDL is edited.

### Migration safety

**[v1 design, in progress]** Migrations are intentionally immutable once committed. If a schema change is needed during development, the entire `vault.db` is discarded (a development-only step) rather than applying a schema downgrade. This prevents accidental loss of pinned data.

---

## What is not stable in v1

The following are **not** stable or will ship as **[v1 design, in progress]**:

- **Document revision history** — The `document_revisions` table, the BLAKE3-256 revision hash, and the repository write/read paths for both are **[shipped]** as of migration 003 (`skein-uo5n`; see section 2). What is still **[v1 design, in progress]**: the `documentRevisions_gc` retention sweep (§1.2 step 4), and the fact that a chat document snapshots its whole re-materialized transcript on every appended message, which is quadratic in turns until that sweep lands.

- **Backup and recovery** — The `recovery_drafts` table and the background recovery indexing safety mechanism (plan task `E2.I14`, design `docs/design/LOCK_POLICY_INDEXING.md` §7) are out of scope for v1.

- **Export staging** — Migration 005's `export_stages` table and the plaintext-sweep machinery (`StagedPlaintextSweeper`, `BootReceiver`, the on-lock sweep) are **[shipped]** as of `skein-0m1z`. What is still **[v1 design, in progress]**: the only producer of a stage row is PDF export (`E2.I11`), whose UI is not yet wired, so no row is written in practice yet; and the instrumented/adversarial coverage §4.4 lists (`ExportPdfPlaintextLifetimeTest`, `LockDuringExportTest`, `BackupExfiltrationTest`, `CrashDuringExportTest`) is compile-only until the emulator lane (`skein-k3b2`) runs.

- **Encryption keys and wrapping** — The vault master's Keystore wrapping and the `keys/key-envelope.v1` file that holds it are **[shipped]** (section 1; `skein-txrh`). Per-attachment content encryption is also **[shipped]**, but not via the wrapping table `docs/design/ATTACHMENT_ENCRYPTION.md` §3.4 originally sketched: `FileAttachmentStore` derives a fresh per-write key via HKDF-SHA256 from the vault master key and a random per-file salt, persisting no wrapped key material. The now-unused `attachment_keys` and `attachment_master_key` tables were dropped in migration 007 (`skein-7d0l`; see section 2).

- **Vault locking and unlock state machine** — The biometric-gated unlock flow and vault re-locking after timeout are specified but not yet implemented (plan tasks `E3.I2`, `E3.I3a`, `E3.I3b`).

- **FTS5 and vec0 indexes** — The actual indexing, ingest queue processing, and retrieval pipeline are **[v1 design, in progress]** (plan tasks `E5.I1`–`E5.I5`). The schema tables exist; the code to populate and query them does not yet land in the codebase.

- **Citation records** — Persisting and reading back citation-record-v1 (`CitationRecord`/`CitationRecordJson` in `core/model`, via `VaultRepository.appendMessage`/`listMessages`), and the replay check `revisionMatches`, are **[shipped]** as of migration 003. What is still **[v1 design, in progress]**: the *producer* — `RetrievalServiceImpl` populating `Retrieved.revisionHash`/`locator` (plan `E5.I13`) — and the *consumer* — the chat surface's "source changed" badge and diff sheet (plan `E5.I16b`/`E6.I8`). Note the locator grammar: citation-record-v1 anchors to **UTF-8 byte offsets** into the revision's body, while `core/rag`'s `Chunk.start`/`Chunk.end` are **char offsets** into `bodyMd`; the producer bead owns that conversion.

- **Persona management** — The `personas` table and system prompt selection are schema-locked and partially implemented. Full persona CRUD and the persona UI are **[v1 design, in progress]** (plan tasks `E4.I8`–`E4.I11`).

- **Model format and verification** — The `models` table schema is stable. Model file import, hash verification, and sigstore attestation validation are **[v1 design, in progress]** (plan tasks `E6.I4`, `E6.I18`, `E6.I19`).

- **PDF and DOCX export** — Markdown export is shipped. PDF export (Android `PrintManager` based) and DOCX export (OOXML writer) are plan tasks `E2.I11` and `E2.I12`, not yet implemented.

---

## References

- **Design spec:** `docs/superpowers/specs/2026-09-19-skein-design.md` (§2.10 frontmatter, §4.9 data model, §5 SQLCipher/schema, §7 retrieval)
- **Schema source:** `core/vault/src/main/resources/migrations/001_initial.sql`
- **Frontmatter keys and example:** `core/model/src/main/kotlin/app/skein/core/model/Transfer.kt`
- **Frontmatter codec:** `core/vault/src/main/kotlin/app/skein/core/vault/codec/Frontmatter.kt`
- **UUIDv7 generator:** `core/vault/src/main/kotlin/app/skein/core/vault/id/Uuid7.kt`
- **SKAT format:** `core/vault/src/main/kotlin/app/skein/core/vault/blob/SkatFormat.kt`
- **Attachment store:** `core/vault/src/main/kotlin/app/skein/core/vault/blob/FileAttachmentStore.kt`
- **Export service:** `core/vault/src/main/kotlin/app/skein/core/vault/export/ExportServiceImpl.kt`
- **Attachment encryption design:** `docs/design/ATTACHMENT_ENCRYPTION.md`
- **Export plaintext lifetime:** `docs/design/export-plaintext-lifetime.md`
- **Post-review resolutions:** `docs/design/POST_REVIEW_RESOLUTIONS.md`
