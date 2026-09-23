# Skein research directive — OfflineLLM 5.1.1 upstream analysis

**Provenance:** owner's directive to the coordinator, received 2026-09-23 (morning, after the PocketPal reconnaissance and the Hub/north-star decisions). Recorded verbatim below the rule so agents can cite it by path. Related authority: `docs/research/POCKETPAL_RECON_BRIEF.md` and its output `docs/research/POCKETPAL_RECON.md` (same classification discipline, different donor), `docs/design/SKEIN_HUB.md`, `docs/design/NORTH_STAR_BRIEF.md`, `docs/Handoffs/skein-fold-m0-hardware-handoff.md` (the M0 measurements this directive asks to protect). The coordinator's reading of it is bd `skein-o5f7` (its output is `research/upstream/offlinellm/`). Reference clones live under the git-ignored `research/clones/`; the tracked analysis lives under `research/upstream/offlinellm/` as §17 asks.

---

## Mission

Deploy a dedicated research agent to perform a source-level engineering review of **OfflineLLM v5.1.1** and determine which implementations, fixes, architectural patterns, optimizations, tests, and UX concepts should be adopted, adapted, referenced, or rejected for Skein.

Primary upstream: repository `https://github.com/jegly/OfflineLLM`; target release/tag `5.1.1`; release `https://github.com/jegly/OfflineLLM/releases/tag/5.1.1`.

This is an **upstream intelligence task**, not permission to replace Skein's architecture or indiscriminately copy OfflineLLM. Skein remains its own offline-first personal AI/knowledge system. OfflineLLM should primarily be treated as a mature reference implementation for Android + llama.cpp + GGUF + CPU/Vulkan inference engineering.

## 1. Research rules

The research agent MUST inspect the actual source code associated with v5.1.1. Do not rely only on README claims, release notes, GitHub summaries, blog posts, or prior agent assumptions. Trace important claims to source code, commits, build configuration, or reproducible behavior.

Pin all findings to: repository; tag/commit; relevant source paths; applicable license; date reviewed.

Do not modify Skein production code during this research task unless separately authorized. Do not introduce new dependencies. Do not alter current M0 benchmark targets. Do not assume an upstream technique is appropriate for Skein merely because it works in OfflineLLM.

## 2. Current Skein context

Skein is an offline-first Android personal AI/knowledge environment targeting GrapheneOS, currently centered on the Pixel 9 Pro Fold development device. Relevant architectural principles include: no `INTERNET` permission; no Google Play Services requirement; no telemetry; local-first operation; reproducible FOSS builds; GGUF model support; llama.cpp native inference; Vulkan acceleration where beneficial; isolated inference architecture; encrypted knowledge vault; user-imported models; model manifests with provenance and hashes; model licenses handled independently from Skein's shipped software dependencies; eventual RAG; FTS/vector retrieval; knowledge graph; repository ingestion; skills; CLI/tooling; agents; optional future selective frontier-model compute.

Skein must remain useful without any network connection. The APK does NOT ship model weights. Default-model manifests point to separately acquired model artifacts. Do not weaken these constraints to match OfflineLLM.

## 3. Primary research questions — A. llama.cpp / JNI architecture

Map OfflineLLM's complete path: Kotlin → JNI → C/C++ → llama.cpp → backend → hardware. Determine: JNI boundary design; lifecycle management; model loading; context creation/destruction; cancellation; token streaming; sampling; memory ownership; thread handling; error propagation; native crash handling; resource cleanup. Compare this against Skein. Identify anything that would simplify or harden Skein's existing native inference layer.

## 4. ARM CPU runtime dispatch

Investigate OfflineLLM's ARM64 CPU dispatch strategy in detail. Determine whether and how it ships multiple optimized kernels for capabilities such as baseline ARMv8, DOTPROD, FP16, I8MM, SVE, other ARM extensions. Document: (1) how CPU capabilities are detected; (2) how the correct implementation is selected; (3) whether selection occurs at build time, load time, or runtime; (4) APK size impact; (5) native library organization; (6) reproducible-build implications; (7) GrapheneOS compatibility; (8) whether the technique is upstream llama.cpp functionality or OfflineLLM-specific engineering. Evaluate whether Skein should adopt it now, schedule it after M0, implement a simpler equivalent, or leave CPU dispatch to llama.cpp.

## 5. Vulkan / CPU backend isolation — HIGH PRIORITY

Study the v5.1.1 fix concerning Vulkan registration affecting CPU-only inference. Determine exactly: what behavior caused CPU inference degradation; how Vulkan registration affected buffer selection; whether GPU memory was allocated despite GPU offload being disabled; whether optimized CPU weight repacking was prevented; how v5.1.1 corrected the problem; which llama.cpp version/API behavior was involved; whether current Skein could suffer from the same condition.

Produce a Skein regression-test specification. At minimum Skein should eventually be able to verify: **CPU-only means genuinely CPU-only.** When Vulkan is disabled: Vulkan should not influence CPU buffer selection; unnecessary GPU memory should not be reserved; optimized ARM kernels should remain available; CPU performance should not materially regress simply because Vulkan support exists in the binary.

Compare this directly against Skein's current M0 Fold measurements. Do NOT assume OfflineLLM's Pixel results apply to the Pixel 9 Pro Fold.

## 6. CPU vs Vulkan benchmark strategy

Investigate OfflineLLM's findings around: CPU generation; Vulkan prompt processing; full GPU offload; partial GPU offload; graph fragmentation; synchronization overhead; shared-memory bandwidth. Determine whether Skein should eventually stop treating backend selection as a static setting.

Research the feasibility of a Skein **Inference Planner**: model + device → short benchmark → recommended execution profile. Potential measurements: prompt-processing tok/s; generation tok/s; time-to-first-token; RAM; GPU memory behavior; thermal state; power implications where measurable; context size; stability. Consider hybrid policies such as prompt processing → Vulkan, autoregressive generation → CPU. Do not implement this yet. Produce an architecture proposal only.

## 7. Context window safety

Study OfflineLLM's handling of GGUF models advertising very large context windows. Determine: how context is capped; how model size influences the cap; how overrides work; how KV-cache requirements are estimated; failure behavior. Compare this against Skein.

Design a future Skein **Resource Planner** concept: GGUF metadata + model size + device RAM + KV requirements + current memory availability → recommended context. The user should retain an advanced override. Skein should avoid blindly allocating a model's theoretical maximum context when that configuration is inappropriate for the device.

## 8. Memory optimizations

Investigate OfflineLLM's memory-related changes, particularly logits allocation. Trace the reported reduction associated with avoiding unnecessarily large logits buffers. Determine: exact upstream implementation; whether the optimization exists in current llama.cpp; whether Skein already benefits from it; whether Skein can accidentally recreate the problem through JNI/API usage.

Perform a broader source audit for other Android-specific memory techniques. Look for: unnecessary copies; JNI copies; mmap usage; model mapping; KV allocation; logits allocation; temporary buffers; Vulkan buffers; lifecycle leaks; context recreation; Android memory-pressure handling. Produce concrete tests Skein can use to detect regressions.

## 9. Incremental conversation / KV cache

Investigate how OfflineLLM preserves conversation state and avoids reprocessing the entire conversation every turn. Trace: KV-cache lifecycle; prompt delta handling; chat-template interaction; context rollover; invalidation; conversation edits; model switching; system-prompt changes.

Compare against Skein's planned RAG architecture. Skein will eventually inject retrieved notes, documents, repository context, knowledge-graph context, skills/tool results. Determine which caching concepts remain valid when dynamic RAG context changes between turns. Do not simply copy a chat-only caching architecture if it would constrain Skein later.

## 10. GGUF import pipeline

Study OfflineLLM's arbitrary GGUF import workflow. Trace: Android file picker → validation → metadata inspection → storage/access → model registration → inference. Evaluate: SAF handling; persisted URI permissions; copying versus direct access; validation; malformed GGUF behavior; metadata parsing; architecture compatibility detection; quantization detection; model naming; duplicate models; deletion; storage accounting.

Compare against the planned Skein Model Manager. Skein's longer-term model sources should support default manifests, user import, and the future Skein Hub; all should eventually converge into a common internal model registry.

## 11. Model provenance and licensing

Skein's current policy direction is: **permissive-only for software distributed as part of the FOSS application; separately acquired model weights may have different licenses, provided their provenance and licensing are explicitly disclosed.** The APK does not ship the default model weights.

Review whether any OfflineLLM mechanisms are useful for recording: model name; exact artifact; upstream source; revision; SHA-256; size; quantization; architecture; license identifier; license URL/reference; acquisition method; compatibility; verification state. Do NOT blindly inherit OfflineLLM's model licensing assumptions. Verify licenses independently.

## 12. Backend diagnostics

Study OfflineLLM's performance/backend diagnostics. Design an equivalent Skein developer diagnostic surface. Potential output: model; GGUF quantization; model digest; llama.cpp revision; backend; GPU; CPU capabilities (DOTPROD, I8MM, SVE); context; KV cache; model RAM; prompt tok/s; generation tok/s; time to first token; thermal state; memory pressure; GPU layers; threads.

The report should eventually be exportable as a privacy-safe diagnostic artifact. Do not include private prompts, vault contents, document names, or other user knowledge.

## 13. Security review

OfflineLLM is a reference, not Skein's security model. Identify anything that conflicts with Skein's stronger isolation goals. Pay particular attention to: Android permissions; network capability; file access; native library loading; arbitrary GGUF parsing; untrusted model files; JNI attack surface; subprocess behavior; shared storage; model download behavior; WebView usage; telemetry; external services.

Assume imported GGUF files are **untrusted input**. Document any attack surfaces Skein should defend against.

## 14. Build and reproducibility review

Study: CMake; Gradle; NDK configuration; llama.cpp pinning; Vulkan build configuration; shader generation; ABI selection; native library packaging; release reproducibility; dependency acquisition. Compare against Skein's FOSS/reproducibility requirements.

We recently observed that Skein's Vulkan-capable native inference library is approximately 24 MiB versus an earlier 12 MiB budget. We have tentatively chosen to **raise the v1 budget and retain Vulkan**, rather than prematurely trimming shaders or going CPU-only. Determine whether OfflineLLM contains techniques that could later reduce Skein's native footprint without restricting arbitrary GGUF support unnecessarily, carrying fragile patches, harming Vulkan performance, or damaging reproducibility. APK-size optimization is secondary to correctness and performance for v1.

## 15. Do not turn Skein into OfflineLLM

Maintain the architectural distinction. OfflineLLM is primarily useful as prior art for local Android inference. Skein's intended scope is broader: local inference + encrypted personal knowledge + retrieval + knowledge graph + documents + repositories + skills + CLI + tools + agents + optional selective external compute later. Any adopted upstream code must fit this architecture rather than forcing Skein into a chat-app architecture.

## 16. Required adoption matrix

Every significant OfflineLLM discovery must receive one classification:

- **ADOPT** — appropriately licensed, compatible with Skein, technically superior, and worth incorporating.
- **ADAPT** — the underlying idea is valuable, but Skein should implement or restructure it for its architecture.
- **REFERENCE** — useful for tests, benchmarks, design decisions, or understanding Android inference, but no implementation should be incorporated.
- **REJECT** — conflicts with Skein's security, architecture, licensing, reproducibility, performance, or long-term goals.

For every item include: feature; OfflineLLM source path; relevant commit/tag; license; classification; reasoning; Skein equivalent/current status; estimated implementation difficulty; expected benefit; risks; recommended milestone.

## 17. Deliverables

Create `research/upstream/offlinellm/` with at least: `README.md`, `ARCHITECTURE_ANALYSIS.md`, `JNI_ANALYSIS.md`, `CPU_DISPATCH_ANALYSIS.md`, `VULKAN_ANALYSIS.md`, `MEMORY_ANALYSIS.md`, `KV_CACHE_ANALYSIS.md`, `GGUF_IMPORT_ANALYSIS.md`, `MODEL_PROVENANCE_ANALYSIS.md`, `SECURITY_ANALYSIS.md`, `BUILD_REPRODUCIBILITY_ANALYSIS.md`, `INFERENCE_PLANNER_PROPOSAL.md`, `REGRESSION_TEST_PROPOSAL.md`, `ADOPTION_MATRIX.md`, `EXECUTIVE_RECOMMENDATION.md`.

The executive recommendation should be concise and answer: (1) what should Skein adopt immediately? (2) what should Skein adapt later? (3) what should only be used as reference? (4) what should Skein explicitly avoid? (5) are any current Skein M0 assumptions wrong? (6) are any current M0 benchmarks potentially misleading? (7) are there bugs OfflineLLM has already solved that Skein is at risk of reproducing? (8) what work can be deleted or avoided because upstream already solved it? (9) what are the five highest-value changes for Skein? (10) should any of those changes occur before M0 is declared complete?

## 18. Protect current progress

This research task must not derail M0. The purpose is to determine whether upstream knowledge can **reduce Skein engineering work and prevent mistakes**. If a finding would require major architectural changes, document it rather than immediately implementing it.

However, immediately flag any finding that suggests: our benchmark is invalid; Vulkan/CPU measurements are contaminated; memory measurements are misleading; current native code has a serious correctness issue; current architecture creates a security vulnerability; model hashes/provenance are incorrect; reproducibility is compromised. These findings should be escalated to the orchestrator before continuing normal work.

## 19. Final research question

The research agent should ultimately answer: what has OfflineLLM already learned about running llama.cpp efficiently, reliably, securely, and maintainably on Android that Skein should not waste time rediscovering?

The objective is not NIH engineering. The objective is also not wholesale reuse. **Reuse proven upstream engineering where appropriate, preserve Skein's differentiated architecture where necessary, and spend our engineering effort on the parts that make Skein unique.**
