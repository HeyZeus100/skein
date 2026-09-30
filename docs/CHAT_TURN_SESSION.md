# Session-owned chat turns

AL-10c (`skein-xtov.24.10.3`) separates durable admission, execution, and observation.
`ModelServices` creates one `ChatTurnController` and `SessionDraftStore` per vault epoch.
A `ChatViewModel` only observes those services; navigation or Activity recreation cancels
its observers, not a turn. The app bootstrap owns the lock observers independently of UI lifecycle.

Admission resolves the chat's owning Space, instructions, Knowledge setting, sampling
parameters, and selected model before appending USER. A new chat creates its document,
appends USER, and deletes the captured draft version through `SessionDraftStore.commitSend`
in one transaction. Existing chats use the same gate. The post-commit hook publishes the
queue entry and acknowledges the draft even if cancellation prevents the caller's return.
Newer draft edits survive that acknowledgment. The landing navigates only if its original
navigation state still applies; a completed send cannot pull the user out of another Space.

The root landing has one stable reserved draft identity per Space; explicit `NewChatKey`
landings use their navigation draft UUID. Only these identities enter navigation state.
Text, selection, and load failures come from the controlled session composer. A failed read
is disabled with a retry action; sending does not clear the text before its transaction commits.

The FIFO serializes one engine across chats. Every queued USER is already durable. A
turn's history stops immediately before its own committed USER, and its model is prepared
only when it reaches the engine. The normal worker waits for real service readiness before
dispatching another request after cancellation. Exact active-model prompt measurement,
model identity checks, and missing-Knowledge-evidence handling remain in `SendPipeline`.

HIGH synchronously freezes the last published text/citations, stops admission, and schedules
cancellation. LOW joins only independently session-owned jobs for at most 150 ms within
the remaining shared observer deadline. LOW never issues a two-way status or cancel RPC.
It then runs the shared `TurnFinalizer` using the frozen snapshot; it holds no writer lock
while waiting on the engine. Normal completion may write only in the matching Unlocked
phase; LOW may write only in the matching Locking phase. Refused writes do not mark the
record finished. HIGH replaces a pending normal snapshot with the interrupted snapshot.
An acknowledgment immediately after COMMIT makes completion, Stop and LOW idempotent.
Blank output makes no assistant row; a failed or timed-out write has no private retry buffer
after lock. The durable trailing USER presents “No answer was saved” and retry reuses that
row rather than appending another USER.

Session close clears previously returned flows, view-model holders, draft flows, queued
records, prepared-turn references, and finalizer message references before repository
quiescence. Old-epoch jobs cannot republish or dispatch a new request. Immutable strings
inside an already executing suspended/blocking call cannot be forcibly erased; cancellation
and repository quiescence remain cooperative. The lock path never waits for that call's
structured children or extends an idle-lock policy.

Chat history and the stored chat header expose Delete through the shared confirmation
coordinator. `tryBeginDelete(chatId)` atomically reserves an idle chat against send/retry
admission; queued, preparing, generating and finalizing turns refuse deletion (LC-22, L10).
The user must Stop an active answer first. A failed transaction releases that reservation.
The repository's synchronous postcommit hook calls `finishDelete` and
`SessionDraftStore.discardCommitted`: retained turn/draft flows are scrubbed before
repository signals publish, including when cancellation races the return from COMMIT.
Both workspace stacks are then pruned. The repository cascades durable drafts and messages.
Deleting a source also filters live retained context offers; quotes already saved inside
other conversations remain and the confirmation discloses them. Independent notes use
the same coordinator with a session editor registry that pauses and drains autosave;
rollback resumes pending edits, and committed deletion blocks late editor registration.
File-backed extracted notes and attachments remain outside this deletion UI (LC-09).

The focused tests cover durable FIFO admission, navigation/recreation, owner/model/history
snapshots, atomic first send, blank recovery, Stop/lock idempotence, pending-delete
suppression, refused persistence before HIGH, completion waiting for a writer, cancellation
after COMMIT, synchronous old-flow scrubbing, and a genuinely blocking status call on a
separate thread that cannot delay LOW or close. App tests exercise the real bootstrap and
Activity lifecycle. Native/device behavior remains subject to the integrated emulator lane;
these tests do not claim hard preemption of native code or model answer quality.
