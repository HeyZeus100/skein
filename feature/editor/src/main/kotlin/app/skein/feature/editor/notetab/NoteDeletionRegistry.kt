package app.skein.feature.editor.notetab

import androidx.compose.runtime.mutableStateMapOf
import app.skein.core.model.DocId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unlocked-session editor participants for confirmed deletion. Owned by the
 * host and shared across panes; UI-thread calls synchronously close write
 * admission before the coordinator awaits already-admitted work.
 */
public class NoteDeletionRegistry {
    private val monitor = Any()
    private val editors = mutableMapOf<DocId, MutableSet<NoteTabState>>()
    private val pending = mutableStateMapOf<DocId, PendingNoteDeletion>()
    private val sourceChanges = mutableMapOf<DocId, PendingNoteSourceDetachment>()
    private val detachedSources = mutableMapOf<DocId, MutableSet<DocId>>()
    private val committedIds = mutableStateMapOf<DocId, Boolean>()

    public fun isDeleting(id: DocId): Boolean = synchronized(monitor) { id in pending || id in committedIds }

    internal fun register(state: NoteTabState): DisposableHandle {
        synchronized(monitor) {
            editors.getOrPut(state.docId) { mutableSetOf() }.add(state)
            detachedSources[state.docId]?.forEach(state::sourceWasDetached)
            when {
                state.docId in committedIds -> state.pauseForDeletion()
                state.docId in pending -> pending.getValue(state.docId).include(state)
                state.docId in sourceChanges -> sourceChanges.getValue(state.docId).include(state)
                state.docId in detachedSources -> {
                    state.pauseForDeletion()
                    state.scheduleSourceReload()
                }
            }
        }
        return DisposableHandle {
            synchronized(monitor) {
                editors[state.docId]?.let { states ->
                    states.remove(state)
                    if (states.isEmpty()) editors.remove(state.docId)
                }
            }
        }
    }

    /** Confirmation has been accepted. Canceling a dialog must never call this. */
    public fun beginDelete(id: DocId): PendingNoteDeletion =
        synchronized(monitor) {
            check(id !in sourceChanges) { "Source metadata is still refreshing" }
            pending.getOrPut(id) {
                PendingNoteDeletion { committed ->
                    synchronized(monitor) {
                        if (committed) committedIds[id] = true
                        pending.remove(id)
                    }
                }.also { deletion ->
                    editors[id]?.forEach(deletion::include)
                }
            }
        }

    /** Reserve surviving AIOUT writers; this never marks their document deleted. */
    public fun beginSourceDetachment(
        id: DocId,
        sourceId: DocId,
    ): PendingNoteSourceDetachment =
        synchronized(monitor) {
            check(id !in pending && id !in committedIds && id !in sourceChanges)
            PendingNoteSourceDetachment(
                sourceId = sourceId,
                committed = {
                    synchronized(monitor) { detachedSources.getOrPut(id) { mutableSetOf() }.add(sourceId) }
                },
                finished = { synchronized(monitor) { sourceChanges.remove(id) } },
            ).also { change ->
                sourceChanges[id] = change
                editors[id]?.forEach(change::include)
            }
        }
}

/** A single id's paused writers, retained even if a pane is disposed mid-delete. */
public class PendingNoteDeletion internal constructor(
    private val finished: (committed: Boolean) -> Unit,
) {
    private val monitor = Any()
    private val states = mutableSetOf<NoteTabState>()
    private var complete = false
    private var rolledBack = false

    internal fun include(state: NoteTabState) {
        synchronized(monitor) {
            if (rolledBack) return
            state.pauseForDeletion()
            if (!complete) states.add(state)
        }
    }

    /** Must complete before the repository delete starts. */
    public suspend fun awaitIdle() {
        synchronized(monitor) { states.toList() }.forEach { it.awaitDeletionIdle() }
    }

    /** After commit, keep every former writer blocked through disposal/lock. */
    public fun commit() {
        synchronized(monitor) {
            if (complete) return
            complete = true
            states.clear()
        }
        finished(true)
    }

    /** Resume pending edits after rollback; a closed vault cannot be written through this old session. */
    public suspend fun rollback() {
        val resumed =
            synchronized(monitor) {
                if (complete) return
                complete = true
                rolledBack = true
                states.toList().also { states.clear() }
            }
        resumed.forEach { it.resumeAfterDeletionFailure() }
        try {
            withTimeoutOrNull(ROLLBACK_MILLIS) {
                resumed.forEach { state ->
                    try {
                        state.flushAfterDeletionFailure()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // A closed/failed writer must not hide the original delete
                        // failure or stop other participants resuming.
                    }
                }
            }
        } finally {
            finished(false)
        }
    }

    private companion object {
        const val ROLLBACK_MILLIS = 2_000L
    }
}

/** Pauses all panes until a committed source detach has been reconciled with their dirty buffers. */
public class PendingNoteSourceDetachment internal constructor(
    private val sourceId: DocId,
    private val committed: () -> Unit,
    private val finished: () -> Unit,
) {
    private val monitor = Any()
    private val states = mutableSetOf<NoteTabState>()
    private var didCommit = false
    private var complete = false
    private var rolledBack = false
    private var conflictingDrafts = false

    internal fun include(state: NoteTabState) {
        synchronized(monitor) {
            if (rolledBack) return
            state.pauseForDeletion()
            if (didCommit) state.sourceWasDetached(sourceId)
            if (complete) state.scheduleSourceReload() else states.add(state)
        }
    }

    public suspend fun awaitIdle() {
        val drained = mutableSetOf<NoteTabState>()
        while (true) {
            val next =
                synchronized(monitor) {
                    if (states.count { it.hasPendingEdits() } > 1) {
                        conflictingDrafts = true
                        error("Multiple panes have pending edits")
                    }
                    states.filterNot { it in drained }
                }
            if (next.isEmpty()) return
            next.forEach { it.prepareSourceDetachment(sourceId) }
            drained += next
        }
    }

    /** Recheck inside the file transaction; never wait for an editor while SQLite is locked. */
    public fun isReadyForCommit(): Boolean = synchronized(monitor) { states.all { it.sourceDetachmentReady() } }

    /** Called at COMMIT, before observers can create another pane with a stale load. */
    public fun commit() {
        synchronized(monitor) {
            if (didCommit || complete) return
            didCommit = true
            states.forEach { it.sourceWasDetached(sourceId) }
        }
        committed()
    }

    /** The coordinator bounds this operation. Timeout/lock leaves old writers safely paused. */
    public suspend fun reload(): Boolean {
        check(synchronized(monitor) { didCommit })
        val reloaded = mutableSetOf<NoteTabState>()
        var success = true
        try {
            while (true) {
                val next =
                    synchronized(monitor) {
                        states.filterNot { it in reloaded }.also { if (it.isEmpty()) complete = true }
                    }
                if (next.isEmpty()) return success
                for (state in next) {
                    if (!state.reloadAfterSourceDetachment()) success = false
                    reloaded += state
                }
            }
        } finally {
            synchronized(monitor) {
                complete = true
                states.clear()
            }
            finished()
        }
    }

    public suspend fun rollback() {
        val resumed =
            synchronized(monitor) {
                if (didCommit || complete) return
                complete = true
                rolledBack = true
                states.toList().also { states.clear() }
            }
        resumed.forEach { it.resumeAfterDeletionFailure() }
        try {
            if (conflictingDrafts) return
            withTimeoutOrNull(2_000) {
                for (state in resumed) {
                    try {
                        state.flushAfterDeletionFailure()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
            }
        } finally {
            finished()
        }
    }
}
