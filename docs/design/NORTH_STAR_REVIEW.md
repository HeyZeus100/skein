# North-star architecture review — seams now, ADRs for the rest

**Status:** review, bd `skein-utrb`. **Date:** 2026-09-22.
**Authority:** `docs/design/NORTH_STAR_BRIEF.md` (owner direction, verbatim), especially its §24
(hygiene checks A–G), §25 (priority rule), §27 (fifteen principles) and §28 (the six actions). This
document *is* §28 items 1, 2, 3, 4 and 6; item 5 ("continue executing the existing critical path") is
satisfied by the fact that nothing here becomes a new bead.
**Read against:** `docs/ARCHITECTURE.md`; plan §2.4 module map
(`docs/superpowers/plans/2026-09-19-skein-v1-plan.md`); `core/model` (`Inference.kt`, `Retrieval.kt`,
`Vault.kt`, `Revisions.kt`, `Transfer.kt`); `core/agent/.../VaultTools.kt`;
`docs/design/{VAULT_TOOL_PRIMITIVES,SKILL_GUARDRAILS,ARTIFACT_ENGINE,SKEIN_HUB}.md`;
`docs/VAULT_FORMAT.md`; `app/.../ingest/IngestPipelines.kt`; `core/rag`
(`RetrievalServiceImpl`, `RetrievedAssembler`, `CitationRecords`); `core/vault/.../ImportServiceImpl.kt`;
`docs/research/POCKETPAL_RECON.md` §7–§8; and the beads `skein-1uw`, `skein-cyq`, `skein-6as`,
`skein-5oi`, `skein-ps0`, `skein-3uh`, `skein-2cd`, `skein-6sd`, `skein-v9g`.
**Constraint honoured throughout:** `docs/Handoffs/skein-v1-autonomous-completion.md` §3. Every seam
below keeps Core at zero `INTERNET` and every model file dual-hash-verified before mmap. Nothing here
relaxes a non-negotiable.

---

## 1. What this document may and may not do

Brief §25 is the governing rule: *introduce a seam only if it is cheap inside work already happening;
otherwise document it and defer*. Brief §28 adds: *do not create a large new implementation queue*.

So every row in §2 carries exactly one of three verdicts:

- **SATISFIED** — the codebase already *is* the seam. The cell cites the type or file that is it.
- **SEAM NOW** — a cheap boundary inside a bead already on the critical path. The row names the bead
  and the exact change; §3 spells each one out. Every SEAM NOW is something a Sonnet agent can do
  inside the named bead in under a day, and none of them changes a locked contract.
- **DEFER** — recorded as an ADR-style entry in §4, with the one line that matters: what must **not**
  be done now that would preclude it.

**This review files no beads.** The coordinator applies the seven SEAM NOW items as notes on the named
beads. Counts: **13 SATISFIED · 7 SEAM NOW · 2 DEFER**.

## 2. Verdict table

### 2.1 The fifteen principles (brief §27)

| # | Principle | Verdict | Citation / exact change |
|---|---|---|---|
| 1 | Local first | SATISFIED | `ManifestGuardPlugin`/`checkManifestGuards*` fails any merged variant carrying `INTERNET`; `DependencyGuardTask` fails on a resolved GMS/Firebase/Play/MLKit group (`build-logic/guards/...`, `ARCHITECTURE.md` §2.2). Not a convention — a build failure. |
| 2 | User owns the brain | SATISFIED | SQLCipher `vault.db` + `attachments/` + `ExportService.exportVaultZip` (`VAULT_FORMAT.md` §§2, 6); the key escape hatch is `PassphraseKeyExport` / `skein-recovery-<date>.json` (`skein-v9g`, closed), which is what makes the brain removable from the device that made it. |
| 3 | Core remains useful offline | SATISFIED | There is no network code path to be offline *from*: no HTTP client, no `INTERNET`. Default models ship as assets (`app/src/main/assets/models/`, `skein-bxk`) and `SKEIN_HUB.md` §5.3 fixes the document-picker import as the baseline, not the fallback. |
| 4 | Models are replaceable | **SEAM NOW** | **`skein-ps0` slice B** (and `skein-3uh`): `/model <name>` and `NewChatAction`'s persona-model switch both go through `ModelRegistry.setDefault` + `ModelSwitcher`, never by constructing or re-binding an engine at the call site. §3.1. |
| 5 | Raw evidence is preserved | SATISFIED | `document_revisions` + `DocumentRevision.bodyMdSnapshot`/`frontmatterSnapshot`, self-verifying under `RevisionHashing.compute` (`core/model/.../Revisions.kt`, migration 003); `ImportService.importText` never overwrites an existing document, it reports `ImportResult.conflictWith` instead (`Transfer.kt:149`). |
| 6 | Derived knowledge carries provenance | SATISFIED | `Retrieved.revisionHash`/`locator` (`Retrieval.kt:96`) hydrated by `RetrievedAssembler` (`skein-g32i`) → `Citation`/`CitationRecord` citation-record-v1 (`Vault.kt:252`). The legacy chunk-id shape is explicitly rendered "source unknown" rather than resolved (`Message.retrievedChunks` KDoc). |
| 7 | Skills are inspectable procedures | **DEFER** | No skill runtime exists; `SKILL_GUARDRAILS.md` is a one-rule stub owned by `skein-q3r7`. ADR-5. |
| 8 | Tools have explicit permissions | SATISFIED | Every write primitive takes an `AuthorizationToken`; `VaultToolError.Unauthorized` is a modelled failure, not an exception (`core/agent/.../VaultTools.kt:209`, `VAULT_TOOL_PRIMITIVES.md` §6, §9 capability tiers). |
| 9 | Agents do not grant themselves authority | SATISFIED | Tokens are minted by the surface holding the user gesture; no primitive mints one (`VAULT_TOOL_PRIMITIVES.md` §6). Structurally reinforced by `PromptAssembler`'s three mechanisms — retrieved text is re-roled to `USER`, never appended to the system segment (`Retrieval.kt:171`) — and by handoff §3 item 12 (no model-derived tool calls in v1). |
| 10 | Mutations are reviewable and reversible | **SEAM NOW** | **`skein-2cd`**: the bottom sheet's Replace / Insert-below commit through `VaultRepository.updateBody`, which writes a `document_revisions` row, rather than mutating editor buffer state only — so the pre-AI text survives as an addressable revision. §3.2. |
| 11 | External compute receives minimum necessary context | **DEFER** | Nothing external exists to send context to. ADR-2 (Context Capsule + privacy policy + redaction). |
| 12 | Network capability stays outside Core | SATISFIED | `SKEIN_HUB.md` §1.1 invariants I1–I5, each with a named enforcement point; Core exports no component to Hub and every transfer is Core-initiated (§2.2). |
| 13 | One Agent Runtime serves multiple interfaces | **SEAM NOW** | **`skein-6as`**: extract the send flow into one non-Compose `SendPipeline` class (retrieve → budget → assemble → stream → parse → persist) that `skein-6sd`'s `InlineAiRunner` and `skein-2cd`'s sheet call, instead of three independent copies. §3.3. |
| 14 | Portable architecture over Android lock-in | SATISFIED | `IsolationGuardPlugin.PURE_JVM_MODULES` forbids the Android plugin on `:core:model`, `:core:agent`, `:core:markdown`, `:core:verify`, `:testing` — a build failure, not a style rule. Revision hashing, UUIDv7, frontmatter and SKAT are all byte-defined in `VAULT_FORMAT.md`, not in an Android API. |
| 15 | Do not sacrifice the current release | SATISFIED | This review files zero beads and changes zero product code. Seven seams, all inside beads already on the critical path, none touching a locked contract. |

### 2.2 The hygiene checks (brief §24 A–G)

| # | Check | Verdict | Citation / exact change |
|---|---|---|---|
| A | Is Chat directly coupled to llama.cpp? | **SEAM NOW** | **`skein-6as`**: `feature/chat/build.gradle.kts` is a bare stub today (`androidx.core.ktx` only) — the choice is completely open. It takes `implementation(project(":core:model"))` and must take **neither** `:core:inference` nor `:core:ipc`; `ChatScreen` names `InferenceEngine`, never `LlamaCppEngine` or `IInferenceService`. §3.4. |
| B | Are mutations bypassing a common approval/diff mechanism? | **SEAM NOW** | **`skein-6sd`**: define `InlineAiRunner` (its own description already names it) as the *single* place in `:feature:editor` where model output becomes a vault write, carrying a caller-supplied gesture label; `skein-2cd`'s sheet calls it rather than writing directly. One insertion point for the v2 approval/diff layer. §3.5. |
| C | Are provenance fields being discarded? | **SEAM NOW** | **`skein-6as`**: persist the assistant turn with `NewMessage.citations = CitationRecords.fromStream(...)`, **not** `NewMessage.retrievedChunks`. The builder already exists (`core/rag/.../chat/CitationRecords.kt`, `skein-n5q`) and its header says it exists for this call. §3.6. |
| D | Is retrieval coupled to one content type? | SATISFIED | `RetrievalService.retrieveContext` speaks `Retrieved`/`DocumentKind`, never a file type; the three recall stages read `IndexStore` chunks; `IngestPipeline`'s steps are constructor-injected and already optional (`embedder = null`, `entities = null` — `app/.../ingest/IngestPipelines.kt`). The *ingress* (`ImportService`'s three per-type methods) is narrower; that is ADR-7, and the additive-method precedent is the mitigation, not a rewrite. |
| E | Are UI surfaces directly implementing agent logic? | **SEAM NOW** | **`skein-ps0` slice B**: AI-bearing palette commands dispatch through `CommandRegistry` actions that call `NewChatAction` (`skein-3uh`) and the §3.3 pipeline; `:feature:shell` must not acquire a `:core:inference` or `:core:rag` dependency to do it. §3.7. |
| F | Are Android-specific storage representations becoming the canonical knowledge format? | SATISFIED | The canonical formats are platform-neutral and byte-specified: SQLCipher schema + numbered migrations, UUIDv7, the frontmatter wire format, SKAT, and BLAKE3 revision hashing (`VAULT_FORMAT.md` §§1–7). The one Android-bound artifact is `keys/key-envelope.v1` (AndroidKeyStore-wrapped), and `skein-v9g`'s passphrase envelope is exactly its portability seam — already closed. |
| G | Are source material and AI-derived knowledge mixed irreversibly? | SATISFIED | `DocumentKind` separates `NOTE`/`CHAT`/`ATTACHMENT`/`AIOUT` at the row level (`Vault.kt:61`), and AI output is a *new* document linked to its origin by an `EdgeKind.CITE` edge plus `source:` frontmatter, never an overwrite (`skein-3uh`'s `AiOutputAction`; `EdgeKind` at `Vault.kt:485`). |

## 3. The seven SEAM NOW items, in full

Each is a note for the named bead. None adds a module, a dependency direction, or a public contract.

**3.1 — `skein-ps0` (slice B), with `skein-3uh` · one model-change path.**
`/model <name>` resolves through `ModelRegistry` (`setDefault`) and hands the actual load to
`ModelSwitcher` (`skein-2va`); `NewChatAction`'s "switch model if the persona's `defaultModel` differs"
calls the same switcher. Neither call site constructs an engine, binds `:inference`, or caches a
`Model`. *Why now:* these are the first two model-change call sites in the tree; a second path added
later is the thing brief §5 warns about. *Cost:* one function call each.

**3.2 — `skein-2cd` · AI edits produce a revision.**
Replace / Insert-below commit through `VaultRepository.updateBody` (or the editor's existing save path
that ends there), so migration 003's `document_revisions` row is written and the pre-AI body stays
addressable by `RevisionHash`. Do not apply the edit to editor buffer state alone and rely on the
autosave debouncer to eventually flush it. *Why now:* brief §21 makes checkpoints structural; this is
the only structural undo Skein has in v1 and it is free. *Cost:* choosing the existing write path.

**3.3 — `skein-6as` · one send pipeline.**
Extract the bead's own send flow (persist USER → `RetrievalService.retrieveContext` → `ContextBudget`
→ `PromptAssembler` → `InferenceEngine.stream` → `CitationParser` segments → persist ASSISTANT) into
a non-Compose class with constructor-injected dependencies; the Composable collects its `Flow` and
renders. `skein-6sd`'s `InlineAiRunner` and `skein-2cd`'s sheet then call it with a different prompt
builder instead of reimplementing the chain. *Why now:* brief §4 calls one runtime across Chat,
Editor and CLI "an important anti-rewrite constraint", and the bead's own acceptance criteria (drive
it with `FakeInferenceEngine` + `FakeRetrievalService`) already push the logic out of the Composable.
*Cost:* structure, not new code.

**3.4 — `skein-6as` · the chat module sees only the contract.**
`feature/chat/build.gradle.kts` gains `implementation(project(":core:model"))` (plus Compose and
whatever `:feature:shell` it needs for `SecureTextField`) and **must not** gain `:core:inference` or
`:core:ipc`. Every signature in the module names `InferenceEngine`, `RetrievalService`,
`PromptAssembler`, `VaultRepository`; the concrete `LlamaCppEngine` is supplied by the `:app`
composition root. *Why now:* the module has no dependencies at all today, so this costs one line; after
the screen is written it costs an import sweep plus a test sweep. This is brief §24 check A answered
by the build graph rather than by review discipline.

**3.5 — `skein-6sd` · one mutation path in the editor.**
`InlineAiRunner` is the only code in `:feature:editor` that turns model output into a vault write. It
takes the origin `DocId`, the proposed text, and a short gesture label ("inline-AI: Rewrite",
"/ai continue") that is recorded with the write. `skein-2cd` calls it. *Why now:* two surfaces are
about to grow write paths within weeks of each other; brief §24 check B is precisely "avoid creating
multiple mutation pathways", and one shared function is where a v2 approval/diff step (Artifact Engine,
`skein-bnwi`) or an audit record (`skein-2chp`) is later inserted once instead of N times. *Cost:* a
function signature. **Not** in scope now: adopting `core/agent`'s `AuthorizationToken` and the
`VaultWriter`/`VaultPatcher` primitives themselves — those are contract-only today
(`VAULT_TOOL_PRIMITIVES.md` §7, "no consumer imports yet"), and half-adopting them would be worse than
one honest function.

**3.6 — `skein-6as` · persist citations, not chunk ids.**
Write the assistant turn with `NewMessage.citations`, built by `CitationRecords.fromStream`. Do not
populate `NewMessage.retrievedChunks`: raw `chunks.id` values are reassigned on re-ingestion, which is
the defect `POST_REVIEW_RESOLUTIONS.md` §1.1 describes, and a row written that way renders "source
unknown" forever. *Why now:* the builder, the record type, the JSON codec and the repository path are
all landed; only the call is missing. This is the one seam where the "later" cost is unrecoverable
data rather than a refactor. *Cost:* one call.

**3.7 — `skein-ps0` (slice B) · the shell dispatches, it does not reason.**
Palette commands that involve the model (`/new chat`, and any later `/ai …`) register a `Command`
whose action delegates to `NewChatAction` / the §3.3 pipeline, supplied to the shell as a lambda or
interface from the composition root. `:feature:shell` acquires no `:core:inference` or `:core:rag`
dependency. *Why now:* slice A already established `CommandRegistry` with injected actions, so slice B
is following a pattern that exists rather than establishing one. *Cost:* none beyond staying in the
existing shape.

## 4. Deferred-concept register (ADRs — no beads)

Format: context → decision → consequence → **must not do now**. Each entry's last line is the
operative one; everything before it is why.

**ADR-1 · Compute Router / `InferenceProvider` beyond the local engine (brief §§5, 6, 12, 13).**
Brief §5 asks for an abstraction "conceptually equivalent to `InferenceProvider`" with
`LocalLlamaProvider` as the only implementation. Skein already has it under a different name:
`InferenceEngine` (`core/model/.../Inference.kt`) is a locked, pure-Kotlin, model-agnostic contract
with no llama.cpp, Binder or Android type in its signature, and `LlamaCppEngine` (`skein-1uw`) is its
first implementation. A `ComputeRouter` that selects between providers under a `ComputePolicy`
(privacy, complexity, battery, thermal, cost) is substantial new implementation with no second
provider to route to, so it is deferred. **Must not do now:** do not let any consumer name
`LlamaCppEngine`, `IInferenceService`, `ErrorCode` or a `LoadRequest` where `InferenceEngine` would do
(§3.4 is the enforcement), and do not add engine-selection logic to a UI surface — a router inserted
later must have exactly one place to go.

**ADR-2 · Context Capsule, Privacy Policy, redaction (brief §§7, 8, 11).**
The minimal, explicitly-approved payload proposed to external compute, behind
Context Builder → Privacy Policy → Redaction → Capsule → Approval → Network Boundary. There is no
external compute and no network boundary in Core, so there is nothing to build. The architectural work
that survives is the *shape* of context construction: `PromptAssembler.assemble` is already pure,
deterministic and returns both the prompt and the exact `Map<Int, Retrieved>` that went into it — that
return value is the honest ancestor of a capsule manifest. **Must not do now:** do not add any API
whose shape is "hand the model the vault" — no retrieval primitive that returns unbounded results, no
tool that takes a raw SQL or FTS string from model output, and no prompt path that bypasses
`PromptAssembler`'s budget. Brief §11 is a hard principle and it is cheapest to keep by never building
its opposite.

**ADR-3 · Hub capability split (brief §10).** **Decided, not deferred** — see §5, applied to
`docs/design/SKEIN_HUB.md` in this branch. Only the capabilities beyond model transfer
(`FRONTIER_INFERENCE`, `BRAIN_PACK_TRANSFER`, `SKILL_TRANSFER`) stay deferred, as reserved names with
no declaration on either side. **Must not do now:** do not declare, in either manifest, a permission
for a capability that has no implementation, and do not widen `MODEL_TRANSFER`'s meaning to cover a
second capability.

**ADR-4 · Compute transparency receipts (brief §14).**
A per-task receipt — local calls, frontier calls, bytes and files exposed, vault exposure, cost. With
no external compute the only true receipt is "0 bytes left the device", which the absence of
`INTERNET` already states more strongly than a UI could. The substrate that a receipt would need is
partly present: `Token.Done` carries `promptTokens`, `generatedTokens`, `ttftMs`, `tokensPerSec`, and
`CitationRecord` records what context a turn consumed. **Must not do now:** do not discard
`Token.Done`'s counters at the surface layer (collapse the stream to text and drop the terminal
token), and do not make `CitationRecord` optional at the point of persistence (§3.6) — a receipt
assembled later is assembled from exactly these two records.

**ADR-5 · Skill format, and compute/privacy declarations in skills (brief §§15, 16).**
A skill as a portable directory (`SKILL.md`, `references/`, `prompts/`, `schemas/`, `scripts/`) that
*declares* compute preferences and context restrictions while `ComputePolicy` stays authoritative.
`SKILL_GUARDRAILS.md` is a one-rule stub; `skein-q3r7` owns the catalog; `skein-ojyi` owns the format.
The principle that must survive is brief §15's last line: a declaration is a request, never a grant.
**Must not do now:** do not build a prompt-fragment or persona mechanism that can inject text into the
`Role.SYSTEM` segment from anything other than `Persona.systemPrompt` — `PromptAssembler`'s contract
(`Retrieval.kt:171`, mechanism 1) is the load-bearing line, and `PromptAssemblerContractTest` enforces
it on every implementation. A skill that could write the system segment would be a skill that grants
itself authority.

**ADR-6 · Brain Packs (brief §17).**
`Local-AI.skein` = knowledge (sources, wiki, graph, entities, provenance) + skills, portable. The brief
itself says "do not finalize a file/archive format yet". The nearest landed thing is the vault-ZIP
export (`VAULT_FORMAT.md` §6) with its manifest — a pack is that plus a scope and a skill directory.
**Must not do now:** do not let the vault-ZIP manifest grow ad-hoc keys that a pack format would have
to inherit, and do not introduce a second export archive shape alongside it; one archive format with a
versioned manifest is what a pack format can later extend.

**ADR-7 · Repository-as-source structural import (brief §18).**
Repository import producing files, symbols, functions, classes, dependencies, imports, tests, commits
and architecture relationships — not only embeddings. Today the knowledge ingress is
`ImportService`'s three per-type methods (`importText`, `importPdf`, `importImage`) plus the SAF/share
intake; a repository would be a fourth. This is deliberately **not** a seam now: the contract is locked
at M0.5 with three implementations and a contract-test suite, and the tree's own precedent
(`skein-uo5n`'s additive `VaultRepository` methods, `skein-3v9`'s additive `CompanionRole` entries,
`skein-k7e9`'s additive `InferenceException` subclasses) shows that adding a method to a locked
contract is cheap and behaviour-compatible. The expensive alternative — a speculative
`SourceAdapter` interface with one implementation — is exactly what brief §25 forbids. **Must not do
now:** do not give a new source type its own bypass into the vault or the index; every ingress keeps
going through `ImportService` → `VaultRepository` → the DB trigger → `ingest_queue`, so a fourth
source type is a fourth method and not a fourth pipeline.

**ADR-8 · Compiled wiki (brief §19).**
Raw sources → parser → extraction → compiled wiki → graph + indexes, as a reusable understanding layer
above raw chunks. Deferred: it is a new subsystem, and v1's derived layer (chunks, embeddings, edges,
entities) already carries the provenance a wiki would need. The brief's own constraint is the one to
protect: the wiki is derived, not source truth, and must stay rebuildable. **Must not do now:** do not
write derived text back into a source document's `body_md`, and do not create a document kind that is
simultaneously source and derivation — `DocumentKind.AIOUT` plus an `EdgeKind.CITE` edge to its origin
is the pattern (check G), and keeping it means the whole derived layer stays droppable and rebuildable.

**ADR-9 · Semantic history (brief §20).**
"How did our understanding reach this state?" — reconstructing a decision path across time, not just
reading current state. The substrate is largely already required for citations:
`document_revisions` gives content-addressed history per document, `CitationRecord` records what
evidence a turn rested on, and `EdgeKind.CITE` records derivation. What is missing is retention and
query, both real work. **Must not do now:** do not make `document_revisions` retention destructive by
default — the GC sweep noted in `VAULT_FORMAT.md` §8 must prune under a stated policy, never silently
collapse a document to its head revision — and do not delete citation records when a message is
truncated or regenerated without recording that it happened (`skein-6as`'s B-4 follow-up already owns
this reconciliation).

**ADR-10 · Background cognitive jobs on the agent primitives (brief §22).**
Index a repository, compile the wiki, extract entities, rebuild the graph, find duplicates and
contradictions, refresh summaries — as background jobs that reuse the Agent/Skill/Tool primitives
rather than a separate automation architecture. Deferred as an epic; the brief's actual constraint is
"do not create a completely separate automation architecture unnecessarily". The tree already has one
background-work spine: `IngestScheduler`/`IngestWorker`/`IngestWorkPort` over androidx.work, with
`IngestPacer` for thermal/battery pacing and `ingest_queue.attempts` for bounded retry (migration 008).
**Must not do now:** do not stand up a second scheduler, worker type or retry counter for any new
background job — extend `IngestWorkPort` and the queue, so there is one place where "what runs in the
background, under what pacing, with what retry budget" is answered.

**ADR-11 · Desktop portability (brief §23).**
One portable, encrypted Skein brain usable from Android, Linux and macOS. Not a v1 build target. Most
of the substrate is already portable by construction (principle 14, check F): the pure-JVM module set
is guard-enforced, and the on-disk formats are byte-specified. The one genuinely Android-bound artifact
is `keys/key-envelope.v1`, and `skein-v9g`'s passphrase export/import (PBKDF2-HMAC-SHA256 600k,
AES-256-GCM, header bound as AAD, `skein-recovery-<date>.json` v1) is the portability seam that already
exists — a desktop client would open a vault through that envelope, not through the Keystore one.
**Must not do now:** do not add an Android type to any `PURE_JVM_MODULES` member (the guard will stop
you, which is the point), do not move `RevisionHashing`, `Uuid7`-shaped identity or the frontmatter
codec behind an Android API, and do not add a second key-wrapping format that the passphrase envelope
cannot express.

## 5. Decision — the Hub capability split (brief §10), applied to `SKEIN_HUB.md`

**The question.** Separate signature-protected permissions, one per Hub capability; or one permission
plus a capability-grant record Hub hands to Core? The requirement from brief §10 is that a user who
enabled model downloads has not thereby authorised sending context to a frontier model, and that Core
can tell the difference.

**Decision: separate signature-level permissions, one per capability that crosses the trust boundary.
No capability-grant record.**

Rationale, in the order that decided it:

1. **It is the only option that makes the frontier path *absent* rather than *reachable-but-refused*.**
   If Core's merged manifest does not declare `app.skein.permission.FRONTIER_INFERENCE`, then no Core
   code — not a bug, not a compromised path, not a future agent that forgot — can invoke Hub's frontier
   entry point. That is a property `ManifestPolicyTest` and `tools/ci/manifest-audit.sh` already know
   how to assert, and one a user can verify in the OS app-info screen.
2. **A grant record would be Hub-asserted data, and `SKEIN_HUB.md` §1.1 I4 says Core distrusts every
   claim Hub makes.** Core could only treat such a record as a hint, which leaves the actual gate still
   unbuilt — the mechanism would add a schema, a signature, a freshness/revocation story, a hint
   quarantine path and a test suite, and enforce nothing. That is machinery for zero v1 callers.
3. **Core answers "downloads enabled, frontier not" locally.** Availability per capability is
   (a) Core declares the matching `<uses-permission>`, (b) `checkSignatures(self, app.skein.hub) ==
   SIGNATURE_MATCH` (§2.4, unchanged), (c) Hub exposes the guarded entry point for that capability.
   No new data format; the `HubAvailability` type §2.4 already introduces carries the answer.
4. **The user's runtime toggle stays inside Hub** and is reported per call as a typed refusal, never
   cached in Core — for the same reason as (2), and because caching it would be the first Hub → Core
   state flow (§4.4, §6.3 reject that).
5. **Cost now vs later.** Now: reserved names in a design document that is still pre-decision, plus one
   sentence in §2.3. Later, after H4 has shipped a single blanket permission: a permission rename
   across two manifests, `ManifestPolicyTest`, `manifest-audit.sh`, and an installed base that must
   reinstall to re-grant. This is the flag with the widest now/later gap in the whole review.

**Applied in this branch** as a minimal amendment to `docs/design/SKEIN_HUB.md`: a new §1.3 stating the
rule and the capability→permission table, invariant **I6** in §1.1, one clarifying sentence in §2.3
(`MODEL_TRANSFER` is the Core-visible leg of `MODEL_DOWNLOAD`, and nothing else), and one REJECT-list
row (§8) against a blanket permission. Nothing else in that document changes; §§1.1–1.2's five existing
invariants, the A2 handoff decision and the §3 acceptance pipeline are untouched and remain
self-consistent with the addition.

## 6. Proposed text (proposed only — not applied by this bead)

### 6.1 For `docs/ARCHITECTURE.md` — a new §0, before §1

```markdown
## 0. North star

Skein is built as a **user-owned cognitive runtime**, not as an Android notes app with a local LLM.
The long-term architecture — a Skein Brain (sources, derived knowledge, skills, provenance, history)
served by one Agent Runtime, reasoning through replaceable models — is stated in
`docs/design/NORTH_STAR_BRIEF.md`. That document is direction, not scope: it explicitly does not
expand v1 (its §24–§25).

What it means for the code in this repository is settled in
`docs/design/NORTH_STAR_REVIEW.md`: where the current architecture already *is* the right seam
(with the type or file that proves it), the small number of boundaries being adopted inside work
already in flight, and the concepts recorded as ADRs with the one thing that must not be done now to
keep each of them possible. Before adding a type, a module or a dependency, check that review's §4 for
a "must not do now" line that covers it.
```

### 6.2 For the plan's §1 (Executive summary) — one framing sentence, inserted before **What ships**

```markdown
**What this is:** v1 is the first shippable slice of a user-owned cognitive runtime
(`docs/design/NORTH_STAR_BRIEF.md`) — a private brain the user owns, reasoned over by replaceable
models — and not an Android notes app that happens to embed an LLM. The scope below is unchanged by
that framing; it governs which of two equally cheap implementations to prefer, nothing more
(`docs/design/NORTH_STAR_REVIEW.md`).
```

## 7. Brief §28 item 6 — where a boundary now materially reduces a future rewrite

Six flags, worst now/later ratio first. Five map to a SEAM NOW in §3; one is a flag *against* acting.

| # | Current implementation | Boundary | Cost now | Cost later |
|---|---|---|---|---|
| F1 | Hub permission model, still pre-decision (`SKEIN_HUB.md`) | One signature permission per capability (§5) | A design-doc amendment — applied in this branch | Permission rename across two manifests, `ManifestPolicyTest`, `manifest-audit.sh`, and an installed base that must reinstall to re-grant a capability it already has |
| F2 | Assistant-turn persistence in `skein-6as` | `NewMessage.citations` instead of `retrievedChunks` (§3.6) | One call to an existing builder | **Not a refactor — data loss.** Chunk ids are reassigned on re-ingestion; those rows render "source unknown" permanently and cannot be re-anchored |
| F3 | `:feature:chat`, a dependency-free stub | Module sees `:core:model` only; screens name `InferenceEngine` (§3.4) | One build-file line, chosen once | An import sweep across every Composable, state holder and test in the module, plus the second engine implementation the sweep exists to permit |
| F4 | Three surfaces about to grow independent send flows (`skein-6as`, `6sd`, `2cd`) | One `SendPipeline` (§3.3) | Structuring code the bead writes anyway; its own ACs already push this way | Three divergent pipelines × (retrieval, budget, assembly, citation parsing, persistence) to reconcile — brief §4's named anti-rewrite constraint, arriving as a rewrite |
| F5 | Two editor write paths about to appear (`skein-6sd`, `skein-2cd`) | One `InlineAiRunner` mutation path (§3.5) | A function signature | Retrofitting approval/diff (`skein-bnwi`) or audit (`skein-2chp`) at N call sites, each with its own tests, instead of one |
| F6 | `ImportService`'s three per-type methods | **None — do not act** (ADR-7) | Zero | Also near zero: the tree's additive-contract precedent makes a fourth method cheap and compatible. A speculative `SourceAdapter` with one implementation would cost more now *and* later |

## 8. What this review deliberately did not do

- **No beads filed.** The seven SEAM NOW items are notes for beads that already exist; §4's eleven
  register entries (ten deferrals plus the decided Hub split) are notes to a future reader. Brief
  §28's first line is explicit about this.
- **No product code, build file, spec, plan or `ARCHITECTURE.md` change.** §6's two passages are
  proposals for the coordinator; the only file this bead edits besides itself is
  `docs/design/SKEIN_HUB.md`, which is pre-decision by its own status line.
- **No non-negotiable weakened.** Every seam keeps Core at zero `INTERNET` (F1 tightens it) and leaves
  the dual-hash-before-mmap path (`ModelVerifier`, `PinnedModelFile`, `IsolatedSessionGate`) untouched.
- **No locked contract changed.** `InferenceEngine`, `VaultRepository`, `RetrievalService`,
  `PromptAssembler`, `ImportService`, the AIDL surface and `ModelManifest` v2 are all as they were.
