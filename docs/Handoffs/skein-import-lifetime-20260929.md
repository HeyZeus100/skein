# App-owned model import lifetime

Baseline `9fa9f6f`; implements the remaining `.19` lifetime gap without changing
IPC, vault/session contracts, inference isolation or immutable-store integrity.
The coordinator owns integration and all device/CI verification. Fold remains HOLD.

`ModelImportStager` extracts the existing picked-file two-pass hash/copy mechanics;
the existing immutable store still owns staging, dual hashing, atomic promotion,
sealing and recovery. The stager has no vault or inference capability. A cancelled
inspection leaves its sealed copy recoverable. The session's manager checks both
coroutine cancellation and the injected authorization callback before import/adoption
registry access and after inspection. Adoption and fresh-copy registration serialize
within a session so the same sealed file is not inspected twice by those paths.
Adoption scans completed sealed candidates against the authorized vault registry,
including copies already known by the app-owned store. A known copy retains its
original digest expectations and BLAKE3; changed bytes fail before inspection.
The old store-only orphan filter remains unchanged for its other callers.

`ModelImportCoordinator` is one application singleton. It owns the store, copy job,
import admission and content-free progress/result flows. Closing an observer or an
Activity cannot cancel the job. Lock synchronously detaches/cancels the independent
registration capability without joining it. Opaque copy continues; a copy finishing
while locked reports `SAVED_FOR_UNLOCK`, and existing orphan adoption registers it
on the next unlock. A fresh session attaching before copy completion can finish
registration. No old session can detach a newly attached manager.

`ModelImportService` enters Android foreground before starting work. Its `dataSync`
type covers local imports. Both foreground-service permissions are declared;
notification updates require `POST_NOTIFICATIONS`, while the mandatory initial FGS
notification remains generic and `VISIBILITY_SECRET` even with that permission
denied. The URI's read grant is forwarded to the service; no URI/model names appear
in notifications or retained result state. Android timeout/destruction requests copy
cancellation without releasing admission until the copy's actual cleanup. Reads are
cooperative between chunks; a provider blocked inside a read cannot be forcibly
interrupted by coroutine cancellation. No hard native/provider preemption is claimed.

The service is `START_NOT_STICKY`, never starts from boot, and never replays a URI
following process death. A promoted sealed copy survives for adoption; interrupted
`.tmp` bytes remain unloadable and the existing store handles them on a later retry.
No progress/result persistence across process death is claimed. Every admission also
has a monotonically increasing process-local token forwarded in the service intent.
Only that token may consume or cancel its pending/running copy. Service completion
uses the newest delivered Android start ID, preventing duplicate-intent foreground
leaks; an old service destruction cannot clear a newer pending or running import.
Malformed/stale intents cannot consume a different admission.

## Exact integration hooks

The root inference agent owns `ModelServices` edits:

```kotlin
// Optional constructor argument preserves existing fixture construction.
public val imports: ModelImportCoordinator? = null

// Production forSession, replacing the per-session ImmutableModelStore construction:
val imports = ModelImportCoordinator.forApplication(context)
val store = imports.store

// ModelManager constructor addition:
canUseRegistry = { sessionEpoch() == epoch && !isLocking() && lockEpoch.get() != epoch }

// Return ModelServices(..., imports = imports).
// In unlocked(), after pushOnSessionUnlocked(epoch), before launching adoption:
imports?.attach(manager) {
    // Recheck this session's authorization before reading its registry/cache.
    manifestCache.refresh()
}

// First in HIGH freezeTurns; also onLocking, onLocked, closeSessionState:
imports?.detach(manager)
```

The callback must check the production session token/lock marker immediately before
`manifestCache.refresh()`. The coordinator checks coroutine cancellation before
calling it, and `manager.setDefault` independently checks session authorization.
The existing repository close/quiescence remains the boundary for an already-entered
SQL operation; this change does not invent a cross-module atomic SQL lock contract.

The exact UI hook is [skein-import-lifetime-shell.patch](skein-import-lifetime-shell.patch).
It applies to baseline `9fa9f6f` and changes only NavShell observation/admission; the
coordinator applies/adapts it to its newer shell and runs formatting/UI tests. It
removes the UI-owned import coroutine and UI-owned default/cache writes. `Done`
outcomes are `IMPORTED`, `SAVED_FOR_UNLOCK`, `REFUSED`, `FAILED`; dismissal clears the
application result. The existing rescued-model observer remains in place.

## Validation and remaining acceptance

New host regressions cover observer disposal, lock during copy/inspection, single
admission, stale-session detach, service-start refusal, no URI replay after process
death, generic foreground notification with permission denied, duplicate/stale service starts,
new admission racing old service destruction, timeout cancellation, both provider streams
closing, no locked registry publication, and sealed-file adoption by a fresh store.
Existing FD/store/registry/adoption regressions remain unchanged.

The manifest audit self-test reaches only its deliberate rogue-export failure;
the two explicitly authorized foreground permissions pass its allowlist. Gradle,
explicit ktlint and focused test results must be recorded by the integration agent
at the integrated source SHA. No emulator was operated by this agent.

Required coordinator runtime requests: import a fixture, recreate/finish the Activity
while copying and re-observe progress; lock during copy, then unlock and confirm one
registry row/default without a second provider copy; lock during real isolated
inspection and verify bounded teardown/no later registry write; deny notification
permission and verify foreground execution plus no sensitive notification text;
kill the process in copy and after sealing, verifying `.tmp` rejection and sealed
adoption respectively. Inspect actual XML rather than top-level workflow status.

Android references: [dataSync service type](https://developer.android.com/develop/background-work/services/fgs/service-types),
[notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission),
[dataSync timeout](https://developer.android.com/about/versions/15/behavior-changes-15).
These justify platform wiring only; they are not runtime acceptance evidence.
