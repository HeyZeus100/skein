# Skein — Design Specification

**Status:** Approved for implementation
**Date:** 2026-09-19
**Author:** Andrew Herrera (@andrew-aherrera-us)
**Reviewers:** Fable 5.1 (research pass 2026-09-19; plan pass 2026-09-19)
**Package ID:** `app.skein`

## Confirmed OQ resolutions (2026-09-19)

- **OQ-1 Package ID:** `app.skein` (product-first, portable if the project changes hands)
- **OQ-2 SQLCipher crypto:** OpenSSL (auditability > ~1–2 MB APK cost)
- **OQ-3 First-run model default:** three-way picker at first run — Gemma 4 E4B (Apache 2.0), Qwen 2.5 3B abliterated (Qwen Research License, no-refusal), or bring-your-own-GGUF. Licenses and behavior tradeoffs shown in the picker.
- Corrections rolling in: MTP does not apply to our default models; `NetworkType.NOT_REQUIRED` (not `NONE`); no `.reproducible-builds.yml` standard exists — use F-Droid metadata + documented build recipe.
- Remaining open OQs (non-gating; tracked in `bd`): attachment encryption default (OQ-5), sigstore offline trust root rotation (OQ-6), Accrescent allowlist status, funding path reframing.

---

## 1. What Skein is

Skein is an offline-first personal knowledge system for Android, with an on-device LLM as its interface layer. It runs on GrapheneOS-flavored Pixel 9 Pro Fold–class hardware and requires nothing but the device — no cloud, no telemetry, no network permission. Chats, notes, and AI outputs are all first-class documents in a single wiki-shaped vault. Users can query, cite, edit, link, and export any of it.

Positioning: closer to an AI-native Obsidian than a chat app. The LLM is the interface to a personal memory substrate; the memory substrate is the product.

## 2. Design principles (non-negotiable)

1. **No `INTERNET` permission** in the manifest, ever. Verifiable by the user via GrapheneOS's per-app network toggle.
2. **No Google Play Services** dependency, ever. Runs identically with or without sandboxed GMS.
3. **No telemetry, no crash reporting, no analytics** — not opt-in, not "anonymized," not at all.
4. **Reproducible builds** from the first tagged release.
5. **All model files hash-verified** before mmap; sigstore attestation supported and preferred.
6. **Inference runs in an isolated process** (`android:isolatedProcess="true"`) — a crafted GGUF cannot reach vault data or Android permissions.
7. **`foss` build flavor** uses only Apache-2.0 / MIT / permissive dependencies. No LGPL, no GPL runtime deps, no proprietary blobs.
8. **Every document has a stable UUIDv7** in its frontmatter (locks in v2 sync compatibility).
9. **All data is app-private**, encrypted at rest with StrongBox-backed keys, exposed to other apps only via a `DocumentsProvider`.
10. **User owns the vault format.** Markdown on disk, Obsidian-compatible, plain text under the encryption layer.

## 3. Scope

### 3.1 v1 (this spec)

Product surface:
- Single vault; multi-persona (persona = system prompt + optional default model)
- Wiki-native timeline UI: chats, notes, AI outputs all mixed chronologically, filterable by persona/tag
- Markdown editor with Obsidian-style live preview
- Wikilinks with autocomplete + backlinks
- Inline AI actions: selection menu (rewrite, continue, summarize, ask, extract entities) + slash commands
- RAG over vault content: vector (sqlite-vec int8/256-d Matryoshka) + BM25 (FTS5) + Personalized PageRank over wikilink/tag/entity graph
- GLiNER entity extraction (no LLM-built graph in v1)
- Import: text/MD/code, PDF (text-only extraction), images (analyzed via Gemma 4 vision when active)
- Export: MD, PDF (via Android PrintManager), DOCX (minimal custom writer + optional user templates)
- Share targets: text, image, file, PDF
- Share source: any note/chat/output out to any app
- Assistant integration: Skein can be default long-press-power assistant, but `onHandleAssist`/`onHandleScreenshot` are no-ops
- Local 2-hop graph view + backlinks drawer
- Two default models: **Qwen 2.5 3B Instruct Abliterated** (Q4_K_M GGUF, ~2 GB) and **Gemma 4 E4B** (Apache 2.0, Q4_K_M GGUF, ~2.5 GB); user can import any GGUF

UI/UX:
- Terminal/editor-inspired dark-default aesthetic; light mode ships
- Command bar + hamburger nav; dual-pane on Fold-unfolded (timeline left, tabbed right pane)
- Cursor-style preview tabs (single-click previews, double-click / edit pins)
- Timeline collapsible to icon rail; split view via ⧉ button when collapsed
- Folded-phone: tabs collapse to "Recent ▾" dropdown; single-pane
- No pane splitting *within* the right pane (timeline-collapse split covers the use case)

### 3.2 v2 (roadmap)

- Multi-device E2E encrypted sync (user-hosted relay or Skein-hosted dumb relay)
- LLM-built knowledge graph (opt-in, charging+idle only)
- Voice input (Gemma 4 native audio if stable in llama.cpp, else Whisper.cpp)
- Model router (only if v1 measurement justifies)
- DOCX ingestion (Apache POI)

### 3.3 v3+ (aspirational)

- Global force-directed graph view with native canvas rendering
- Image generation via stable-diffusion.cpp (optional install module)
- On-device LoRA fine-tuning on the user's vault
- XLSX/PPTX ingestion
- Dedicated coding model integration

### 3.4 Explicit non-goals

- **Not** a chat app that happens to use a local model
- **Not** a Signal / messenger competitor
- **Not** a mainstream consumer product
- **Not** shipped for iOS in v1
- **Not** shipped on the Play Store
- **Not** shipped in GrapheneOS's official app repo (they don't accept third-party apps)
- **Not** monetized in v1 (no paid tier, no cloud service)
- **No** cloud fallback inference under any circumstances

## 4. Architecture

### 4.1 Process topology

Three Android processes, one APK:

- **`:app`** (main) — UI, vault access, orchestration, RAG, editor. Full app permissions (SAF, `POST_NOTIFICATIONS`, biometric, opt-in mic in v2).
- **`:inference`** (`android:isolatedProcess="true"`) — model mmap + llama.cpp inference. No Android permissions at all. Bound service; communicates via AIDL/Messenger: prompt tokens in, output tokens streamed out. Blast radius of any llama.cpp parser exploit is contained here.
- **`:embedder`** (also isolated) — GLiNER + embedding model in ONNX Runtime Mobile (or llama.cpp GGUF embedding path, TBD by M0 measurement). Same isolation rationale.

Startup: `:app` → biometric unlock → StrongBox key unwrap → SQLCipher open → bind `:inference` → hash-verify current model → mmap → ready.

### 4.2 Subsystems

- **Vault** — SQLCipher-encrypted SQLite database (`vault.db`) plus an attachment blob directory (`attachments/<uuidv7>`); attachments are referenced by rows in `documents` with `kind='attachment'`. All app-private, exposed via `DocumentsProvider`.
- **Inference engine** — `InferenceEngine` Kotlin interface; v1 implementation `LlamaCppEngine`
- **Embedder** — separate isolated process; produces int8 embeddings for chunks, entity extractions for documents
- **RAG service** — hybrid retriever (vector + BM25 + PPR)
- **Ingest worker** — WorkManager job with charging+idle constraints; watches document changes, chunks + embeds + entity-extracts
- **UI layer** — Jetpack Compose, Material 3 (themed dark-default terminal aesthetic), responsive to Fold state
- **Editor** — Compose-based Markdown live-preview editor with wikilink autocomplete, slash commands, selection menu
- **Assistant service** — `VoiceInteractionService` with `onHandleAssist`/`onHandleScreenshot` as no-ops
- **Share handler** — `Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE` receiver
- **Export service** — MD / PDF / DOCX writers

## 5. Data model

SQLite (via SQLCipher), one file `vault.db` in app-private storage. Bundled SQLite is a custom NDK build — SQLCipher 4.17.0 amalgamation + sqlite-vec v0.1.9 + FTS5 statically linked into `libskein_sqlite.so`, with OpenSSL 3.5.4 `libcrypto.a` statically linked in — surfaced to Kotlin via a vendored `androidx.sqlite` `BundledSQLiteDriver`-derivative JNI (see `native/sqlite/README.md`). The E0.I7 spike verified all three features (encryption, `vec0` KNN, FTS5 `MATCH`) work on a single connection on the API 35 arm64-v8a emulator; see `docs/SPIKE_E0_I7_RESULTS.md`.

```sql
CREATE TABLE documents (
  id TEXT PRIMARY KEY,               -- UUIDv7, stored in frontmatter
  kind TEXT NOT NULL,                -- 'note' | 'chat' | 'attachment' | 'aiout'
  title TEXT NOT NULL,
  body_md TEXT,                      -- Markdown source (null for binary)
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  persona_id TEXT REFERENCES personas(id),
  frontmatter JSON,
  content_hash TEXT
);
CREATE INDEX idx_documents_updated ON documents(updated_at DESC);
CREATE INDEX idx_documents_persona ON documents(persona_id);

CREATE TABLE chunks (
  id INTEGER PRIMARY KEY,
  doc_id TEXT REFERENCES documents(id) ON DELETE CASCADE,
  ord INTEGER NOT NULL,
  text TEXT NOT NULL,
  token_count INTEGER,
  embedder_id TEXT,
  embedder_version INTEGER
);
CREATE INDEX idx_chunks_doc ON chunks(doc_id);

CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='id');
CREATE VIRTUAL TABLE chunks_vec USING vec0(embedding int8[256]);

CREATE TABLE edges (
  src_id TEXT NOT NULL,
  dst_id TEXT NOT NULL,
  kind TEXT NOT NULL,                -- 'wikilink' | 'tag' | 'entity' | 'cite'
  weight REAL DEFAULT 1.0,
  created_at INTEGER,
  PRIMARY KEY (src_id, dst_id, kind)
);
CREATE INDEX idx_edges_dst ON edges(dst_id, kind);

CREATE TABLE entities (
  id INTEGER PRIMARY KEY,
  canonical_name TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  first_seen INTEGER,
  UNIQUE(canonical_name, entity_type)
);

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  chat_doc_id TEXT REFERENCES documents(id) ON DELETE CASCADE,
  role TEXT NOT NULL,                -- 'user' | 'assistant' | 'system'
  content_md TEXT NOT NULL,
  model_id TEXT,
  retrieved_chunks JSON,             -- chunk ids used as RAG context
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_messages_chat ON messages(chat_doc_id, created_at);

CREATE TABLE personas (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  system_prompt TEXT,
  default_model TEXT,
  created_at INTEGER
);

CREATE TABLE models (
  id TEXT PRIMARY KEY,               -- 'qwen-2.5-3b-abliterated-q4km'
  path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  attestation_url TEXT,
  format TEXT NOT NULL,              -- 'gguf' | 'onnx'
  capabilities JSON,                 -- ['text', 'vision']
  size_bytes INTEGER,
  imported_at INTEGER
);

CREATE TABLE ingest_queue (
  doc_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
  reason TEXT,                       -- 'created' | 'updated' | 'reembed'
  queued_at INTEGER
);
```

Encryption key wrapping: SQLCipher passphrase derived from a StrongBox-backed hardware key, biometric-gated, kept in memory only while unlocked. SQLCipher is built with the OpenSSL crypto provider.

**SQLite build note.** SQLCipher and androidx `BundledSQLiteDriver.addExtension` are *separate* SQLite builds — SQLCipher-android does not permit runtime extension loading. We compile a custom NDK build that statically links the SQLCipher amalgamation, sqlite-vec, and FTS5 into one library, exposed via a vendored androidx driver JNI. The week-1 spike (`E0.I7`) validates this build.

## 6. Inference stack

**Backend:** llama.cpp only in v1.

- Pinned to a specific commit as a git submodule
- Built for `arm64-v8a` with `-DGGML_VULKAN=ON` for Mali-G715 acceleration
- JNI bindings written from scratch (thin wrapper; no third-party JNI dependencies)
- Loaded in the isolated `:inference` process; single model resident at a time (16 GB RAM, ~4 GB model + ~1 GB KV cache + Android overhead)
- Model file mmap'd read-only; hash verified against `models.sha256` before mmap; sigstore attestation checked when `attestation_url` is set
- MTP (multi-token prediction) enabled if the model supports it and measurement shows ≥1.5× throughput

**Interface:**
```kotlin
interface InferenceEngine {
    suspend fun load(model: Model): Result<Unit>
    fun stream(prompt: Prompt, params: SamplingParams): Flow<Token>
    suspend fun embed(text: String): FloatArray
    suspend fun cancel()
    suspend fun unload()
}
```

**Effective context cap:** 16K tokens in v1 (verify in M0 measurement). Context above 16K blows RAM budget on the Fold.

**Model swap:** warm-swap (unload → mmap-verify → load new); pays ~2–5 s TTFT penalty. Router deferred to v2.

**Multi-token prediction (MTP):** does *not* apply to Gemma 4 or Qwen 2.5 (llama.cpp's MTP targets Qwen 3.6-class architectures). Correction from earlier draft. Revisit if v2 models support it.

## 7. RAG pipeline

### 7.1 Ingest (WorkManager, `NetworkType.NOT_REQUIRED`, charging + idle constraints)

1. Trigger: SQLite trigger on `documents.updated_at` writes to `ingest_queue`
2. Chunker: paragraph-boundary semantic chunker, ~512 tokens, 64-token overlap
3. Embedder: `nomic-embed-text-v1.5` via GGUF (llama.cpp embedding) OR ONNX RT (decide by M0 measurement); truncate to 256-d via Matryoshka; quantize float32→int8
4. Entity extraction: GLiNER int8 ONNX; ~100–150ms per chunk on-device
5. Wikilink extraction: regex over `body_md`, resolve to doc IDs, upsert `edges`
6. Backlinks: computed via `SELECT src_id FROM edges WHERE dst_id = ?` — no separate storage

### 7.2 Retrieve

`retrieveContext(query: String, k: Int = 8, persona_id: String? = null): List<Retrieved>`

1. **Recall** (~30 candidates, cheap, parallel):
   - Vector: sqlite-vec cosine top-30
   - Lexical: FTS5 BM25 top-30
   - Graph seed: entity-mention lookup, expand 2 hops
2. **Rank** (30 → k):
   - Personalized PageRank over union, edge weights by kind (`wikilink` 1.0 > `entity` 0.6 > `tag` 0.4)
   - Optional cross-encoder rerank (ms-marco-MiniLM-L-6-v2 ONNX) if ≤200 ms budget confirmed in M0
3. **Return:** `[{chunk_id, doc_id, doc_title, text, score, source_kind}]` with numbered `[N]` markers

### 7.3 Prompt assembly

```
[persona.system_prompt]

[recent chat turns bounded by context budget]

Retrieved context:
[1] <title> · <source_kind>
    <chunk text>
[2] ...

User: <query>
```

Retrieved text is **data, not instructions.** Model may cite `[N]`; citations render as clickable in the UI, opening the source as a preview tab.

## 8. UI/UX

### 8.1 Visual style
- **Terminal/editor-inspired**, dark-default with light mode
- Monospace primary type (IBM Plex Mono or similar Apache/OFL-licensed alternative)
- Restrained accent color (cyan/violet); no gradients or dropshadows

### 8.2 Navigation
- Persistent command bar at top: search + slash-commands + model status ('qwen · ●' / 'gemma · ⏸')
- Hamburger reveals: Timeline · Notes · Graph · Personas · Settings
- Dual-pane on Fold-unfolded: timeline left (30%), tabbed right pane (70%)
- Timeline collapsible via ◂ toggle → 40 px icon rail
- Single-pane on folded / narrow displays

### 8.3 Tabs
- Cursor-style preview tabs (italic name; single-click replaces; double-click / edit pins)
- Tab types: 💬 chat · 📄 note · 📎 attachment
- Split view via ⧉ button + long-press tab menu (v1); drag-to-split (v1.1)
- Split auto-collapses timeline
- Folded phone: tabs → "Recent ▾" dropdown

### 8.4 Chat surface
- Streaming tokens rendered with citation markers
- Inline citation click → source opens as preview tab; also expands inline excerpt on tap
- Context panel toggleable in header (⚹ context)
- Bottom bar: `$` prompt · reply text · `[[` wikilink autocomplete · slash-commands · 📎 attach · ⏎ send

### 8.5 Editor
- Obsidian-style live preview: raw MD is the source of truth on disk; visual rendering swaps in as cursor leaves a line
- Inline AI: selection menu (Rewrite · Continue · Summarize · Ask · Extract entities · custom) + slash commands (`/ai continue`, `/link related`)
- Backlinks drawer at bottom of every note (collapsible)
- Local 2-hop graph accessible via ✦ button

### 8.6 Graph view
- Local 2-hop force-directed layout centered on selected document
- Node colors by document `kind`
- Edge weights by `edges.weight` (visual thickness)
- Tap node → preview; long-press → pin as tab
- Global view deferred to v2 (needs native canvas rendering)

### 8.7 Onboarding
- First-run: choose default model (Qwen abliterated vs Gemma 4 vs import own), download via SAF, verify hash, unlock biometric, create first persona
- No account, no email, no signup

## 9. Threat model

Full doc: `THREAT_MODEL.md` (delivered by M3). Highlights:

**Adversary classes in scope:**
- Malicious model publisher / crafted GGUF exploiting parser
- Malicious note content injecting prompts
- Physical attacker with unlocked device
- Co-installed malicious app
- Backup exfiltration (Seedvault D2D)
- Third-party keyboard leakage
- Compromised model mirror / MITM during import

**Mitigations:**
- Isolated `:inference` process; hash + sigstore verification pre-mmap
- CaMeL-style separation: retrieved text is data; model output never derives Intent URIs or tool calls; every out-of-app action requires explicit user tap
- StrongBox-backed keys, biometric-gated, idle-unload after N minutes
- `FLAG_SECURE` on vault/chat surfaces; `Notification.VISIBILITY_SECRET`
- `dataExtractionRules` excludes DB + models from Seedvault D2D; passphrase export as opt-in
- No exported components; tight `DocumentsProvider` grants (no persistable URI permissions)
- `textNoSuggestions` + no personalized-learning on all vault inputs
- Release builds strip verbose logging; llama.cpp not permitted to log prompt/chunk content
- Isolated inference process cannot open files, sockets, or bind services beyond its Binder connection to `:app`
- Model file loaded once, mmap held, re-verification required on any re-open (TOCTOU defense)

**Explicit out-of-scope:**
- Compromised OS or unlocked bootloader
- Forensic extraction of unlocked, cooperating device
- Timing/thermal side-channels
- Traffic analysis (moot — no network)

## 10. Distribution & licensing

- **License:** Apache 2.0
- **Contributor DCO** (`Signed-off-by`), no CLA
- **Governance:** BDFL for year 1, documented path to broader
- **Build:** Gradle 8+, Kotlin 2.0+, Jetpack Compose (native Android), minSdk 30, targetSdk latest, arm64-v8a only for `foss` flavor
- **Signing:** own keys, StrongBox-attested; SHA-256 published per release
- **Reproducible builds:** F-Droid `metadata/app.skein.yml` + a documented build recipe (`docs/REPRODUCIBLE_BUILDS.md`); publish `diffoscope` invocation in release notes. (Correction: there is no `.reproducible-builds.yml` standard.)
- **Package size target:** ≤30 MB APK for `foss` flavor
- **Distribution channels (in order):**
  1. GitHub Releases (signed APK, day one)
  2. Obtainium manifest (day one)
  3. Accrescent submission (during M3)
  4. IzzyOnDroid submission (during M3)
  5. F-Droid main merge request (during M3, cycle takes ~2 months)
- **Not on:** Play Store, GrapheneOS official repo

## 11. Milestones (parallel execution model)

Target: **12–14 weeks to public v1.0.**

| Phase | Wks | Serial/Parallel | Notes |
|---|---|---|---|
| **M0 Measurement** | 1 | Serial (hardware) | Fold on GOS; llama.cpp Vulkan benchmarks; embedder comparison; deliverable `MEASUREMENTS.md` |
| **M0.5 Contracts** | 1 | Serial (design) | `InferenceEngine`, `VaultRepository`, `RetrievalService`, `PersonaService` interfaces locked |
| **M1 Substrate** | 3 | Parallel (5–7 agents) | Vault+DB · Encryption+StrongBox · Inference JNI · Compose shell · Build+CI+RB |
| **M2 Wiki + RAG** | 3 | Parallel (5–7 agents) | Editor · Wikilinks/backlinks · Ingest+embedder+GLiNER · Retrieval · Timeline UI · Tabs · Graph view |
| **M3 Polish + distribution** | 3 | Parallel (3–5 agents) | PDF/DOCX export · Assistant integration · Share targets · Threat model doc · README/PRIVACY/SECURITY · Onboarding · Reproducible build recipe · Submissions |
| **M4 Launch** | 1 | Serial (human) | Community announcements, submission follow-through |

**Realistic total:** 12 weeks best case, 14 weeks accounting for M1 interface friction + F-Droid cycle slippage.

## 12. Parallel execution model

### 12.1 Agent tier mapping

| Tier | Use for | Concurrency |
|---|---|---|
| **Haiku 4.5** | Boilerplate, small refactors, doc formatting, test scaffolding, CI YAML, dep updates, i18n | High (5–10 concurrent) |
| **Sonnet** | Mainline implementation: Compose UI, business logic, SQLite queries, JNI wrappers, retrieval pipeline | Medium (3–5 concurrent) |
| **Opus** | Architecture, security-sensitive code (isolated process, keys, sigstore), tricky concurrency, correctness-critical model loading | Low (1–2 concurrent) |
| **Fable 5.1** | Deep research, adversarial reviews, benchmark analysis, threat-model critique | Background, 1 at a time |

### 12.2 Task tracking (bd / beads)

- All work tracked in `bd`; no markdown TODOs, no `TodoWrite`
- Epics per subsystem: `vault`, `inference`, `rag`, `ui`, `editor`, `security`, `build`, `distribution`
- Each issue: title · description · acceptance criteria · dependency IDs · tier hint · estimated hours
- Labels: `parallel-safe` · `needs-hardware` · `needs-human-review` · `blocks-others` · `spike` · `docs`
- Dependency DAG tracked in bd; `bd ready` surfaces unblocked work
- Agents claim/close via `bd update <id> --claim` and `bd close <id>` with findings pushed to issue thread
- `bd remember` for persistent cross-agent knowledge

### 12.3 Coordination modes

- **A · Direct dispatch (primary):** human runs `bd ready --limit 20`, dispatches subagent per task with appropriate tier
- **B · Wave orchestration (pushes):** Opus coordinator agent reads `bd ready`, dispatches 5–8 parallel subagents, integrates, tests, closes
- **C · Background research:** Fable 5.1 launched as background agent for architectural questions; delivers report; recommendations go into `bd` as new issues

### 12.4 Hardware bottleneck

- Only one Pixel 9 Pro Fold available
- **Dedicated test-runner Sonnet agent** owns device queue: pulls `needs-hardware` labeled issues, runs one at a time, reports
- All other work runs on Android 15 emulator or unit tests

## 13. Success signals (year 1)

- Third-party reproducible-build verifications (IzzyOnDroid RB, community rebuilds)
- Unprompted mentions in GrapheneOS "which AI app?" forum threads
- Issues from users running vaults >1k notes
- Non-author contributors with merged PRs
- Security reports filed against `THREAT_MODEL.md`
- Accrescent + F-Droid main inclusion

**Ignored:** stars, download count, launch-day traffic, "trending" placements.

## 14. Funding intent (year 1)

- GitHub Sponsors open from day one; no gating, no pressure
- **FUTO grant** application during M2 (best fit; anti-oligopoly, user-empowering software)
- **NLnet** application framed around the *infrastructure* pieces (DocumentsProvider vault, sqlite-vec Android packaging, RB tooling) — not "on-device AI" (per Fable's research: NLnet Restack excludes AI under 1M users)
- **No paid tier, no cloud service, no premium features** in year 1
- Reassess year 2 based on demand; likely candidate is E2E encrypted multi-device sync as an Obsidian-style optional paid service

## 15. Open questions to resolve in M0

1. Embedder path: nomic-embed-text-v1.5 via GGUF (llama.cpp) vs ONNX Runtime Mobile — decide by measured latency + memory
2. Cross-encoder rerank in v1: fits ≤200 ms budget on Fold with GLiNER + inference concurrent? Measure and decide
3. Effective context cap: is 16K tokens the right ceiling, or can we push to 24K/32K with Matryoshka-quantized KV cache?
4. MTP (multi-token prediction) uplift on Qwen 2.5 abliterated — supported? What throughput gain?
5. Thermal throttling profile: how long can we sustain inference before throttle? Batch indexing needs adaptive backoff

## 16. Interface contracts (locked at M0.5)

To be produced by M0.5:
- `InferenceEngine` (Kotlin)
- `VaultRepository` (Kotlin)
- `RetrievalService` (Kotlin)
- `PersonaService` (Kotlin)
- `ExportService` (Kotlin)
- `ModelManifest` (JSON schema)
- `bd` epic + label taxonomy

Once these ship, M1 parallel workstreams begin.

## 17. Risks (from Fable pass, ranked)

1. **Runtime / model churn** — llama.cpp CVEs, GGUF format changes. Mitigation: dependency verification, pinned submodule, physical Fold in CI.
2. **First-run friction: getting a 3 GB model into a no-INTERNET app.** Mitigation: default to smaller Gemma 4 E2B or Qwen 3B, hash-verified SAF import, optional separate "fetcher" companion app with `INTERNET`.
3. **Thermal + battery reality on Tensor G4.** Mitigation: WorkManager `NetworkType.NONE` + charging + idle constraints; `PowerManager.getThermalHeadroom` gating; GLiNER not LLM for entity extraction.
4. **Scope** — historical rate for solo maintainers is 2× estimate. Mitigation: aggressive cuts already applied; parallel agents amortize the excess.
5. **Vault-location contradiction** (Fable's hidden risk): resolved by `DocumentsProvider`-exposed app-private storage + `dataExtractionRules` + passphrase export.

---

**Approval status:** Product shape, technical architecture, and execution model approved by human in brainstorming session 2026-09-19. Ready for implementation-plan generation via `superpowers:writing-plans` skill (Fable 5.1 subagent).
