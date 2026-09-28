# Session draft service

`SessionDraftStore` (`feature/chat/drafts`) implements the session side of AL-10 / D7 M6–M9.
Its rows and repository contracts are described in `CHAT_DRAFT_STORAGE.md`.
The store is a session service; a composable must never construct or close it.

The app supplies the open repository, immutable session epoch, session coroutine scope,
and two synchronous phase callbacks. `unlockedEpoch()` returns that epoch only while the
actual unlock state is Unlocked. `lockingEpoch()` returns it only while that same session
is Locking. All other states return null. Do not derive these callbacks from an
asynchronously mapped flow: write admission must read the current phase directly.
The app registers a LOW lock observer for the session lifetime, delegates its bounded
`onLocking(epoch, budgetMillis)` and `onLocked(epoch)` calls, and calls `close()` before
removing the observer or cancelling the session scope.

`state(key)` loads an encrypted row and exposes Loading, Ready, LoadError or Closed.
LoadError is not a writable empty draft. An explicit `retryLoad(key)` retries the read
only in the same unlocked epoch. Ready includes a `DraftSnapshot` (text, selection and
session-local version) and a save-failure flag. Errors contain no repository diagnostics,
text or ids. Draft/key string representations are content-free.

`update(key, draft)` returns the accepted snapshot or null when writing is not allowed.
Changed drafts debounce for two seconds. `requestStopFlush()` launches on the session
scope, so ON_STOP does not depend on an active composition. Both normal paths recheck
phase after acquiring the repository writer. The LOW lock path cancels debounces and
flushes all dirty entries in one transaction under its supplied timeout. A failed batch
rolls back. No fallback file, preference, Bundle, log or deferred next-epoch retry exists.
`close()` permanently scrubs every StateFlow previously handed to a subscriber and
cancels pending loads and flushes, including early session disposal before onLocked.

## Atomic send admission

The controller captures Ready.snapshot, then calls:

```kotlin
store.commitSend(key, snapshot.version) {
    // Create the first chat here if needed, then append its USER message.
    // Return the controller's persisted message/chat identity.
}
```

The callback contains repository work only: no model, retrieval, UI or engine waits.
The store uses a shared send/flush mutex and one repository transaction to run that
callback and delete the draft. A non-suspending `afterTransactionCommit` hook acknowledges
memory before cancellation can interrupt the dispatch back to the caller. Rollback never
acknowledges the draft. Already-consumed versions cannot be admitted twice.

If the current version still matches, acknowledgment clears the draft and advances its
version. If the user edited meanwhile, it retains that text, advances the version and
marks it dirty again, even when that newer edit was already flushed before the send
deleted the row. A lock that starts immediately after commit still permits this memory
acknowledgment; it does not create another write. The LOW flush then saves only genuinely
newer text. A delayed old flush cannot resurrect consumed text.

`rememberDraftComposerState(store, key)` is the controlled Compose adapter. It exposes
readiness, errors, enabled state, a TextFieldValue and its change handler, and delegates
ON_STOP. Compose observes only phase/version/error stamps; value reads consult the source
StateFlow synchronously, so close is visible before a collector resumes. Text and selection
come from the store; only the IME composition range is local.
No text Saver or SavedStateHandle is used. The controller/app integration owns connecting
this seam to the composer and send button; the adapter has no direct clear/send action.
