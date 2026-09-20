# Skein v1 — Autonomous Completion Handoff

**Status:** Working document, updated 2026-09-20 · `main` at commit `760d8ee`
**Purpose:** Everything a coordinator (human or agent) needs to spin up an autonomous multi-agent loop that drives Skein v1 to shippable release. Written so a fresh Claude session with no prior context can pick this up and execute.
**Authority:** Advisory + operational. Non-negotiables in §3 override anything below.

---

## 0. TL;DR for a fresh session

You are the coordinator for the Skein Android app. Skein is a private, on-device, GrapheneOS-first personal knowledge system with an on-device LLM. It is ~25% complete by shipping functionality; substrate + design is ~85% done. The remaining 75% is Kotlin/JNI/Compose implementation against designs that already exist.

To continue, in this order:

1. Read this file.
2. Read `docs/superpowers/specs/2026-09-19-skein-design.md` (approved spec — authoritative).
3. Read `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` (implementation plan; 167 issues).
4. Read `docs/design/POST_REVIEW_RESOLUTIONS.md` (architecture for the 4 hardest subsystems).
5. Read `docs/Handoffs/skein-fold-m0-hardware-handoff.md` (Fold lab operational state).
6. Run `bd prime` then `bd ready --limit 30` to see the queue.
7. Never touch the Fold except through the dedicated hardware-runner agent (§7.4).
8. Use worktree-isolated dispatch (§6.2) and follow the merge protocol (§6.4).

The definition of done for v1 is the seven-step loop from spec §11 executed once on-device with real data:

> **unlock → write/import → index → search/ask → cite → approve edit → export**

Everything else is optional or v2.

---

## 1. Mission

### 1.1 One-line goal

> Ship Skein v1.0 — an offline, on-device, GrapheneOS-friendly personal knowledge system where a user completes the seven-step loop on their own Pixel 9 Pro Fold, verifiably without network access, with an auditable release artifact.

### 1.2 Definition of Done (v1.0 shippable)

All of the following are true and reproducible on `main`:

1. **App installs on GrapheneOS on Pixel 9 Pro Fold** from a signed APK produced by `./gradlew :app:assembleFossRelease`, with `AndroidManifest.xml` containing **zero `INTERNET`-class permissions** (enforced by `checkManifestGuards`).
2. **A user can unlock the vault** with biometrics; SQLCipher DB opens; key material stays in StrongBox.
3. **A user can write a note** in Markdown live-preview and it persists across app restarts.
4. **A user can import a text/PDF/image file** via Storage Access Framework or Android share sheet; PDF text extracts, image analyzes via Gemma 4 vision (when active model supports it), text ingests.
5. **The vault indexes new content** during authorized-unlocked sessions (charging + idle NOT required; per skein-3xyu decision).
6. **A user can ask the AI** and get a streamed response with inline citations back to specific document revisions (revision-hashed per skein-uo5n).
7. **A user can approve/reject the AI's proposed edit** to a note through the inline AI selection menu or slash commands.
8. **A user can export** a note as Markdown, PDF, or DOCX through the DocumentsProvider or share sheet, with plaintext staging enforced under a 10-minute WorkManager TTL + `BOOT_COMPLETED` cleanup.
9. **All guards green**: `checkManifestGuards`, `checkDependencyGuards`, `checkIsolationGuards`, `licenseAudit`, `RawTextFieldTest`, `verifyAmalgamationHashes`, `./gradlew check`.
10. **CI green** on `main` for the release commit; reproducible-build workflow verifies byte-identical rebuild.
11. **MEASUREMENTS.md** contains formal M0 benchmarks (CPU vs Vulkan × context 4K/8K/16K × 3 repeats × Q4_K_M artifact) with device provenance per hardware handoff §14.
12. **THREAT_MODEL.md** and **PRIVACY.md** ship with the release. PRIVACY.md exists and is honest. THREAT_MODEL.md needs writing (M3 deliverable).
13. **Distribution artifacts** exist: GitHub release with signed APK, F-Droid metadata prepared for merge request, Obtainium manifest published, Accrescent application submitted (approval may lag ship).

### 1.3 Explicit non-goals for v1

Never drift into these under any autonomous decision-making:

- Cloud anything (no fallback inference, no sync server, no telemetry endpoint)
- Play Store distribution
- Play Services / GMS anything
- Root, unlocked bootloader, or weakened GrapheneOS posture
- Multi-device sync (v2)
- LLM-built knowledge graph (v2)
- Voice input (v2)
- Image generation (v2/v3)
- Artifact Engine — DOCX/PDF *edit* (v2)
- Any INTERNET permission for any reason

---

## 2. Current State (2026-09-20, main @ 760d8ee)

### 2.1 By raw metrics

- ~60 bd issues closed
- ~140 bd issues open (implementation + v2/v3 vision + some duplicates)
- ~80 commits on `main`
- ~250–400 agent-hours consumed (Sonnet + Opus + Haiku + Fable)
- ~85% design complete, ~30% code complete, ~15% end-to-end-usable complete
- Fable's original v1 estimate: 1,148 agent-hours. Estimated remaining: **235–360 agent-hours**.

### 2.2 What SHIPS today (verified by inspection of `main`)

**Foundations**
- Gradle 21-module scaffold (`:app`, 9 `:core:*`, 2 isolated services, 10 `:feature:*`, `:testing`)
- Version catalog pinned; `gradle/verification-metadata.xml` covers 1130 SHA-256-verified artifacts
- CI (unit + lint + assemble on every push/PR, ~5.5 min), DCO check, dependency review, Dependabot (gradle + actions, weekly), reproducible-build workflow on release tags
- Build guards (module isolation, INTERNET/GMS denylist, license audit) — `checkManifestGuards` proved value by rejecting ONNX Runtime 1.29 for adding INTERNET
- `foss` flavor arm64-v8a only; `dev` flavor adds x86_64 for emulator

**Native vault backend**
- `libskein_sqlite.so` — SQLCipher 4.17.0 + OpenSSL 3.5.4 (static) + sqlite-vec v0.1.9 + FTS5
- 6.6 MB stripped arm64-v8a; byte-identical rebuilds; ELF NEEDED lists only `liblog libandroid libm libdl libc` (no runtime `libcrypto.so`)
- Integrity: `SHA256SUMS.txt` in-tree, CMake `FATAL_ERROR` on hash drift, `reproducible-build.yml` regenerates + diffs on release tags
- Amalgamation-committed policy locked in `docs/design/AMALGAMATION_POLICY.md`

**UI shell (no live content yet)**
- Compose theme + IBM Plex Mono + terminal/editor aesthetic + WCAG AAA contrast
- Adaptive layout: fold posture detection, dual-pane on unfolded, icon rail, timeline collapse, split coordinator
- Tab system: preview/pinned semantics (Cursor-style), Recent dropdown on folded phone
- Nav drawer + hamburger + command bar (with SecureTextField, from skein-yb3m)
- Settings screen with Security/Models/Vault/About sections
- About screen with license list (LicensesRepository reads `licenses.json`, groups by SPDX)
- MainActivity + SKEIN_SHELL_ROOT test tag; FLAG_SECURE runtime toggle

**Security substrate**
- Manifest baseline: `allowBackup=true` scoped by `dataExtractionRules`; `fullBackupContent=@xml/backup_rules_legacy` (API 30 path); `hasFragileUserData`; `ProfileInstallReceiver` removed
- Data extraction exclusions cover `vault.db*`, `attachments/`, `models/`, `cache/staging_export/`, `keys/`, sensitive shared prefs
- FLAG_SECURE-on-default + notification `VISIBILITY_SECRET` helper + recents `setTaskDescription` stub (API 33 guarded)
- SecureTextField primitive (`InterceptPlatformTextInput` + `IME_FLAG_NO_PERSONALIZED_LEARNING` + `TYPE_TEXT_FLAG_NO_SUGGESTIONS`)
- `RawTextFieldTest` source-scan enforces SecureTextField at every call site

**Markdown**
- `:core:markdown` AST with GFM + first-class wikilinks (`org.jetbrains:markdown` under the hood, wrapped)
- Compose `AnnotatedString` renderer
- 38 unit tests; 200KB perf smoke

**ONNX Runtime**
- Wired into `:embedder-service` (`onnxruntime-android 1.27.0`, MIT)
- `OnnxSession` wrapper isolates ONNX types from callers
- Smoke test with a bundled 1×1 identity model
- ONNX 1.29 explicitly blocked (vendor added INTERNET permission)

**Test infrastructure**
- `:testing` pure Kotlin JVM module (added to `PURE_JVM_MODULES` isolation-guard allowlist)
- 6 interface fakes for plan §4 contracts (Inference/Vault/Index/Retrieval/Persona/Embedder) — scaffold only
- JVM / Robolectric (SDK 34 per bd memory) / instrumented tier structure
- `docs/TESTING.md` documents tier + how to run
- `RawTextFieldTest` in `:testing`

**Design substrate**
- Spec: `docs/superpowers/specs/2026-09-19-skein-design.md` (post-review resolutions applied)
- Plan: `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` (1148 hours, 167 issues, 6 milestones)
- Roadmap: `docs/ROADMAP.md`
- Artifact Engine v2 sketch: `docs/design/ARTIFACT_ENGINE.md`
- Post-review architectural decisions: `docs/design/POST_REVIEW_RESOLUTIONS.md` (citation stability, model verification, Binder contracts, export flow)
- Vault tool primitives: `docs/design/VAULT_TOOL_PRIMITIVES.md` + `:core:agent` interfaces (`VaultReader/Writer/Patcher/Search/Links/Tags/Personas`, `AuthorizationToken`, `RevisionConflict`)
- Amalgamation policy: `docs/design/AMALGAMATION_POLICY.md`
- Skill guardrails: `docs/design/SKILL_GUARDRAILS.md`

**Docs on `main`**
- `README.md`, `LICENSE` (Apache 2.0), `NOTICE` (11 SPDX-tagged third-party attributions)
- `CONTRIBUTING.md` (DCO + bd workflow), `GOVERNANCE.md` (BDFL year 1), `CODE_OF_CONDUCT.md`, `SECURITY.md`
- `PRIVACY.md` (400 lines, 11 sections, every claim tagged `[shipped]` / `[v1 design]` / `[v2 roadmap]`)
- `docs/BD_TAXONOMY.md`, `docs/TESTING.md`, `docs/VERIFICATION.md`, `docs/BACKUP_EXCLUSIONS.md`
- `docs/Handoffs/skein-fold-m0-hardware-handoff.md` (this file's sibling)

**Hardware lab**
- Pixel 9 Pro Fold provisioned, GrapheneOS Android 17
- ADB over USB works; `skein-fold-agent` SSH-over-ADB alias verified end-to-end
- Termux env frozen: clang, cmake, git, wget, python, openssh, vulkan-headers/loader/tools, shaderc, spirv-headers, spirv-tools
- Stable llama.cpp binaries at `~/skein-device/bin/{llama-bench,llama-cli}-{cpu,vulkan}` with SHA-256 recorded
- Mali-G715 Vulkan proven; Q3_K_M smoke inference completed (23.47 pp / 5.61 tg — smoke only, NOT formal)
- Baseline captured to `tools/m0-benchmark/output/baseline/{device-baseline,llama-binaries.sha256}-2026-09-20.*`

### 2.3 What's DESIGNED but not implemented

Every architecture-level question has a resolution doc. Zero of these have Kotlin/JNI implementations:

- **Attachment encryption** — `skein-wa1l` immutable-write-per-UUID + fresh per-attachment key + deterministic IV from UUID prefix
- **Model verification** — `skein-st1r` immutable store + `FileChannel.tryLock` + SHA-256 pre-mmap + BLAKE3-256 post-mmap + companion-file hashes + `ManifestBinding v2`
- **Binder contracts** — `skein-pn1l` AIDL v2 with 32 KiB inline cap, 128 KiB refuse ceiling, `SharedMemRef` via ParcelFileDescriptor, `linkToDeath` both directions, backpressure via `dropped` field
- **Export flow** — `skein-7ki2` DocumentsProvider-only, `grantUriPermissions=false` + declared `<grant-uri-permission android:pathPrefix>` subsets, WorkManager 10-min TTL + `BOOT_COMPLETED` cleanup + immediate sweep on lock
- **Citation stability** — `skein-uo5n` `document_revisions` table (BLAKE3 content-addressed) + versioned `citation-record-v1` JSON with `(document_id, revision_hash, locator, excerpt, excerpt_hash, source_kind)` + source-changed banner
- **Canonical storage** — `skein-vhtu` SQLCipher-primary + DocumentsProvider-exposes-Markdown-as-files
- **Lock policy** — `skein-3xyu` unlocked-session indexing only; lock cancels across all 3 processes
- **RC split** — `skein-4pqj` E0.I23 → 23a (produce) + 23b (validate); E10.I15 depends on 23b
- **Vault tool primitives** — `:core:agent` interfaces defined; implementations in `:core:vault` + `:core:rag` (E2/E5)

### 2.4 What's COMPLETELY ABSENT

The seven-step user loop has zero cells wired end-to-end. Concretely, none of these code paths exist:

- SQLite schema creation (documents / chunks / edges / entities / messages / personas / models / ingest_queue)
- SQLCipher key derivation + biometric-gated unwrap + StrongBox wrapping
- `VaultRepository` implementation (interface-only in `:core:agent`)
- `DocumentsProvider` implementation
- Attachment write path (encryption per skein-wa1l)
- Migration framework (revisions 001–005 designed, none applied)
- `:inference` isolated service Binder impl
- `LlamaCppEngine` JNI wrapper
- Model manifest handling + hash-verified SAF import
- Model load with `FileChannel.tryLock` + dual-hash discipline
- AIDL contracts + backpressure
- Token streaming from `:inference` to `:app` UI
- `:embedder` real integration (only smoke test)
- Chunker
- Embedding generation
- GLiNER entity extraction
- Vector search (sqlite-vec cosine)
- Lexical search (FTS5 BM25)
- Personalized PageRank
- Ingest worker (WorkManager, unlocked-session gated)
- `RetrievalService` implementation
- Prompt assembler with retrieved-content-is-data separation
- Live-preview Markdown editor UI (Compose)
- Wikilink autocomplete
- Backlinks panel
- Inline AI: selection menu + slash commands
- Selection menu actions (rewrite, continue, summarize, ask, extract entities)
- Chat surface streaming tokens with citations
- Context panel (retrieved chunks preview)
- Local 2-hop graph view
- Timeline surface (wiki-native chronological)
- Note rendering, chat rendering, AI-output rendering
- PDF text extraction (PdfBox-Android or similar)
- Image → Gemma 4 vision analysis path
- DOCX minimal writer
- DOCX template loader
- MD/PDF/DOCX export orchestration
- Export staging cache + WorkManager expiry + BOOT_COMPLETED cleanup
- Share targets (`ACTION_SEND` receivers) for text/image/file/PDF
- Share source (share sheet integration for outbound)
- First-run onboarding flow
- Three-way model picker UI (Gemma 4 / Qwen abliterated / BYOG)
- Model download UX (SAF picker + hash verify + sigstore attestation)
- Persona editor
- Persona-scoped filtering in timeline
- `VoiceInteractionService` (locked-down, `onHandleAssist` no-op)
- Formal M0 benchmarks (only smoke)
- MEASUREMENTS.md contents
- THREAT_MODEL.md
- Vendored androidx.sqlite JNI driver (skein-e2ki)
- Instrumented AndroidTest wrapping libskein_sqlite.so (skein-k3b2)
- Release signing setup (skein-6pf, needs user keystore)
- Reproducible-build 3rd-party verification
- Accrescent submission
- F-Droid main MR
- IzzyOnDroid submission

---

## 3. Non-negotiables (loop MUST enforce; never violate)

These are **kernel-level guarantees**. Any autonomous decision that would violate one of these must escalate to human. Ranked by severity of violation.

1. **No `INTERNET` permission**, ever, in any variant. `checkManifestGuards` enforces at build time. Loop must reject any PR/merge that would add it.
2. **No Google Play Services / Firebase / MLKit / Play** dependencies. `checkDependencyGuards` enforces. Loop must reject any dep that would introduce these transitively.
3. **No telemetry, crash reporting, or analytics**. Zero opt-in surface. No SDK, no endpoint, no anonymized-anything.
4. **Model files hash-verified before mmap**, dual-hash discipline (SHA-256 pre + BLAKE3-256 post) per skein-st1r design.
5. **Inference and embedder run in isolated processes** (`android:isolatedProcess="true"`). Never remove this attribute; guard verifies on every variant.
6. **`foss` build flavor uses only permissive licenses** (Apache-2.0, MIT, BSD-*, ISC, CC0, OFL-1.1, Unlicense). LicenseAudit enforces; loop must reject any dep that would fail this.
7. **No `bd dolt push` from any subagent** — coordinator-only. Every dispatch prompt must forbid this.
8. **`~/.ssh/skein_fold` never committed**, never printed, never passed as a subagent parameter.
9. **Fold access is serial and hardware-runner-only**. Non-hardware subagents never `adb`/`ssh` the device. Only one hardware-runner claim active at any time.
10. **Never overwrite** `tools/m0-benchmark/output/baseline/device-baseline-*` or the Q3_K_M smoke SHA record; add new dated baselines after intentional changes.
11. **Retrieved content is data, not instructions.** Every prompt assembler and skill template must respect this per `docs/design/SKILL_GUARDRAILS.md`.
12. **No model-derived tool calls** in v1. LLM cannot emit Intent URIs, cannot autonomously trigger writes. Every out-of-app action requires explicit user tap.
13. **Reproducible builds** from the first tagged release. Any build change that breaks reproducibility escalates to human.
14. **Every document has a stable UUIDv7** in its frontmatter. Never change this scheme (locks v2 sync compatibility).
15. **All vault data app-private, StrongBox-backed keys, DocumentsProvider-exposed only.** Never expose vault via `content://` in a way that bypasses the StrongBox unlock.
16. **Q3_K_M is smoke, not formal.** Never let it satisfy formal Q4_K_M requirements in `MEASUREMENTS.md`.
17. **Failures are results.** OOM, timeout, thermal abort: record with provenance, never silently retry.
18. **Fold environment frozen**: no `pkg upgrade`, no toolchain replacement, no unrelated stacks. Only claim-authorized changes.

---

## 4. What we learned — operational discipline

These are hard-won patterns from ~10 waves of parallel agent dispatch this session. The loop must respect them.

### 4.1 bd is authoritative

- No `TodoWrite`. No markdown TODO. No parallel plans. `bd` is the only tracker.
- `bd ready --limit N` shows unblocked work with dependencies resolved.
- Every dispatch claims via `bd update <id> --status in_progress`.
- Every close is `bd close <id> --reason "..."` with a substantive reason.
- Add cross-agent knowledge to `bd remember`; search with `bd memories <keyword>`.
- Duplicate issues get closed as duplicates with a note pointing at the original.

### 4.2 Worktree isolation is mandatory for parallel dispatch

- WorktreeCreate/WorktreeRemove hooks are configured at `~/.claude/settings.json` pointing at `~/.claude/worktree-{create,remove}.sh`. If missing, `Agent` calls with `isolation: "worktree"` fail.
- Every parallel dispatch uses `isolation: "worktree"` — provably prevents collisions like the `SkeinApp.kt` conflict from wave 2.
- Worktrees land under `$scratchpad_dir/worktrees/<agent-name>` on branch `claude/agent-<name>`.
- Each agent's SSH-key access, JDK, Android SDK path (`local.properties`) must be resolved per-worktree; agents provision these themselves via `brew install openjdk@17` and `local.properties` pointing at `/Users/andrewherrera/android-sdk`.

### 4.3 Merge protocol

- Agent pushes to `origin/claude/agent-<name>` (topic branch); never to `main`.
- Coordinator fetches, then attempts `git merge --ff-only`; if that fails (main advanced during agent work), does `git merge --no-ff` with a full merge-commit message.
- **Critical shell gotcha**: never chain `git merge --ff-only ... || git merge --no-ff ... && git push --delete ...` — the `&&`/`||` chain has bitten us twice. Use explicit `if git merge --ff-only ...; then push; else merge-no-ff; push; fi` OR do it in separate commands.
- After push, `git push origin --delete claude/agent-<name>` to clean up.
- If a topic-branch push preceded a failed merge and the branch got deleted from origin: the commit is still reachable locally (`git cat-file -e <sha>`) and can be merged directly by SHA.
- Squash-merge for multi-commit "story" branches (like the CI wave); merge-commit for atomic single-commit work.
- Force-with-lease is acceptable on `main` only when you own the last commit and are amending trivia (like gitignore fixes).

### 4.4 Pre-close verification is non-negotiable

The loop must ensure every dispatch prompt requires all four before close:

```
./gradlew :<module>:ktlintCheck       # ← the one skein-fvne missed
./gradlew :<module>:check             # ktlint + lint + unit tests
./gradlew :app:check                  # integration doesn't break :app
# For UI/Compose modules, previews must render
```

Missing `ktlintCheck` on new modules is the #1 CI-breaking pattern this session. bd memory `pre-close verification checklist` documents this.

### 4.5 Agents surface real bugs while working

Multiple agents this session found pre-existing bugs during verification:

- `skein-2mv` Sonnet found the ProfileInstallReceiver lint failure (`skein-jecn`)
- `skein-2at` Sonnet fixed a debug-variant `androidx.activity.compose` classpath bug
- `skein-9qb` Sonnet found `androidx.tracing` missing from verification-metadata (`skein-2nfc`)
- `skein-i0op` Sonnet found `overrides.json` was being read from the wrong path (fixed inline)
- `skein-7uwy` Sonnet found `CommandBar.kt` was using raw `TextField` (created `skein-yb3m`)

The loop should treat "agent found a related bug" as a normal event — file a followup, don't silently expand scope.

### 4.6 Spec-over-prompt discipline

When a dispatch prompt contradicts the authoritative spec or bd issue, agents should FOLLOW the spec and NOTE the deviation, not silently follow the prompt. Multiple agents demonstrated this:

- `skein-msy` Sonnet: prompt said `allowBackup=false` but spec §9 said scope with `dataExtractionRules` — followed spec, tabled the difference explicitly
- `skein-bkn` Sonnet: noticed `POST_REVIEW_RESOLUTIONS §4.3` amended earlier E3.I1 posture — deviated from bd issue text, documented amendment
- `skein-2mv` Sonnet: plan called `:testing` an Android library; agent made it pure Kotlin JVM for isolation-guard purity — deviated, documented, updated `PURE_JVM_MODULES`

The loop should INSTRUCT agents to prefer authoritative documents over dispatch prompts, and to document deviations explicitly (via `bd note`) before closing.

### 4.7 Model-tier selection heuristic

Empirical rules from this session:

| Task shape | Model | Wall clock typical |
|---|---|---|
| Docs / templates / small refactor / trivia fix | Haiku 4.5 | 2–5 min |
| One-file implementation with tests (Compose Composable, small task) | Sonnet | 10–20 min |
| Multi-file feature (adaptive layout, tab system, settings screen) | Sonnet | 20–40 min |
| Architecture spike / design doc / security-critical code | Opus | 20–60 min |
| Deep research pass (Fable's original plan, coordinated design pass) | Fable 5.1 | 15–60 min (background) |
| Native NDK / cross-language integration (JNI, C interop) | Opus | 30–60 min |

Sonnet is the workhorse; Haiku for cheap trivia; Opus for architecture and native. Fable reserved for research/adversarial review, not routine implementation.

### 4.8 Duplicate/scope-creep resolution

When multiple agents file overlapping issues (`skein-hvsh` dup of `-twog`, `-nmzn` dup of `-6eoj`, `-58xz` dup of `-bxfk`, `-6fe3` dup of `-bxfk`, `-k0qp` dup of `-bxfk`), coordinator closes the duplicate with a note pointing at the original. Never leave duplicates open. Fable's early plan may have similar overlaps to resolve.

### 4.9 CI failures are signal

When main CI goes red, the loop must diagnose before continuing to dispatch. Silent-red CI let ProfileInstallReceiver break 5+ merges before it was caught. Prefer explicit "check `gh run list --branch main` at the start of every dispatch wave" over trust.

### 4.10 The Fold is a shared, serial resource

Only the dedicated hardware-runner agent (definition in `docs/DEVICE_RUNNER.md` when it exists — currently forthcoming as part of skein-79od) may:
- `adb` the device
- `ssh skein-fold-agent`
- pull baselines, push binaries, modify Termux
- run benchmarks

Other agents consume produced artifacts (`tools/m0-benchmark/output/`) but never talk to the device. The queue is strictly serial: one hardware issue in `in_progress` at any time.

---

## 5. Architecture snapshot (for a fresh session)

### 5.1 Module DAG (as of `main`)

```
:app
├── :feature:shell         (theme + nav + adaptive layout + tabs + split)
├── :feature:timeline      (placeholder)
├── :feature:chat          (placeholder)
├── :feature:editor        (placeholder — AST parser in :core:markdown)
├── :feature:graph         (placeholder)
├── :feature:models        (placeholder)
├── :feature:personas      (placeholder)
├── :feature:onboarding    (placeholder)
├── :feature:build         (placeholder — not sure what this is; may be dead)
├── :feature:settings      (Settings + About + FlagSecureToggle)
├── :inference-service     (isolated process; stub)
├── :embedder-service      (isolated process; smoke test only)
├── :core:vault            (placeholder — schema + repo not implemented)
├── :core:rag              (placeholder)
├── :core:markdown         (AST + renderer — real)
├── :core:model            (data types — pure Kotlin JVM)
├── :core:agent            (vault tool primitives — interface only)
├── :core:security         (placeholder)
├── :core:ipc              (placeholder — AIDL contracts not defined)
├── :core:export           (placeholder)
├── :core:inference        (placeholder — engine interface not defined)
└── :core:build            (placeholder — meta module)

:testing (pure JVM; shared fakes + RawTextFieldTest)
```

Guards enforce:
- `PURE_JVM_MODULES` allowlist: `:core:model`, `:core:markdown`, `:core:agent`, `:testing` (per IsolationGuardPlugin)
- Service modules (`:inference-service`, `:embedder-service`) may depend on `:core:ipc`, `:core:model`, `:core:security`, `:testing` — nothing UI-side
- `:app` may depend on everything (single leaf module for wiring)

### 5.2 The seven-step loop — where each step lives

| Step | Feature module | Core module(s) | Service | Design doc |
|---|---|---|---|---|
| Unlock | :feature:shell (biometric prompt) | :core:security | — | POST_REVIEW_RESOLUTIONS §2 (via wa1l/msy) |
| Write | :feature:editor | :core:markdown, :core:vault | — | VAULT_TOOL_PRIMITIVES |
| Import | :feature:shell (share target) | :core:vault | — | VAULT_TOOL_PRIMITIVES §export |
| Index | (background WorkManager) | :core:rag, :core:vault | :embedder-service | POST_REVIEW_RESOLUTIONS §1 (uo5n) |
| Search / Ask | :feature:chat | :core:rag, :core:agent | :inference-service | POST_REVIEW_RESOLUTIONS §3 (pn1l) |
| Cite | :feature:chat | :core:rag (citation-record-v1) | — | POST_REVIEW_RESOLUTIONS §1 |
| Approve edit | :feature:editor (inline AI) | :core:agent (AuthorizationToken) | :inference-service | VAULT_TOOL_PRIMITIVES §authorization |
| Export | :feature:shell + DocumentsProvider | :core:export | — | POST_REVIEW_RESOLUTIONS §4 (7ki2) |

### 5.3 Interface contracts (locked)

- `:core:agent/tools/VaultTools.kt` — `VaultReader`, `VaultWriter`, `VaultPatcher`, `VaultSearch`, `VaultLinks`, `VaultTags`, `VaultPersonas`, aggregate `VaultTools`, `AuthorizationToken`, `RevisionConflict`, `ToolResult<T>` sealed hierarchy
- `POST_REVIEW_RESOLUTIONS.md §3` — AIDL v2 sketches for `:app ↔ :inference-service` and `:app ↔ :embedder-service`
- `docs/superpowers/plans/2026-09-19-skein-v1-plan.md §4` — canonical Kotlin interfaces for `InferenceEngine`, `VaultRepository`, `IndexStore`, `RetrievalService`, `PromptAssembler`, `PersonaService`, `ExportService`, `ImportService`, `EmbedderService`

### 5.4 Data model (SQL schema — from spec §5 with post-review amendments)

The schema is fully specified in `docs/superpowers/specs/2026-09-19-skein-design.md §5` with additions from `POST_REVIEW_RESOLUTIONS.md`:

- `documents`, `chunks` (+ `chunks_fts`, `chunks_vec`), `edges`, `entities`, `messages`, `personas`, `models`, `ingest_queue` — from spec
- `document_revisions` (BLAKE3 content-addressed) — from POST_REVIEW_RESOLUTIONS §1
- `export_stages` — from POST_REVIEW_RESOLUTIONS §4
- `attachment_master_key`, `attachment_keys` — from `docs/design/ATTACHMENT_ENCRYPTION.md` §3.4 (the 3-layer attachment key hierarchy; see also `docs/design/LOCK_POLICY_INDEXING.md` for the lock-triggered cancellation semantics that apply to in-flight attachment work)

Migrations 001–005 pre-numbered in POST_REVIEW_RESOLUTIONS. First migration to run against SQLCipher will be 001 (base schema) once `:core:vault` implementation lands. Note: `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` currently has an independent migration 003 (`ingest_attempts`, from its own `E5.I10`) that has not yet been reconciled with POST_REVIEW_RESOLUTIONS' 003–005 numbering, nor with `LOCK_POLICY_INDEXING.md`'s proposed `006_recovery_drafts.sql` — see the `> COORDINATOR TODO` on `E3.I3` in the plan doc.

---

## 6. The autonomous loop — design

### 6.1 Goal statement for `/loop`

Suggested single-line goal:

```
Drive Skein v1.0 to shippable release by continuously pulling `bd ready`,
dispatching worktree-isolated agents at appropriate tiers, merging their
work through the protocol, and iterating until §1.2 Definition of Done
is met OR a non-negotiable (§3) blocks progress and requires human input.
```

### 6.2 Per-iteration loop body

```pseudocode
loop:
    1. State check
        - Fetch origin/main
        - `git status` clean, `git log --oneline -1` recorded
        - `gh run list --branch main --limit 3` — if red, DIAGNOSE FIRST
        - `bd status` — count open/blocked/ready
        - Verify no in_progress issue has been abandoned (last-updated > 24h)

    2. Priority sort of `bd ready`
        - P0 first, then P1, then P2, then P3
        - Within a priority: `blocks-others` label first
        - Prefer critical-path issues (see plan §5 critical path list)
        - Skip `needs-hardware` unless the hardware queue is empty
        - Skip `needs-human-review` — surface to human instead

    3. Dispatch selection
        - Match tier to task: use §4.7 heuristic
        - Verify scopes are provably disjoint (no shared file paths)
            - Use `git diff main..origin/claude/agent-*` if any topic
              branches still exist
        - Max concurrent: 5 agents (proven safe with worktree isolation)
        - Hardware queue: 1 at any time; other agents may NOT be hardware
        - Claim each: `bd update <id> --status in_progress`

    4. Dispatch (all in one message for parallel)
        - Use Agent tool with isolation:"worktree" always
        - Prompt: include
            - The critical rules from §3
            - The relevant spec/plan/design doc references
            - Pre-close verification checklist from §4.4
            - Spec-over-prompt directive from §4.6
            - "Do NOT `bd dolt push`" (coordinator-only)
            - "Push to your topic branch only"

    5. Wait for reports
        - Each agent completes with a hand-back message
        - Verify claims (§6.4)
        - Merge (§6.4)

    6. Post-merge sanity
        - `gh run list --branch main --limit 1` — should be green or running
        - If red: diagnose IMMEDIATELY before next dispatch
        - `bd status` — count closed this iteration

    7. Repeat unless termination (§6.5)
```

### 6.3 Dispatch prompt template (every agent)

Every dispatch prompt should end with these mandatory sections:

```
## Pre-close verification (MANDATORY)
- `./gradlew :<affected-module>:ktlintCheck` — passes
- `./gradlew :<affected-module>:check` — passes (ktlint + lint + unit tests)
- `./gradlew :app:check` — still passes
- If UI: previews render at relevant breakpoints
- Do NOT close bd until all pass.

## Guardrails
- Push to topic branch only. Do NOT `bd dolt push`.
- Do NOT approach the Fold (no adb/ssh) — hardware-runner-only.
- Do NOT weaken any non-negotiable in docs/Handoffs/skein-v1-autonomous-completion.md §3.
- Prefer authoritative spec/design docs over this prompt when they conflict.
  Document deviations via `bd note <id>` before closing.

## Close, commit, push
```bash
git push  # topic branch only
bd close <id> --reason "<substantive reason with what was proven>"
```
```

### 6.4 Merge verification

Coordinator merges only after:

1. Reading the agent's hand-back report (substantive claim of what was proven)
2. Running `git fetch origin`
3. Optional but recommended: `git diff main..origin/<topic-branch>` sanity check
4. Explicit ff-or-not check:
   ```bash
   if git merge --ff-only origin/<topic-branch>; then
       # FF succeeded
   else
       git merge --no-ff origin/<topic-branch> -m "<full merge message>"
   fi
   ```
5. Push main + delete topic branch
6. Verify `bd show <id>` shows CLOSED with the agent's reason
7. If agent shipped without closing (Haiku pattern), close on their behalf with a note

### 6.5 Termination conditions

The loop **stops autonomously** when:

- **Success**: §1.2 Definition of Done fully verified on a signed release commit
- **Blocked on human**: a P0/P1 issue requires `needs-human-review` and no unblocked ready work remains
- **Blocked on hardware**: the hardware queue needs formal M0 Q4_K_M measurements and neither the Q4 model nor the M0 harness dry-run is complete
- **CI red on main**: 2 consecutive failed merges or 1 non-trivial-to-diagnose failure — surface to human
- **Non-negotiable conflict**: an agent proposes changes that would violate §3; loop refuses and reports
- **Cost ceiling**: total agent tokens exceed a per-session limit (recommend 2M cumulative before human check-in)

### 6.6 The three parallel tracks

The loop can run **three parallel tracks** with different constraints:

- **Track A — Non-hardware v1 implementation** (5 agents in parallel with worktree isolation)
    - Vault, RAG, editor, chat, import/export, personas, model management, first-run — mostly Sonnet with Opus for architecture-critical pieces
    - Uses `:testing` fakes to defer real hardware coupling until Track B lands
- **Track B — Hardware/M0 track** (1 hardware-runner agent at a time)
    - Serial. Adapts harness, runs dry-run, acquires Q4 model, runs formal matrix, writes MEASUREMENTS.md
    - Every issue claimed against this track prevents other hardware-runner claims
- **Track C — Documentation + polish** (Haiku, low-priority, in parallel with anything)
    - THREAT_MODEL.md, CHANGELOG.md, in-app help, release-notes template, F-Droid metadata prep
    - Runs when Track A + Track B are saturated or blocked

Track A is the critical path; Track B unblocks Track A at the point where formal measurement decisions gate architecture (embedder path choice, context-length caps); Track C runs opportunistically.

---

## 7. Failure modes + recovery

Concrete patterns encountered this session. The loop should be prepared for all of them.

### 7.1 Merge conflict on parallel scope overlap

**Encountered**: `skein-b0b` (Settings screen) added `destinationContent` slot to `SkeinApp.kt`; `skein-2at` (SplitCoordinator) also modified `SkeinApp.kt`. Merged with 2at first; b0b later hit conflict.

**Recovery**: manually resolve — read both branch's changes, choose the combined semantics (usually both are wanted), commit merge.

**Prevention**: pre-dispatch, scope map. If two agents will touch the same file, serialize them.

### 7.2 Topic-branch deleted before merge landed

**Encountered**: shell chaining `git push --delete` after a failed merge caused the topic branch to disappear before the merge completed.

**Recovery**: `git cat-file -e <commit-sha>` verifies commit is still in local object DB. `git merge --no-ff <sha>` merges directly by SHA. Push main.

**Prevention**: never chain `merge ... && push --delete ...`. Use explicit `if/then`.

### 7.3 Java missing in worktree

**Encountered**: Haiku agents can't verify because worktree has no JDK. `./gradlew` fails with "Unable to locate a Java Runtime."

**Recovery**: agents can `brew install openjdk@17` and write a gitignored `local.properties` pointing at `/Users/andrewherrera/android-sdk`. Existing agents (skein-tfj, skein-1ld, skein-2mv, skein-i0op) all did this successfully.

**Prevention**: dispatch prompts should include the environment-provisioning recipe as a template step.

### 7.4 CI silent-red persisting

**Encountered**: ProfileInstallReceiver lint failure broke every merge to main for 5+ commits before it was caught.

**Recovery**: diagnose with `gh run view --log-failed`, apply targeted fix, commit + push.

**Prevention**: after every merge, `gh run list --branch main --limit 1` and refuse to start the next wave if red.

### 7.5 Agent reports "closed" but bd shows still-open

**Encountered**: Haiku agents sometimes finish work + push + report but forget `bd close`. Also sometimes the close succeeds but the reason field is empty.

**Recovery**: coordinator verifies via `bd show <id>` and closes on the agent's behalf with a substantive reason.

**Prevention**: dispatch prompts should say explicitly "Do NOT forget the `bd close` step" (already added to Haiku prompts).

### 7.6 Dependabot introducing a non-negotiable violation

**Encountered**: ONNX Runtime 1.29 declared INTERNET permission. `checkManifestGuards` correctly rejected.

**Recovery**: close the PR as declined, file a tracking issue for future upgrade attempts.

**Prevention**: guards are the mechanism; the loop only needs to actually merge Dependabot PRs after CI passes, not force-merge.

### 7.7 Agent creates duplicate issue

**Encountered**: 5+ times this session (skein-hvsh, -nmzn, -58xz, -6fe3, -k0qp — all duplicates of already-fixed issues).

**Recovery**: coordinator closes duplicate with a note pointing at the original.

**Prevention**: dispatch prompts should say "if you find a pre-existing bug during verification, search bd first via `bd search '<keyword>'` before filing a followup."

### 7.8 Agent submits fabricated verification results

**Not encountered**, but plausible: an agent could claim ktlint/tests passed without running them.

**Recovery**: CI on the topic branch verifies. If topic-branch CI is red, do not merge.

**Prevention**: dispatch prompts must include "verification results MUST cite actual command output; a claim without command output is a red flag."

### 7.9 Serial-hardware violation

**Not encountered so far**, but the risk is real: a non-hardware agent could run `adb` or `ssh skein-fold-agent`.

**Recovery**: revert the agent's commit if it produced device-side changes; audit `~/.ssh/known_hosts` and Termux `authorized_keys` for anomalies.

**Prevention**: every dispatch prompt explicitly says "Do NOT approach the Fold." The hardware-runner is a dedicated role, and only that role has device-touching commands in its prompt.

---

## 8. Track A — non-hardware v1 implementation queue (ordered)

The order below reflects the critical path from Fable's plan §5 plus dependencies discovered during design. **Each row is a bd issue or a group of related issues.** Loop should follow this order roughly (dependencies enforce partial order; parallel where possible).

### 8.1 Immediate wave (unblocks everything downstream)

| Priority | Issue(s) | Rationale | Tier |
|---|---|---|---|
| P0 | `skein-e2ki` — vendor androidx.sqlite JNI driver | Every downstream vault operation needs this to open SQLCipher through Kotlin. **Blocks: everything vault-related.** | Opus |
| P0 | `skein-79od` (in flight now) — M0 harness adaptation | Unblocks formal M0 → embedder path decision → downstream retrieval work | (running) |

### 8.2 Vault core (once JNI driver lands)

| Issue | Description | Depends on |
|---|---|---|
| E2.I1 | SQLite schema DDL + migration 001 | e2ki |
| E2.I2 | `VaultRepository` implementation (documents, chunks, messages, personas) | E2.I1 |
| E2.I3 | SQLCipher key management (StrongBox-backed) | e2ki, E2.I1 |
| E2.I4 | Biometric unlock flow (`BiometricPrompt` → key unwrap) | E2.I3 |
| E2.I5 | Attachment write path (immutable-write per skein-wa1l) | E2.I2, wa1l |
| E2.I6 | Export staging + WorkManager TTL + BootReceiver | 7ki2 design |
| E2.I7 | DocumentsProvider implementation | E2.I2, vhtu |
| Migration 003 | `document_revisions` table (uo5n) | E2.I1 |
| Migration 005 | `export_stages` table (7ki2) | E2.I1 |

### 8.3 Inference service (parallel with vault after e2ki)

| Issue | Description | Depends on |
|---|---|---|
| E4.I1 | `LlamaCppEngine` JNI wrapper | native/llama.cpp build |
| E4.I2 | Model manifest handling + hash-verified SAF import | ModelManifest v2 (st1r) |
| E4.I3 | AIDL contracts + `SharedMemRef` | pn1l design |
| E4.I4 | `:inference-service` Binder impl + token streaming Flow | E4.I1, E4.I3 |
| E4.I5 | `linkToDeath` + backpressure + service-death recovery | E4.I3 |
| E4.I6 | Model file `FileChannel.tryLock` + dual-hash discipline | st1r |
| Migration 004 | `models.post_mmap_blake3` column | E4.I2 |

### 8.4 Embedder service (parallel with inference)

| Issue | Description | Depends on |
|---|---|---|
| E5.I1 | Embedder AIDL contract | pn1l |
| E5.I2 | GLiNER entity extraction wiring | Embedder AIDL |
| E5.I3 | Embedding generation path (choose nomic vs Qwen3 post-M0) | M0 decision |
| E5.I4 | Chunker (~512 tokens, 64 overlap) | none |
| E5.I5 | sqlite-vec vector search | e2ki, chunker |
| E5.I6 | FTS5 BM25 lexical search | e2ki |
| E5.I7 | Personalized PageRank over edges | E5.I4, E5.I5 |
| E5.I8 | `RetrievalService` implementation | E5.I5, E5.I6, E5.I7 |
| E5.I9 | Prompt assembler with retrieved-content-is-data separation | E5.I8, q3r7 |
| E5.I10 | Ingest worker (WorkManager, unlocked-session gated) | E2, E5.I4 |
| Migration 002 | Real citation-record-v1 in `messages.retrieved_chunks` | uo5n |

### 8.5 Editor surface

| Issue | Description | Depends on |
|---|---|---|
| E7.I1 | Live-preview Markdown editor Composable | :core:markdown |
| E7.I2 | Wikilink autocomplete | :core:markdown |
| E7.I3 | Backlinks panel | E2 |
| E7.I4 | Inline AI selection menu | :core:agent, :inference |
| E7.I5 | Slash commands (`/ai continue`, `/link related`) | E7.I4 |
| E7.I6 | Local 2-hop graph view | E5.I7 |

### 8.6 Chat surface

| Issue | Description | Depends on |
|---|---|---|
| E6.I8 | Chat screen with streaming tokens | E4.I4, E5.I8, E5.I9 |
| E6.I9 | Inline citations with source-changed banner | uo5n |
| E6.I10 | Context panel toggle | E5.I8 |
| E6.I11 | Chat history rendering | E2.I2 |
| E6.I12 | Model swap UI in chat header | E4.I2 |

### 8.7 Timeline

| Issue | Description | Depends on |
|---|---|---|
| E6.I13 | Wiki-native timeline (chats + notes + AI outputs mixed) | E2.I2 |
| E6.I14 (done) | Settings screen ✓ | shipped |
| E6.I15 | Persona-scoped filtering | Personas |

### 8.8 Import/export

| Issue | Description | Depends on |
|---|---|---|
| Import.I1 | PDF text extraction (PdfBox-Android) | none |
| Import.I2 | Image → Gemma 4 vision path | E4.I2 (multimodal support) |
| Import.I3 | Share target (`ACTION_SEND` receivers) | none |
| Export.I1 | DOCX minimal writer | none |
| Export.I2 | DOCX template loader | Export.I1 |
| Export.I3 | MD/PDF/DOCX export orchestration | E2.I6 |

### 8.9 Personas, onboarding, model management

| Issue | Description | Depends on |
|---|---|---|
| P.I1 | Persona editor | E2 |
| P.I2 | First-run flow | none |
| P.I3 | Three-way model picker | E4.I2 |
| P.I4 | Model download UX (SAF picker + verify + attestation) | E4.I2 |
| P.I5 | Assistant integration (locked-down `VoiceInteractionService`) | none (small) |

### 8.10 Distribution

| Issue | Description | Depends on |
|---|---|---|
| `skein-6pf` | Release signing setup | needs user keystore |
| Dist.I1 | F-Droid metadata prep + MR | signing |
| Dist.I2 | Obtainium manifest publish | signing |
| Dist.I3 | Accrescent application | signing |
| `skein-mkq` | CHANGELOG.md + release-notes template | none |

---

## 9. Track B — hardware/M0 queue

Serial. Only the dedicated hardware-runner agent per `docs/DEVICE_RUNNER.md` (in flight as skein-79od) executes these.

1. **skein-79od (in flight)** — M0 harness adaptation + `docs/DEVICE_RUNNER.md`
2. Coordinator (human or hardware-runner) runs `./tools/m0-benchmark/run.sh --smoke` — one CPU cell + one Vulkan cell on Q3_K_M smoke artifact. Verifies pipeline end-to-end.
3. **skein-bxk** — acquire Q4_K_M artifact (Qwen 2.5 3B abliterated + Gemma 4 E4B), record SHA, license, source URL
4. **skein-9cg** — formal M0 matrix: CPU vs Vulkan × context 4K/8K/16K × 3 repeats × Q4 artifact
5. Optional context extension: 24K/32K if 16K passes
6. Sustained thermal run (long generation, time-to-throttle measurement)
7. Embedder path measurement (nomic GGUF vs Qwen3 ONNX RT — decides E5.I3)
8. **skein-5hr** — write `docs/MEASUREMENTS.md` from the formal cell JSONs
9. **skein-k3b2** — instrumented AndroidTest wrapping `libskein_sqlite.so`
10. Production-path revalidation (once E4.I4 lands): re-run benchmarks THROUGH the isolated `:inference-service` + JNI + AIDL path, compare to Track B CLI baseline

---

## 10. Track C — docs + polish (opportunistic)

| Issue | Description | Tier |
|---|---|---|
| `skein-mkq` | CHANGELOG.md + release-notes template | Haiku |
| THREAT_MODEL.md (M3 deliverable) | Full threat model doc per POST_REVIEW_RESOLUTIONS + spec §9 | Sonnet |
| In-app help / first-run tutorial | Once onboarding + editor land | Haiku |
| Community reply templates | Issue triage responses | Haiku |
| F-Droid metadata content | Screenshots, descriptions | Haiku (with human review) |
| Store listing copy | Accrescent, Obtainium, IzzyOnDroid | Haiku (with human review) |

---

## 11. Sample loop script (pseudocode for /loop)

```
GOAL:
Drive Skein v1.0 to the §1.2 Definition of Done by dispatching worktree-isolated
agents from bd ready until either:
  - all §1.2 items verified on a signed release commit, or
  - blocked on human decision (§3, or a P0/P1 issue labeled needs-human-review), or
  - blocked on hardware (§9 gate before formal M0 decision), or
  - CI red on main after 2 consecutive failures, or
  - cost ceiling (2M cumulative agent tokens without human check-in).

ITERATION (repeat until termination):
  1. State:
     - git fetch; verify working tree clean; record HEAD
     - gh run list --branch main --limit 1: if red, DIAGNOSE + FIX (no dispatch)
     - bd status; bd ready --limit 30

  2. Track selection:
     - Track A queue (§8): pick up to 4 issues by priority + blocks-others +
       critical-path; verify scopes disjoint
     - Track B queue (§9): 0 or 1 hardware-runner issue; NEVER more
     - Track C queue (§10): pick up to 1 Haiku doc issue if capacity remains
     - Total concurrent dispatch: max 5

  3. Claim: bd update <each> --status in_progress

  4. Dispatch (single message, multiple Agent calls):
     - Every call: subagent_type appropriate, model appropriate (§4.7),
       isolation: "worktree"
     - Prompt template §6.3 for every one

  5. Wait for hand-back reports

  6. For each returned agent:
     a. Verify: gh run list --branch <topic> (if any CI ran)
     b. git fetch origin
     c. if git merge --ff-only origin/<topic>:
          then merge; else merge --no-ff with substantive message
     d. git push origin main
     e. git push origin --delete <topic>
     f. Verify bd show <id> is CLOSED (if not, close on their behalf)
     g. Handle new issues filed by the agent (dedupe, prioritize)

  7. Post-wave: gh run list --branch main --limit 1
     - If red: STOP; diagnose; do not next-wave
     - If green: continue

  8. Every N iterations (recommend N=5):
     - Save summary to bd remember
     - Push a status update commit if docs changed
     - Consider whether termination conditions apply

  9. Cost check: if cumulative subagent tokens > 2M, STOP; report to human.

EXIT (any of):
  - §1.2 Definition of Done verified: SUCCESS. Report.
  - Non-negotiable violation attempted or CI red twice: STOP. Report.
  - Ready queue empty AND no in-flight AND no §1.2 items remaining: SUCCESS.
  - Ready queue empty AND items remain in §1.2: BLOCKED. Report what needs human.
```

---

## 12. Fresh-session bootstrap

If you (a fresh Claude session) picked this up with no prior context:

1. **Read this document top to bottom.**
2. **Confirm you are the coordinator, not a hardware runner or a code agent.** The coordinator dispatches; it does not itself write feature code.
3. **Run the state check:**
   ```bash
   cd /Users/andrewherrera/skein
   git status              # should be clean
   git log --oneline -5    # know where main is
   bd prime                # loads bd workflow context
   bd status               # count open/blocked/ready
   bd ready --limit 10     # see immediate work
   gh run list --branch main --limit 3   # CI state
   ```
4. **If CI is red on main**: diagnose before anything else. Read the failing job log. If the fix is small and obvious (like the ktlint or lint bugs this session), fix inline in a small commit. If not, dispatch a small Haiku or Sonnet to fix it.
5. **If CI is green and there's ready work**: pick from §8 (Track A) in priority order, verify scope disjoint, dispatch a wave following §11.
6. **Never touch the Fold.** That's Track B, and it has a dedicated runner.
7. **If in doubt about a decision that would affect §3 non-negotiables**: STOP and ask the human.

---

## 13. Quick reference — where things live

| What | Where |
|---|---|
| This handoff | `docs/Handoffs/skein-v1-autonomous-completion.md` |
| Hardware lab handoff | `docs/Handoffs/skein-fold-m0-hardware-handoff.md` |
| Approved spec | `docs/superpowers/specs/2026-09-19-skein-design.md` |
| Implementation plan | `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` |
| Post-review resolutions | `docs/design/POST_REVIEW_RESOLUTIONS.md` |
| Roadmap (v1/v2/v3) | `docs/ROADMAP.md` |
| Artifact Engine (v2) | `docs/design/ARTIFACT_ENGINE.md` |
| Vault tool primitives | `docs/design/VAULT_TOOL_PRIMITIVES.md` |
| Amalgamation policy | `docs/design/AMALGAMATION_POLICY.md` |
| Skill guardrails | `docs/design/SKILL_GUARDRAILS.md` |
| bd taxonomy | `docs/BD_TAXONOMY.md` |
| Testing docs | `docs/TESTING.md` |
| Verification (SHA metadata) | `docs/VERIFICATION.md` |
| Backup exclusions | `docs/BACKUP_EXCLUSIONS.md` |
| Privacy | `docs/PRIVACY.md` |
| M0 baselines | `tools/m0-benchmark/output/baseline/` |
| Native SQLite | `native/sqlite/` |
| Build guards | `build-logic/guards/` |
| Interface stubs | `core/agent/src/main/kotlin/app/skein/core/agent/tools/VaultTools.kt` |
| Compose shell | `feature/shell/` |

---

## 14. What "done" looks like

At the end of the autonomous loop, the following are all true and no other significant work is pending:

- [ ] `./gradlew :app:assembleFossRelease` produces a signed APK
- [ ] `unzip -l app-foss-release.apk | grep 'lib/arm64-v8a/'` shows `libskein_sqlite.so`, `libonnxruntime.so`, `libllama.so` (or equivalents)
- [ ] `aapt2 dump badging app-foss-release.apk` shows zero INTERNET-class permissions
- [ ] `aapt2 dump xmltree app-foss-release.apk AndroidManifest.xml` shows both services `isolatedProcess=true`
- [ ] Reproducible-build CI job green: two clean builds produce byte-identical APK SHA-256
- [ ] `./gradlew check` green
- [ ] `MEASUREMENTS.md` contains formal Q4_K_M numbers with device provenance
- [ ] `THREAT_MODEL.md` written and covers all classes from spec §9
- [ ] Manual test on a Fold with fresh install:
  1. Install APK via `adb install`
  2. First-run: pick model, wait for download, verify SHA, unlock with biometric
  3. Write a note in Markdown; close app; reopen; note persists
  4. Import a PDF from Files app via share → text extracts to a note
  5. Ask the AI a question about the imported content
  6. Response streams with inline citation `[1]`; tap citation → source note opens
  7. Highlight text in a note → inline AI menu → "Rewrite" → approve diff
  8. Export the modified note as PDF via share sheet → PDF arrives in Files app
- [ ] End-to-end test above completes without network activity (verify via GrapheneOS per-app network toggle showing Skein has never requested network)
- [ ] GitHub release tag pushed; signed APK attached; SHA-256 published in release notes
- [ ] F-Droid MR opened; Accrescent application submitted; Obtainium manifest posted

If all items check, **v1.0 ships**. The loop stops.

---

## 15. Final notes to the loop

- Every decision you make is auditable through `bd` and `git`. Behave accordingly.
- If you're unsure whether an action violates §3, IT DOES. Escalate.
- Agents will surprise you with insights, deviations, and bugs found. Log every one via `bd note` or `bd create`. Do not discard them.
- Q3_K_M smoke numbers are not formal M0 conclusions. They tell you Vulkan works; they do not tell you which backend or context length to ship.
- The seven-step loop is the north star. Every dispatch should ultimately support one of those seven cells. If an issue doesn't clearly serve one of them, ask whether it's v1 or v2.
- The user (Andrew) is the human of record. When human input is needed, be specific about what decision is blocked and what the trade-offs are.

Good luck. Ship it well.
