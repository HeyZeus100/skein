// skein-xtov.24.7 (AL-08): T3 and the shell's lock hooks (ADAPTIVE_LAYOUT_SPEC.md
// §7.1, §7.7; SECURITY_REVIEW_D7.md §6). One ViewModelStore per entry under an
// Activity-scoped owner that the LOCK clears (M12), never composition: the
// stock `rememberViewModelStoreNavEntryDecorator` clears a store only once its
// entry has left composition, and Compose pauses while the Activity is stopped
// — exactly when a screen-off lock lands (spec §8.9 item 3, the spike's
// `StockDecorator` evidence).
package app.skein.feature.shell.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntryDecorator
import app.skein.core.navigation.Destination
import app.skein.core.navigation.SkeinKey
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The session entry stores (T3) and the shell's two lock hooks (B4), in one
 * Activity-scoped `ViewModel` so they survive recreation (§7.1) and stay
 * registered with [UnlockManager] for exactly that long.
 *
 * A [LockObserverPriority.LOW] observer, so the lock runs it after the HIGH
 * tier and before TEARDOWN closes the vault:
 *  - [onLocking] drains every pending writer (M8): an entry's unsaved work is
 *    committed while the key is live, and the vault close comes after.
 *  - [onLocked] clears every store (every entry `ViewModel` gets `onCleared`)
 *    and runs the [doOnLocked] hooks (M12). It runs on the lock's own thread,
 *    composed or not, so a stopped Activity is cleared too.
 *
 * Stores are keyed by destination and content key: a content key is unique
 * within a stack, not across stacks (`SkeinKey.contentKey`).
 */
class SessionEntryStores(
    unlockManager: UnlockManager,
) : ViewModel(),
    LockObserver {
    private val stores = HashMap<Pair<Destination, Any>, ViewModelStore>()
    private val writers = CopyOnWriteArrayList<suspend () -> Unit>()
    private val lockHooks = CopyOnWriteArrayList<() -> Unit>()

    /** Bumped by [clearAll], so an entry that recomposes before it leaves picks up a fresh store. */
    internal var generation by mutableIntStateOf(0)
        private set

    // Last: a lock may call back from another thread as soon as this is registered.
    private val registration = unlockManager.addLockObserver(this)

    val size: Int get() = synchronized(stores) { stores.size }

    internal fun storeFor(
        destination: Destination,
        contentKey: Any,
    ): ViewModelStore = synchronized(stores) { stores.getOrPut(destination to contentKey) { ViewModelStore() } }

    internal fun clear(
        destination: Destination,
        contentKey: Any,
    ) {
        synchronized(stores) { stores.remove(destination to contentKey) }?.clear()
    }

    /** Every entry `ViewModel` gets `onCleared` now, composed or not. */
    fun clearAll() {
        val all = synchronized(stores) { stores.values.toList().also { stores.clear() } }
        all.forEach(ViewModelStore::clear)
        generation++
    }

    /** Work that must reach the vault before it closes (a draft, an editor's autosave); drained at LOCKING. */
    fun addPendingWriter(flush: suspend () -> Unit): DisposableHandle {
        writers += flush
        return DisposableHandle { writers.remove(flush) }
    }

    /** Session holders the lock clears (T4, transient ids): run at `onLocked`, after the stores. */
    fun doOnLocked(hook: () -> Unit): DisposableHandle {
        lockHooks += hook
        return DisposableHandle { lockHooks.remove(hook) }
    }

    override val priority: LockObserverPriority = LockObserverPriority.LOW

    // The lock's shared window bounds this (UnlockManager's withTimeoutOrNull).
    override suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        coroutineScope { writers.map { async { it() } }.awaitAll() }
    }

    override fun onLocked(epoch: Long) {
        clearAll()
        lockHooks.forEach { it() }
    }

    override fun onUnlocked(epoch: Long) = Unit

    override fun onCleared() {
        registration.dispose()
        clearAll()
    }
}

/** The T3 entry decorator over [SessionEntryStores] for one destination's stack; a popped entry's store is cleared. */
internal fun sessionEntryDecorator(
    stores: SessionEntryStores,
    destination: Destination,
): NavEntryDecorator<SkeinKey> =
    NavEntryDecorator(
        onPop = { contentKey -> stores.clear(destination, contentKey) },
        decorate = { entry ->
            val generation = stores.generation
            val owner =
                remember(entry.contentKey, generation) {
                    val store = stores.storeFor(destination, entry.contentKey)
                    object : ViewModelStoreOwner {
                        override val viewModelStore: ViewModelStore = store
                    }
                }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { entry.Content() }
        },
    )
