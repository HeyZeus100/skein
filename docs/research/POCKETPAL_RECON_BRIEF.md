# Skein orchestrator briefing — PocketPal AI reconnaissance, Skein Hub, local AI pipeline acceleration

**Provenance:** owner's briefing to the coordinator, received 2026-09-22 (late evening, after Fold smoke #1). Recorded verbatim below the rule so agents can cite it by path; the coordinator's reading of it is bd epic `skein-rkrq` (reconnaissance `skein-e8ly`, design `skein-1m7v`; `bd list --label hub`). Related authority that this briefing does not replace: `docs/superpowers/specs/2026-09-19-skein-design.md` (§2 non-negotiables — Core keeps no INTERNET permission), `docs/design/MODEL_STORE.md` (`ImmutableModelStore`), the `ModelManifest` v2 schema (`core/model/src/main/resources/schema/model-manifest.schema.json`, skein-3v9), `:core:verify` (skein-hiwb), plan items E4.I5 (skein-cyq, `ModelManager`/`ModelRegistry`), E4.I6 (skein-5oi, chat templates), E0.I4 (skein-bxk) and E0.I8 (skein-5hr, `MEASUREMENTS.md`).

---

Upstream: a-ghorbani/pocketpal-ai
Purpose: Use PocketPal AI as a reference/donor project to accelerate Skein's local-model infrastructure without replacing Skein's architecture or weakening its security model.

## 1. Objective

Perform a focused engineering reconnaissance of PocketPal AI and determine which components, patterns, tests, algorithms, UX decisions, and implementation details can accelerate Skein.

PocketPal has already solved a substantial amount of mobile local-LLM engineering that overlaps with capabilities Skein either needs now or will need shortly.

The objective is NOT: fork PocketPal and turn it into Skein.

The objective is: mine PocketPal for solved problems so Skein does not spend engineering waves rediscovering them.

Treat PocketPal as: reference architecture; donor repository; behavioral specification; test-case source; benchmark reference; mobile inference reference; model-management reference; Hugging Face integration reference.

Preserve Skein's existing architectural direction.

## 2. Why PocketPal is relevant

PocketPal currently demonstrates production mobile implementations around: GGUF local inference; llama.cpp-based inference; Hugging Face model discovery; direct Hugging Face GGUF downloads; gated Hugging Face models; local GGUF imports; model management; model loading/unloading; model configuration; GGUF metadata; chat templates; streaming generation; chat persistence; model switching; benchmarking; prompt-processing measurements; token-generation measurements; memory measurements; hardware acceleration; CPU fallback; speculative decoding; model lifecycle management; configurable assistants/Pals; tool/function calling/Talents; local TTS; Android E2E testing; mobile inference failure handling.

PocketPal should therefore be inspected before Skein independently implements equivalent infrastructure.

## 3. Architectural rule

PocketPal does NOT become Skein's architecture. PocketPal is primarily built around a React Native stack and llama.rn.

Skein remains based around its existing native architecture: Kotlin, Jetpack Compose, JNI, llama.cpp, ONNX Runtime, SQLCipher, sqlite-vec, FTS5, native Android process isolation — plus Skein-specific systems: encrypted vault; local RAG; knowledge graph; document intelligence; Markdown workspace; entity extraction; local embeddings; isolated inference; isolated embedder; reproducible builds; GrapheneOS-oriented security; offline-first operation.

Do not migrate Skein toward React Native. Do not introduce PocketPal dependencies simply because PocketPal uses them. Do not replace working native Skein infrastructure with llama.rn merely to match PocketPal.

Extract solutions, not necessarily implementations.

## 4. Reconnaissance workstream

Create a bounded PocketPal reconnaissance effort. Clone or vendor a pinned PocketPal revision into a research/reference location separate from production Skein code, for example `research/upstream/pocketpal-ai/`.

Record: repository URL; exact upstream commit SHA; version/tag; license; inspection date; files/subsystems inspected; useful patterns; potentially reusable code; tests worth porting; rejected architecture; security implications; dependencies; attribution requirements.

Create `docs/research/POCKETPAL_RECON.md`.

Every finding should answer: can this eliminate work, eliminate uncertainty, improve reliability, or improve Skein's local-model UX?

## 5. Classify everything found

Every potentially useful PocketPal component receives one classification:

- **COPY** — small self-contained implementation worth adapting directly where licensing permits.
- **PORT** — behavior or algorithm worth reimplementing in native Skein/Kotlin.
- **STUDY** — architecture worth understanding without adopting directly.
- **TEST** — failure case or test worth reproducing in Skein.
- **DEFER** — useful capability that belongs after the immediate Skein release path.
- **REJECT** — architecture or dependency that conflicts with Skein's design.

Prefer PORT + TEST over large-scale copying.

## 6. First major target: model acquisition

PocketPal's Hugging Face integration is one of the highest-value systems to study. PocketPal allows users to discover and download GGUF models from Hugging Face directly from the application.

Study the complete flow: Hugging Face search → repository discovery → GGUF discovery → file selection → quantization selection → storage validation → download → progress → completion → GGUF inspection → model registration → loading → inference.

Investigate specifically: Hugging Face API requests; pagination; repository search; model search; GGUF filtering; nested files; metadata extraction; file sizes; model parameter information; gated repositories; authentication; access tokens; download progress; cancellation; retry; interrupted downloads; storage checks; local model import; model deletion; model bookmarking; model state persistence; error handling; model loading after download.

Extract the behavioral state machine. Do NOT mechanically translate TypeScript into Kotlin.

## 7. Create a Skein model-source abstraction

Avoid embedding Hugging Face assumptions throughout Skein. Design around `ModelSource`, with potential implementations `SkeinCatalogSource`, `HuggingFaceSource`, `LocalFileSource`, and potential future `LANModelSource`, `EnterpriseModelSource`, `ExternalRepositorySource`.

Conceptually: ModelSource → Search → Resolve → Artifact → Download / Import → Verify → Inspect → Register → Benchmark → Load.

The inference engine should not care where the model originated.

## 8. Model registry

Create or evolve one authoritative Skein model registry. Conceptual pipeline: ModelSource → ModelDescriptor → ModelArtifact → integrity validation → GGUF inspection → Skein ModelManifest → compatibility evaluation → runtime profile → InferenceManager.

A model manifest should eventually support fields such as: Skein model ID; display name; source; repository; repository revision; artifact filename; model architecture; parameter count; quantization; file size; SHA-256; tokenizer metadata; chat template; context capability; runtime engine; installed location; installation timestamp; compatibility status; benchmark results; preferred runtime settings.

Do not overengineer the schema before required. Design the seam correctly first.

## 9. Skein Hub

The preferred architecture for Internet-based model acquisition is a separate application: **Skein Hub**. Skein Hub is responsible for network-side model acquisition. Skein Core remains the private offline knowledge environment.

Architecture: INTERNET → SKEIN HUB (INTERNET permission; Hugging Face access; model discovery; model downloads; catalog access; artifact hashing; preliminary inspection) → CONTROLLED ARTIFACT HANDOFF → SKEIN CORE (NO INTERNET permission; encrypted vault; inference; embeddings; RAG; graph; documents; editor; local agents).

This preserves one of Skein's strongest security properties: the application containing the user's private knowledge does not possess Android Internet permission. That is materially stronger than merely promising not to upload data.

## 10. Core security principle

Treat Skein Hub as an untrusted network-side component. Even though we control both applications, Skein Core must distrust all artifacts arriving from Skein Hub. Hub is trusted to identify itself. Hub is NOT trusted to certify Internet data as safe. This distinction is fundamental.

## 11. Hub must never access the vault

Establish this as an architectural invariant: Skein Hub never reads the Skein vault. Hub does not need notes, conversations, embeddings, the graph database, documents, prompts, user history, the entity database, personal context, or agent state. Hub needs model catalogs, model metadata, device compatibility information where explicitly provided, download state, and downloaded artifacts.

Do not create a convenience API that later becomes a vault-exfiltration channel.

## 12. Hub should not command Core

Avoid architecture like Hub calling `executeCommand(...)`, `loadModel(...)`, `readVault(...)`, `runAgent(...)`. Instead: Hub says "Artifact X is available"; Core says "I will independently inspect it and determine whether I accept it." Think artifact transfer rather than remote control API.

## 13. Controlled Android handoff

Investigate a narrow Android-native transfer mechanism. Preferred pattern: Skein Hub → read-only content URI / controlled handoff → Skein Core staging area → Core copies artifact → Core validates artifact → Core accepts/rejects artifact.

Avoid a permanently shared writable filesystem area if possible. Use temporary/read-only URI grants where appropriate. Core should ultimately own its installed model artifact.

## 14. Signature-level authentication

Because Skein controls both APKs, investigate Android signature-protected communication — for example a dedicated permission conceptually equivalent to `us.skein.permission.MODEL_TRANSFER` with `protectionLevel="signature"`. This can prevent unrelated applications from impersonating Skein Hub.

However: same signing identity means Core can authenticate that the sender is genuine Skein Hub. It does NOT mean Core should trust the downloaded GGUF. Authentication and artifact trust must remain separate.

## 15. Model files are untrusted input

GGUF files downloaded from the Internet must be treated as untrusted binary input. They are parsed by native code. Therefore downloaded does NOT equal trusted.

Target pipeline: Hub download → hash → handoff → Core staging → file-size validation → GGUF structural validation → metadata validation → architecture compatibility → runtime compatibility → isolated inference process → ModelRegistry.

Never allow arbitrary metadata from an Internet model to directly control privileged Core operations.

## 16. Hashing and integrity

Hub should calculate SHA-256 after download. Where an authoritative upstream hash is available, compare against it. Core should independently hash the transferred artifact after receiving/copying it. Hub: artifact SHA256 = X; Core: received SHA256 = X; then proceed with validation.

Hash equality proves transfer integrity. It does NOT prove that the model itself is safe. Keep those concepts separate.

## 17. GGUF validation

Study PocketPal's handling of GGUF metadata and loading failures. Skein should validate before registering a model. Potential checks: valid GGUF magic; supported GGUF version; supported architecture; sane tensor counts; sane metadata lengths; sane context values; file-size consistency; quantization support; tokenizer presence; chat-template validity; runtime compatibility.

Use llama.cpp's own validation capabilities wherever appropriate rather than inventing an independent incomplete parser. Keep actual model parsing/loading inside the isolated inference boundary where possible.

## 18. Hugging Face authentication

PocketPal supports gated Hugging Face models using user access tokens. Study: token entry; authentication flow; request construction; gated-model errors; expired tokens; revoked tokens; missing permissions; user-facing error handling.

For Skein Hub: HF credentials belong in Hub. They should never enter Skein Core. Do not store tokens in the vault, ordinary plaintext preferences, logs, diagnostics, exports, model manifests, URLs, or the clipboard unless explicitly requested. Use Android Keystore/StrongBox-backed secret handling where appropriate. Public model downloading should work without authentication.

## 19. Curated Skein model catalog

Raw Hugging Face search should not be the primary experience for ordinary users. Create the concept of **Skein Recommended Models**, for example "Recommended for this device": FAST (Qwen, Q4_K_M, small download, low memory, fast inference); BALANCED (larger model, better reasoning, moderate memory); ADVANCED (Browse Hugging Face →).

The curated catalog should contain models Skein has actually validated.

## 20. Device-aware model recommendations

PocketPal already benchmarks mobile inference. Skein should extend this idea into empirical compatibility. Use actual Skein measurements to build `DeviceProfile` and `ModelRuntimeProfile`. Possible measurements: model load time; TTFT; prompt processing tok/s; token generation tok/s; peak RSS; context tested; sustained generation; thermal state; thermal degradation; backend; thread count; batch size; memory configuration; battery/power where practical.

Then Hub can eventually display Excellent / Good / Heavy / Not recommended based on actual measured profiles. Do not rely solely on parameter count.

## 21. GGUF metadata and chat templates

Study PocketPal carefully here. Skein should minimize hard-coded model-specific knowledge. Prefer authoritative GGUF metadata where available for tokenizer, chat template, architecture, context, model metadata. Avoid endless mappings like Qwen → template A, Gemma → template B, Llama → template C unless necessary as compatibility fallbacks.

Create tests for: valid template; missing template; malformed template; unsupported template; legacy model; model requiring override.

## 22. Download manager

PocketPal has already encountered real-world mobile download problems. Mine this aggressively. Skein Hub needs a robust state machine, for example: Requested → Resolving → CheckingStorage → Downloading → Downloaded → Hashing → Inspecting → ReadyForTransfer → Transferred. Interrupt states: Paused; Cancelled; NetworkLost; AuthenticationFailed; InsufficientStorage; Corrupted; Unsupported; RetryableFailure; FatalFailure.

Downloads may be multiple gigabytes. Test: process death; device sleep; network changes; Wi-Fi → cellular; insufficient disk; partially downloaded file; app restart; duplicate download; canceled download; corrupted file; renamed upstream artifact; repository disappearing mid-download.

PocketPal issues and tests should be harvested for cases already discovered by its users.

## 23. Model storage

Define clear ownership. Hub storage: temporary downloads; incomplete artifacts; transfer-ready artifacts; cache. Core storage: accepted models; runtime metadata; benchmark profiles; trusted registry state. After successful transfer, Hub should be able to remove its copy. Do not unnecessarily maintain duplicate multi-gigabyte models.

## 24. Model import without Hub

Skein Core should still support user-mediated local GGUF import, preserving an entirely networkless workflow: external downloader → Android document picker → Skein Core → validate → import. Hub is optional infrastructure. It should not become mandatory for running Skein.

## 25. PocketPal model settings

Inspect PocketPal's runtime configuration UX. Current PocketPal exposes concepts including context, memory mapping, memory locking, Flash Attention, KV cache types, GPU layers, weight repacking, speculative decoding, draft models, model offload/load behavior.

Do not blindly expose every llama.cpp knob in Skein. Divide settings into **Automatic** (Skein chooses validated settings) and **Advanced** (technical users can override them). The default Skein experience should not require understanding llama.cpp.

## 26. Automatic runtime profiles

Skein can go beyond PocketPal by learning good settings for a device/model pair. Example `ModelRuntimeProfile` for a Pixel-class Fold and a Qwen GGUF: context, threads, batch, mmap, flash attention, KV type, backend — then "Apply Recommended". The benchmark harness should determine these settings empirically.

## 27. Benchmarking

PocketPal includes mobile benchmarking for prompt processing, token generation and memory. Study: benchmark methodology; warmup behavior; prompt construction; sampling; metric collection; memory measurement; repeated runs; result storage; device metadata; model metadata.

Do not blindly copy its scoring/ranking scheme. Skein cares more about "will this model work well on this specific device?" than "which phone wins a leaderboard?".

## 28. Hardware acceleration

PocketPal contains useful work around CPU, Metal, Android GPU/OpenCL, Qualcomm Adreno, Qualcomm Hexagon/NPU, fallback, backend discovery. Not all of this applies to Skein's target hardware. Do NOT import Qualcomm-specific infrastructure simply because it exists.

Study the generalized parts: backend abstraction; capability detection; backend initialization; failure detection; fallback; benchmark comparison; configuration persistence. Skein's measurements on its actual hardware remain authoritative.

## 29. Chat loop

PocketPal provides a mature reference implementation for mobile local chat. Compare Skein against PocketPal for: streaming; cancellation; retry; interrupted generation; context exhaustion; message editing; regeneration; state restoration; model switching; backgrounding; foregrounding; unloading; reloading; process interruption.

Do not replace Skein's architecture. Extract missing behavior and tests.

## 30. Inline editing

PocketPal's message editing/regeneration behavior should be studied. Useful concepts include editing historical input, invalidating downstream context, regeneration, cancellation, persistence, restoring UI state.

However: PocketPal chat editing is NOT equivalent to Skein artifact editing.
