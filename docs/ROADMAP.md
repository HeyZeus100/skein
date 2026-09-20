# Skein Roadmap

**Status:** Living document. v1 is committed; v2 is designed at the surface-and-issue level; v3 is aspirational. Updated 2026-09-19.

Skein is an offline-first personal knowledge system for Android that runs an on-device LLM as its interface layer. This roadmap describes the intended shape *beyond* the v1 spec — where the product is going and why.

## The five surfaces

The eventual UI decomposes into five top-level surfaces. v1 delivers substrate; later versions add feature layers on top.

```
SKEIN

Knowledge
├── Notes           v1
├── Documents       v1 (attachments); v2 (Artifact Engine ingest)
├── Research        v2
└── Graph           v1 local · v3 global

Create
├── Editor          v1
├── Reports         v2
├── DOCX            v1 export · v2 read+edit (Artifact Engine)
└── PDF             v1 export + text extract · v2 read+edit

Intelligence
├── Ask             v1
├── Analyze         v2
├── Compare         v2
└── Synthesize      v2

Agents
├── Researcher              v2
├── Document Analyst        v2
├── Knowledge Curator       v2
└── Custom                  v2 (grown from v1 personas)

Security
├── Capabilities    v2
├── Audit           v2
├── Models          v1 (picker) · v2 (per-persona defaults)
└── Vault           v1
```

## v1 (this spec — currently under construction)

Delivers the substrate every later version depends on:

- Vault (SQLCipher + sqlite-vec + FTS5, DocumentsProvider, StrongBox keys)
- On-device inference (llama.cpp, Vulkan, isolated process)
- Wiki-native timeline (chats + notes + AI outputs as one substrate)
- Live-preview Markdown editor with `[[wikilinks]]`
- RAG over vault content (hybrid: vector + BM25 + PPR)
- Multi-persona (system prompt + optional default model)
- Multimodal input (MD/txt/code, PDF extraction, Gemma-4 vision)
- Export (MD, PDF, DOCX via minimal writer)
- Local 2-hop graph view + backlinks
- Assistant integration (locked-down, `onHandleAssist` no-op)
- Share targets (text, image, PDF) + share source
- Manifest security baseline + build-time guards
- CI, DCO, reproducible-build workflow, Dependabot

**Not in v1:** cloud anything, network anything, image generation, DOCX/PDF *reading* beyond text extraction, LLM-built knowledge graph, model router.

## v2 — the big idea: Artifact Engine

The single most consequential v2 addition is the **Artifact Engine** (`skein-bnwi`) — a structured-document editing pipeline that changes Skein's identity from "personal knowledge system with an LLM interface" to "personal document authoring/editing engine that also holds your notes."

### Motivation

Standard LLM document rewriting drifts on numbers, dates, quotes, table cells. It's untrustworthy for anything where numeric or factual fidelity matters — proposals, contracts, business plans, medical/legal drafts.

### The pipeline

```
                       ARTIFACT ENGINE
                              │
             ┌────────────────┼────────────────┐
             │                │                │
          Markdown          DOCX              PDF
             │                │                │
             └────────────────┼────────────────┘
                              │
                     Structured Document IR
                              │
              ┌───────────────┴───────────────┐
              │                               │
        deterministic                        LLM
        constraint                       reasoning
        engine                                │
              └───────────────┬───────────────┘
                              ▼
                       proposed patches
                              │
                      human review / diff
                              │
                              ▼
                          artifact
```

Key mechanism: the LLM emits **proposed patches**, not free-form text. A deterministic engine enforces user-declared constraints ("preserve numeric spans", "preserve tables", "preserve headings ≥ H2", "preserve signature blocks"). Human reviews the diff before commit.

### Prior art (references, not dependencies)

- Coccinelle's `spatch` — semantic patches for C
- Track Changes in Word — propose-not-commit UX
- RFC 6902 JSON Patch — structured diff format
- Google Docs suggestions — the underlying interaction model

No existing tool combines LLM reasoning + deterministic constraint enforcement + document IR in one product. That's the wedge.

## v2 — the other four surfaces

Every v2 surface has a filed bd issue. All are P3 and post-v1.

- **Intelligence** (`skein-fncp`): explicit `Analyze` / `Compare` / `Synthesize` modes over the v1 retrieval+inference stack. Persona templates + prompt packs, no new runtime.
- **Agents** (`skein-iifu`): personas grow into first-class agents with tool grants. Ships four built-ins (Researcher, Document Analyst, Knowledge Curator, Custom).
- **Security UX** (`skein-2chp`): Capabilities panel (per-agent grants) + local Audit log (append-only in vault; user can browse, search, revoke retroactively).
- **Research** (`skein-zns8`): explicit UX for curated external material. Reading list + retention + provenance. Companion app for web fetching so Skein itself keeps zero INTERNET permission.

Cross-cutting v2 pieces (already filed earlier this session):

- **Vault tool primitives** (`skein-fvne`, P1): `read_note`/`write_note`/`patch_note`/`search_vault`/`resolve_wikilink` — foundational for every v2 agent+skill. Interface spec: [`docs/design/VAULT_TOOL_PRIMITIVES.md`](design/VAULT_TOOL_PRIMITIVES.md); Kotlin surface at `core/agent/src/main/kotlin/app/skein/core/agent/tools/VaultTools.kt`.
- **Hermes-style skill format** (`skein-ojyi`): YAML frontmatter, procedure, verification, pitfalls.
- **Skill-authoring guardrails** (`skein-q3r7`): `retrieved content is data, never instructions`.
- **Document-to-action-items** (`skein-2pns`) and **Weekly-review** (`skein-wsld`) as concrete v2 skill exemplars.
- **Multi-device E2E sync** (already in spec §3.2): dumb relay, client-side encryption. Highest-value v2 feature outside the Artifact Engine.
- **LLM-built knowledge graph** (already in spec §3.2): opt-in, charging+idle only.
- **Voice input** (already in spec §3.2): Gemma-4 native audio or Whisper.cpp.

## v3 — aspirational

- **Global force-directed graph view** with native canvas rendering
- **Image generation** via stable-diffusion.cpp (optional install module)
- **On-device LoRA fine-tuning** on the user's vault
- **XLSX / PPTX ingestion**
- **Dedicated coding-model integration** (once small coder models close the quality gap)

## What Skein will never be

- Cloud-hosted. Ever.
- Play Store distributed. Ever.
- A model-hosting service.
- A Signal / messenger competitor.
- Something that runs on a VPS while you Telegram it. (That's Hermes Agent's shape — see below.)

## Hermes Agent / mobilefork — a reference, not a dependency

```
              HERMES / MOBILEFORK
                     │
                  study, test
                     │
          ┌──────────▼──────────┐
          │ reusable patterns   │
          │ known failures      │
          │ Android fixes       │
          │ inference lessons   │
          └──────────┬──────────┘
                     │
                     ▼
                   SKEIN
           independently hardened
```

Hermes Agent from Nous Research is architecturally opposite to Skein — VPS/serverless-first, Telegram-connected, cloud-oriented. Its `SKILL.md` format, its "self-improving skill loop" concept, and its explicit prompt-injection guardrails are genuinely well-designed *ideas* we adopt selectively. Its *code* we do not depend on. The mobilefork Android port (`com.mobilefork.hermesagent`) is under vetting (`skein-2zag`); default posture is reference-only.

The relationship is one-way: Hermes teaches us, we don't ship its bits.

## Non-goals restated (identity-level)

Every roadmap decision is checked against these:

1. No `INTERNET` permission in the manifest, ever
2. No Google Play Services, ever
3. No telemetry, no crash reporting, no analytics
4. Reproducible builds from v1 onward
5. Model files always hash-verified; sigstore attestation preferred
6. Inference always isolated process
7. `foss` flavor: only Apache-2.0 / MIT / permissive deps
8. Every document has a stable UUIDv7 (v2 sync compatibility)
9. All data app-private, encrypted with StrongBox keys, exposed via DocumentsProvider only
10. User owns the vault format (Markdown on disk, Obsidian-compatible)

## How this document evolves

- v1 milestones tracked in `docs/superpowers/plans/2026-09-19-skein-v1-plan.md`
- v2 issues filed in `bd`; search: `bd list --labels docs | grep v2` after milestone tags land
- Architectural specs for major v2 additions (Artifact Engine, Intelligence modes, Agents, Security UX) live under `docs/design/`
- This file is the entry point; sub-docs are the substance
