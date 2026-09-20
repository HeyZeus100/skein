# Vault Tool Primitives — Design Spec

**Status:** interface-only design (2026-09-19). Interfaces landed at
`core/agent/src/main/kotlin/app/skein/core/agent/tools/VaultTools.kt`.
Implementations follow in E2 (`:core:vault`) and E5 (`:core:rag`).

**Owner (design):** roadmap holder
**bd tracking issue:** `skein-fvne`
**Blocks:** every v2 skill/agent surface (skein-ojyi, skein-2pns, skein-wsld,
skein-iifu, skein-fncp, skein-bnwi Artifact Engine invocations).

---

## 1. Why this exists

Hermes-style skills (Nous Research `note-taking/obsidian/SKILL.md`) work
because they assume a fixed, small set of file/search primitives —
`read_file`, `write_file`, `patch`, `search_files`. Every procedure a skill
teaches boils down to a sequence of those calls. That stability is why a
skill written today keeps working when the underlying implementation is
rewritten.

Skein needs the equivalent for its vault. The v1 inline-AI selection menu,
slash commands, and (v2) skill dispatcher all need to reach into the vault
through **the same** interface — one that is stable across versions,
independently testable, and structured so a scheduler can approve calls
before they mutate state.

This spec formalizes that surface now, before v2 skills land, so:

1. v2 agents/skills have a stable target from day one.
2. v1's editor/chat surfaces can consume the same primitives, letting a
   later skill inherit the surfaces' existing behavior for free.
3. The Artifact Engine (skein-bnwi) engine calls compose *these* primitives
   rather than re-inventing a document access layer.
4. The Capabilities panel (skein-2chp) has a finite, enumerable set of
   grants to hand out — one per primitive-tier below.

**This document defines interfaces only.** No implementation. Nothing
imports the new module yet.

## 2. Design invariants

1. **Direct-invocation Kotlin interfaces.** v1's authorization model is
   *the user tapped a button*; the surface handler invokes primitives
   directly on the user's behalf. v2 layers a skill dispatcher on top; the
   dispatcher mints tokens from Capabilities-panel grants and calls the
   same primitives. Neither model requires the primitives themselves to
   marshal to JSON or emit event objects. Kotlin `suspend fun` on a
   sealed-return type is the surface.
2. **Every write takes an `AuthorizationToken`.** The token is opaque to
   the primitive; the surface layer mints it. v1 tokens correlate to a
   user gesture id ("inline-AI:Improve", "editor:save"). v2 tokens
   correlate to a skill run id under a granted capability. The primitive
   only checks presence + `notAfterMillis`.
3. **Typed errors, no exceptions on modeled failures.**
   `sealed interface VaultToolError` + `ToolResult<T>`. Callers exhaust
   the error set. The impl still throws for programming faults
   (`IllegalStateException` on a genuinely impossible branch), not for
   business-modeled failures.
4. **Revision-hashed retrieval.** Every retrieval hit returns
   `(document_id, revision_hash, locator, excerpt)` so a caller can build
   a `citation-record-v1` (`docs/design/POST_REVIEW_RESOLUTIONS.md §1.3`)
   entry directly. No caller of `search_vault` can produce an unstable
   citation.
5. **Patches address by base revision.** A `PatchOp` carries the
   `baseRevision` it was authored against; on apply, if the head has
   moved, the whole batch fails with `RevisionConflict`. This is what
   lets a v2 skill compose "read → propose patches → apply" without
   drift, and what the Artifact Engine's constraint-preserving edit
   pipeline layers on top of.
6. **`:core:agent` is pure Kotlin/JVM.** It sits in the isolation-guard
   allowlist alongside `:core:model` and `:core:markdown`. Every
   consumer — Android UI, isolated inference service host process,
   headless test — links against it. Nothing else in the module tree may
   drag Android SDK into it.

## 3. The primitive set

Naming below matches the Kotlin interface fragment.

### 3.1 `read_note(id) -> Note`

Kotlin: `VaultReader.readNote(id: DocId): ToolResult<Note>`

- **Semantics:** returns the head revision of the referenced document,
  with `revisionHash` populated. Reads never mutate.
- **Failure modes:** `NotFound`, `VaultLocked`, `StorageFailure`.
- **Idempotency:** trivially idempotent (read).
- **Concurrency:** safe from any thread; suspending.
- **Backing plan interface:** `VaultRepository.getNote(id)` — plan
  `E2.I2`.
- **Capability tier:** `vault.read`. v1 surfaces get it unconditionally;
  v2 skills must be granted it.

### 3.2 `write_note(NoteDraft) -> NoteId`

Kotlin: `VaultWriter.writeNote(draft, token): ToolResult<WriteResult>`
where `WriteResult(id, revisionHash, wasNoOp)`.

- **Semantics:** create (draft.id == null) or full replace (draft.id
  set). Canonicalizes body + frontmatter, hashes, and inserts into
  `document_revisions` (POST_REVIEW §1.3) before flipping the head. A
  byte-identical replay is a no-op — `wasNoOp = true` — with the head
  revision returned unchanged.
- **Failure modes:** `Unauthorized`, `InvalidInput`, `VaultLocked`,
  `StorageFailure`.
- **Idempotency:** content-idempotent (repeated identical writes → no
  new revision).
- **Concurrency:** serialized behind the vault's single writer per spec
  §4.2. Two concurrent writers with distinct content both persist as
  revisions; last-write-wins at head; no citation is lost because both
  revisions remain in the revisions table.
- **Backing plan interface:** `VaultRepository.upsertNote` +
  `newRevision` — POST_REVIEW §1.5 (plan `E2.I2` / `E2.I4`).
- **Capability tier:** `vault.write`.

### 3.3 `patch_note(id, PatchOp[]) -> Result`

Kotlin: `VaultPatcher.patchNote(id, ops, token): ToolResult<WriteResult>`.

- **Semantics:** applies a list of `PatchOp` atomically against the
  document's head revision. All ops must share the same
  `baseRevision`; the impl asserts this. Byte offsets in later ops
  refer to the *original* revision, not intermediate state. The
  Artifact Engine (skein-bnwi) supersets this op set in v2.
- **Failure modes:** `RevisionConflict` (head advanced past
  `baseRevision`), `Unauthorized`, `InvalidInput`, `NotFound`,
  `VaultLocked`, `StorageFailure`.
- **Idempotency:** **not** idempotent. Callers must not retry on
  success. On `RevisionConflict`, callers re-read, rebuild ops, retry.
- **Concurrency:** serialized behind the writer lock.
- **Backing plan interface:** new `VaultRepository.applyPatch(docId, ops)`
  to be added alongside `newRevision` (plan `E2.I4`).
- **Capability tier:** `vault.write`.

### 3.4 `search_vault(query, filters) -> [Hit]`

Kotlin: `VaultSearch.search(query, k, filters): ToolResult<List<Hit>>`.

- **Semantics:** the vector + BM25 + PPR pipeline of spec §7.2, shaped
  into hits carrying `(documentId, revisionHash, locator, excerpt,
  sourceKind)`. `k` defaults to 8 (matches `retrieveContext` default).
  `filters` narrows by persona, kind, tag set, or `updatedSinceMillis`.
- **Failure modes:** `VaultLocked`, `RetrievalBudgetExceeded`,
  `StorageFailure`.
- **Idempotency:** read-only; deterministic given vault state.
- **Concurrency:** safe; may share a small read connection pool with
  ingest (POST_REVIEW §5).
- **Backing plan interface:** `RetrievalService.retrieveContext` (plan
  `E5.I13`). Hit fields map onto `Retrieved` per POST_REVIEW §1.3.
- **Capability tier:** `vault.search`. Inline surfaces call freely;
  skills need a grant so cross-persona retrieval always emits an audit
  event (skein-2chp).

### 3.5 `resolve_wikilink(text, contextId) -> NoteId?`

Kotlin: `VaultLinks.resolveWikilink(text, contextId, strict):
ToolResult<DocId?>`.

- **Semantics:** takes the raw text inside `[[...]]`, resolves via
  title, alias, or anchor. `contextId` disambiguates by proximity
  (same-folder wins, then most-recently-linked). `strict = true`
  returns `UnresolvedLink` on miss; `strict = false` returns `Ok(null)`.
- **Failure modes:** `UnresolvedLink` (strict-only), `VaultLocked`,
  `StorageFailure`.
- **Idempotency:** read-only.
- **Backing plan interface:** `VaultRepository.resolveWikilink` — an
  augmentation to E2.I2 (the ingest pipeline already computes edges
  per spec §7.1 step 5).
- **Capability tier:** `vault.read`.

### 3.6 `list_backlinks(id) -> [NoteId]`

Kotlin: `VaultLinks.listBacklinks(id): ToolResult<List<DocId>>`.

- **Semantics:** `SELECT src_id FROM edges WHERE dst_id = ? AND kind =
  'wikilink'`. Spec §7.1 step 6 already commits to computing this
  live from the edges table (no separate storage), which is the same
  path this primitive rides.
- **Failure modes:** `VaultLocked`, `StorageFailure`.
- **Idempotency:** read-only.
- **Capability tier:** `vault.read`.

### 3.7 `list_by_tag(tag) -> [NoteId]`

Kotlin: `VaultTags.listByTag(tag): ToolResult<List<DocId>>`.

- **Semantics:** returns doc ids whose frontmatter `tags:` array (as
  canonicalized by the ingest pipeline) contains `tag`.
- **Failure modes:** `VaultLocked`, `StorageFailure`.
- **Idempotency:** read-only.
- **Backing plan interface:** `VaultRepository.listByTag` — new method
  to add at plan `E2.I2` if not already present.
- **Capability tier:** `vault.read`.

### 3.8 `enumerate_persona(id) -> Persona`

Kotlin: `VaultPersonas.enumeratePersona(id): ToolResult<Persona>`.

- **Semantics:** fetches a persona row (name + system prompt +
  default model + created_at). Personas are not secrets, but the
  distinct capability tier means an audit event fires whenever a
  skill fans out across persona identities (skein-2chp).
- **Failure modes:** `NotFound`, `VaultLocked`, `StorageFailure`.
- **Idempotency:** read-only.
- **Backing plan interface:** `PersonaService.get(id)` (spec §16,
  locked at M0.5).
- **Capability tier:** `personas.read`.

### 3.9 Aggregate: `VaultTools`

Kotlin: `interface VaultTools : VaultReader, VaultWriter, VaultPatcher,
VaultSearch, VaultLinks, VaultTags, VaultPersonas`.

The aggregate exists for a caller that wants the whole surface (the
inline-AI menu handler, a skill dispatcher). Primitives stay their own
interfaces so a fake or subset consumer implements only what it needs.

## 4. Value types

Every type is declared in `VaultTools.kt`:

- Identifiers: `DocId`, `RevisionHash`, `Tag`, `PersonaId` — all
  `@JvmInline value class` wrapping a `String`.
- Enums: `DocKind` (`NOTE | CHAT | ATTACHMENT | AIOUT`), `SourceKind`
  (`VECTOR | LEXICAL | GRAPH | RERANK`; matches citation-record-v1).
- Records: `Note`, `NoteDraft`, `Hit`, `Locator`, `Persona`,
  `SearchFilters`.
- Patch: `sealed interface PatchOp` with `Replace`, `Insert`, `Delete`,
  `FrontmatterMerge` (all revision-anchored via `baseRevision`).

Two of these are worth calling out:

- **`Hit`** carries the citation-record-v1 fields directly:
  `documentId`, `revisionHash`, `locator`, `excerpt`, `sourceKind`.
  A caller that persists these into `messages.retrieved_chunks`
  gets citation stability by construction — no separate resolver
  step.
- **`PatchOp.baseRevision`** is the pivot for revision-safety.
  Without it, a v2 skill that reads + edits + applies could clobber
  a concurrent write. With it, the apply fails and the skill
  retries; the pattern is coordination-free.

## 5. Errors

```kotlin
sealed interface VaultToolError {
    val message: String
    data class NotFound(...) : VaultToolError
    data class RevisionConflict(docId, expected, actual, message) : VaultToolError
    data class Unauthorized(message) : VaultToolError
    data class UnresolvedLink(text, message) : VaultToolError
    data class InvalidInput(field, message) : VaultToolError
    data class VaultLocked(message) : VaultToolError
    data class StorageFailure(message, cause) : VaultToolError
    data class RetrievalBudgetExceeded(message) : VaultToolError
}

sealed interface ToolResult<out T> {
    data class Ok<T>(val value: T) : ToolResult<T>
    data class Err(val error: VaultToolError) : ToolResult<Nothing>
}
```

Deliberately not `kotlin.Result` — we want the error to be an
exhaustive sealed set, not `Throwable`. `Unauthorized` is present now
so v1 impls can return it on absent/expired tokens; v2 uses the same
code point when the Capabilities-panel grant is missing.

## 6. Authorization model

```kotlin
data class AuthorizationToken(
    val gestureId: String,       // correlates to the user gesture / skill run
    val label: String,           // "inline-AI: Improve", "skill: weekly-review"
    val notAfterMillis: Long,    // token lifetime
)
```

- **v1 mint sites:** inline-AI selection menu (`InlineAiHandler`),
  slash-command handlers, editor save button, share-target intake.
  Each gesture mints a fresh token, passes it to the write primitive,
  and drops it. Tokens are single-use in v1 by convention (the impl
  logs a warning if the same `gestureId` recurs within a short
  window).
- **v2 mint sites:** the skill dispatcher (skein-iifu, skein-2chp).
  It receives a Capabilities grant for the skill run, mints a token
  scoped to that run, and threads it into every tool call the skill
  makes. Audit log entries reference the `gestureId`.

The primitives themselves do not care which mint site produced the
token. They only require that one accompany every write. This is the
key structural decision that lets v1's direct-invocation surface and
v2's dispatcher-mediated surface share the *same* primitive
implementations without a rewrite.

**Reconsideration recorded (per skein-fvne task brief):** an earlier
draft considered returning "typed proposal" objects for writes rather
than performing the write directly, so the v2 dispatcher could gate
each call. Rejected because v1's inline-AI menu is *already* the
authorization event (the user tapped the button). Wrapping every
write in a proposal + approve round-trip in v1 would either
short-circuit through the same handler (adding nothing) or force the
inline menu to render its own diff UI (out of v1 scope). The
`AuthorizationToken` parameter is the smaller, symmetric alternative:
one word in the signature; v1 mints on gesture; v2 mints on grant.

## 7. Module wiring

- New Gradle module `:core:agent`, pure Kotlin/JVM.
- `core/agent/build.gradle.kts` applies `libs.plugins.kotlin.jvm` and
  `app.skein.guard.isolation`, and `implementation(project(":core:model"))`.
- Added to `PURE_JVM_MODULES` in
  `build-logic/guards/src/main/kotlin/app/skein/gradle/IsolationGuardPlugin.kt`
  so the guard rejects any future application of the Android plugin.
- Included in `settings.gradle.kts`.
- No consumer imports yet — this is a contract-only landing.

## 8. Relationship to plan interfaces

The primitives are a *thin, AI-facing shape* over the plan's larger
service interfaces. They are not a replacement — the plan interfaces
carry more surface (batch upsert, streaming reads, ingest triggers)
than an AI caller should see.

| Primitive              | Plan interface (backing)             | Plan issue     |
|------------------------|--------------------------------------|----------------|
| `read_note`            | `VaultRepository.getNote`            | `E2.I2`        |
| `write_note`           | `VaultRepository.upsertNote` + `newRevision` | `E2.I2` / `E2.I4` |
| `patch_note`           | `VaultRepository.applyPatch` (new)   | `E2.I4`        |
| `search_vault`         | `RetrievalService.retrieveContext`   | `E5.I13`       |
| `resolve_wikilink`     | `VaultRepository.resolveWikilink`    | `E2.I2` aug.   |
| `list_backlinks`       | `edges` table query                  | `E2.I2`        |
| `list_by_tag`          | `VaultRepository.listByTag` (new)    | `E2.I2` aug.   |
| `enumerate_persona`    | `PersonaService.get`                 | M0.5 lock      |

The impls in `:core:vault` and `:core:rag` wrap those service calls,
handle the `AuthorizationToken` presence check, translate storage
exceptions into `VaultToolError`, and hash + record revisions before
returning. That's the entire delta.

## 9. Capability tiers (feeds skein-2chp)

Every primitive maps onto one of three tiers. The Capabilities panel
(v2) grants at this granularity; the audit log (v2) records at this
granularity.

- `vault.read` — `read_note`, `resolve_wikilink`, `list_backlinks`,
  `list_by_tag`.
- `vault.write` — `write_note`, `patch_note`.
- `vault.search` — `search_vault`.
- `personas.read` — `enumerate_persona`.

v1 surfaces are granted the first three tiers by default (the user
gesture is the grant). v2 skills receive each grant explicitly per
skill install.

## 10. Non-goals

- **No inference primitive here.** `InferenceEngine` is a separate
  contract (§6, spec §16). A v2 skill that composes retrieval +
  inference calls both directly.
- **No implementation.** Nothing in `:core:agent` compiles into an
  executable class. Everything is `interface` / `data class` /
  `sealed`.
- **No fakes here.** Fakes belong in `:testing`, per E10.I1
  (skein-2mv, closed). When `:testing` grows fakes for these
  interfaces they will live at
  `testing/src/main/kotlin/app/skein/testing/fakes/FakeVaultTools.kt`.
- **No JSON schemas here.** The interfaces are Kotlin surfaces. If a
  v2 skill dispatcher needs a JSON envelope for cross-process
  invocation, that envelope is defined at the dispatcher, not here.
- **No sync/relay concerns.** v2's E2E sync (`skein-` sync issues)
  operates on `document_revisions`; the primitives above are already
  compatible because `write_note` guarantees every content-distinct
  write becomes a fresh revision row.

## 11. Testing plan (interfaces only — no impl yet)

For this issue: no runtime tests. The module is compile-checked by
`./gradlew :core:agent:build`; the isolation guard is verified by
`./gradlew :core:agent:checkIsolationGuards`.

For the implementation issues (E2/E5):

- Every primitive gets a JVM unit test on `:testing` fakes that
  round-trips the type contract (Result shape, error subtypes
  reachable).
- Revision-safety tests: `patch_note` against a stale
  `baseRevision` returns `RevisionConflict`; `write_note` with
  byte-identical content sets `wasNoOp = true`.
- Authorization tests: absent / expired token → `Unauthorized`.
- Retrieval tests: `search_vault` returns `Hit`s whose
  `(documentId, revisionHash, locator)` round-trips through
  `citation-record-v1` unchanged.

## 12. Cross-references

- `docs/superpowers/specs/2026-09-19-skein-design.md` — §5 (data
  model), §7 (RAG), §16 (M0.5 interface contracts).
- `docs/design/POST_REVIEW_RESOLUTIONS.md` — §1 (citation stability,
  revision-hashed retrieval). The primitives are the interface over
  the design in that doc.
- `docs/design/ARTIFACT_ENGINE.md` — §14 (implementation ordering)
  now names `patch_note` + `write_note` as the vault-side entry
  points the engine uses at step 7 (vault integration).
- `bd` issues: `skein-fvne` (this), `skein-uo5n` (citation
  stability), `skein-bnwi` (Artifact Engine), `skein-2chp`
  (Capabilities + Audit), `skein-iifu` (agents), `skein-ojyi`
  (skill format), `skein-q3r7` (skill guardrails), `skein-2mv`
  (test infra — where fakes will land).

## 13. Open design questions (post-v1)

1. **Streaming reads.** Do we want a `readNoteStream(id): Flow<NoteChunk>`
   for very large notes? Defer until an actual caller (Artifact
   Engine over a 300-page PDF-derived doc) needs it.
2. **Batch retrieval.** Should `search_vault` accept multiple queries
   (a "compare over N drafts" skill)? Defer until Compare mode
   (skein-fncp) starts.
3. **Capability shape.** `AuthorizationToken` is presence-checked in
   v1. The v2 shape (tier + grant id + scope hash) is
   Capabilities-panel work (skein-2chp), not this issue's.
