# Lock Policy vs. Background Indexing — Design Resolution

**Status:** Design (not implementation)
**Date:** 2026-09-20
**Author:** Design agent (worktree `agent-a9cfd22a6d8d4fe42`)
**Resolves:** `skein-3xyu` — P0 architectural contradiction between the
WorkManager background-indexing constraints, the "unlocked vault required to
index" rule, and default screen-off locking.

**Locked prior decisions this design must honor** (do not contradict):

- `skein-wa1l` — enforced immutable-write per attachment UUID.
- `skein-4pqj` — RC production split from RC validation.
- `skein-vhtu` — SQLCipher-primary; `DocumentsProvider` exposes vault content
  as Markdown; no plaintext-Markdown-on-disk representation.
- The `skein-3xyu` bead's own NOTES already record the direction of travel
  (2026-09-19): *"index only during authorized unlocked sessions, with a
  small 'idle batch during unlock' mode. Do NOT extend key lifetime past
  lock."* This document is the full design write-up of that decision — it
  does not reopen the choice, it specifies it precisely enough to implement
  and test.
- `docs/design/POST_REVIEW_RESOLUTIONS.md` cross-cutting note: "The unlock
  lifecycle... Lock cancels in-flight IPC (§3), aborts a pending model
  verify/load (§2), invalidates short-lived share grants (§4), and freezes
  citation resolution to the last known revision (§1)." This document adds
  indexing and editor-save semantics to that same lifecycle and gives the
  lifecycle itself a name and a contract (`SessionState` / `LockObserver`,
  §5 below) so future sections can hang off it the same way §1–§4 of
  `POST_REVIEW_RESOLUTIONS.md` hang off `skein-3xyu`'s one-line decision.

**Non-negotiables** (spec §2, Handoff §3): no `INTERNET` permission, no
cleartext-at-rest, isolated `:inference`/`:embedder` processes stay isolated,
model files remain hash-verified before mmap, no Play Services, no
telemetry, default screen-off locking, StrongBox-backed keys, biometric
unlock. **This design does not weaken any of these.** In particular it does
not extend the lifetime of the unwrapped vault key past a lock event under
any circumstance, including "just to finish an indexing batch."

---

## Table of contents

- [1. Problem statement](#1-problem-statement)
- [2. Options considered](#2-options-considered)
- [3. Recommended design](#3-recommended-design)
- [4. Lock invalidation semantics](#4-lock-invalidation-semantics)
- [5. APIs and contracts](#5-apis-and-contracts)
- [6. Failure modes and testing plan](#6-failure-modes-and-testing-plan)
- [7. Plan-doc amendments needed](#7-plan-doc-amendments-needed)

---

## 1. Problem statement

### 1.1 Restatement in product terms

The plan wants three things simultaneously:

1. Indexing (chunk → embed → entity-extract → link) runs in the background,
   scheduled by WorkManager, so the user never has to sit and wait for a
   note to become searchable.
2. Indexing requires the vault to be unlocked, because chunk text, entity
   extractions, and embeddings are all derived from plaintext note content
   that only exists in memory while the SQLCipher connection and the
   unwrapped master key are live.
3. The vault locks by default when the screen turns off (Handoff §3.15,
   plan `E3.I14`: *"lock when screen turns off" (default on)*), and no
   other default behavior keeps it unlocked in the background.

Any two of these are easy. All three together are not: a background job
that *requires the screen to be off* to become eligible, combined with a
security policy that *locks the vault the moment the screen turns off*,
means the job's eligibility and the job's precondition are anti-correlated
by design.

### 1.2 Restatement in Android API terms

The plan's own text for `E5.I10` (`IngestWorker`) is unambiguous about the
exact APIs in play:

> `Constraints(requiresCharging=true, requiresDeviceIdle=true,
> requiredNetworkType=NOT_REQUIRED)` ... Runs only while the vault is
> unlocked (otherwise returns `retry()`)

Four Android APIs interact here, and each has a specific, citable semantic
that makes the deadlock precise rather than approximate:

1. **`Constraints.Builder.setRequiresDeviceIdle(true)`**
   (`androidx.work.Constraints`,
   https://developer.android.com/reference/androidx/work/Constraints.Builder#setRequiresDeviceIdle(boolean)).
   WorkManager documents this constraint as intended for "maintenance"-style
   work and defers to the platform's own idle/Doze detection
   (`DeviceIdleController`); the device is considered idle only after the
   screen has been off and the device stationary for a platform-determined
   interval, and thereafter only during periodic maintenance windows
   (https://developer.android.com/topic/performance/power/doze-standby).
   Screen-off is a *necessary* precondition of idle, not a coincidental
   correlate — there is no path to `requiresDeviceIdle` becoming `true`
   with the screen on.
2. **WorkManager's constraint model is conjunctive across ALL constraints on
   a `WorkRequest`, and re-evaluated continuously**
   (https://developer.android.com/develop/background-work/background-tasks/persistent/how-workmanager-schedules-tasks).
   A `WorkRequest` becomes eligible to run only at the instant every
   constraint is simultaneously satisfied, and the framework has no notion
   of "was satisfied earlier in this session" — it evaluates at dispatch
   time.
3. **`KeyguardManager.isDeviceLocked()` /
   `isKeyguardLocked()`**
   (https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked()).
   Google's own guidance ties screen-off, under a default (non-"never
   lock") lock-screen policy, directly to the keyguard becoming secured;
   Skein's own `UnlockManager` (`E3.I3`) additionally locks *itself*
   (independent of the OS keyguard) on `screen off with policy` — this is
   an app-level lock, stricter than and layered on top of the OS keyguard,
   specifically so that a rooted/ADB-unlocked keyguard bypass can't leave
   the vault key resident.
4. **`BiometricPrompt` / `KeyGenParameterSpec` authentication lifetime**
   (https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUserAuthenticationParameters(int,%20int)).
   The StrongBox-backed wrapping key is configured (plan `E2.I3`,
   confirmed at `2449`) with `setUserAuthenticationParameters(0,
   AUTH_BIOMETRIC_STRONG)` — **zero-second validity, i.e. per-operation
   authentication**. The unwrapped 32-byte master key that this produces is
   then held as a plain `ByteArray` inside `:app`'s process by
   `UnlockManager` (plan `E3.I3`) — **not** re-derived from Keystore per
   use. This is the crux: Android Keystore's authentication timer governs
   only the *unwrap* operation; once unwrapped, the master key's lifetime
   in memory is governed entirely by Skein's own code, not by any OS
   mechanism. There is no OS signal that revokes an already-extracted
   secret when the screen turns off — `UnlockManager` must notice the lock
   event itself and zero the array. This is why "index only during
   authorized unlocked sessions" is an application-level contract, not
   something the platform enforces for us, and why it is possible (and was
   nearly proposed as a workaround) to simply *not* zero the key on
   screen-off and let indexing keep running — which would be a silent,
   permanent weakening of the default-lock non-negotiable.

Putting 1–4 together: `requiresDeviceIdle` cannot become `true` without the
screen having been off; screen-off triggers `UnlockManager`'s own lock path
independent of and stricter than the OS keyguard; a locked `UnlockManager`
has already zeroed the master key and closed the SQLCipher connection by
the time WorkManager's constraint evaluator would consider the work
eligible. **The constrained periodic `IngestWorker` is eligible only in a
state where its own precondition (`retry()` if locked) is already false.**
It is not merely inefficient — under the plan's original wording it can
_never_ do real work via the periodic path; every dispatch immediately
returns `Result.retry()` and the 15-minute periodic re-arms forever. The
`E5.I19` acceptance criterion — *"Constrained worker verified not to run
unplugged"* — was written to test the *charging* half of the constraint set
and would pass even though the deeper idle/unlock contradiction was never
exercised, which is exactly how this got past review the first time.

### 1.3 Why "just extend the unlock window a bit" is not an acceptable fix

The two shapes of quick fix that come up in review threads for this class of
bug are both rejected here, on the record, so future contributors don't
re-propose them without re-reading this section:

- **"Delay the lock by N seconds after screen-off so idle-triggered
  indexing has a chance to run."** This is a disguised extension of the key
  lifetime past the user's own screen-off signal. It also does not actually
  fix the deadlock — Doze's idle window can arrive minutes after screen-off,
  far past any delay tolerable for a "the vault is locked" UI guarantee.
- **"Keep the master key resident but require biometric re-auth for
  anything user-facing, only the indexer gets silent access."** This is a
  two-tier trust model that contradicts spec §9's threat model line "physical
  attacker with unlocked device" mitigation — the mitigation assumes locked
  means "no plaintext-capable code path is live," not "no *UI* is live."

---

## 2. Options considered

These are the three options recorded in the bead. All three are workable in
isolation; the choice is about which one is compatible with the rest of the
locked design (`skein-vhtu`, spec §9 threat model, Handoff §3 non-negotiable
15) without weakening it.

### 2.1 Option 1 — Explicit indexing during authorized unlocked sessions

Indexing is not scheduled by device-idle/charging constraints at all in the
security-relevant sense. `IngestWorker` (or its successor) only enqueues and
only runs while `SessionState.phase == UNLOCKED`. A charging-gated,
idle-tolerant *batch size* policy still exists (small batches, "idle batch
during unlock" — see §3.2), but idle/charging are throughput knobs, not
authorization gates.

**Pros:**
- Removes the deadlock structurally: the ingest worker's authorization
  check and its scheduling constraint are no longer opposed, because there
  is exactly one gate (unlock) instead of two independent gates that can
  point in different directions.
- Zero key-lifetime extension. Matches the bead's own recorded decision and
  the "physical attacker with unlocked device" threat-model line exactly:
  the set of moments where plaintext-capable code can run is identical to
  the set of moments a human already chose to have the device unlocked for.
- Reuses machinery this plan already has: `UnlockManager.state`
  (`StateFlow<UnlockState>`, `E3.I3`) is already the source of truth for
  "is it safe to touch plaintext"; this option makes indexing subscribe to
  it directly instead of adding a second, WorkManager-shaped notion of
  "is it safe."

**Cons:**
- A user who unlocks Skein rarely (e.g., only to read, via a cached search
  index, never to write) accumulates a growing backlog before it ever gets
  indexed. Mitigated in §3.2 by indexing eagerly and incrementally *during*
  every unlocked session rather than waiting for idle.
- No indexing progress while the phone sits in a pocket unlocked-but-off
  for the user's normal daily unlock cadence — this is fine (see §3), but
  it is a real behavior change from the plan's original "we index while
  you sleep" mental model, and needs a line in the Settings › Indexing copy
  (`E6.I14`) saying so.

### 2.2 Option 2 — User-configurable extended-unlock windows

Add a Settings toggle: "Allow indexing to keep the vault unlocked for up to
N minutes after screen-off." When enabled, `UnlockManager`'s screen-off lock
transition is suppressed (but idle-timeout and explicit lock are not) for
up to N minutes specifically to let a queued indexing pass complete.

**Pros:**
- Preserves the "index while idle/charging overnight" mental model for
  users who opt in.
- Bounded — N minutes, not indefinite — so it is less severe than "never
  lock while indexing."

**Cons:**
- This *is* extending key lifetime past lock, by definition, for whatever
  window is chosen. The bead's own decision note says "do NOT extend key
  lifetime past lock" specifically to head this off. Even opt-in, it adds a
  second lock-policy code path (`screen-off-but-extended`) that every
  future audit of `UnlockManager` has to reason about, and it directly
  weakens the spec §9 physical-attacker mitigation for exactly the users
  who turn it on — the ones who most want overnight indexing are the ones
  who will have their vault sitting unlocked, screen off, unattended,
  overnight.
- Still does not fully solve the deadlock: `requiresDeviceIdle` can still
  fire *after* N minutes have elapsed, landing back in the same trap one
  layer down, just with a delay.
- Adds real UI surface (a security-tradeoff toggle) that needs its own
  string review, its own default (must default off), and its own line in
  `THREAT_MODEL.md` — nontrivial cost for a benefit ("indexing finishes
  faster") that Option 1 with eager per-session indexing (§3.2) already
  captures for all but the rarest-unlock users.

### 2.3 Option 3 — Two-tier index (metadata anytime, content on unlock)

Split indexing into a lightweight tier that never touches plaintext body
content (file existence, size, `updated_at`, frontmatter fields already
visible without decrypting `body_md` if frontmatter were stored outside the
encrypted blob) that can run anytime, and a content tier (chunking,
embedding, entity extraction — all of which require plaintext) that only
runs during unlock, per Option 1.

**Pros:**
- The "lightweight" tier genuinely has no idle/lock conflict, because it
  needs no key at all.
- Could give a faster "0 new documents pending" / "12 documents queued"
  Settings › Indexing readout even while locked.

**Cons:**
- `skein-vhtu` locks the vault to SQLCipher-primary with **no** plaintext
  representation outside the encrypted DB — including frontmatter, which
  lives in the same encrypted `documents` row as `body_md` (spec §5). There
  is no metadata-anytime tier available without either (a) duplicating
  frontmatter into an unencrypted side table, which is a new plaintext-at-
  rest surface the threat model does not currently accept, or (b) reading
  from the encrypted DB anyway, which reintroduces the exact same unlock
  requirement Option 1 already has.
  - Note that `documents.updated_at` itself is what feeds `ingest_queue`
    (spec §7.1 step 1, "SQLite trigger on `documents.updated_at`") — the
    trigger fires inside the encrypted DB regardless, so even the "which
    documents changed" signal needs the vault open.
- The content tier still has the exact same design question as Option 1,
  so this option does not replace Option 1, it only adds a thin metadata
  layer on top of it — and that layer's value (a locked-state "N pending"
  counter) can be approximated for free by persisting the last known
  pending count into an unencrypted preference at lock time (a single
  integer, not user content) instead of building a second indexing tier.

### 2.4 Comparison

| | Removes the deadlock | Key-lifetime impact | New attack surface | New user-facing surface | Reuses existing machinery |
|---|---|---|---|---|---|
| **1. Unlock-session indexing** | Structurally, yes | None — never extended | None | One Settings string change | `UnlockManager.state` |
| **2. Configurable extended-unlock** | Only partially, and only for N minutes | Extends it, by an admitted, bounded amount | New lock-policy branch; overnight-unlocked window for opted-in users | New security toggle + default + docs | Reuses `UnlockManager`, but forks its lock path |
| **3. Two-tier index** | Only for the tier that turns out not to need plaintext, which is none of the content-relevant work | Same as Option 1 for the content tier | New plaintext-adjacent side table if metadata is to be genuinely lock-independent | A "pending count while locked" readout | Duplicates trigger logic already in the encrypted DB |

---

## 3. Recommended design

### 3.1 Decision

**Adopt Option 1: index only during authorized unlocked sessions.** Small
idle batches are permitted, but only *inside* an unlocked session — idle
and charging remain throughput/backoff signals (as `ThermalGovernor`,
`E4.I9`, already treats thermal state), never authorization gates. The
vault key's lifetime is never extended past a lock event for any reason,
including "let the current batch finish" — in-flight batches are cancelled,
not raced to completion (see §4.2).

Justification, beyond the per-option comparison in §2.4:

- It is the only option that removes the deadlock **structurally** rather
  than by narrowing the window in which it can occur. A narrowed window is
  still a window; Handoff §3's non-negotiable 15 ("StrongBox-backed keys...
  Never expose vault via `content://` in a way that bypasses the StrongBox
  unlock") and the spec §9 threat model both read as absolute guarantees,
  not probabilistic ones, and this design should give them an absolute
  mechanism to match.
- It costs the least new surface. No new Settings toggle, no new plaintext-
  adjacent table, no new lock-policy branch to audit. The entire fix is:
  stop gating indexing on WorkManager device-idle/charging constraints for
  *authorization* purposes, and gate it on `SessionState.phase` instead.
- It composes cleanly with the already-locked `document_revisions` design
  (`POST_REVIEW_RESOLUTIONS.md` §1): revision creation already happens
  "inside `IngestWorker` (which is gated by unlock)" — that document
  already assumed something close to this design when it was written one
  day earlier in the same review cycle. This document makes that
  assumption explicit and precise instead of implicit.

### 3.2 What "small idle batches OK" means precisely

"OK" here means: while the vault is unlocked, an ingest pass is permitted to
opportunistically use `PowerManager.isDeviceIdleMode()` /
`ThermalGovernor.state` as a *courtesy* signal for batch sizing, exactly the
way `E5.I10` already treats thermal `Reduced`/`Paused`. Concretely:

1. **Trigger, not schedule, on unlock and on document change.** Instead of
   a WorkManager `PeriodicWorkRequest` with `requiresDeviceIdle=true` as an
   authorization gate, `IngestScheduler` enqueues a **one-time, expedited**
   `IngestWorker` run (a) immediately on every transition to
   `SessionState.phase == UNLOCKED`, and (b) on every `documents.updated_at`
   trigger while already unlocked (debounced, as today). WorkManager is
   still used — for its retry/backoff/battery-aware dispatch semantics —
   but its `Constraints` no longer include `requiresDeviceIdle`, and
   `requiresCharging` becomes a soft batch-size input read at run time
   (via `BatteryManager`/`ThermalGovernor`) rather than a hard
   `Constraints` gate. This is the one-line fix to `E5.I10`'s constraint
   set; §7 lists it as a plan amendment.
2. **Batches are small and preemptible: target ≤ 30 seconds of wall-clock
   work per batch.** This bounds the worst-case delay between a lock
   request and the isolated services actually stopping (§4.2) — the
   contract is "the current batch finishes or is interrupted within its
   own ~30s budget," never "the batch races to finish no matter how long
   it takes because the vault is still nominally open."
3. **A batch that happens to start while the device is idle (screen off,
   plugged in, but *unlocked* — e.g., the user unlocked, started a long
   read/chat session, then the screen timed out while `lockOnScreenOff`
   happens to be configured off, or the idle timer has not yet elapped) is
   simply a batch like any other.** Nothing about idle state changes the
   authorization logic; it only changes whether `IngestPipeline` opts into
   a larger batch size (up to a cap) because thermal/battery conditions are
   favorable. If the session locks mid-batch (idle timeout elapses,
   `E3.I3`), the batch is cancelled per §4.2 regardless of how much idle
   "credit" it thought it had.
4. **No periodic background wake exists purely to index.** Skein does not
   ask the OS to wake it up to index while locked. This is a deliberate
   simplification versus the original plan's "wakes up while charging and
   idle" framing, and it is the direct, intended behavior change flagged in
   §2.1's cons: indexing throughput now tracks unlock frequency, not wall
   clock. For Skein's actual usage pattern (a personal knowledge tool a
   user opens deliberately, per spec §1) this is judged to be the right
   trade — a user who writes ten notes in one unlocked session gets all ten
   indexed by the end of that session (bounded by batch throughput), rather
   than waiting for a Doze maintenance window that may not arrive before
   the next unlock anyway.

### 3.3 Restated non-negotiable

**Do not extend the unwrapped master key's lifetime past a lock event under
any circumstance.** No "finish the current batch" grace period on the key
itself. §4.2's cancellation contract is the mechanism that makes this safe
without losing indexing progress: batches are small enough that cancelling
mid-batch costs at most ~30s of redone work on next unlock (chunking is
idempotent per `E5.I10`'s existing `ingest_attempts` retry design), which is
a far smaller cost than the alternative of ever holding the key open past
the point the user (or the idle timer) said "lock now."

---

## 4. Lock invalidation semantics

This section enumerates, exhaustively, what a lock transition MUST do. It
is the direct answer to the bead's own observation: *"Currently plan only
unloads the text model — insufficient."* Everything below is new normative
text; §7 lists exactly which plan items it amends.

A lock transition has three sub-phases, corresponding to the `SessionPhase`
states in §5: `UNLOCKED → LOCKING → LOCKED`. `LOCKING` is a bounded-time
window (target budget: **2000 ms**) in which pending work is asked to stop
and the one authorized write (a pending editor save, §4.3) is allowed to
complete; `LOCKED` is the state in which the key is gone and no
plaintext-capable code may run, full stop.

### 4.1 Stop new work in both isolated processes

The instant `LOCKING` begins, `:app` pushes a lock notice (§5.2,
`onSessionLocking`) to both `:inference` and `:embedder`. Each service
immediately sets its cached "authorized epoch" to a sentinel
(`AUTHORIZED_EPOCH_NONE`), which is checked as the very first statement of
every remaining AIDL entry point (`load`, `generate`, `embed`,
`extractEntities`, `rerank`). This is a synchronous, non-blocking flag flip
— it happens on the Binder thread handling `onSessionLocking` and is
visible to every subsequent call before `onSessionLocking` even returns,
because it is a `volatile`/`AtomicLong` write, not a suspend function. From
this instant, **no new unit of work is admitted**, regardless of whether
the in-flight cancellation in §4.2 has finished unwinding yet.

This closes the specific hole the bead called out: previously, only
"unload the text model" happened on lock, which does nothing to stop a
`generate()` or `embed()` call that was already queued (but not yet
started) on a service's internal executor between the lock signal and the
model unload.

### 4.2 Cancel in-flight processing

**WorkManager side (`:app` process).** `IngestScheduler` calls
`WorkManager.cancelUniqueWork("skein-ingest")` synchronously on
`onLocking`. Per WorkManager's documented `stop()` semantics
(https://developer.android.com/reference/androidx/work/ListenableWorker#onStopped()),
a `CoroutineWorker`'s backing coroutine has its `Job` cancelled and
`isStopped` flips to `true`; `IngestWorker` must check `isStopped` (or rely
on structured-concurrency cancellation propagating into any suspend call it
awaits) between pipeline steps and return promptly rather than let the
cancellation exception propagate through an unguarded `try/catch(Throwable)`
that would swallow it (a real risk given `E5.I10`'s existing "each step's
failure is logged and the doc is re-queued" error handling — that handling
must not catch `CancellationException`).

**Isolated-process side: how does an isolated process observe the
cancel?** This needs to be precise because a `CoroutineWorker`'s
cancellation is local to `:app`'s process — it does not, by itself, do
anything inside `:embedder`, which is a separate process with its own
process-local coroutine scopes. The observation path is:

1. `IngestWorker`'s cancellation (from `WorkManager.cancelUniqueWork`, or
   from `LockObserver.onLocking` calling it directly — see §5.1) causes the
   `IngestPipeline` coroutine to receive a `CancellationException` at its
   next suspension point, which includes the suspend call across AIDL
   (`suspendCancellableCoroutine` wrapping the `IEmbedderService.embed`
   Binder call, per the client-side pattern already implied by
   `POST_REVIEW_RESOLUTIONS.md` §3's async `generate`/`cancel` pair).
2. Cancelling that suspend point runs its `invokeOnCancellation` handler,
   which the client wires to call the service's own `cancel(requestId)`
   entry point (oneway, matching the existing `IInferenceService.cancel`
   pattern; §7 amends `IEmbedderService` to add the same `cancel(requestId)`
   oneway method it currently lacks — see `POST_REVIEW_RESOLUTIONS.md` §3.3,
   which defined `IEmbedderService.embed` as synchronous with no cancel
   path).
3. Inside `:embedder`, `cancel(requestId)` sets a per-request
   `volatile aborted: Boolean` on the matching `RequestSlot` (mirroring
   `InferenceWorker`'s existing pattern from `POST_REVIEW_RESOLUTIONS.md`
   §3.3). The embedding/entity-extraction native call loop (ONNX Runtime
   session run over a batch) checks `aborted` **between chunks within the
   batch**, not only between batches — this requires the batch executor to
   call the native inference in a per-chunk loop rather than handing the
   whole batch to a single opaque native call, which is already implied by
   spec §7.1's "~100–150ms per chunk" framing for entity extraction.
4. `onSessionLocked` (sent once `LOCKING`'s budget elapses or all
   acknowledgments are in, whichever is first) is the hard backstop: on
   receipt, the service does not wait for graceful unwind any further — it
   frees whatever the in-flight call was holding (native buffers, shared
   memory fds) unconditionally, exactly as if the request had errored, and
   marks itself `LOCKED` (refusing everything, including `status()`, other
   than `unload()`/diagnostic calls that touch no plaintext).

The general rule this establishes: **a lock signal is delivered as a push
(`onSessionLocking`/`onSessionLocked`), not inferred by a service from the
absence of new calls.** An isolated process cannot poll `:app`'s
`UnlockManager.state` (it has no access to it — that is the entire point of
process isolation), so `:app` must actively tell it.

### 4.3 Pending editor saves — flush, not discard, with a bounded deadline

**Decision: flush, with a hard timeout and a discard-safe fallback.**

Rationale for flush-over-discard: the editor is the primary way a user
creates content in Skein (spec §1, §8); losing an in-progress edit because
the screen happened to time out is a severe, trust-destroying failure mode
for a personal knowledge tool, categorically worse than the small security
cost of a bounded, already-in-flight write completing during the `LOCKING`
window (the key has not been zeroed yet at this point — see the ordering in
§4.4 — so the write is not "using a key after lock," it is "using the key
during the bounded shutdown sequence that lock triggers," which is a
different and acceptable thing).

Concretely:

1. `LockObserver.onLocking(epoch, budgetMillis = 2000)` is delivered to
   `EditorViewModel` (or whichever view model owns unsaved editor state)
   before the vault connection closes.
2. The view model synchronously (off the main thread, but blocking its own
   completion signal) performs the same write path autosave already uses —
   this design does not add a new write path, it changes *when* autosave's
   existing debounce is force-flushed. If autosave already ran within the
   last debounce interval, this is a no-op (nothing pending).
3. The write is given a hard deadline of `budgetMillis` (default 2000 ms,
   configurable per §5 constant, generous relative to "single row UPDATE on
   SQLCipher" but bounded so a stalled write cannot indefinitely block
   lock). `withTimeoutOrNull(budgetMillis) { flush() }`.
4. **On success:** the vault now reflects the edit; proceed to §4.4.
5. **On timeout (flush did not complete in time — e.g., I/O stall, WAL
   checkpoint contention):** the still-valid (not yet zeroed) master key is
   used for one more, smaller write: persist the raw unsaved buffer into a
   `recovery_drafts` table (new migration, §7) keyed by `(document_id,
   captured_at)`, which is a strictly smaller, append-only write far less
   likely to stall than the full document update. If even that fails (the
   flush budget's remaining slice is exhausted), the buffer is discarded
   and the failure is logged — data loss is possible only in this doubly-
   degraded case, which should be exercised directly by
   `EditorFlushTimeoutTest` (§6.3).
6. On next unlock, if a `recovery_drafts` row exists for the currently open
   (or most recently open) document, the editor surfaces a "recovered
   unsaved draft — keep or discard" prompt before showing the persisted
   version, then deletes the row either way.

This is a genuinely new piece of schema/UI relative to the plan as written
(the plan's `E3.I3` description says only "`VaultManager.close()`,
`InferenceEngine.unload()`... zero the key array, clear any cached
decrypted attachments" — no editor-flush step at all today). §7 lists it as
a plan amendment against `E3.I3` and `E7.*` (editor).

### 4.4 Close vault access

Ordering matters here and is now specified explicitly (the plan's `E3.I3`
text lists the actions but not their order):

1. Editor flush completes or times out (§4.3) — this is the last operation
   permitted to use the live key.
2. `VaultManager.close()` — the SQLCipher/`BundledSQLiteDriver` connection
   is closed. Before close, ensure `PRAGMA cipher_memory_security = ON` is
   set for the connection's lifetime (SQLCipher's own documented option to
   zero internal key-schedule and page-buffer memory on deallocation,
   https://www.zetetic.net/sqlcipher/sqlcipher-api/#cipher_memory_security)
   — this is a one-line addition to the connection-open path
   (`E2.I1`/`E2.I13`) that gives defense-in-depth against key material
   SQLCipher itself might otherwise leave in freed heap pages, independent
   of anything Skein's own Kotlin code zeroes.
3. **Zero the passphrase `ByteArray`** — `UnlockManager` holds the 32-byte
   master key as a `ByteArray` it fully owns (never as a `String`, which is
   immutable and cannot be reliably zeroed in the JVM). `java.util.Arrays.fill(key, 0)`
   (or `java.security.SecureRandom` overwrite followed by zero-fill, for the
   marginal extra assurance against compiler dead-store elimination — the
   existing `E3.I3` acceptance criterion already asserts *"the `ByteArray`
   returned earlier by `masterKey()` is all zeros (same array instance
   zeroized)"*, so the mechanism is already specified; this document adds
   only the ordering constraint that it happens after step 1, and the
   SQLCipher pragma in step 2).
4. Set the reference to `null` so the zeroed array itself becomes eligible
   for GC rather than lingering reachable-but-zeroed (belt-and-suspenders;
   the zero-fill is the actual security property, the `null` is hygiene).

### 4.5 Clear sensitive state in `:app`, `:inference`, `:embedder`

The bead is explicit that "unload the text model" alone is insufficient.
The table below is the exhaustive per-process inventory this design
requires; each row names a specific field/scope and the action taken on
`onLocking`/`onLocked`.

**`:app`**

| State | Action on lock |
|---|---|
| `UnlockManager.masterKey: ByteArray?` | Zero-fill, then null (§4.4.3) |
| `VaultManager`'s `SQLiteConnection` | `close()` (§4.4.2) |
| `EditorViewModel.pendingBody: MutableStateFlow<String>` | Flushed or recovery-drafted (§4.3), then cleared to empty/idle state |
| `ChatViewModel`'s in-flight partial assistant output buffer | Discarded (not user-authored; only fully-persisted turns matter — see §4.2's cancellation of the underlying `generate` call) |
| `RetrievalCache` (recent citation excerpts kept for UI snappiness) | `clear()` |
| `IngestScheduler`'s in-memory queue/progress state (`IngestProgress` `StateFlow`) | Reset to `Idle`; WorkManager work cancelled (§4.2) |
| `VaultScope` (the `SupervisorJob`+`Dispatchers.IO` scope every vault-touching coroutine is launched in) | `cancel()`; a fresh scope is created on next unlock rather than reusing a cancelled one |
| `ChatScope` (per active conversation) | `cancel()` |
| Clipboard-adjacent state, if any citation "copy excerpt" action left a pending value | Out of scope for this document (no plan item currently holds plaintext in the system clipboard past a single user-initiated copy); flagged here only so a future clipboard feature does not silently reopen this class of leak |

**`:inference`**

| State | Action on lock |
|---|---|
| `authorizedEpoch: AtomicLong` | Set to `AUTHORIZED_EPOCH_NONE` immediately (§4.1) |
| Active `RequestSlot` (in-flight `generate`) | Cancelled per §4.2's existing `cancel`/`onDone(CANCELLED)` pattern (already designed in `POST_REVIEW_RESOLUTIONS.md` §3.3); this document only adds that *lock* is a trigger for it, in addition to explicit client cancellation |
| `LlamaContext` native handle (holds the KV cache — decrypted prompt/retrieved-context token state) | The KV cache buffer is memset to zero **before** `llama_free(ctx)` is called, not merely freed. This requires a small native wrapper (`skein_ctx_free_secure`, new — §7) since `llama_free` alone does not guarantee zeroing; freed-but-unzeroed heap pages are a residual-plaintext risk this design closes explicitly, matching the spirit of SQLCipher's `cipher_memory_security` pragma above |
| `PromptScope` (the service's internal coroutine scope for `generate`) | `cancel()` |
| mmap'd model weights | **Not cleared.** Model weights are public (bundled or user-imported from a public source), not vault secrets; keeping the model mapped across a lock/unlock cycle avoids an expensive reload and is explicitly permitted (matches `E3.I3`'s existing note that "models stay mmapped in the isolated process is acceptable per spec only while unlocked" — this document clarifies that mmap may persist through a lock as an idle resource as long as *no session-derived plaintext state* persists with it; if idle-unload timers (spec §9, "idle-unload after N minutes") separately choose to unmap, that is an independent policy, not a lock-triggered one) |

**`:embedder`**

| State | Action on lock |
|---|---|
| `authorizedEpoch: AtomicLong` | Set to `AUTHORIZED_EPOCH_NONE` immediately (§4.1) |
| In-flight embed/entity-extraction batch's input buffer (raw chunk text pending embedding) | Aborted at the next per-chunk checkpoint (§4.2); buffer zeroed before the call frame unwinds |
| `EmbedScope` | `cancel()` |
| Any `SharedMemRef`-backed `MemoryFile`/ashmem region still open for the in-flight request | Immediately `close()`d; per the existing IPC ownership rule in `POST_REVIEW_RESOLUTIONS.md` §3.2 ("the service OWNS every fd it receives and MUST close() after processing, success or failure") this is really just that rule's error path being exercised by a lock-triggered cancellation instead of a normal completion — no new rule, but the lock path must be added to the test matrix that exercises it (§6.3) |
| GLiNER/ONNX Runtime session objects | Not torn down (analogous to model mmap above — the *model* is not a secret; only request-scoped buffers are) |

---

## 5. APIs and contracts

This section is a design sketch, not an implementation. Types are
illustrative Kotlin; exact package paths are chosen by whichever plan item
implements this (§7).

### 5.1 `SessionState` and `LockObserver` — the `:app`-side contract

```kotlin
// core/security/src/main/kotlin/app/skein/security/session/SessionState.kt
// ILLUSTRATIVE — sketch, not implementation.

/** Supersedes/extends UnlockManager.UnlockState (E3.I3) with an explicit
 *  LOCKING phase and a monotonic epoch that isolated services key on. */
enum class SessionPhase { LOCKED, UNLOCKING, UNLOCKED, LOCKING }

/** Opaque, monotonically increasing per successful unlock. Never reused. */
@JvmInline value class SessionEpoch(val value: Long) {
    companion object { val NONE = SessionEpoch(-1L) }
}

enum class LockReason { USER_REQUESTED, IDLE_TIMEOUT, SCREEN_OFF_POLICY, KEY_INVALIDATED }

interface SessionState {
    val phase: StateFlow<SessionPhase>
    val epoch: StateFlow<SessionEpoch>

    /** Existing E3.I3 entry point; unchanged signature. */
    suspend fun unlock(cipher: Cipher): UnlockResult

    /**
     * Begins the LOCKING sequence: notifies every registered LockObserver
     * (in registration order; editor-flush observers first — see registry
     * ordering note below), pushes onSessionLocking to both isolated
     * services, waits up to [budgetMillis] for observers to acknowledge,
     * then transitions to LOCKED regardless (hard backstop, §4.2).
     * Idempotent: calling lock() while already LOCKING/LOCKED is a no-op.
     */
    fun lock(reason: LockReason, budgetMillis: Long = 2000)

    /** E3.I3's existing idle-timer touch(); unchanged. */
    fun touch()
}

/**
 * Implemented by anything in :app that owns state which must be flushed
 * or cleared on lock (EditorViewModel, IngestScheduler, VaultManager,
 * RetrievalCache, ...). Registered with a LockObserverRegistry at
 * construction; the registry calls these in a fixed order so that, e.g.,
 * the editor's flush (which needs the key) always runs before
 * VaultManager.close() zeroes it.
 */
interface LockObserver {
    /** Priority determines ordering; lower runs first. Flush-needing
     *  observers use LOW (~0-99); pure-clear observers use HIGH (~900+). */
    val lockPriority: Int

    /** Must respect [budgetMillis] via withTimeoutOrNull or equivalent;
     *  a slow observer does not block others past the shared deadline. */
    suspend fun onLocking(epoch: SessionEpoch, budgetMillis: Long)

    /** Fired after the key is zeroed and the vault connection is closed.
     *  MUST NOT touch anything key-derived; pure in-memory cleanup only. */
    fun onLocked(epoch: SessionEpoch)

    fun onUnlocked(epoch: SessionEpoch)
}
```

### 5.2 Cross-process propagation — AIDL additions

Neither `IInferenceService` nor `IEmbedderService` (as designed in
`POST_REVIEW_RESOLUTIONS.md` §3.3) currently has a push channel for lock
events; `IEmbedderService.embed` additionally lacks any `cancel` at all.
Both gaps are closed with a small, symmetric addition to each interface —
this is additive to the v2 AIDL contract, not a v3 renumbering, since it
only adds methods:

```aidl
// core/ipc/src/main/aidl/app/skein/ipc/IInferenceService.aidl
// core/ipc/src/main/aidl/app/skein/ipc/IEmbedderService.aidl
// ILLUSTRATIVE delta — additive to both interfaces identically.

/**
 * oneway. :app pushes this the instant SessionState enters LOCKING.
 * The service must, before this call returns control to the Binder
 * thread pool: (1) set authorizedEpoch to NONE so no new call is
 * admitted, (2) begin cancelling any in-flight request tied to the
 * previous epoch, honoring budgetMillis as a best-effort deadline for
 * graceful unwind (see onSessionLocked for the hard backstop).
 */
oneway void onSessionLocking(long epoch, long budgetMillis);

/**
 * oneway. Sent once LOCKING's budget has elapsed (or all in-flight
 * work has already acknowledged cancellation, whichever is first).
 * The service must unconditionally free any remaining request state
 * (native buffers, shared-memory fds) as of this call, with no further
 * grace period. Idempotent.
 */
oneway void onSessionLocked(long epoch);

/**
 * oneway. IEmbedderService currently has no cancel path (unlike
 * IInferenceService.cancel). Added here so lock-triggered cancellation
 * (and ordinary client-initiated cancellation) can interrupt an
 * in-flight embed/extractEntities batch between chunks (§4.2).
 */
oneway void cancel(int requestId);   // IEmbedderService only; IInferenceService already has this
```

Every existing request-carrying method (`load`, `generate`, `embed`,
`extractEntities`, `rerank`) gains a `sessionEpoch: Long` field on its
request Parcelable (`LoadRequest`, `GenerateRequest`, `EmbedRequest`, ...).
This is belt-and-suspenders relative to the push channel above: the push
tells a service "stop admitting new work now," and the per-request epoch
lets the service's `guard()` check (§5.3) reject, with a specific error
code, any request that was already in flight on the Binder call queue at
the moment of the push but had not yet reached the service's business
logic — a race the push alone cannot close because Binder delivers calls
concurrently across its thread pool.

```kotlin
// ErrorCode addition (extends POST_REVIEW_RESOLUTIONS.md §3.3's ErrorCode object)
object ErrorCode {
    // ... existing values (OK, HASH_MISMATCH, ..., INTERNAL) ...
    const val SESSION_LOCKED = 11   // new
}
```

### 5.3 `IsolatedSessionGate` — the service-side contract

Implemented identically, but as two separate instances, inside `:inference`
and `:embedder` — they do not share code across the process boundary, only
the shape of the contract.

```kotlin
// inference-service/.../IsolatedSessionGate.kt
// embedder-service/.../IsolatedSessionGate.kt
// ILLUSTRATIVE — same shape, two independent implementations.

sealed interface GateResult {
    data object Admit : GateResult
    data class Refuse(val code: Int) : GateResult   // ErrorCode.SESSION_LOCKED
}

class IsolatedSessionGate {
    private val authorizedEpoch = AtomicLong(SessionEpoch.NONE.value)

    /** Called from the oneway onSessionLocking handler. Synchronous, fast. */
    fun onLocking(epoch: Long, budgetMillis: Long) {
        authorizedEpoch.set(SessionEpoch.NONE.value)
        // ... signal cancellation to any RequestSlot matching `epoch` ...
    }

    fun onLocked(epoch: Long) {
        // Hard backstop: free anything still holding request state.
    }

    fun onUnlocked(epoch: Long) = authorizedEpoch.set(epoch)

    /** First statement of every AIDL entry point that touches plaintext. */
    fun guard(requestEpoch: Long): GateResult =
        if (requestEpoch == authorizedEpoch.get()) GateResult.Admit
        else GateResult.Refuse(ErrorCode.SESSION_LOCKED)
}
```

A cold-start default of `SessionEpoch.NONE` is deliberate and load-bearing:
if `:inference` or `:embedder` is killed (LMK, crash, `ServiceDied` per
`POST_REVIEW_RESOLUTIONS.md` §3.4) and restarted, it must come back
**unauthorized by default**, never implicitly trusting whatever epoch a
stale client might still send. `:app` re-sends `onUnlocked` on every fresh
bind, so a legitimately-unlocked session recovers immediately; a
maliciously or accidentally re-bound service after a lock does not.

### 5.4 Lock transition sequence

```
:app (SessionState)              :inference                :embedder
      |                               |                         |
lock(reason) called                   |                         |
      |--- onSessionLocking(e, 2000) ->|                         |
      |--- onSessionLocking(e, 2000) ------------------------- ->|
      |   [authorizedEpoch := NONE in both, immediately]         |
      |                               |                         |
      |--- LockObserver.onLocking() to EditorViewModel (flush)   |
      |--- LockObserver.onLocking() to IngestScheduler (cancel WM)|
      |                               |                         |
      |          (up to budgetMillis for graceful unwind)        |
      |                               |                         |
      |--- onSessionLocked(e) ------->|                         |
      |--- onSessionLocked(e) ---------------------------------->|
      |   [hard backstop: free remaining native/fd state]        |
      |                               |                         |
VaultManager.close()                  |                         |
zero masterKey ByteArray              |                         |
phase := LOCKED                       |                         |
```

---

## 6. Failure modes and testing plan

### 6.1 Invariants (property-style tests)

These are stated as invariants that must hold across **every** interleaving
of lock/unlock/work events, not just the happy-path sequences named in
§6.3. Each is phrased so it can be checked mechanically against recorded
event traces from the fuzz harness in §6.2.

- **I1 (no unauthorized admission).** No AIDL call is ever admitted
  (`GateResult.Admit`) with `requestEpoch != authorizedEpoch` at the moment
  `guard()` runs.
- **I2 (no dangling ingest work while locked).** At every instant where
  `SessionState.phase == LOCKED` has been true for at least one full
  `onSessionLocked` round trip, `WorkManager.getWorkInfosByTag("skein-ingest")`
  contains no `WorkInfo.State.ENQUEUED` or `RUNNING` entries. This is a
  direct regression test for the original deadlock class: before this
  design, the failure mode was the *opposite* pathology (nothing ever ran);
  after, the risk shifts to "something keeps running after lock," so this
  invariant guards the new failure direction.
- **I3 (vault handle closed iff locked).** `VaultManager`'s connection is
  non-null only while `phase == UNLOCKED` or `UNLOCKING`; null in `LOCKING`
  (after the flush step) and `LOCKED`.
- **I4 (key zeroed iff locked).** `UnlockManager.masterKeyBytesForTest()`
  (a test-only accessor) is either a live 32-byte key (`UNLOCKED`) or an
  all-zero 32-byte array or `null` (`LOCKED`) — never a stale non-zero array
  once `phase == LOCKED` has been observed.
- **I5 (durable-or-recovered, never silently lost).** For any editor buffer
  that was non-empty and unsaved at the moment `lock()` was called, after
  the lock transition completes, exactly one of (a) the vault's persisted
  document body reflects it, or (b) a `recovery_drafts` row exists for it —
  never neither.
- **I6 (isolated-process cold-start is unauthorized).** Immediately after a
  simulated `:inference`/`:embedder` process restart, `guard()` refuses
  every request until an explicit `onUnlocked` is received, regardless of
  what epoch value the request claims.

### 6.2 Fuzz harness design

A model-based fuzz harness generates random interleavings of a small event
alphabet — `{Unlock, LockUserRequested, LockIdleTimeout, EditType,
StartIngestBatch, StartGenerate, StartEmbed, KillInferenceProcess,
KillEmbedderProcess, DocumentsProviderRead}` — against a simulated clock
(`TestScope`/`advanceTimeBy`, matching `E3.I3`'s existing `UnlockManagerTest`
pattern), driving:

- `:app`-side logic directly (real `SessionState`, real `LockObserver`
  registrations, `WorkManagerTestInitHelper` for a fake-but-real
  WorkManager, per `E5.I10`'s existing test setup).
- `:inference`/`:embedder`-side logic via a hand-rolled in-process fake
  `Binder`/AIDL stub pair (no real cross-process IPC in the fuzz loop —
  that is reserved for the instrumented tests below, since real Binder
  round-trips are too slow for a fuzz budget of thousands of interleavings
  per CI run).

After each generated sequence, all six invariants in §6.1 are checked
against the full event trace. On failure, the harness shrinks the
interleaving (standard property-test shrinking: drop events, compress
timing gaps) to the minimal reproducing sequence and emits it as a
regression fixture — this is the mechanism that would have caught the
original `requiresDeviceIdle`-vs-lock deadlock, since "every generated
sequence containing `LockIdleTimeout` immediately followed by
`StartIngestBatch`'s constraint check never actually starts" is exactly the
kind of pattern a fuzz-with-invariant-checking run surfaces, versus a
example-based test that has to already know to write that specific case.

### 6.3 Instrumented tests (real IPC, real WorkManager, on-device/emulator)

- `LockDuringIngestBatchTest` — start a real ingest batch against
  `:embedder`, call `lock(USER_REQUESTED)` mid-batch, assert
  `onSessionLocking` is observed by `:embedder` within a small bound (e.g.
  50 ms — this is a same-device Binder call, not network), and the batch
  either completes within its ≤30s budget or is cancelled with its
  shared-memory fds closed (checked via `/proc/<pid>/fd` leak-check, same
  technique as `POST_REVIEW_RESOLUTIONS.md`'s `FdOwnershipTest`).
- `LockDuringGenerateTest` — start `generate()`, lock mid-stream, assert
  `onDone(stopReason = CANCELLED)` is delivered to the client callback and
  that `skein_ctx_free_secure`'s zero-then-free path ran (instrumented via
  a dev-only counter/hook, matching the pattern of
  `POST_REVIEW_RESOLUTIONS.md`'s `PostMmapDigestTest` dev-only shim).
- `EditorFlushOnLockTest` — type into the editor, trigger
  `LockIdleTimeout`, assert the vault (re-opened on next simulated unlock)
  contains the typed content without any user-initiated save.
- `EditorFlushTimeoutTest` — inject a test-only I/O stall (a fault-
  injecting `SQLiteConnection` wrapper compiled only in `dev`/`androidTest`
  builds, matching the `dev`-build-type-only pattern already established
  by `POST_REVIEW_RESOLUTIONS.md`'s `PostMmapDigestTest`) so the primary
  flush exceeds `budgetMillis`; assert a `recovery_drafts` row exists and
  is surfaced on next unlock (I5).
- `WorkManagerNeverEnqueuesWhileLockedTest` — the direct regression test
  for the bead's exact defect: assert that no `skein-ingest` work is ever
  in `ENQUEUED`/`RUNNING` state while `phase == LOCKED`, across a sequence
  that includes a `documents.updated_at` trigger firing while locked
  (queued in `ingest_queue`, but not dispatched as work until unlock).
- `IsolatedProcessColdStartUnauthorizedTest` — kill `:embedder` via
  `Process.killProcess` (dev-only, matching
  `POST_REVIEW_RESOLUTIONS.md`'s `ServiceDeathRecoveryTest`), restart it,
  and send an `embed()` request carrying a stale (pre-kill) epoch before
  any `onUnlocked` — assert `ErrorCode.SESSION_LOCKED` (I6).

### 6.4 Failure modes enumerated

- **Process death mid-`LOCKING`.** If `:embedder` is killed by the LMK
  while `onSessionLocking` was in flight, there is nothing to observe the
  cancellation acknowledgment from — this is fine, because `:app`'s
  `onLocking` deadline (`budgetMillis`) elapses regardless and the vault
  key is zeroed on schedule either way; the service's cold-start default
  (`AUTHORIZED_EPOCH_NONE`, §5.3) means a restarted `:embedder` cannot
  accidentally resume the cancelled work.
- **`:app` process death mid-flush.** SQLite WAL mode (already the vault's
  mode) means a crash mid-write does not corrupt the DB; the transaction
  either committed or did not. The `recovery_drafts` fallback (§4.3) is a
  belt-and-suspenders measure for the *timeout* case, not the crash case —
  a crash simply loses whatever was mid-transaction, matching normal SQLite
  durability guarantees, and is out of scope for this design (it is not a
  lock-specific failure mode).
- **ANR risk from blocking on flush.** The flush in §4.3 runs off the main
  thread; `lock()` itself must not block the caller (typically a
  `BroadcastReceiver` for screen-off, or `UnlockManager`'s own idle-timer
  coroutine) past a short bound. `budgetMillis` bounds the *observers'*
  work, not the caller of `lock()`, which should return immediately after
  kicking off the `LOCKING` sequence asynchronously — `lock()` is
  fire-and-forget from the caller's perspective; UI that needs to know
  "are we fully locked yet" observes `SessionState.phase` instead of
  awaiting `lock()`'s return.
- **Clock skew / epoch source.** `SessionEpoch` is an in-process monotonic
  counter (e.g., `SystemClock.elapsedRealtimeNanos()`-seeded or a simple
  incrementing `Long` persisted nowhere), never a wall-clock timestamp —
  this avoids any issue with clock changes, NTP sync, or timezone, and
  matches the property that epochs only need to be comparable within a
  single boot session (isolated services are re-bound, and thus
  re-authorized, on every `:app` process restart anyway).
- **StrongBox unavailable (fallback to TEE).** Orthogonal to this design —
  `E2.I3` already handles `StrongBoxUnavailableException` by falling back
  to a non-StrongBox Keystore key. Every invariant and test in this section
  applies identically regardless of which hardware backing produced the
  wrapped key; this design operates entirely on the *unwrapped* key's
  lifetime in `:app`'s process memory, which is independent of StrongBox
  vs. TEE.
- **A batch that exceeds its 30s target.** Treated as a bug in the batch
  sizer, not a security exception — the ≤30s target (§3.2) is a design
  budget for cancellation latency, not a hard cap enforced by killing the
  batch at 30s during normal (non-lock) operation. If it is exceeded during
  a lock event, §4.2's per-chunk cancellation checkpoint still applies
  (cancellation granularity is per-chunk, not per-batch), so the actual
  cancellation latency in the worst case is bounded by one chunk's
  processing time (~100–150 ms per spec §7.1), not by the batch's total
  length.

---

## 7. Plan-doc amendments needed

**Not applied by this document.** These are deltas for the coordinator to
apply to `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` at
implementation time, listed in the same style as
`POST_REVIEW_RESOLUTIONS.md` §5. Quoted fragments are the plan's current
text, reproduced only to identify the edit point — the plan document itself
is not modified here.

1. **`E5.I10` (`IngestWorker`)** — remove `requiresDeviceIdle=true` from
   the periodic `Constraints`. Current text: *"`Constraints(requiresCharging=true,
   requiresDeviceIdle=true, requiredNetworkType=NOT_REQUIRED)`"*. New:
   periodic scheduling is dropped in favor of one-time, expedited
   `IngestWorker` enqueues triggered by `SessionState` transitioning to
   `UNLOCKED` and by `documents.updated_at` while already unlocked (§3.2).
   `requiresCharging` becomes a batch-size input read at run time via
   `BatteryManager`, not a `Constraints` gate. Retire the "unique periodic
   work `skein-ingest`... `ExistingPeriodicWorkPolicy.UPDATE`" framing in
   favor of `ExistingWorkPolicy.APPEND_OR_REPLACE` one-time requests.
2. **`E5.I10` acceptance criteria** — replace *"Locked vault →
   `Result.retry()` without touching the queue"* with *"Locked vault →
   `IngestScheduler` does not enqueue any `WorkRequest` at all (I2, §6.1);
   `ingest_queue` rows accumulate but no worker runs until `onUnlocked`."*
   Add the new invariant tests from §6.1/§6.3 (`WorkManagerNeverEnqueuesWhileLockedTest`,
   `LockDuringIngestBatchTest`) as acceptance criteria.
3. **`E5.I19` (on-device ingest validation)** — current acceptance
   criterion *"Constrained worker verified not to run unplugged (`adb
   shell dumpsys jobscheduler` shows constraints unsatisfied)"* tested only
   the charging half of the original defect. Add a criterion that
   specifically exercises lock/unlock cycling during the 1k-note fixture
   run and confirms indexing resumes from the queue (not restart) after
   each unlock, matching the bead's decision note point (4): *"on next
   unlock, indexing resumes from queue, not restart."*
4. **`E3.I3` (`UnlockManager`)** — description currently ends the lock
   sequence at *"`VaultManager.close()`, `InferenceEngine.unload()`... zero
   the key array, clear any cached decrypted attachments."* Expand to the
   full ordered sequence in §4 of this document: push
   `onSessionLocking`/`onSessionLocked` to both isolated services (§4.1,
   §4.2), flush-or-recovery-draft the editor (§4.3) *before* closing the
   vault, set `cipher_memory_security = ON` on the SQLCipher connection
   (§4.4), and only then zero the key. Introduce the `SessionPhase.LOCKING`
   intermediate state (§5.1) — `E3.I3`'s current states are `Locked →
   Unlocking → Unlocked(since) → Locked`, missing the bounded
   flush-and-notify window this design requires.
5. **`E3.I14` (Lock policy settings)** — add a line to the Settings ›
   Indexing copy (already referenced by this item's deps on `E6.I14`)
   explaining that indexing runs during unlocked sessions, not in the
   background while locked — the direct user-facing consequence of §2.1's
   accepted con.
6. **AIDL contract (`E0.I16`, and the `pn1l` v2 amendments already listed
   in `POST_REVIEW_RESOLUTIONS.md` §5.1)** — add `onSessionLocking`,
   `onSessionLocked` to both `IInferenceService` and `IEmbedderService`,
   and add `cancel(requestId)` to `IEmbedderService` (currently absent —
   `POST_REVIEW_RESOLUTIONS.md` §3.3 defined `embed` as synchronous with no
   cancellation path). Add `sessionEpoch: Long` to `LoadRequest`,
   `GenerateRequest`, `EmbedRequest`, and any other request Parcelable that
   reaches plaintext-capable code. Add `ErrorCode.SESSION_LOCKED = 11`.
7. **`E4.I3`/`E4.I4` (`:inference` implementation) and `E5.I1`
   (`:embedder` implementation)** — implement `IsolatedSessionGate` (§5.3)
   as the first check in every AIDL entry point; implement
   `skein_ctx_free_secure` (native zero-then-free of the KV cache buffer,
   §4.5) as a new small native addition alongside the existing
   `llama_free` call site.
8. **New migration `006_recovery_drafts.sql`** — `recovery_drafts(document_id
   TEXT, captured_at INTEGER, body_md TEXT, PRIMARY KEY (document_id,
   captured_at))`, referenced by §4.3. Numbering assumes migrations 003–005
   from `POST_REVIEW_RESOLUTIONS.md` §1/§2/§4 land first; renumber if the
   coordinator sequences differently.
9. **New plan item, `E3.I3b` or similar** — `SessionState`/`LockObserver`
   registry (§5.1) as a distinct, testable unit separate from
   `UnlockManager` itself (or as an expansion of `UnlockManager` — the
   coordinator should decide based on how much of `E3.I3`'s existing test
   suite would need rewriting either way).
10. **`E7.*` (editor)** — whichever plan item owns `EditorViewModel`'s
    autosave/debounce logic gains the force-flush-on-lock hook (§4.3) and
    the recovery-draft prompt UI.
11. **`E10` (testing)** — add a new item (e.g. `E10.I19`, following the
    numbering convention of `POST_REVIEW_RESOLUTIONS.md`'s `E10.I18`) for
    the fuzz harness in §6.2 and the invariant checks in §6.1, plus the
    instrumented tests in §6.3.

All amendments are compatible with the four decisions already locked by
`POST_REVIEW_RESOLUTIONS.md` (`wa1l`, `3xyu` — this document *is* `3xyu`'s
detailed design — `4pqj`, `vhtu`) and do not reopen any of them.

---

**End of document.**
