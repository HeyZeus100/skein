# Skein Context — where perception could eventually plug in

**Read-only survey.** Nothing in Skein production code was changed. Repo HEAD at survey time: `main@0303779fc`
(2026-09-29). Paths are repo-relative. Line numbers are approximate where marked `~`.

## 1. Bottom line

Skein already defines **the seams** for images (and, loosely, audio) in its IPC contract and domain model, but
**none of the code that would use them exists yet**:

| Seam | Exists | Implemented? |
|---|---|---|
| `Prompt.images: List<ByteArray>`, `Capability.VISION` | `core/model/.../Inference.kt` ~:103 | **No.** `PromptSpiller.kt:102,126` always passes `emptyList()` attachments |
| `GenerateRequest.attachmentFds: List<SharedMemRef>` (role `image`/`audio`, `mimeHint`) | `core/ipc/.../Parcels.kt` ~:331, ~:577 | **No.** `InferenceService.kt:~502` closes attachments unused ("E4.I11 owns images") — verified |
| mmproj companion in the model manifest (`companions[].role = mmproj`), `ModelFileRole.MMPROJ` | manifest schema v2, `:core:verify` | Schema only. The Picked-import path cannot attach an mmproj |
| `LlamaNative.modelHasVision` | `native/llama/jni/skein_jni.cpp:1378` | Metadata scan only |
| llama.cpp `tools/mtmd` (libmtmd, clip, gemma4v/gemma4a, whisper-enc…) | in submodule | **Not compiled.** `native/llama/CMakeLists.txt:612` sets `LLAMA_BUILD_TOOLS OFF` — verified |
| `ImportService.importImage` ("store as attachment; if VISION loaded, create AIOUT description + CITE edge") | `Transfer.kt:216` | **Throws** (`ImportServiceImpl.kt:232`). Bead `skein-rni` / plan E2.I9 |
| Embedder service | `:embedder-service` | **Stub** (`onBind` returns null). Ingest/query run with `embedder = null` |
| Camera / mic / ASR / OCR | — | **None.** No CAMERA / RECORD_AUDIO permission (verified in `app/src/main/AndroidManifest.xml`) |
| Tool calling | `core/agent/.../VaultTools.kt` (interface only), `core/security/.../ActionPolicy.kt` | **Model-derived tool calls are banned in v1** (spec §9) |

Planned work that Senses research should feed:
- **E4.I11 / bead `skein-m6v` (M3):** "Vision input for Gemma 4 E4B (mmproj via llama.cpp multimodal)". `mtmd_init_from_file` → `mtmd_tokenize` → `mtmd_helper_eval_chunks`, native image decode in the isolated process (no Android Bitmap), eager vs lazy mmproj (~1 GB RSS), OCR-like acceptance test. Plan: `docs/superpowers/plans/2026-09-19-skein-v1-plan.md:3254`.
- **E2.I9 / bead `skein-rni`:** `importImage` → AIOUT description ("Describe this image factually… List any visible text verbatim.").
- **Spec §3.2 v2:** "Voice input (Gemma 4 native audio if stable in llama.cpp, else Whisper.cpp)". Spec §4.1: ":app … opt-in mic in v2".
- **ROADMAP v2:** Agents (`skein-iifu`), Capabilities panel + audit log (`skein-2chp`), skill format / guardrails.

## 2. Components and how perception would interface

### 2.1 Inference (`:core:inference`, `:inference-service`, `native/llama`)
- llama.cpp submodule `third_party/llama.cpp` @ `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` (build 10964, tag `v0.4.1`). Single source of truth: `native/llama/PINNED_COMMIT` (checked by `tools/ci/check-submodules.sh`).
- A custom JNI (`native/llama/jni/skein_jni.cpp`) builds a single static `libskein_llama.so` (CPU + Vulkan on arm64-v8a).
- The `:inference` process is `isolatedProcess="true"`, not exported, with a single worker thread. AIDL `IInferenceService` (`core/ipc/src/main/aidl/app/skein/ipc/`) provides `load / inspect / generate(callback) / cancel / unload / embed / tokenCount / status / measurePrompt` plus session-epoch lock pushes. Tokens are batched to the client every ≤20 ms / ≤16 KiB.
- Transport rules (`TransportRules.kt`): 32 KiB inline cap. **Images and audio must travel as file descriptors** (`SharedMemRef`).
- **Interface point:** a Senses "eyes"/"ears" payload maps onto `SharedMemRef(fd, size, mimeHint, role="image"|"audio")` in `GenerateRequest.attachmentFds`. This is the same shape as Kavya's `image_url` / `input_audio` content parts, but passed by fd rather than base64 JSON.
- **Lab relevance:** the lab's `llama-server` is built from **this exact pinned commit**, so the lab's vision/audio results directly predict what enabling `tools/mtmd` in Skein's CMake would give.

### 2.2 Model management (`:core:inference` `ModelManager`, `ImmutableModelStore`, `:core:verify`)
- Manifest v2 (`core/model/src/main/resources/schema/model-manifest.schema.json`): `format` gguf|onnx, sha256 (+blake3), `capabilities` [text, vision, embedding, ner, rerank], `companions[]` incl. `mmproj`. Fixture: `core/inference/src/test/resources/manifests/valid/gemma-4-e4b-it-q4km.skein.json`.
- The core app has **no downloader** (no INTERNET). Models are imported via SAF into `filesDir/models/<id>/` (0400/0500), SHA-256 is checked before mmap and BLAKE3 after. Network acquisition is reserved for the separate "Skein Hub" APK (`docs/design/SKEIN_HUB.md`, designed, not built).
- Catalog (`tools/m0-benchmark/models.yaml`): Qwen2.5-3B-abliterated (Q3/Q4), **Gemma 4 E4B Q4_K_M [text, vision]**, Gemma 4 E2B Q4_K_M [text], nomic-embed-text-v1.5, Qwen3-Embedding-0.6B, GLiNER small, ms-marco-MiniLM.
- **Interface point:** capabilities would need `audio` (not in the schema today); an ASR model would be a new `format` or an `onnx` entry.

### 2.3 Embeddings (`:embedder-service`, `core/model/.../Embedder.kt`)
- Contract: nomic-embed-text-v1.5, `search_document:`/`search_query:` prefixes, 256-d Matryoshka, L2, int8 → 256 bytes. Store: sqlite-vec `chunks_vec vec0(embedding int8[256] cosine)` (`core/vault/.../migrations/001_initial.sql:82`).
- **Interface point:** perception outputs reach retrieval **as text** (captions, OCR, transcripts) through normal chunking. **Visual embeddings** (CLIP/SigLIP-style) would need a second vector table and are outside current plans.

### 2.4 Vault, RAG, graph, citations, timeline (`:core:vault`, `:core:rag`)
- SQLCipher with raw SQL migrations (no Room). Tables: `documents` (kind NOTE/CHAT/ATTACHMENT/AIOUT, frontmatter JSON, content_hash, mime_type), `chunks`, `chunks_fts`, `chunks_vec`, `edges` (WIKILINK/ENTITY/TAG/CITE with weights), `entities`, `messages`, `ingest_queue` (DB trigger on document write), `document_revisions`.
- Retrieval: vector + BM25 + graph recall → PPR ranker / score fusion → `Retrieved(revisionHash, locator)` → citations.
- Attachments are encrypted in the SKAT container (`VAULT_FORMAT.md`).
- **Interface point (endorsed pattern, ADR-7/ADR-8 in `docs/design/NORTH_STAR_REVIEW.md`):**
  1. The raw capture (photo, audio clip) becomes an **`ATTACHMENT`** via `VaultRepository.createAttachment`.
  2. The derived text (caption / OCR / transcript) becomes an **`AIOUT`** document with `source:` frontmatter plus a **`CITE`** edge.
  3. The `ingest_queue` trigger then chunks, embeds, extracts entities, and places it on the timeline.
  4. The new source enters as **a new `ImportService` method** ("fourth method, not fourth pipeline").

  This maps cleanly onto our research `Observation` record (see `KAVYA_ARCHITECTURE.md` §B.2).

### 2.5 Security boundaries (`:core:security`, manifests, build-logic guards)
- Non-negotiables (spec §2, `docs/superpowers/specs/2026-09-19-skein-design.md:32-44`): **no INTERNET permission ever**, no GMS, no telemetry, isolated-process inference, reproducible builds, hash-verified models.
- Permission set pinned exactly by `app/src/test/kotlin/app/skein/ManifestPolicyTest.kt:52-73` + `tools/ci/manifest-audit.sh`. **Adding CAMERA/RECORD_AUDIO would (correctly) fail these checks**, so it requires an explicit, reviewed policy change.
- `DependencyGuardTask` bans GMS/Firebase/Play/**ML Kit**, so Google ML Kit OCR/ASR is structurally excluded. Perception must use self-contained runtimes (llama.cpp mtmd, whisper.cpp, ONNX).
- Isolated services may depend only on `{:core:ipc, :core:model, :core:verify}` (+ onnxruntime for the embedder), enforced by `IsolationGuardPlugin`.
- Threat model (spec §9): model output must never derive Intent URIs or tool calls; every out-of-app action needs an explicit tap (`ActionPolicy`). `PromptGuardInjectionCorpusTest` already tests tool-call JSON/XML injection.
- **Relevance:** Kavya's `ACTION:` regex pattern would violate Skein's v1 threat model. Her JSON-schema-constrained output plus actuator-side validation is compatible with a future *tap-confirmed* action proposal.

### 2.6 Agents / tools (`:core:agent`)
- `VaultTools` interfaces (read/write/patch/search/links/tags/personas + `AuthorizationToken`) and `docs/design/VAULT_TOOL_PRIMITIVES.md` (capability tiers).
- `docs/design/NORTH_STAR_BRIEF.md`: SOURCE → KNOWLEDGE → SKILL → TOOL → AGENT → INTERFACE, one Agent Runtime, an `InferenceProvider`/Compute Router, a privacy firewall.
- MCP: not in code.

## 3. Desktop / macOS tooling today
- No macOS inference path exists in Skein. `tools/m0-benchmark/` drives the Fold over ADB + SSH/Termux. `native/llama/tests/tokenizer/` is a CPU-only host build (no Metal). `docs/ux/MAC_UX_LAB_PLAN.md` rejects Compose Desktop.
- **The Senses lab is therefore the first Metal-enabled host build of Skein's pinned llama.cpp.**

## 4. Device targets
- Pixel 9 Pro Fold class on GrapheneOS: Tensor G4, 16 GB RAM, Mali-G715 (Vulkan). `device-baseline.txt`: Android 17 (`comet`, `CP2A.260805.005`), arm64-v8a, 8 cores. minSdk 30, target/compileSdk 37.
- Budget (spec §6): 16K context cap, one resident model (~4 GB model + ~1 GB KV). **An mmproj adds ~0.5–1 GB**, which is why E4.I11 plans lazy mmproj loading.

## 5. Quality gates (for any future integration; not run for Senses)
`./gradlew ktlintCheck test testDevDebugUnitTest check` · `tools/test/run-jvm.sh` · CI: `check-submodules.sh`, `lint check`,
`assembleFossDebug`, `jni-symbols.sh`, `no-content-logging.sh`, `manifest-audit.sh`, `validate-manifests.py`.
`Senses/` is not a Gradle module and is not referenced by `settings.gradle.kts`, so it cannot affect these gates.
