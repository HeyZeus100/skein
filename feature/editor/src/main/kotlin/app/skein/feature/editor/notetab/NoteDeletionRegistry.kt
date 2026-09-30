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
    private val committedIds = mutableStateMapOf<DocId, Boolean>()

    public fun isDeleting(id: DocId): Boolean = synchronized(monitor) { id in pending || id in committedIds }

    internal fun register(state: NoteTabState): DisposableHandle {
        synchronized(monitor) {
            editors.getOrPut(state.docId) { mutableSetOf() }.add(state)
            if (state.docId in committedIds) state.pauseForDeletion() else pending[state.docId]?.include(state)
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
