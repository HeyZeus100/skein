// E2.I4: small in-process event dispatcher. `VaultRepositoryImpl` emits a
// `TableChange` after each committed write; its own `observe*` flows
// re-query on the events they care about. Not persisted, not
// cross-process, not durable across a process restart — a fresh instance
// lives exactly as long as the `VaultRepositoryImpl` that owns it.

package app.skein.core.vault.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Wraps a `MutableSharedFlow<TableChange>` with no replay (a late
 * subscriber only sees writes from *after* it subscribes — `observe*`
 * callers cover the "state at subscription time" case themselves via
 * `onStart { emit(...) }` before merging in [events]) and a generous
 * extra buffer so [emit] — called from inside the writer's `Mutex` — never
 * suspends waiting for a slow collector.
 */
public class ChangeBus {
    private val flow: MutableSharedFlow<TableChange> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = EXTRA_BUFFER_CAPACITY)

    public val events: SharedFlow<TableChange> = flow.asSharedFlow()

    /**
     * Best-effort, non-suspending publish. `tryEmit` only fails to enqueue
     * once the extra buffer itself is exhausted (i.e. an absurd number of
     * writes with zero collectors draining them); a dropped tick just means
     * an idle observer's next re-query is coalesced with the following one,
     * which is an acceptable trade against ever blocking a writer.
     */
    public fun emit(change: TableChange) {
        flow.tryEmit(change)
    }

    private companion object {
        const val EXTRA_BUFFER_CAPACITY: Int = 64
    }
}
