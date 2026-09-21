package app.skein.feature.shell.tabs

import androidx.compose.runtime.Stable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Duration

/**
 * Minimal flush hook for the tab host (plan `E6.I9`, bd `skein-u01`;
 * `docs/design/LOCK_POLICY_INDEXING.md` §4.3): on lock, every open editor's
 * pending autosave must be flushed (not silently discarded) before the
 * vault closes. `:feature:shell` owns the tab host but cannot depend on
 * `:feature:editor` (see [app.skein.feature.shell.SkeinApp]'s doc for why —
 * `:feature:editor` depends on `:feature:shell` for `SecureBasicTextField`,
 * so the reverse edge would cycle), so it cannot know about
 * `EditorState.flush` directly. This registry is the generic seam: any tab
 * content (today only the note tab; a future chat draft could use it too)
 * registers a bare `suspend (Duration) -> Boolean` handle keyed by [TabId]
 * while it's composed, and unregisters on disposal.
 *
 * This is *not* the full `SessionState`/`LockObserver` registry
 * (`E3.I3b`) — that owns the whole ordered lock sequence (isolated
 * services, then the editor flush, then the vault close) across the
 * *process*, not just the tabs currently on screen. This registry only
 * gives that future coordinator (or, until it lands, whatever `:app` code
 * currently drives lock) something concrete to call: [flushAll].
 */
@Stable
public class FlushRegistry {
    private val handles: MutableMap<TabId, suspend (Duration) -> Boolean> = mutableMapOf()

    /** Registers (or replaces) the flush handle for [tabId]. */
    public fun register(
        tabId: TabId,
        flush: suspend (Duration) -> Boolean,
    ) {
        handles[tabId] = flush
    }

    /** Removes [tabId]'s handle, e.g. when its tab content leaves composition. */
    public fun unregister(tabId: TabId) {
        handles.remove(tabId)
    }

    /**
     * Flushes every currently-registered handle concurrently, each bounded
     * by [deadline]. Returns `true` only if every handle confirmed its
     * content saved; a handle that misses the deadline (or an empty
     * registry) doesn't throw — the lock sequence's own budget window
     * decides how to react to a `false`.
     */
    public suspend fun flushAll(deadline: Duration = Duration.ofSeconds(2)): Boolean =
        coroutineScope {
            handles.values
                .map { flush -> async { flush(deadline) } }
                .map { it.await() }
                .all { it }
        }

    /** Number of tabs currently registered — for tests/diagnostics. */
    public val size: Int get() = handles.size
}
