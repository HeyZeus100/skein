# Skein v1 — Autonomous Completion Handoff

**Status:** Working document, updated 2026-09-22 · `main` at commit `ad98b7b` (local; `origin/main` is `c94e1c4` — the skein-nxk merge is not pushed yet, see §2.0)
**Purpose:** Everything a coordinator (human or agent) needs to spin up an autonomous multi-agent loop that drives Skein v1 to shippable release. Written so a fresh Claude session with no prior context can pick this up and execute.
**Authority:** Advisory + operational. Non-negotiables in §3 override anything below.

---

## 0. TL;DR for a fresh session

You are the coordinator for the Skein Android app — a private, on-device, GrapheneOS-first personal knowledge system with an on-device LLM. **The substrate is largely built; the user loop is not.** The vault, editor, ingest, retrieval, prompt assembly, citation records, export engine, AIDL contract, JNI layer and the isolated inference service all exist on `main` with tests. What does not exist is the path a user actually walks: no chat surface, no app-side engine client binding the service, no model import UI, no inline-AI/approve step. Read the seven-step table in §2.1 before believing any other status statement in this repository.

To continue, in this order:

1. Read this file.
2. Read `docs/ARCHITECTURE.md` (module map, process topology, startup sequence — re-verified at `ad98b7b`).
3. Run `bd prime`, then `bd ready`, to see the queue; §8 says which of it to take.
4. Read `docs/superpowers/specs/2026-09-19-skein-design.md` (approved spec) and `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` (implementation plan) when an issue points you at a section — both are authoritative, and several plan sections carry dated coordinator amendments that win over the original text.
5. Read `docs/design/POST_REVIEW_RESOLUTIONS.md` for the four hardest subsystems (citations, model verification, Binder, export).
6. Never touch the Fold except through the dedicated hardware-runner agent (§7.4 / §4.10).
7. Use worktree-isolated dispatch (§6.2) and the merge protocol (§6.4).

Do **not** treat §2 of any earlier revision of this handoff, or any "planned"/"stub"/"not yet landed" label dated before `ad98b7b`, as current — the 2026-09-20 inventory said the vault schema, key management, JNI, AIDL and inference service were "completely absent"; all of them have since landed.

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

## 2. Current State (2026-09-22, main @ ad98b7b)

### 2.0 Read this first — repository state the tree cannot tell you

- `main` is **one merge ahead of `origin/main`**: `ad98b7b` (skein-nxk, the inference service) is verified locally (full native build, both isolation guards, `jni-symbols.sh`, `no-content-logging.sh`, manifest audit — all green) but **not pushed**; `origin/main` is `c94e1c4`. The main checkout also carries one **uncommitted** file the nxk merge needs to compile its instrumented tests: `core/inference/src/androidTest/kotlin/app/skein/core/inference/models/ImmutableModelStoreInstrumentedTest.kt` (the `LoadPhaseHook.afterPreMmapVerify` override re-typed from `ManifestBinding` to `VerifyBinding`). Commit that fix, check CI, then push — in that order. A stray untracked `Untitled/` directory (an empty `.git`, not ours) can be deleted.
- The API **weekly limit** was hit on 2026-09-22 (resets 2026-09-27 18:00 America/Los_Angeles). Every subagent in flight was terminated; three left uncommitted work in their worktrees (§2.3). Until the reset, work is coordinator-only.
- CI on `main` was red on **every push from 2026-09-20 until 2026-09-21 ~20:00 PT** and nobody noticed for a day because local verification was green: a missing Linux `aapt2` checksum in `gradle/verification-metadata.xml` (fixed at `f0bba85`; bd memory `verification-metadata-linux-classifier`), then one Compose test's 5 s ceiling on the 2-core runner (fixed at `9812787`). **At `c94e1c4` both `CI` and the two-runner `Reproducible build check` are green** (runs 35682300562 / 35682300542 — 1 of the 3 consecutive commits skein-egyu needs). The `Emulator instrumented tests` lane is red for a workflow reason (`sdkmanager` not on PATH → no adb → `dexBuilderDevDebugAndroidTest`), filed as a bug; exclude it with `tools/loop/ci-status.sh --require-green --ignore Emulator` until fixed, and run that script before dispatching anything (§4.9).
- `bd stats`: 181 closed / 337 total, 94 ready. 317 commits on `main`.
- The M0.5 gate review (`skein-pe3`) is complete — 18 findings, no P0, five P1 (three fixed in-bead by skein-nxk, one by skein-va7y, one re-scoped as skein-w2vj) — and **awaits the owner's sign-off**; the bead is `in_progress` with `needs-human-review`.

### 2.1 The seven-step loop — what actually runs

| Step | State | Evidence on `main` (confirm before trusting) |
|---|---|---|
| Unlock | **Shipped** | `core/vault/.../session/UnlockManager.kt`, `app/.../vault/{VaultBootstrap,DeviceVaultOpener}.kt`, `feature/shell/.../auth/{VaultSetupScreen,BiometricUnlockScreen,VaultResetScreen}.kt`; StrongBox-backed `VaultKeyProviderImpl`; passphrase export/import (skein-v9g); reset (skein-v3wb). `needs-human-review` flags remain on v3wb (affordance placement) and v9g (PBKDF2-over-Argon2id, UX copy). |
| Write | **Shipped** | `feature/editor` (21 files): `SkeinEditor` live preview, autosave, `[[` wikilink autocomplete, frontmatter chip, `NoteTab`, `BacklinksDrawer`; the timeline and graph screens are real too. |
| Import | **Partial** | Engine landed: `ImportServiceImpl` handles text/Markdown/code/PDF (skein-qdo) and never overwrites by frontmatter id (skein-ddpt). **No UI calls it yet** — no import screen or share-target receiver is wired; image/vision ingest does not exist; `feature/onboarding` is still `Placeholder.kt`. |
| Index | **Partial** | `IngestWorker`/`IngestScheduler` (skein-7v3) populate FTS and wikilink/tag edges during unlocked sessions; migration 008 stamps `chunks.revision_hash` + UTF-8 byte offsets and persists `ingest_attempts` (skein-zx15); `documentRevisions_gc` runs once per unlock (skein-a2yr). **Vectors stay "pending"** — the real `EmbedderService` is not wired (skein-hwsa, blocked on skein-079 / skein-lbw). |
| Ask | **Missing** | `feature/chat` is `Placeholder.kt`. `inference-service/.../InferenceService.kt` **is implemented** (skein-nxk: `IsolatedSessionGate` on every entry point, pinned-fd verification, `loadModelFromFd`, segmented tokenization that never parses specials in content, streaming with backpressure, cancel, OOM handling; 157 JVM tests) and **is not bound from `:app`** — no `LlamaCppEngine`/`ModelManager` client exists (skein-1uw / skein-cyq). `RetrievalServiceImpl`, `PromptAssemblerImpl` and `ContextBudget` exist and are exercised only by tests. |
| Cite | **Partial** | Schema and types are complete: migration 003 `document_revisions` + `citation-record-v1` (skein-uo5n), read-side `Chunk`/`Retrieved.locator` with the CRLF remap (skein-g32i), `CitationParser` + `CitationRecords` (skein-n5q). **No citation UI**, and nothing produces chat turns yet. |
| Approve | **Missing** | No inline-AI selection menu, slash commands or approve-diff in the editor; `:core:agent`'s `AuthorizationToken` primitives are interface-only. |
| Export | **Partial** | `ExportServiceImpl` (Markdown, zip, DOCX, PDF), "Share as text" / "Save as…" UI (skein-fay), `VaultDocumentsProvider`, and the staged-plaintext lifetime — migration 005 `export_stages`, `StagedPlaintextSweeper`, `BootReceiver`, on-lock sweep (skein-0m1z). PDF export does not yet record a stage row (skein-efwt), so the 10-minute bound is not live, and the outbound share-sheet loop is not closed end to end. |

Cross-cutting substrate that is done and must not be re-dispatched: `libskein_sqlite.so` (SQLCipher + sqlite-vec + FTS5, reproducible); `libskein_llama.so` (llama.cpp v0.4.1 + Vulkan, reproducible after skein-ylux's pinned fix for an NDK `glslc` miscompile; 24-symbol JNI surface, skein-3aw); `:core:ipc` AIDL v2 + `TransportRules` (skein-mfw / skein-nxk); `:core:verify` (`ModelVerifier`, `PinnedModelFile`, shared by `:app` and both isolated processes); `ModelManifest` v2 schema/parser/CI validator (skein-3v9); `ImmutableModelStore`; `PromptGuard`; `ThermalGovernor`; tokenizers, chunker and PPR ranking; the two-runner reproducible-build workflow and `tools/rb/*` (skein-ddp); `contractReport`; the consolidated `:testing` fakes and builders.

### 2.2 Already merged — delete the worktree and the `claude/agent-*` branch; do not cherry-pick

Every commit below is an ancestor of `ad98b7b` (`git merge-base --is-ancestor <sha> HEAD`). Worktrees live under `/private/tmp/claude-501/-Users-andrewherrera/2a94e1c2-1397-433c-b622-a3763240b60e/scratchpad/worktrees/`; their gitdirs under `/Users/andrewherrera/skein/.git/worktrees/`. Remove with `git worktree remove --force <path>` from the main checkout (an `rm -rf` alone strands the gitdir), then delete the topic branch locally and on origin if it still exists.

| Bead | Topic commit | Merge on `main` | Worktree |
|---|---|---|---|
| skein-a2yr revision GC + chat-snapshot bound | `504bef6` | `6042328` | `agent-a19ed0eaf0351252f` |
| skein-ylux `libskein_llama.so` determinism (NDK `glslc` miscompile; pinned shader patch) | `56ce51c` | `05bc17b` | `agent-ae4ceb02af18cd84b` |
| skein-ddpt import must not overwrite by UUID | `590be04` | `3592e8a` | `agent-a2ef4dca71c364020` |
| skein-va7y `lock()` always zeroizes | `6ececba` | `3d7f68f` | `agent-a362e017d435bf526` |
| skein-zh7o SYSTEM history rendered as data | `6b7b8dd` | `544b99b` | `agent-ad11841ef6af3a1b4` |
| skein-fsn notifications | `83b4092` (Sonnet takeover; its history also contains the Haiku commits `8fa1e49`/`e7f992c` — production code reviewed unchanged; `e7f992c` claimed "AC tests, verification PASS" while *deleting* the one trivial test `8fa1e49` had added, so the merged tree had no tests until the takeover) | `c94e1c4` | `agent-a87bdf96b68a09598` and `agent-a10c5d97b52fe7c28` |
| skein-ddp reproducible builds | `2a49b7b` | `0be4b5c` | `agent-a5bbd8b65c4e8c25a` |
| skein-nxk `:inference` service + `:core:verify` | `c95f726` (+ `658dda1`) | `ad98b7b` — **not yet pushed** | `agent-a7ea8995bc2f98dcf` |

Every other directory under `worktrees/` belongs to an earlier, already-merged bead; apply the same ancestor test before deleting it.

### 2.3 Uncommitted, not on `main` — replay onto current `main`; do not merge the stale branch

| Bead | Worktree | Base | What is there | Disposition |
|---|---|---|---|---|
| skein-p8rn Migrator applied-migrations ledger | `agent-adb5c4627bac86c44` | `220dd9c` | Modified `core/vault/.../db/migrations/Migrator.kt`, `MigratorTest.kt`, `MigratorInstrumentedTest.kt`, `005_export_stages.sql` (header), `docs/VAULT_FORMAT.md`. **No commit.** No applied-migrations ledger exists on `main`; a DB already at `user_version` 8 silently never receives a gap-filling reserved migration (005 today; 002/004/006 later). | Read the diff; replay onto current `main` (which now has 008 and skein-a2yr's repository changes); verify per the bead's ACs; or discard and re-dispatch after the limit resets. |
| skein-3yal `onError` / diagnostic sanitization | `agent-afaa68822117effd9` | `05bc17b` | Modified `core/ipc/.../ErrorCodes.kt` + `ErrorMappingTest.kt`, `core/model/.../Inference.kt`, `app/.../system/AndroidSkeinLogSink.kt`; new `core/model/.../DiagnosticSanitizationTest.kt`, `app/.../system/AndroidSkeinLogSinkTest.kt`. **No commit.** `sanitizeDiagnostic` is not on `main`. | This is the fix that keeps a compromised isolated process from injecting arbitrary text into logcat/UI through `onError(message)` (review finding, P2). Replay onto current `main` — skein-nxk has since added `ErrorCodes.asServiceFailure/codeOf` in the same file — verify, commit. |
| skein-mzm5 reentrant transactions in the in-memory fake | `agent-a3fe26b001f8786e1` | `6042328` | Modified `testing/.../InMemoryVaultRepository.kt` and `VaultRepositoryContractTest.kt` — the agent was **mid-refactor** (replacing `writeLock.withLock` sites with a `writeTx`) when the limit hit; almost certainly does not compile. | Read the diff before deciding; the new contract-test cases may be worth keeping, the half-done fake edit probably not. Discarding and re-dispatching is acceptable. |

`tools/loop/worktree-gc.sh` (dry run at `417cc9f`) also reports **10 more dirty worktrees and 2 unmerged branches from earlier sessions** — content untriaged: the unmerged `agent-ad4910b68c56be89e` (`14d7fd1`) carries a candidate fix for the open skein-ebcx on top of superseded E1.I3 CI fixups (cherry-pick the one commit; noted on the bead), and `agent-a70cb19763b6d9959` (`8d25695`, a skein-qsux WIP) is superseded. The triage bead lists every one; the script never removes a dirty or unmerged worktree.

### 2.4 Human decisions outstanding (surface these; never decide them autonomously)

- **skein-pe3** — M0.5 gate sign-off (§2.0).
- **skein-gtym** (P0) — the Vulkan `libskein_llama.so` is 24.1 MiB vs the ≤ 12 MB budget (1,115 embedded SPIR-V variants; upstream has no knob): ship CPU-only arm64 (4.3 MiB, loses the measured Mali prompt-processing win), trim the shader set with a carried patch, or raise the budget — a MEASUREMENTS.md (skein-5hr) + APK-budget (skein-jn3) decision.
- **skein-zond** (P0) — real default-model manifests need the model downloads (skein-bxk, human) and a policy on `LicenseRef-Qwen-Research` in the FOSS audit.
- `needs-human-review` on skein-v3wb and skein-v9g (§2.1, Unlock).
- **skein-72vx** — whether ingest may use a foreground service / expedited work.
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

### 5.1 Module DAG (as of `ad98b7b`; `settings.gradle.kts` lists exactly these 23 — re-read it and `docs/ARCHITECTURE.md` §2.1 before trusting a row)

```
:app                     (composition root: VaultServices, MainActivity, ingest wiring, notifications, export staging, BootReceiver)
├── :feature:shell       (theme, nav, adaptive layout, tabs, unlock/setup/reset screens, SecureTextField)
├── :feature:editor      (SkeinEditor live preview, autosave, wikilink autocomplete, note tabs, backlinks)
├── :feature:timeline    (timeline screen + rail)
├── :feature:graph       (local graph screen)
├── :feature:settings    (Settings incl. Security lock policy, Indexing hint, About)
├── :feature:chat        (Placeholder.kt — E6.I8 skein-6as, blocked on skein-1uw)
├── :feature:personas    (Placeholder.kt)
├── :feature:onboarding  (Placeholder.kt)
├── :feature:models      (Placeholder.kt)
├── :inference-service   (isolated :inference process; InferenceService IMPLEMENTED — skein-nxk; libskein_llama.so + 24-symbol JNI — skein-ca2/3aw/ylux)
├── :embedder-service    (isolated :embedder process; ONNX Runtime session smoke only — skein-lbw pending)
├── :core:vault          (SQLCipher driver, migrations 001/003/005/007/008, VaultRepositoryImpl, IndexStoreImpl, keys, UnlockManager, attachments, import/export impls, DocumentsProvider)
├── :core:rag            (Chunker, tokenizers, recall stages, PPR, RetrievalServiceImpl, PromptAssemblerImpl, CitationParser, IngestPipeline)
├── :core:inference      (ImmutableModelStore, ModelManifest v2, WireBindings, ContextBudget/TokenCounter, ThermalGovernor)
├── :core:verify         (pure JVM; ModelVerifier, PinnedModelFile — shared with both isolated processes)
├── :core:ipc            (AIDL v2 + Parcelables + ErrorCodes.toException + TransportRules)
├── :core:security       (PromptGuard, CitationFilter)
├── :core:export         (export helpers, PDF staging, ExportStage)
├── :core:markdown       (AST + renderer — pure JVM)
├── :core:model          (locked contracts, Blake3, Revisions, CitationRecordJson, SkeinLog — pure JVM)
└── :core:agent          (vault tool primitives — interfaces only)

:testing (pure JVM; fakes, builders, contract suites, RawTextFieldTest)
```

`feature/build/` and `core/build/` on disk are stray build-output directories, not modules.

Guards enforce (`build-logic/guards/src/main/kotlin/app/skein/gradle/IsolationGuardPlugin.kt`):
- `PURE_JVM_MODULES` = `:core:model`, `:core:markdown`, `:core:agent`, `:core:verify`, `:testing`
- Service modules may declare project deps only on `:core:ipc`, `:core:model`, `:core:verify` (plus `com.microsoft.onnxruntime` for `:embedder-service`) — nothing UI-side
- `:app` is the single wiring leaf

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

Migrations shipped on `main`: `001_initial`, `003_document_revisions`, `005_export_stages`, `007_drop_attachment_master_key`, `008_ingest_attempts` (`core/vault/src/main/resources/migrations/INDEX.txt`; `PRAGMA user_version` reaches 8; `Migrator` applies in numeric order). 002, 004 and 006 stay reserved (`skein-voys`, `docs/VAULT_FORMAT.md` §7). `Migrator` applies only versions above the current `user_version`, so a reserved number landing after a higher one is skipped on an already-migrated database — the applied-migrations ledger that fixes this (skein-p8rn) is uncommitted in its worktree (§2.3).

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

## Ponytail ladder (MANDATORY before writing any code — https://github.com/dietrichgebert/ponytail, MIT)
Read the problem completely first — be lazy about the solution, never about
reading. Then, for every piece of code you are about to write, stop at the
FIRST rung that answers it:
1. Does this need to exist?   → no: skip it (YAGNI)
2. Already in this codebase?  → reuse it, don't rewrite
3. Stdlib does it?            → use it
4. Native platform feature?   → use it
5. Installed dependency?      → use it
6. One line?                  → one line
7. Only then: the minimum that works
Never skip validation, error handling, security or the tests the bead's
acceptance criteria name — the ladder trims code, not guardrails. Name the
rung you stopped at for any non-obvious choice in your hand-back.
(Coordinator sessions also run the `ponytail` Claude Code plugin — installed
2026-09-22; subagents get the ladder from this block regardless.)

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

## 8. Next work (ordered; replaces the 2026-09-20 queue)

The old "immediate wave" (vendor the sqlite JNI driver, M0 harness adaptation) is done and merged. Do **not** open a broad wave. The next chain is one product path, in this order:

1. **Land the local state.** Commit the uncommitted androidTest fix in the main checkout (§2.0), confirm `gh run list --branch main --limit 5` is not red for a reason you have not diagnosed, push `main` (`ad98b7b`), and let one CI run finish without a competing push.
2. **Salvage or discard §2.3.** Replay skein-3yal first (it is a security fix), then skein-p8rn, onto current `main` after reading their diffs; read skein-mzm5's diff and discard the half-refactor unless its contract cases stand on their own.
3. **Delete the merged worktrees in §2.2** (and any other whose commit is an ancestor of `HEAD`) with `git worktree remove --force`, never `rm -rf` alone.
4. **One product chain, serially — nothing in parallel that touches the same modules:**
   1. **skein-1uw** — app-side `LlamaCppEngine` over the bound `IInferenceService` (Opus). Read the bead's NOTES first: four beads left instructions there (`TokenCounter` over `tokenCount` — skein-4c7; `ErrorCodes.toException` with `HashMismatch(expected, actual)` built client-side — skein-udbm; `ModelNotifier` calls — skein-fsn; `sessionEpoch` threading + `DeathRecipient` + `SessionLocked` — plan E4.I4 as amended). Nothing in `:app` binds `:inference` today.
   2. **Hash-verified model import** — skein-cyq (`ModelManager`/`ModelRegistry`, migration 004 `models.post_mmap_blake3`; wait for the p8rn ledger or accept the documented gap-fill caveat) over `ImmutableModelStore` + `WireBindings.toWire`. The tiny GGUF for the emulator lane comes from skein-80p (E4.I2), not from a real model download (skein-bxk is a human task).
   3. **A streaming answer with one citation on the chat surface** — `feature/chat` (E6.I8, skein-6as) over `RetrievalServiceImpl` → `ContextBudget` → `PromptAssemblerImpl` → engine → `CitationParser` → `CitationRecords` → `VaultRepository.appendMessage`. Chat-template application and sampling defaults are skein-5oi. `ChatMessageParcel.contentFd` (skein-nxk J5) is how a long prompt spills.
5. **Then, and only then:** a Fold smoke that exercises "ask" (successor to skein-94fh) and the formal M0 matrix (Track B, §9). Formal M0 and the Fold smoke wait until that path exists.

Everything else in `bd ready` — the review follow-ups (skein-8c9r, 6j93, x9xn, 556t, i1y1, 5g42, qvxb …), the embedder service (skein-lbw / skein-079 / skein-hwsa), inline AI (E7), onboarding, personas, the models UI, distribution — is deferred behind that chain unless it unblocks it.

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
| Architecture (module map, startup sequence, guards) | `docs/ARCHITECTURE.md` |
| Native llama.cpp build, JNI, reproducibility | `native/llama/` (README §4 hashes, §8 JNI; `patches/` for the pinned shader fix) |
| Reproducible-build tooling + toolchain manifest | `tools/rb/`, `reproducible-builds.yml`, `.github/workflows/reproducible-build.yml` |
| CI helper scripts (manifest audit, JNI symbols, no-content logging, submodules) | `tools/ci/` |
| Agent worktrees (scratchpad) | `/private/tmp/claude-501/-Users-andrewherrera/2a94e1c2-1397-433c-b622-a3763240b60e/scratchpad/worktrees/` (gitdirs under `.git/worktrees/`) |

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
