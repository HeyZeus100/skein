# Inference lifecycle and demo readiness — 2026-09-29

## Integration source and ownership

Baseline: `9fa9f6fdda8a6f624d0e9b248e7a17e92f01890f`.
Verified code SHA: `6a6fd5d8493aa4835f97c762af14345eb4ac1250`.
Full four-module gate source: `64bdb15cc7d1f67fa017884853ad8b37dc3881fa`.
Topic: `codex/inference-lifecycle-20260929`.
Worktree: `/Users/andrewherrera/skein-worktrees/inference-lifecycle-20260929`.
The coordination record contains approved module/foreground-manifest/import API ownership,
lock teardown contract and narrow AskPath fixture delegation. MainActivity/NavShell remain
coordinator-owned. No shared IPC/model/build/session/controller contract was edited.
No main push, direct Beads command, PR, CI dispatch or emulator/device operation was performed here.
Earlier commits inherited automatic Beads pre-commit/prepare-commit-msg hooks; this was
reported in inference.json. Subsequent commits/push bypass only those inherited hooks via
a per-command override; shared hook files/configuration remain unchanged.

## Changes and integration hooks

Inspection and load share cancellable verification/native-load operation ownership. JNI
progress uses the operation's BooleanSupplier; native handles and duplicated FILE streams
are RAII-owned. The caller descriptor stays caller-owned. Native cancellation is cooperative
at progress/verification checkpoints; the terminal lock kills the isolated process on its
Binder thread without waiting for a blocked native worker. Publication is serialized with
session revocation, and stale epoch pushes cannot revoke a newer authorized session.

Client blocking transactions own their request descriptors until the actual Binder call
returns, while revoked/cancelled callers stop waiting. Managed app state clears synchronously
without a two-way unload on the lock deadline. Late callbacks/bindings cannot publish READY
or damage a newer binding; same-epoch cancellation does not send a destructive terminal lock.
Interrupted worker waiters retain ownership until completion and restore the interrupted flag.
Cleanup invoked from the native worker executes directly on its owning thread, avoiding the
OOM unload self-deadlock (`skein-gg11.35`); a real HandlerThread regression proves secure cleanup and reload.

Import copy belongs to an application singleton and a generic foreground dataSync service.
Tokenized admission protects replacement service starts from old completion/destruction;
copy can survive Activity disposal/lock while registration capability detaches. Sealed copies
are adopted against the next authorized registry, including copies known to the shared store.
Matching adoption clears only the saved result captured before that scan. A shared lifecycle
gate permanently revokes each session's attach/rescue admission at HIGH/close, so an old
parked unlock cannot reattach its manager or cancel a replacement registration. The exact shell
patch is `skein-import-lifetime-shell.patch`; the coordinator has adapted it to its newer UI.
See `skein-import-lifetime-20260929.md` for API and lifecycle acceptance cases.

## Demo boundary and evidence

Freeze target Sep 29 16:00 PDT; demo target 17:00 PDT. Current Qwen Q3 remains the candidate;
no model/backend/CPU-floor/Vulkan/performance experiment is introduced. The public host copy
has SHA-256 `2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12` and
1,590,475,744 bytes. Original metadata report is retained unchanged and independently reproduced.
The pinned host vocabulary probe passes four native rendering/tokenization cases and records
native EOG for im_end151645, endoftext151643 and `</s>`128247 (the last is upstream normalization).
Template SHA is `cd8e9439f0570856fd70470bf8889ebd8b5d1107207f67a5efb46e342330527f`.
Raw JSON/template/provenance live in `docs/eval/runs/2026-09-29-inference-model-identities/`.
This is host classification evidence, not generated stop/answer-quality or selected-app-artifact
proof. Tiny exact-token/control isolation regression passes and remains separate.

## Coordinator runtime requests and open gates

On the exact combined source, inspect actual ordinary XML for:
- LlamaNativeTest descriptor/path progress cancellation ownership cases.
- InferenceServiceInstrumentedTest lockingMidGenerationCancelsThenLockedTerminatesTheIsolatedProcess,
  aLockingServiceRefusesTheNextGenerateBeforeProcessTeardown, staleLockedPushDoesNotKillNewlyAuthorizedService.
- LlamaCppEngineInstrumentedTest lockAfterInspectionTerminatesTheProcessAndOnlyANewUnlockCanRebind.

Exercise import across Activity recreation, lock during copy/inspection, notification denial,
process death before/after sealing, and new-unlock adoption. Observe real isolated-process death,
late READY rejection and clean load/generation after unlock. JVM fakes do not close these gates.

The sole physical demo runner receives a coordinator-released candidate only. Verify selected
app-private artifact identity, inspect actual opt-in native parity/EOG JSON, produce short benign
answers with natural terminal reasons, Stop and resend, then lock/unlock and answer again.
Preserve unsuccessful attempts. No literal stop fallback hides a failure. Keep broader Qwen/Gemma
quality matrices outside this demo candidate; exact requests remain in the readiness handoff.

`.19`/`.20` runtime acceptance remains open until the above evidence arrives. `.28`/`.30` have
identity/classification progress only. `skein-bxk`, `skein-7s1`, `skein-9cg`, `skein-5hr`, and
`.26`/`.27` stay open with their prior unmet evidence/decisions. Formal M0 HOLD remains; only the
later authorized data-preserving demo update/checks are released to the designated demo session.
No retrieval policy, embeddings, gold labels, thresholds, owner files or recovery worktrees changed.

## Validation at the full-gate source

The final `final-full-gates.log` is BUILD SUCCESSFUL (881 tasks):
`:core:verify:check :core:verify:ktlintCheck :core:inference:check :core:inference:ktlintCheck
:inference-service:check :inference-service:ktlintCheck :app:check :app:ktlintCheck`
plus both flavors' app/service AndroidTest Kotlin compilation, with
`-Pskein.syntheticBenchmark=true --max-workers=2`. JDK17 and repository NDK27.3.13750724
were used. Every heavy check held its own atomic build lease and released only its token.

Actual XML was inspected and copied to `build/agent-logs/final-xml/`:

| Module | Executed | Failures/errors/skips |
|---|---:|---:|
| core/inference | 329 | 0/0/0 |
| core/verify | 16 | 0/0/0 |
| inference-service | 270 per Dev/Foss flavor | 0/0/0 |
| app | 243 per Dev/Foss flavor | 0/0/0 |
| Total | 1,371 | 0/0/0 |

The critical XML cases include same-epoch cancelled-bind retry; real-worker decode OOM
cleanup/reload; all three parked-unlock/cache import regressions; and the revised exact
terminal-lock/no-sync-unload AskPath test. These are JVM/Robolectric checks, not Android
runtime acceptance. Opt-in tests compile but were not executed here.

`native-abi-final.log` passes DevDebug external native build for arm64-v8a and x86_64
and ordinary app/service AndroidTest compilation. `native-guards-final.log` passes all
27 JNI symbols, 16KiB ELF LOAD alignment, all three pinned submodules, and the tiny
old31/new30 exact-token/control-isolation/concurrent-context regression. `host-eval.log`
contains 90 passing Python evaluation/qualification tests. The new host Qwen probe's
four cases pass; raw evidence is committed in the linked model-readiness bundle.

Logs, scripts, raw XML, failed-attempt snapshots, `final-validation.json` and
`validation-sha256.txt` remain in this worktree's `build/agent-logs/`. Original failures
were retained: shared-store adoption tests, obsolete sync-unload expectation, import
integration compile errors and test formatting. The initial combined native probe
command exited1 only because its following tiny check used a missing worktree-local
path; Qwen itself exited0. A verified copy of the existing tiny fixture then passed
without changes to the test. Do not interpret that first wrapper exit as a Qwen failure.

The final documentation commit follows this verified source and is the branch handoff
head. `inference.json` records its exact full SHA, push result and coordinator requests.

## Requested UI-result identity follow-up

The coordinator subsequently approved `dismissResult(expected: ModelImportState.Done? = null)`
to protect delayed UI timers from dismissing a newer result. Code commit
`6a6fd5d8493aa4835f97c762af14345eb4ac1250` changes only that helper and its focused
regression. The synchronized check uses reference identity, including when the newer
outcome equals the old outcome; the no-argument call remains compatible. The coordinator
must pass its captured `Done` object from delayed callbacks and owns the shell changes.

`dismissal-app-final.log` passes full `:app:check`, explicit `:app:ktlintCheck` and both
opt-in app AndroidTest Kotlin compilations (807 tasks, max-workers=2, owned atomic lease).
Actual XML in `build/agent-logs/dismissal-xml/` contains 244 tests per flavor, zero
failures/errors/skips, including the new stale identity case in both. Core/service/native
source is unchanged from the verified full gate; it was not needlessly rebuilt. The
current combined test inventory is 1,373 cases across these gates. Runtime acceptance
remains unchanged and pending.
