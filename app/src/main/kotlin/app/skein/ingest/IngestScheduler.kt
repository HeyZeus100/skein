// E5.I10 (skein-7v3) — `IngestScheduler`: when ingest work is enqueued, when
// it is cancelled, and the authorised entry point (`IngestRunner`) the
// `IngestWorker` calls into. Implements the amended `E5.I10`
// (`docs/design/LOCK_POLICY_INDEXING.md` §3.2, §4.2, §5.1, §7.1):
//
//   • Trigger, not schedule. A pass is enqueued (a) when `VaultBootstrap`
//     exposes an open session — i.e. `bringUp` succeeded after an unlock —
//     and (b) on every document change while that session is open,
//     debounced. Both come from one subscription to the session's
//     `observeTimeline` flow: its first emission (immediate on subscribe)
//     is the open-session trigger and is enqueued at once, every later
//     emission is a document change and is debounced. Doing it as one flow
//     rather than "enqueue, then subscribe and drop the first emission"
//     closes a race — a change landing between the enqueue and the
//     subscription would be folded into the dropped first emission and
//     never trigger a pass; this way it is drained by the first pass.
//     Nothing is ever enqueued while locked (invariant I2): the enqueue
//     path requires a live `AuthorizationToken`, and the subscription
//     lives only as long as the session.
//   • Cancel before the vault closes. This is a HIGH-priority `LockObserver`:
//     `UnlockManager` runs HIGH observers before LOW ones, and
//     `VaultBootstrap`'s session-closing observer is LOW, so the unique
//     work is cancelled (and the worker's coroutine with it) before the
//     connections go away and before the key is zeroed. Verified by
//     `IngestSchedulerTest`'s ordering assertion.
//   • Epoch-bound runs. `runPending(sessionEpoch)` refuses unless the vault
//     is `Unlocked` under exactly that epoch and a session is open; the
//     pipeline's pace callback re-checks both before every document, so a
//     lock landing mid-batch stops the pass at the next document boundary
//     (`IngestPace.LOCKED`) even if the WorkManager cancel is still in
//     flight.
//   • The idle timer is never touched (`UnlockManager.poke` is not called
//     here): background ingest must not keep the vault open past the
//     user's own inactivity timeout.
//   • `documentRevisions_gc` (skein-a2yr, POST_REVIEW_RESOLUTIONS.md §1.2
//     step 4): the first authorized `runPending` call for a given
//     `sessionEpoch` also runs `VaultRepository.sweepUnreferencedRevisions()`
//     before the ingest pipeline, at most once per unlocked session — see
//     [sweepRevisionsOnceForEpoch]. This is the "nightly-during-unlock" sweep
//     `LOCK_POLICY_INDEXING.md` requires run inside the authorized-unlock
//     pass, never at lock time or as a reason to extend the key's lifetime:
//     it runs under the same `sessionEpoch` authorization as the ingest
//     pass it shares `runMutex` and the `Locked`/stale-epoch bail-out with,
//     and [onLocked] resets the once-per-epoch gate so the next unlock gets
//     its own sweep.
//   • Bounded retries. `ingest_queue.attempts` (migration 008, skein-zx15)
//     is a persisted, per-document counter read and written through
//     `session.repository`, so a poisoned document is dropped after three
//     failing passes total — not per unlocked session. This scheduler no
//     longer owns an in-memory counter to reset on lock (the pre-008
//     `IngestAttempts` stand-in it used to hold did, and reset in
//     `onLocked`; that reset is gone along with the field, since the
//     persisted count is meant to survive the lock/unlock boundary it used
//     to be cleared by).
//
// A cold start sweeps whatever unique work a previous process left behind
// (`port.cancel()` in `init`): the vault is locked on every process start.

package app.skein.ingest

import app.skein.core.model.SkeinLog
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository

/** Counts only — safe for `SecureNotification` (E3.I8 / skein-fsn) and Settings › Indexing. */
data class IngestProgress(
    val running: Boolean = false,
    val processed: Int = 0,
    val vectorsPending: Int = 0,
)

/** Builds the pipeline for one pass: the open session and the pace callback. */
typealias IngestPipelineFactory = (VaultSession, () -> IngestPace) -> IngestPipeline

/**
 * See the file header.
 *
 * @param unlockManager source of `Unlocked`/epoch truth; this scheduler registers itself as a lock observer.
 * @param session `VaultBootstrap.session` — non-null exactly while the vault is open.
 * @param port WorkManager (or a recording fake).
 * @param pacer thermal/battery signal for the pass.
 * @param pipelines builds the pipeline for a session (`IngestPipelines.forSession`).
 * @param scope where [start]'s subscription runs (the process-wide vault scope).
 * @param debounceMillis coalescing window for document-change triggers.
 */
class IngestScheduler(
    private val unlockManager: UnlockManager,
    private val session: StateFlow<VaultSession?>,
    private val port: IngestWorkPort,
    private val pacer: IngestPacer,
    private val pipelines: IngestPipelineFactory,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : IngestRunner,
    LockObserver {
    private val progressState = MutableStateFlow(IngestProgress())

    /** Progress of the current/last pass, reset to idle on lock. */
    val progress: StateFlow<IngestProgress> = progressState.asStateFlow()

    private val runMutex = Mutex()

    /** [sessionEpoch] the `documentRevisions_gc` sweep last ran for, or null. Guards [sweepRevisionsOnceForEpoch]. */
    @Volatile
    private var lastSweptEpoch: Long? = null

    init {
        port.cancel()
        unlockManager.addLockObserver(this)
    }

    /**
     * Subscribes to the session and its document changes (file header): the
     * timeline's first emission enqueues immediately (zero debounce), every
     * later one after [debounceMillis]. Call once.
     */
    @OptIn(FlowPreview::class)
    fun start(): Job =
        scope.launch {
            session.collectLatest { open ->
                if (open == null) return@collectLatest
                open.repository
                    .observeTimeline(TimelineFilter(), limit = 1)
                    .withIndex()
                    .debounce { if (it.index == 0) 0L else debounceMillis }
                    .collect { enqueueIfAuthorized() }
            }
        }

    /** User-triggered "index now" (E6.I14): the same enqueue path, no-op while locked. */
    fun indexNow() = enqueueIfAuthorized()

    // ---- IngestRunner --------------------------------------------------

    override suspend fun runPending(sessionEpoch: Long): IngestOutcome =
        runMutex.withLock {
            val open = session.value
            if (open == null || !authorized(sessionEpoch)) return@withLock IngestOutcome.Locked(0, 0)
            sweepRevisionsOnceForEpoch(sessionEpoch, open.repository)
            val pipeline =
                pipelines(open) {
                    if (session.value !== open || !authorized(sessionEpoch)) IngestPace.LOCKED else pacer.pace()
                }
            pacer.begin()
            try {
                progressState.value = IngestProgress(running = true)
                val outcome = pipeline.run { processed -> progressState.update { it.copy(processed = processed) } }
                progressState.value =
                    IngestProgress(
                        running = false,
                        processed = outcome.processed,
                        vectorsPending = outcome.vectorsPending,
                    )
                outcome
            } finally {
                progressState.update { it.copy(running = false) }
                pacer.end()
            }
        }

    // ---- LockObserver --------------------------------------------------

    override val priority: LockObserverPriority = LockObserverPriority.HIGH

    override suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        port.cancel()
    }

    override fun onLocked(epoch: Long) {
        progressState.value = IngestProgress()
        // Next unlock gets a fresh sessionEpoch and so must get its own sweep.
        lastSweptEpoch = null
    }

    override fun onUnlocked(epoch: Long) = Unit

    // ---- internals -----------------------------------------------------

    /**
     * Runs `documentRevisions_gc` (skein-a2yr) at most once per unlocked
     * session: a no-op unless this is the first authorized [runPending] call
     * seen for [sessionEpoch] this unlock. Always called from inside
     * [runMutex] (only [runPending] calls this), and only once the caller
     * has already confirmed the session is authorized under [sessionEpoch]
     * — this never runs while locked or under a stale epoch.
     *
     * The count-only log line is the only thing this ever logs — spec §9
     * forbids logging content, and `sweepUnreferencedRevisions` never
     * returns anything but a count.
     */
    private suspend fun sweepRevisionsOnceForEpoch(
        sessionEpoch: Long,
        repository: VaultRepository,
    ) {
        if (lastSweptEpoch == sessionEpoch) return
        lastSweptEpoch = sessionEpoch
        val removed = repository.sweepUnreferencedRevisions()
        SkeinLog.i(TAG, "documentRevisions_gc: removed $removed unreferenced revision(s)")
    }

    private fun enqueueIfAuthorized() {
        val epoch = currentEpoch() ?: return
        port.enqueue(epoch)
    }

    private fun currentEpoch(): Long? = (unlockManager.state.value as? UnlockState.Unlocked)?.token?.epoch

    private fun authorized(sessionEpoch: Long): Boolean = currentEpoch() == sessionEpoch

    companion object {
        /** Coalesces a burst of edits (autosave ticks) into one pass. */
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 2_000L

        /** `SkeinLog` tag for the `documentRevisions_gc` count-only log line. */
        private const val TAG: String = "IngestScheduler"
    }
}
