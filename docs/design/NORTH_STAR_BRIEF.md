# Skein orchestrator direction update — architectural north star: a user-owned cognitive runtime

**Provenance:** owner's briefing to the coordinator, received 2026-09-22 (late night, after the PocketPal/Hub briefing). Recorded verbatim below the rule so agents can cite it by path. It supplements `docs/research/POCKETPAL_RECON_BRIEF.md` and `docs/design/SKEIN_HUB.md`; it does **not** expand v1 scope (its own §24–§25 and §28 say so). The coordinator's reading of it is bd `skein-utrb` (its output is `docs/design/NORTH_STAR_REVIEW.md`).

---

This document supplements the existing PocketPal and Skein architecture briefings.

Do not interpret this document as authorization to expand the current v1 scope.

Its purpose is to establish the architectural direction so current implementation decisions do not accidentally prevent the system Skein is intended to become.

## 1. Product north star

Skein should no longer be conceptualized internally as: an Android notes application with a local LLM.

The intended long-term architecture is: a private, user-owned cognitive runtime that can accumulate knowledge, skills and history; reason over them with replaceable models; and safely act on the user's digital environment.

Android/GrapheneOS is the first runtime and primary proving ground. The architecture should not unnecessarily prevent future desktop/Linux/macOS clients.

Skein should remain useful without: a cloud account; a subscription; a network connection; a specific model vendor; a specific inference provider.

## 2. The model is not the brain

This is a fundamental design principle. Do not allow Skein's accumulated intelligence to become coupled to a particular model.

Conceptually: the SKEIN BRAIN (raw sources, compiled wiki, knowledge graph, search indexes, entities, decision history, skills, rules, provenance) → Agent Runtime → Compute Router → { Local Model | optional Frontier Compute }.

Models are replaceable reasoning engines. The user's accumulated Skein should survive model replacement.

## 3. Architectural primitives

Future Skein capabilities should generally map into the following primitives, and these concepts must remain distinct:

SOURCE → KNOWLEDGE → SKILL → TOOL → AGENT → INTERFACE

- **SOURCE** — original evidence: Markdown, PDF, DOCX, repository, conversation, image, dataset, imported graph.
- **KNOWLEDGE** — derived/indexed representation: wiki, chunks, embeddings, entities, relationships, timelines, summaries.
- **SKILL** — procedural knowledge; answers "how should this task be performed?".
- **TOOL** — executable capability; answers "what operation can Skein perform?".
- **AGENT** — combines knowledge, skills, tools and reasoning toward an objective.
- **INTERFACE** — how the user interacts with that runtime: chat, editor, CLI, command palette, future desktop client.

Do not collapse these layers unnecessarily.

## 4. One agent runtime

Do not build separate intelligence implementations for Chat, Editor, CLI and future interfaces. Target: one Agent Runtime serving Chat, Editor and CLI. They should eventually share: context construction; retrieval; model access; skills; tools; permissions; approval system; provenance; checkpoints.

This is an important anti-rewrite constraint.

## 5. Add a compute abstraction

Current Skein may use llama.cpp directly underneath the application. Do not allow higher-level agent logic to become permanently coupled to one inference implementation.

Establish or preserve an abstraction conceptually equivalent to `InferenceProvider`. Initial implementation: `LocalLlamaProvider`. Future possibilities: `LocalLlamaProvider`, `OpenAIProvider`, `AnthropicProvider`, `GeminiProvider`, `CustomProvider`.

This does NOT mean implementing frontier providers now. It means the Agent Runtime should request inference rather than becoming inseparable from llama.cpp-specific APIs.

## 6. Selective compute

Long-term Skein should support local-first reasoning with explicit escalation to frontier compute when justified and permitted. This is not conventional cloud AI. Local execution remains the default.

Potential flow: user request → local agent → local retrieval → task decomposition → can the local model solve it? YES → local; NO → frontier candidate → privacy policy → context capsule → user/policy approval → frontier compute → local integration.

The objective is to spend remote compute only where it materially improves the result.

## 7. Context capsules

Introduce the architectural concept of a **Context Capsule**: the explicit, minimal payload Skein proposes to expose to external compute. Example — Purpose: concurrency analysis. Included: `AgentRuntime.kt`, `InferenceManager.kt`, the relevant stack trace. Excluded: personal vault, unrelated projects, conversations, credentials, knowledge graph. Provider: external reasoning provider. Approval: required.

Frontier providers should never receive implicit access to the entire Skein brain. The Core constructs the minimum required context.

## 8. Privacy firewall

Before any external inference request: Context Builder → Privacy Policy → Redaction → Context Capsule → Approval → Network Boundary.

Potential future controls: AIRGAP, LOCAL ONLY, SELECTIVE, PROJECT-APPROVED, DEVELOPER. Project/namespace policy should eventually be possible, e.g. `Personal/ frontier = NEVER`, `Private-Research/ frontier = APPROVAL`, `Public-Code/ frontier = ALLOWED`.

No skill, agent or model may override these policies.

## 9. Skein Hub becomes the network gateway

The previous decision to separate Skein Hub becomes even more strategically useful. Target: INTERNET → SKEIN HUB (INTERNET ✓: HF models, downloads, catalogs, optional frontier AI) → TRUST BOUNDARY → SKEIN CORE (INTERNET ✗: vault, wiki, graph, RAG, skills, agents).

Core should retain no Android Internet permission if technically feasible. Hub should become a narrowly scoped network broker.

## 10. Separate Hub capabilities

Do not treat Hub installation as blanket authorization for network behavior. Conceptually separate permissions/capabilities: MODEL_DISCOVERY, MODEL_DOWNLOAD, FRONTIER_INFERENCE, BRAIN_PACK_DOWNLOAD, SKILL_DOWNLOAD, future network capabilities.

A user who enables model downloads has not necessarily authorized sending context to a frontier model.

## 11. Frontier models never receive the vault

This should become a hard architectural principle. Do NOT implement "frontier model → search entire vault". Prefer: Skein Core → local search → local retrieval → local ranking → minimal Context Capsule → frontier reasoning.

This dramatically reduces both privacy exposure and remote token usage.

## 12. Selective compute should also control cost

Future ComputePolicy should be able to reason about: privacy; task complexity; local model capability; network availability; context size; latency; battery; thermal state; provider capability; monetary cost. Conceptual request: `reasoning = HIGH; context = 30K; privacy = SELECTIVE; max_remote_cost = $0.20`.

The Agent Runtime should not care which provider satisfies the request. The Compute Router resolves it under policy.

## 13. Local models should do the cheap work

The intended hybrid architecture is NOT "send difficult prompts to the cloud". It should support task decomposition: LOCAL COMPUTE (classify request, search 10,000 documents, retrieve candidates, rank evidence, extract entities, build context, identify the unresolved question) → FRONTIER COMPUTE (perform difficult reasoning/synthesis) → LOCAL COMPUTE (validate returned claims, reconnect citations, format output, update local state, propose wiki changes, propose graph changes).

Remote compute should become a specialist resource.

## 14. Compute transparency

Future Skein should make external compute observable. Potential task receipt: local inference 14 calls; frontier inference 1 call; external context 18 KB, 3 files; vault exposure none; remote cost $0.09.

The user should be able to inspect what left their device. This transparency should influence architecture even if the UI comes much later.

## 15. Skills may declare compute requirements

Skills should eventually be capable of declaring requirements/preferences such as `compute: { local_preferred: true, frontier_allowed: true, frontier_requires_approval: true }` and context restrictions such as `privacy: { permitted: [active_document, public_reference], forbidden: [personal, credentials] }`.

However: skills describe requested capability. They do not grant themselves authority. ComputePolicy remains authoritative.

## 16. Skills should be portable and inspectable

Continue toward an architecture where a skill may conceptually resemble `skills/android-debug/{SKILL.md, references/, prompts/, schemas/, scripts/}`. Skills should preferably be: human-readable; versionable; inspectable; portable; permission-declared; model-independent where practical.

This makes Skein capable of accumulating procedural intelligence without fine-tuning the model.

## 17. Brain Packs = knowledge + optional skills

Continue preserving the concept of portable Brain Packs, conceptually `Local-AI.skein` = Knowledge (sources, wiki, graph, entities, provenance) + Skills (benchmark-model, analyze-gguf, debug-llama, build-android). A Brain Pack can therefore teach Skein both what something is and how to work with it.

Do not finalize a file/archive format yet.

## 18. Repositories are knowledge sources

Repository import should eventually create structural understanding rather than only embeddings: files, symbols, functions, classes, dependencies, imports, tests, documentation, commits, architecture relationships. This enables Cursor-like codebase reasoning while preserving Skein's broader knowledge model.

## 19. Compiled wiki

Continue planning for an LLM-Wiki-inspired compiled knowledge layer: Raw Sources → Parser → Extraction → Compiled Wiki → Graph + Indexes. This layer should capture reusable understanding so the local model does not repeatedly reconstruct everything from raw chunks.

But: the wiki is derived knowledge, not source truth. It must retain provenance and remain rebuildable.

## 20. Semantic history

Skein should eventually understand not merely "what is true now?" but "how did our understanding reach this state?" — e.g. March: prototype uses model A → June: testing moves to model B → August: thermal measurements reject B → Current: model A restored; question "why are we using model A?" answered by reconstructing the decision path.

Preserve enough history/provenance now that this remains possible.

## 21. Checkpoints + provenance + diffs form the safety model

Increasing agent autonomy should be balanced by PROVENANCE + PERMISSIONS + DIFFS + CHECKPOINTS + USER APPROVAL. Do not solve agent safety primarily through prompts. Enforce it structurally.

## 22. Future background work

Skein should eventually support local background cognitive jobs such as: index repository; compile wiki; extract entities; rebuild graph; detect duplicates; find contradictions; refresh summaries; analyze imported papers; curate knowledge. These jobs should use the same Agent/Skill/Tool primitives where practical. Do not create a completely separate automation architecture unnecessarily.

## 23. Portability

Avoid Android-specific assumptions in core data formats where practical. The intended future could include one portable/encrypted SKEIN BRAIN used from Android, Linux and macOS. This does NOT require building desktop applications now. It means portable schemas, explicit interfaces, deterministic formats, and platform-specific capabilities behind adapters should be preferred when reasonable.

## 24. What this means for current development

Do NOT begin implementing frontier APIs, desktop Skein, a full Brain Pack marketplace, multi-agent swarms, a massive skills library, cloud sync, or arbitrary shell agents because of this document.

Instead, when touching current architecture, ask:

- **A.** Is Chat directly coupled to llama.cpp? If yes, consider whether a small inference abstraction prevents future problems.
- **B.** Are mutations bypassing a common approval/diff mechanism? Avoid creating multiple mutation pathways.
- **C.** Are provenance fields being discarded? Preserve source identity where practical.
- **D.** Is retrieval coupled to one content type? Prefer extensible source abstractions.
- **E.** Are UI surfaces directly implementing agent logic? Prefer the shared Agent Runtime.
- **F.** Are Android-specific storage representations becoming the canonical knowledge format? Avoid unnecessary platform lock-in.
- **G.** Are source material and AI-derived knowledge being mixed irreversibly? Keep them distinguishable.

These are architectural hygiene checks, not new epics.

## 25. Current priority rule

This update must NOT destabilize the current build. Rule: if an abstraction can be introduced cheaply while implementing existing work, introduce the seam. If supporting the future architecture requires substantial new implementation, document it and defer it.

Do not rewrite functional components solely to satisfy speculative future architecture. Do not expand current release scope.

## 26. Long-term system model

SKEIN HUB (network boundary: models / optional compute / packs) → TRUST BOUNDARY → SKEIN CORE: raw sources → knowledge compiler → { wiki, graph, indexes } → Skein Brain → { knowledge, skills, rules } → Agent Runtime → Tool Registry → Compute Router → { local models | frontier via Hub } → permission / diff → checkpoint → commit → { Chat, Editor, CLI }.

## 27. North-star principles

Use these principles when future architectural choices conflict:

1. Local first.
2. User owns the brain.
3. Core remains useful offline.
4. Models are replaceable.
5. Raw evidence is preserved.
6. Derived knowledge carries provenance.
7. Skills are inspectable procedures.
8. Tools have explicit permissions.
9. Agents do not grant themselves authority.
10. Mutations are reviewable and reversible.
11. External compute receives minimum necessary context.
12. Network capability stays outside Core where practical.
13. One Agent Runtime serves multiple interfaces.
14. Portable architecture is preferred over unnecessary Android lock-in.
15. Do not sacrifice the current release to build the future prematurely.

## 28. Immediate orchestrator action

Do not create a large new implementation queue from this briefing. Instead:

1. Review the existing architecture against the principles above.
2. Identify only architectural decisions currently being made that could create expensive future lock-in.
3. Introduce cheap seams where appropriate.
4. Record deferred concepts as architecture notes/ADRs rather than implementation tasks.
5. Continue executing the existing critical path.
6. Flag any current implementation where adopting one of these boundaries now would materially reduce a future rewrite.

The desired result is: ship the current Skein while quietly ensuring that its foundations can grow into the larger cognitive runtime described here. Do not allow the north star to become scope creep.
