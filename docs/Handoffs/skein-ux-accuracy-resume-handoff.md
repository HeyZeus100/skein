# Skein UX and accuracy: paused execution handoff

**Resumed by the owner on 2026-09-28.** The pause below is historical. The owner
authorized resuming these assignments, required checks, and delivery of verified
commits. **Fold remains HOLD.** Read the current
[execution evidence](skein-ux-accuracy-execution.md) for resumed results; preserve
the original checkpoint artifacts and recovery trees described here.

**Owner paused work:** 2026-09-28, approximately 13:06 America/Los_Angeles.
**Implementation head:** `13b73377f8f6c4fc485d82726b33280fd510cbb4`.
Documentation/checkpoint commits follow that implementation head. The pause
checkpoint commit uses `[skip ci]` to avoid starting automatic workflows; resumed
implementation must run the pending final-head gates. Read the current
Git head before restarting. The owner required implementation, tests, CI dispatch
and device work to stop until their subsequent resume instruction above. All
three agents were interrupted;
the coordinator completed only this handoff and repository delivery afterward.

Start with this file, then the [execution evidence](skein-ux-accuracy-execution.md)
and [continuation design](skein-ux-accuracy-continuation-plan.md). The older
[UX handoff](skein-ux-overhaul-handoff.md) and
[v1 handoff](skein-v1-autonomous-completion.md) remain historical/design context.
`docs/ux/` contains the accepted product specs. Beads owns task status; this file
records the checkpoint, evidence and restart procedure rather than replacing it.

## Owner intent and operating constraints

The owner authorized carrying out the continuation plan, recovering three
interrupted Claude agents, and improving the imported local model's factuality.
They prefer autonomous, noninteractive execution and no repeated permission
questions. The explicit pause suspended that authorization until the owner resumed.

The target is better measured answers, evidence and uncertainty handling. Do not
promise Astra/ChatGPT capability parity or elimination of hallucinations. No
real Fold answer-quality improvement has yet been established. Keep the offline
design, no INTERNET permission, isolated inference, encryption and existing
lock deadlines. Never weaken isolation to enable Vulkan or improve benchmarks.

The requested Codex configuration was saved and successfully loaded by CLI
0.155.1 in `~/.codex/config.toml`:

```toml
approval_policy = "never"
default_permissions = ":danger-full-access"
```

Backup: `~/.codex/config.toml.before-permissions-20260927-224700.bak`.
These settings did not change this already-running thread's managed sandbox.
Several background escalations waited unseen for many minutes. On resumption,
agents should send exact commands/workdirs needing new escalation to the
coordinator, who uses the normal tool approval mechanism. Existing authorization
is not permission to bypass the actual sandbox. Do not launch a nested agent or
change security controls to evade it.

## Repository and preserved work

Repository: `/Users/andrewherrera/skein`, branch `main`.
Before the pause handoff, main was 12 commits ahead of origin's `71cef4d`.
Handoff delivery includes those integrated commits and this checkpoint. Verify
actual remote state with Git when restarting.

The owner's untracked `Untitled/` and `logos/` are untouched. Preserve them, old
stashes, original Claude recovery worktrees and every active checkpoint below.
Do not run broad worktree garbage collection, reset, or stash cleanup. Some
historical prunable worktree registrations exist; they are unrelated to this task.

| Worktree | Branch/head and disposition |
|---|---|
| `/private/tmp/skein-codex-token-boundaries` | `codex/token-boundaries`, `417dcb4`; clean. Native fix merged as `13b7337`. Native host/build evidence remains under `build/agent-logs/`. |
| `/private/tmp/skein-codex-real-retrieval` | `codex/real-retrieval-evaluation`, `8789314`; harness merged. Two unfinished untracked workflow/helper files remain; see checkpoints below. |
| `/private/tmp/skein-codex-synthetic-quality` | `codex/synthetic-quality`, `af196409c3505776f37d94663a9cb73b495228c3`; clean. **Only this final overlay-provenance commit is unmerged.** Earlier smoke commits are already integrated. |
| `/private/tmp/skein-codex-fake-timeline-lock` | `04c1def`; clean, merged as `927d071`. Root took over the commit and integrated verification after a background approval stall. |
| `/private/tmp/skein-codex-citation-integrity` | `4f2ff2b`; clean, merged as `678ec75`. |
| `/private/tmp/skein-codex-authoritative-evidence` | `a13caae`; clean. Both provenance commits merged. |
| `/private/tmp/skein-codex-session-turn-controller` | `8ecc34a`; clean, merged as `25a008a`. Its local dependency commits duplicate already-integrated work. |

Other prior worktrees are preserved. The original Claude recovery trees share:

```text
/private/tmp/claude-501/-Users-andrewherrera-skein/3492f4fe-cc93-467b-9082-91faed1249eb/scratchpad/worktrees/
```

The shell recovery suffix is `agent-a31c5098ee75829b8`, vault/runner suffix
`agent-a5e9224f2563c29c9`, and Obsidian suffix `agent-aa98d6a7f684fb232`.
Do not remove these on an assumption that a clean or merged branch means its
recovery evidence is disposable.

## Durable copies of work not integrated

The following are committed as **inactive checkpoint artifacts** under
`docs/Handoffs/checkpoints/2026-09-28-paused/`, so recovery does not rely on `/tmp`.
They are not active workflows or reviewed production code.

| Checkpoint | Meaning and SHA-256 |
|---|---|
| `tokenizer-overlay-provenance.patch` | Exact format-patch of unmerged `af19640`. SHA `91e2e03648172a784a348b24f3305473c7c6032c07c6509977c8f1c7bff34c02`. Prefer cherry-pick if the object exists; otherwise inspect/apply this patch once. |
| `retrieval-diagnostic.yml.pending` | Copy of `.github/workflows/retrieval-diagnostic.yml` from retrieval worktree. SHA `a01f55ec9a0fda60bf77f83da09c3d3116b32cdcb228ab4844405253862f4b47`. Unreviewed, untested, never dispatched. |
| `run_real_retrieval.py.pending` | Copy of `tools/eval/run_real_retrieval.py` from retrieval worktree. SHA `cd9a5f3c7b449219cce5cc7127558c188621b38cf229b72f4ee1033cfcb045c9`. Unreviewed, untested helper for the separate diagnostic workflow. |

`af19640` adds a required `tokenizer_overlay_sha256` to benchmark preparation,
declared-host provenance in the Android manifest, and smoke validation against
the hash of `native/llama/tokenizer-patches/PINS.txt`. Eighteen host Python tests
passed in its worktree. **Android compilation and ktlint for that commit remain
pending.** Do not claim the host declaration independently proves APK contents;
the verified installed APK digest is a separate provenance link.

The unfinished retrieval workflow references `test_run_real_retrieval.py`, which
**does not yet exist in this checkpoint**. A unittest discovery with a missing
pattern can pass with zero tests. Finish real runner regressions and verify test
discovery before activating it. Review its fresh-emulator requirement, package
retention after AGP instrumentation, report collection on failure, host timeout,
and declared source identity. Do not move the `.pending` files into active paths
merely to get a green workflow.

## What is integrated

The shell, reset, model import/search relocation, Settings cleanup, vault
quiescence/batched `kindsOf`, and Obsidian link resolution were recovered earlier.
Their implementation and original native evidence are recorded in the execution
report and Beads. The more recent integrated commits are:

| Main commit | Result |
|---|---|
| `25a008a` | Session-owned FIFO, durable USER admission, atomic new-chat/draft consumption, navigation/recreation survival, Stop/lock finalization. |
| `1163cf2`, `02d4372` | CHAT excluded from automatic evidence; AIOUT always excluded until explicitly promoted to a source note. CHAT-only explicit history retrieval retains provenance and Space filtering. |
| `678ec75` | Strict stored-citation validation, excerpt integrity helper, conservative revision retention independent of display validity. |
| `1435774` | Test-only native fixture repair: fresh consumed key copies, measured output reservation, explicit oversized-context refusal. |
| `927d071` | In-memory timeline snapshots share the transaction lock, with owned-transaction reentrance and post-quiesce reads. |
| `bcdab15`, `2fb78d8` | Production recall-stage controls and real encrypted-vault retrieval evaluator with anchored metrics. |
| `78a96ab`, `1ef511f` | Bounded tiny structural smoke profile; timeout source metadata explicitly unavailable before finalization. |
| `13b7337` | One complete native tokenization with explicit scaffold byte authorization, preserving ordinary merges and control isolation. |

Encrypted draft storage (`96df909`), SessionDraftStore (`91d51c4`), synchronous
post-COMMIT acknowledgments (`d1ed1ad`), exact prompt measurement/context fitting,
immutable per-turn Space/model binding and evidence policy predate these commits.
Read `docs/CHAT_TURN_SESSION.md`, `docs/CITATION_INTEGRITY.md`,
`docs/ANSWER_EVIDENCE_PROVENANCE.md`, `docs/RETRIEVAL_EVAL.md` and
`tools/eval/ANDROID_SYNTHETIC.md` before changing their contracts.

The controller's LOW lock observer joins independently owned jobs for at most
150 ms; it makes no two-way engine RPC. Normal and lock-time persistence have
separate epoch authority. Refused writes do not mark a turn finished. Session
close clears previously returned flows and stored references independently of
Compose. Do not reintroduce unbounded engine waits or noncancellable writes.
LC-22 delete UI is still absent; its future flow must call the tested deletion
suppression seam before stopping/deleting and clear the draft entry.

The tokenizer overlay does not edit the llama.cpp submodule. It applies a
before/patch/after-hash-pinned patch to generated build sources, consistently
redirecting all llama sources, headers and PCH. Policy is per call, with no TLS
or global mutable authorization. Overlay manifest SHA-256:
`71aba69a0fba8abc2df0a8f74f537182b79b29d60b13e74b7dc901e52dc36f29`.
Keep this distinct from upstream llama commit
`b29c606e28a01b1bc8c1351026a0fa6e616bf6c4`.

## Verification already completed

Do not rerun an entire old matrix merely because context was lost. Use these
results, then run checks justified by new integration or unresolved concerns.
No new tests were manually started after the owner's pause.

| Evidence | Exact limit |
|---|---|
| Main `1435774`, `build/agent-logs/citation-evidence-merged-check.log` | Model/vault/RAG/chat/testing checks, explicit ktlint and affected AndroidTest compilation: PASS, 540 tasks. Seven excerpt-tamper and five authoritative-evidence tests pass. Native tamper/sweep test is compiled, not device-executed. |
| Main `927d071`, `build/agent-logs/session-integrated-race-fixed.log` | Full testing-fakes/testing/chat/app checks and explicit ktlint, both app AndroidTest variants: PASS, 863 tasks, 82 seconds. This corrects the earlier integrated app CME failure. |
| Retrieval worktree `8789314`, `build/agent-logs/retrieval-harness-final.log` | Full testing/RAG/vault checks and ktlint, Dev AndroidTest compile/manifest: PASS, 232 tasks. Fourteen metric and three stage-control regressions. No actual retrieval diagnostic yet. |
| Tokenizer worktree `417dcb4`, `build/agent-logs/tokenizer-host-parity.log` | Actual pinned tiny-model native IDs match reference; original split regression explicitly reproduces 31 versus corrected 30 tokens. Full/empty authorization equivalence, straddling controls and concurrent policies pass. |
| Same tokenizer tree, `tokenizer-full-check.log`, `tokenizer-jni-guard.log` | 252 unit tests per variant, zero failures/skips; both AndroidTest compilations; Android arm64-v8a/x86_64 native builds; 27 JNI exports and ELF/privacy guards pass. Negative overlay-hash and absent-PCH configuration checks also pass. Independent read-only review found no concrete issues. |
| Main `13b7337`, `build/agent-logs/native-retrieval-merged-check.log` | Merged RAG/vault/testing/inference checks and explicit ktlint, both service AndroidTest compilations, both Android native ABIs: PASS, 343 tasks, 30 seconds. |
| Synthetic worktree `af19640` | 18 host Python tests pass; Android benchmark compilation and lint pending. |

The merged opt-in app benchmark must be compiled after the pending provenance
commit is integrated. The latest full app suite predates `13b7337`; complete the
app integration gate on the final resumed head rather than calling it already
verified. The ordinary emulator, reduced-profile smoke and real retrieval
diagnostic have **not** run on the new merged head.

JDK and SDK used successfully:

```text
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/Users/andrewherrera/android-sdk
```

Use `--max-workers=2`, per-worktree `build/agent-logs/` outputs, and inspect actual
exit codes. Do not pipe away Gradle failures. Fresh worktrees need local.properties
and pinned submodules; never commit changed gitlinks. Main has an ignored,
verified `inference-service/src/androidTest/assets/tiny.gguf` copied from the
tokenizer tree, SHA `741ad12b64088fedc17c33aacb22e48be1972ef36a39f03666dd68bd15614fb9`.
Running `fetchTestModel` together with lint exposed an implicit task-input
dependency in an earlier isolated command; fetching separately then checking
passed. Do not misclassify that build-graph failure as a model/runtime failure.

## Native/device evidence and release hold

**Fold remains HOLD.** There were no installs, UI interactions or generations on
the Fold in this continuation. Only the sole runner may use adb/SSH or dispatch
device workflows after resumption; other agents must not start their own queues.

Existing exact emulator `36362463113` at `a75964c` passed app 37/vault 180/native
30 with zero failures/skips. Later ordinary run `36383767287` at `d33bcb4` had
app 37/0, vault 190/1, native 32/3. Those failures motivated `1435774`, and the
new tests have not been rerun on a device yet. Do not use the old passing run as
acceptance for the new implementation.

Synthetic run `36384222843` at `71cef4d` retained 12 rows: 2 OK, 10 timeouts.
Native parity was 4/5; Unicode whitespace differed despite equal rendered text.
Original records are committed under `docs/eval/runs/2026-09-28-tiny-smoke-71cef4d/`.
They must remain unchanged. New profile `tiny-structural-v2` uses 1024 context,
2 threads and 4 output tokens, retaining all 12 cases, seed 17 and the 60-second
deadline. This is a different structural workload; a passing repeat cannot
establish a speed or factuality improvement over the earlier 4096/4/64 profile.

More raw runner artifacts remain in
`/private/tmp/skein-codex-synthetic-quality/build/agent-logs/`, including both
failed-run directories and `emulator-36383767287-summary.json`. The older passing
XML is in the original vault runner tree's `build/agent-logs/emulator-36362463113/`.

Read-only Fold observations, which must be rechecked before future actions:

```text
USB serial: 52241FDKD000LV
Installed APK SHA-256: b610ca509e069d5716ff02c5019e6b8448b76886c06a18d20725038bd54d70aa
Model folder: qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6/model.gguf
Model size: 1,590,475,744 bytes
GGUF header: HuihuiAi Qwen2.5 3B Instruct Abliterated, Q3_K_M, context 32768
```

Only the model header was compared; **the full Qwen file hash is still pending**.
No private vault was inspected or uploaded. Do not repair SSH aliases or remove
keystore/lock-screen settings. Never uninstall/reset to make tests pass. An
eventual authorized install uses `adb install -r`, preserving data, after reviewed
emulator artifacts and a check that the user is not generating an answer.

## Suggested bounded restart assignments

When the owner resumes, use one coordinator plus at most three isolated agents.
The coordinator alone runs `bd` because embedded Dolt takes exclusive locks.

**Coordinator:** read this checkpoint and current Git/Beads state; review and
integrate `af19640` once; review the unfinished retrieval workflow/helper; run
final app/benchmark integration checks; push one stable head for device work.
The ordinary and synthetic workflows must be checked against that exact full
SHA before interpreting artifacts. Avoid pushing a new head that cancels the
ordinary run before it supplies evidence.

**Native/accuracy agent:** retain the proven tokenizer design and help diagnose
any actual runtime failure. Verify current Qwen template/EOS behavior through the
real isolated service after release to the runner. Do not weaken exact-token
references, control isolation, or context reservations to make a test pass.

**Retrieval agent:** finish and test the manual diagnostic workflow/helper from
the inactive copies; preserve all gold labels and thresholds. The runtime may
expose a real graph-recall weakness: queries mention “Project Alder”, while a
seed title is “Project Alder brief” and current GraphRecall uses exact titles.
Twelve links being correctly materialized does not prove the query can reach
their seeds. Measure failures; do not rewrite gold to manufacture success.

The evaluator validates every indexed row against production Chunker output,
but its citation oracle includes only NOTE/ATTACHMENT rows because CHAT revision
snapshots are intentionally empty. Returned generated kinds are hard failures.
Raw rank positions are preserved: duplicates consume a position with zero gain,
and rank 9 cannot enter recall@8. Embeddings are not installed; full hybrid is
always INELIGIBLE. Recall ≥ .75 and nDCG ≥ .60 remain reported diagnostic gates.
Review how the opt-in class is represented in ordinary emulator XML: it currently
uses `assumeTrue` and can appear as an intentional skipped test. Do not describe
that as executed, silently ignore other skips, or hide it to claim a green gate.

**Sole hardware/CI runner:** after the coordinator releases the reviewed head,
run ordinary emulator, synthetic smoke and the separate retrieval diagnostic.
Read real XML/JSON, not only workflow statuses. Check migration011/wrong-key,
context-overflow/cancel/BUSY/lock, post-COMMIT, draft, citation-retention,
quiesce/kinds/import/process-death and tokenizer cases. Retain missing rows,
timeouts and failures. Only afterward consider the physical Fold queue.

## Beads and remaining acceptance boundaries

Recently closed: `skein-gg11.32.1` (generated-evidence exclusion), `skein-f17n`
(timeline fake race), plus earlier `skein-382u`, `skein-92xz`, `skein-ee5s`,
`skein-w8bh`, `skein-jgs` and planning `skein-i0zm`.

Keep actual acceptance gates open: `skein-gg11.28` (real Qwen behavior), `.30`
(answer evaluation/model comparison), `.31` (revision-aware landing/altered badge
and semantic support), `.32` (weak-evidence rejection/follow-up retrieval), `.33`
(integrated native exact budgeting), `.34` (Space binding/All Spaces scope),
`skein-9744` (actual retrieval diagnostic), `skein-i1y1` (new native retention
regression), and AL-10 `skein-xtov.24.10` with its `.1`, `.2`, `.3` children.
The shell/reset physical acceptance beads `.24.23` and `.24.21` remain open.

Other known boundaries remain in their existing issues: P0 missing-key recovery
`skein-gg11.29`; real embedder chain `skein-lbw` → `skein-079` → `skein-hwsa`;
canonical import IDs `.27.1`; atomic file/derived-note deletion `.27.2`; remaining
UX waves and v1 release journey. `skein-3iw` now explicitly records cleanup of
new-chat drafts during Space deletion/reassignment (these rows have no persona
FK). Do not declare the whole overhaul or answer-quality objective complete.

No Dolt remote is configured. `bd dolt push` reports remote origin missing;
do not invent a destination or repeatedly retry. Git delivery does not back up
the ignored local Beads database. Use `bd remember` for durable local workflow
knowledge, not MEMORY.md. Preserve the owner's pause in issue notes and resume
only on their instruction.
