# Vault Format

On-disk layout, encryption, data model, and wire format for Skein vaults. This document describes the shipped v1 surface.

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
- Other app-private files reserved for future use (model caches, key material wrapped under StrongBox, etc.)

No user-readable Markdown or configuration files live on disk inside the vault. The on-disk layout is opaque; the Markdown representation is materialized on demand via `ExportService.exportMarkdown()` or `ExportService.exportVaultZip()` (see section 6).

**[v1 design, in progress]** App-private storage is enforced by the Android OS — no other app can read these paths without root. Export to user-accessible storage (Downloads, Documents) goes through `DocumentsProvider` or an explicit user-initiated export action.

---

## SQLCipher database

**[shipped]** The vault database uses SQLCipher (AES-256-CTR page-level encryption, via `sqlcipher://` URI) at `PRAGMA user_version = 1`, currently at schema version 1 only.

### Database schema (v1)

Migration file: `core/vault/src/main/resources/migrations/001_initial.sql`

The schema is locked by the design spec (§5) and implements these tables:

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
| `content_hash` | TEXT | Content-addressed hash (reserved for future migrations) |
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

Indexes: `chunks_doc` (by `doc_id`).

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
| `retrieved_chunks` | JSON | Citation records pointing to retrieved text (see PRIVACY.md §1) |
| `created_at` | INTEGER NOT NULL | Unix milliseconds since epoch |

Indexes: `messages_chat` (by `chat_doc_id`, `created_at`).

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

#### `attachment_master_key` and `attachment_keys`

**[v1 design, in progress]** Key wrapping tables for the attachment content encryption (see section 5). Details in `docs/design/ATTACHMENT_ENCRYPTION.md` §3.4.

### Encryption and locking

**[v1 design, in progress]** The SQLCipher passphrase is derived from a hardware-backed key (StrongBox) that is unwrapped only after biometric authentication and held in memory while the vault is unlocked. When the vault is locked, this key material is wiped from memory.

---

## Frontmatter wire format

**[shipped]** Every document (note, chat, attachment, aiout) serialized as Markdown carries a YAML frontmatter block with this wire format and canonical key order.

### Canonical keys

Defined in `core/model/src/main/kotlin/us/aherrera/skein/core/model/Transfer.kt` (`FrontmatterKeys`):

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

(This example is pinned as `FrontmatterExamples.NOTE` in `core/model/src/main/kotlin/us/aherrera/skein/core/model/Transfer.kt` and is CI-tested to match this document.)

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

**[shipped]** Each file in the `attachments/` directory is a streaming, chunked container named `SKAT` (Skein-Chunked-Attachment, defined in `core/vault/src/main/kotlin/app/skein/core/vault/blob/SkatFormat.kt`).

### File layout

A SKAT file is a sequence of:

1. **Header (32 bytes, fixed layout)**
2. **Chunks (repeating: encrypted plaintext + authentication tag)**

### Header

| Offset | Size | Field | Value |
|--------|------|-------|-------|
| 0 | 4 | Magic | `SKAT` (ASCII) |
| 4 | 1 | Version | 1 (current) |
| 5 | 4 | Chunk size (little-endian) | 1,048,576 (1 MiB) — `CHUNK_SIZE` |
| 9 | 8 | Total plaintext bytes (little-endian) | Length of the original unencrypted file |
| 17 | 15 | Reserved | All zeros |

The first 9 bytes (magic + version + chunk size) form the "static header," which is fed into every chunk's AAD (see below). The `totalPlaintextBytes` field at offset 9 is **not** part of chunk AAD; it is patched in after streaming completes so the container genuinely streams rather than buffering.

### Chunk framing

Plaintext is split into chunks of up to 1 MiB each:

- **Chunk index 0:** bytes 0–1,048,575 of plaintext
- **Chunk index 1:** bytes 1,048,576–2,097,151 of plaintext
- (and so on)

The last chunk may be shorter than 1 MiB.

Each chunk is encrypted with:
- **Algorithm:** AES-256-GCM (Galois/Counter Mode)
- **Key:** Derived from the vault master key via HKDF-SHA256 (see section 5.4 below)
- **Nonce (IV):** 12 bytes — chunk index, little-endian, zero-padded to 12 bytes
- **AAD (Additional Authenticated Data):** Static header (9 bytes) + chunk index (8 bytes, little-endian)
- **Ciphertext:** Encrypted plaintext chunk
- **Authentication tag:** 16 bytes (128 bits)

Bytes written to disk per chunk:

```
[ciphertext (variable)] || [auth_tag (16 bytes)]
```

If a chunk's plaintext is *P* bytes, its encrypted form is *P* + 16 bytes.

### Per-attachment key derivation

**[shipped]** Each attachment is assigned a unique content encryption key, derived deterministically from the vault master key and the attachment UUID. This is critical for GCM safety — see section 5.5 below.

**Derivation:**

```
content_key = HKDF-SHA256(
  salt        = vault_master_key,
  ikm         = attachment_uuid.utf8Bytes,
  info        = "skein-attachment-v1",
  L           = 32 bytes
)
```

The salt (master key) is wiped immediately after the derivation step. The content key is wiped after the file is written or read.

Implementation: `core/vault/src/main/kotlin/app/skein/core/vault/blob/FileAttachmentStore.kt`, using the hand-rolled `Hkdf.kt` (RFC 5869).

### Write-once enforcement

**[shipped]** Once an attachment is written to `attachments/<uuid>`, it cannot be overwritten. Any attempt to write a second attachment with the same UUID raises `AttachmentException.AlreadyExists`. This prevents GCM nonce reuse (see section 5.5).

### Truncation detection

**[shipped]** The `totalPlaintextBytes` field in the header makes truncation detectable. If trailing chunks are missing, the plaintext size is known at decode time, and a read that stops short of that size is detected as a truncation error rather than a successful decode of a shorter file.

### GCM IV reuse risk mitigation

**[v1 design, in progress]** AES-256-GCM requires that a given `(key, nonce)` pair is used to encrypt at most one plaintext. Without the write-once invariant, an overwrite would reuse `(content_key, nonce)` on different plaintext — a critical cryptographic failure.

The v1 design avoids this by construction:
- Each attachment UUID gets a unique per-attachment content key (derived from the vault master key via HKDF).
- The nonce is deterministic: `nonce = chunk_index`, zero-padded to 12 bytes.
- Because `content_key` is unique per UUID and a UUID identifies exactly one plaintext for its lifetime (write-once), the pair `(content_key, nonce)` is used exactly once, ever, for exactly one plaintext. There is no way to reach an unsafe state without violating the write-once invariant — which the store enforces.

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
```

Each migration is a file named `NNN_<description>.sql` where `NNN` is a zero-padded integer (001, 002, etc.). Migrations are applied in INDEX.txt order. Statements within a migration are separated by a bare `;` at end-of-line; comment lines (`--`) and blank lines are ignored.

### Current migrations

**[shipped]** Only one migration exists:

- `001_initial.sql` — v1 schema (see section 2)

Future migrations are tracked in the plan and design docs; none are yet in-tree.

### Migration safety

**[v1 design, in progress]** Migrations are intentionally immutable once committed. If a schema change is needed during development, the entire `vault.db` is discarded (a development-only step) rather than applying a schema downgrade. This prevents accidental loss of pinned data.

---

## What is not stable in v1

The following are **not** stable or will ship as **[v1 design, in progress]**:

- **Document revision history** — The `document_revisions` table (plan task `E2.I8`, design `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3) is not in Migration 001. Content addressing (BLAKE3 revision hashes) and citation stability are out of scope for v1 and will land in a later migration.

- **Backup and recovery** — The `recovery_drafts` table and the background recovery indexing safety mechanism (plan task `E2.I14`, design `docs/design/LOCK_POLICY_INDEXING.md` §7) are out of scope for v1.

- **Export staging** — Migration 005 (export staging tables and the plaintext-sweep machinery) is deferred to a later release.

- **Encryption keys and wrapping** — The key wrapping tables (`attachment_master_key`, `attachment_keys`) are schema-locked but the key derivation, wrapping, and unwrapping logic is **[v1 design, in progress]** (plan task `E3.I6`, design `docs/design/ATTACHMENT_ENCRYPTION.md` §3.4).

- **Vault locking and unlock state machine** — The biometric-gated unlock flow and vault re-locking after timeout are specified but not yet implemented (plan tasks `E3.I2`, `E3.I3a`, `E3.I3b`).

- **FTS5 and vec0 indexes** — The actual indexing, ingest queue processing, and retrieval pipeline are **[v1 design, in progress]** (plan tasks `E5.I1`–`E5.I5`). The schema tables exist; the code to populate and query them does not yet land in the codebase.

- **Citation records** — The `messages.retrieved_chunks` JSON field design is locked (plan task `E2.I8`, design `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3), and the Kotlin type (`CitationRecord` in `core/model`) is stable, but the logic to populate and validate citations is **[v1 design, in progress]**.

- **Persona management** — The `personas` table and system prompt selection are schema-locked and partially implemented. Full persona CRUD and the persona UI are **[v1 design, in progress]** (plan tasks `E4.I8`–`E4.I11`).

- **Model format and verification** — The `models` table schema is stable. Model file import, hash verification, and sigstore attestation validation are **[v1 design, in progress]** (plan tasks `E6.I4`, `E6.I18`, `E6.I19`).

- **PDF and DOCX export** — Markdown export is shipped. PDF export (Android `PrintManager` based) and DOCX export (OOXML writer) are plan tasks `E2.I11` and `E2.I12`, not yet implemented.

---

## References

- **Design spec:** `docs/superpowers/specs/2026-09-19-skein-design.md` (§2.10 frontmatter, §4.9 data model, §5 SQLCipher/schema, §7 retrieval)
- **Schema source:** `core/vault/src/main/resources/migrations/001_initial.sql`
- **Frontmatter keys and example:** `core/model/src/main/kotlin/us/aherrera/skein/core/model/Transfer.kt`
- **Frontmatter codec:** `core/vault/src/main/kotlin/app/skein/core/vault/codec/Frontmatter.kt`
- **UUIDv7 generator:** `core/vault/src/main/kotlin/app/skein/core/vault/id/Uuid7.kt`
- **SKAT format:** `core/vault/src/main/kotlin/app/skein/core/vault/blob/SkatFormat.kt`
- **Attachment store:** `core/vault/src/main/kotlin/app/skein/core/vault/blob/FileAttachmentStore.kt`
- **Export service:** `core/vault/src/main/kotlin/app/skein/core/vault/export/ExportServiceImpl.kt`
- **Attachment encryption design:** `docs/design/ATTACHMENT_ENCRYPTION.md`
- **Export plaintext lifetime:** `docs/design/export-plaintext-lifetime.md`
- **Post-review resolutions:** `docs/design/POST_REVIEW_RESOLUTIONS.md`
