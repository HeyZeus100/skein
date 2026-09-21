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
//   • Bounded retries. One `IngestAttempts` counter per unlocked session
//     (the in-memory stand-in for the not-yet-landed `ingest_attempts`
//     column — see that file's header) is shared by every pass of the
//     session and reset on lock (§4.5), so a poisoned document is dropped
//     after three failing passes rather than retried forever.
//
// A cold start sweeps whatever unique work a previous process left behind
// (`port.cancel()` in `init`): the vault is locked on every process start.

package app.skein.ingest

import app.skein.core.rag.ingest.IngestAttempts
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

/** Counts only — safe for `SecureNotification` (E3.I8 / skein-fsn) and Settings › Indexing. */
data class IngestProgress(
    val running: Boolean = false,
    val processed: Int = 0,
    val vectorsPending: Int = 0,
)

/** Builds the pipeline for one pass: the open session, the session's failure counter, and the pace callback. */
typealias IngestPipelineFactory = (VaultSession, IngestAttempts, () -> IngestPace) -> IngestPipeline

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

    /** Per-session failure counter (file header); reset in [onLocked]. */
    private val attempts = IngestAttempts()

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
            val pipeline =
                pipelines(open, attempts) {
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
        attempts.reset()
    }

    override fun onUnlocked(epoch: Long) = Unit

    // ---- internals -----------------------------------------------------

    private fun enqueueIfAuthorized() {
        val epoch = currentEpoch() ?: return
        port.enqueue(epoch)
    }

    private fun currentEpoch(): Long? = (unlockManager.state.value as? UnlockState.Unlocked)?.token?.epoch

    private fun authorized(sessionEpoch: Long): Boolean = currentEpoch() == sessionEpoch

    companion object {
        /** Coalesces a burst of edits (autosave ticks) into one pass. */
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 2_000L
    }
}
