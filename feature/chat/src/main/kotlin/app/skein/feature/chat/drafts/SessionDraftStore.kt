package app.skein.feature.chat.drafts

import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** A revision is local to one key in one session; it must never enter saved instance state. */
public data class DraftSnapshot(
    val draft: ChatDraft,
    val version: Long,
)

public sealed interface DraftLoadState {
    public data object Loading : DraftLoadState

    public data class Ready(
        val snapshot: DraftSnapshot,
        val saveFailed: Boolean = false,
    ) : DraftLoadState

    public data object LoadError : DraftLoadState

    public data object Closed : DraftLoadState
}

/**
 * D7 M6/M7: session memory and encrypted rows are the only draft stores. No content is logged.
 *
 * The app's session owner supplies synchronous callbacks reading the actual lock state, registers a
 * LOW observer forwarding [onLocking]/[onLocked], and calls [close] when removing that observer.
 * A mapped/collected phase flow must not be used: its scheduling delay would admit a late write.
 * The owner must close this store before cancelling its session scope. No composable owns this store.
 *
 * [commitSend] serializes draft flushes with atomic USER append and draft deletion. Until the turn
 * controller uses that gate, the UI must not clear drafts on Send.
 */
public class SessionDraftStore(
    private val repository: VaultRepository,
    private val epoch: Long,
    private val unlockedEpoch: () -> Long?,
    private val lockingEpoch: () -> Long?,
    sessionScope: CoroutineScope,
    private val debounceMillis: Long = 2_000,
) : AutoCloseable {
    private class Entry {
        val state = MutableStateFlow<DraftLoadState>(DraftLoadState.Loading)
        var dirty = false
        var version = 0L
        var consumedVersion = -1L
        var loadJob: Job? = null
        var debounceJob: Job? = null
    }

    private val guard = Any()
    private val entries = mutableMapOf<ChatDraftKey, Entry>()
    private val flushMutex = Mutex()
    private val activeFlushes = mutableSetOf<Job>()
    private val ownerJob = SupervisorJob(sessionScope.coroutineContext[Job])
    private val scope = CoroutineScope(sessionScope.coroutineContext + ownerJob)
    private val closedFlow = MutableStateFlow<DraftLoadState>(DraftLoadState.Closed).asStateFlow()
    private var closed = false
    private var lockingStarted = false

    init {
        require(debounceMillis >= 0) { "Invalid draft debounce" }
        ownerJob.invokeOnCompletion { close() }
    }

    /** Always consults the source phase synchronously; a late keystroke cannot become a dirty draft. */
    public val isWritable: Boolean
        get() = synchronized(guard) { admitsUnlocked() }

    public fun state(key: ChatDraftKey): StateFlow<DraftLoadState> =
        synchronized(guard) {
            if (closed) return@synchronized closedFlow
            entries[key]?.let { return@synchronized it.state.asStateFlow() }
            if (!admitsUnlocked()) return@synchronized closedFlow
            val entry = Entry()
            entries[key] = entry
            startLoad(key, entry)
            entry.state.asStateFlow()
        }

    public fun retryLoad(key: ChatDraftKey): Boolean =
        synchronized(guard) {
            val entry = entries[key] ?: return@synchronized false
            if (!admitsUnlocked() || entry.state.value != DraftLoadState.LoadError) return@synchronized false
            entry.state.value = DraftLoadState.Loading
            startLoad(key, entry)
            true
        }

    /** Returns the accepted revision; null means loading, failed read, lock, or closed session. */
    public fun update(
        key: ChatDraftKey,
        draft: ChatDraft,
    ): DraftSnapshot? =
        synchronized(guard) {
            if (!admitsUnlocked()) return@synchronized null
            val entry = entries[key] ?: return@synchronized null
            val ready = entry.state.value as? DraftLoadState.Ready ?: return@synchronized null
            if (ready.snapshot.draft == draft) return@synchronized ready.snapshot
            val snapshot = DraftSnapshot(draft, ++entry.version)
            entry.state.value = DraftLoadState.Ready(snapshot)
            entry.dirty = true
            scheduleSave(key, entry)
            snapshot
        }

    /**
     * The controller's admission seam. [appendUser] may create the first chat, then appends USER and
     * returns its identity. It must not wait for retrieval or an engine, or call back into this store.
     * The repository postcommit hook acknowledges memory before a cancelled dispatcher return can
     * interrupt the caller. A failed/rolled-back transaction never consumes the captured draft.
     */
    public suspend fun <T> commitSend(
        key: ChatDraftKey,
        sentVersion: Long,
        appendUser: suspend () -> T,
    ): T =
        coroutineScope {
            val sendJob = coroutineContext[Job]!!
            synchronized(guard) {
                check(admitsUnlocked()) { "Draft session unavailable" }
                activeFlushes += sendJob
            }
            try {
                flushMutex.withLock {
                    repository.transaction {
                        synchronized(guard) {
                            val entry = entries[key]
                            check(
                                admitsUnlocked() &&
                                    entry != null &&
                                    entry.state.value is DraftLoadState.Ready &&
                                    sentVersion > entry.consumedVersion &&
                                    sentVersion <= entry.version,
                            ) { "Draft is not available for sending" }
                        }
                        val result = appendUser()
                        repository.deleteDraft(key)
                        repository.afterTransactionCommit { acknowledgeSent(key, sentVersion) }
                        coroutineContext.ensureActive()
                        result
                    }
                }
            } finally {
                synchronized(guard) { activeFlushes -= sendJob }
            }
        }

    /** Caller holds flushMutex, and this runs only in the repository's synchronous postcommit hook. */
    private fun acknowledgeSent(
        key: ChatDraftKey,
        sentVersion: Long,
    ) = synchronized(guard) {
        if (closed) return@synchronized
        val entry = entries[key] ?: return@synchronized
        val ready = entry.state.value as? DraftLoadState.Ready ?: return@synchronized
        entry.consumedVersion = maxOf(entry.consumedVersion, sentVersion)
        if (ready.snapshot.version != sentVersion) {
            // Invalidate an earlier flush's mark-clean as well as preserving the newer text.
            entry.state.value = DraftLoadState.Ready(DraftSnapshot(ready.snapshot.draft, ++entry.version))
            entry.dirty = true
            if (admitsUnlocked()) scheduleSave(key, entry)
        } else {
            entry.debounceJob?.cancel()
            entry.debounceJob = null
            entry.dirty = false
            entry.state.value = DraftLoadState.Ready(DraftSnapshot(ChatDraft(""), ++entry.version))
        }
    }

    /** Session-owned launch survives a stopped/disposed composer; admission is rechecked at the writer. */
    public fun requestStopFlush() {
        synchronized(guard) {
            if (admitsUnlocked()) scope.launch { flushOnStop() }
        }
    }

    public suspend fun flushOnStop(): Boolean = flush(lockFlush = false)

    /** Called only by the app-owned LOW observer, before quiesce/TEARDOWN. No engine waits here. */
    public suspend fun onLocking(
        lockEpoch: Long,
        budgetMillis: Long,
    ): Boolean {
        synchronized(guard) {
            if (closed || lockEpoch != epoch || lockingEpoch() != epoch) return false
            lockingStarted = true
            entries.values.forEach {
                it.debounceJob?.cancel()
                it.debounceJob = null
            }
        }
        if (budgetMillis <= 0) return false
        return withTimeoutOrNull(budgetMillis) { flush(lockFlush = true) } ?: false
    }

    public fun onLocked(lockEpoch: Long) {
        if (lockEpoch == epoch) close()
    }

    /** Also required on session disposal before onLocked; every previously handed-out flow is scrubbed. */
    override fun close() {
        val flushes =
            synchronized(guard) {
                if (closed) return
                closed = true
                entries.values.forEach { it.state.value = DraftLoadState.Closed }
                entries.clear()
                activeFlushes.toList().also { activeFlushes.clear() }
            }
        ownerJob.cancel()
        flushes.forEach { it.cancel() }
    }

    private fun admitsUnlocked(): Boolean = !closed && !lockingStarted && unlockedEpoch() == epoch

    private fun admitsFlush(lockFlush: Boolean): Boolean =
        !closed && if (lockFlush) lockingStarted && lockingEpoch() == epoch else admitsUnlocked()

    /** Invoked under guard; LAZY ensures the job is registered before an unconfined dispatcher runs it. */
    private fun startLoad(
        key: ChatDraftKey,
        entry: Entry,
    ) {
        entry.loadJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val draft = repository.readDraft(key) ?: ChatDraft("")
                    synchronized(guard) {
                        if (admitsUnlocked() && entries[key] === entry) {
                            entry.state.value = DraftLoadState.Ready(DraftSnapshot(draft, entry.version))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    synchronized(guard) {
                        if (admitsUnlocked() && entries[key] === entry) entry.state.value = DraftLoadState.LoadError
                    }
                }
            }
        entry.loadJob?.start()
    }

    private fun scheduleSave(
        key: ChatDraftKey,
        entry: Entry,
    ) {
        entry.debounceJob?.cancel()
        entry.debounceJob =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(debounceMillis)
                flush(lockFlush = false, onlyKey = key)
            }
        entry.debounceJob?.start()
    }

    private data class Pending(
        val key: ChatDraftKey,
        val snapshot: DraftSnapshot,
    )

    private suspend fun flush(
        lockFlush: Boolean,
        onlyKey: ChatDraftKey? = null,
    ): Boolean =
        coroutineScope {
            val flushJob = coroutineContext[Job]!!
            synchronized(guard) {
                if (!admitsFlush(lockFlush)) return@coroutineScope false
                activeFlushes += flushJob
            }
            try {
                flushMutex.withLock {
                    val pending =
                        synchronized(guard) {
                            if (!admitsFlush(lockFlush)) return@withLock false
                            entries.mapNotNull { (key, entry) ->
                                val ready = entry.state.value as? DraftLoadState.Ready
                                if (entry.dirty && ready != null && (onlyKey == null || onlyKey == key)) {
                                    Pending(key, ready.snapshot)
                                } else {
                                    null
                                }
                            }
                        }
                    if (pending.isEmpty()) return@withLock true
                    try {
                        val admitted =
                            repository.transaction {
                                // A queued ON_STOP/debounce can acquire the writer only after Locking began.
                                if (!synchronized(guard) { admitsFlush(lockFlush) }) return@transaction false
                                for (item in pending) {
                                    coroutineContext.ensureActive()
                                    val current =
                                        synchronized(guard) {
                                            val entry = entries[item.key]
                                            val ready = entry?.state?.value as? DraftLoadState.Ready
                                            return@synchronized !closed &&
                                                entry?.dirty == true &&
                                                ready?.snapshot?.version == item.snapshot.version
                                        }
                                    if (current) repository.writeDraft(item.key, item.snapshot.draft)
                                }
                                coroutineContext.ensureActive()
                                true
                            }
                        if (admitted) {
                            synchronized(guard) {
                                for (item in pending) {
                                    val entry = entries[item.key] ?: continue
                                    val ready = entry.state.value as? DraftLoadState.Ready ?: continue
                                    if (ready.snapshot.version == item.snapshot.version) {
                                        entry.dirty = false
                                        entry.state.value = ready.copy(saveFailed = false)
                                    }
                                }
                            }
                        }
                        admitted
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        synchronized(guard) {
                            for (item in pending) {
                                val entry = entries[item.key] ?: continue
                                val ready = entry.state.value as? DraftLoadState.Ready ?: continue
                                if (ready.snapshot.version == item.snapshot.version) {
                                    entry.state.value = ready.copy(saveFailed = true)
                                }
                            }
                        }
                        false
                    }
                }
            } finally {
                synchronized(guard) { activeFlushes -= flushJob }
            }
        }
}
