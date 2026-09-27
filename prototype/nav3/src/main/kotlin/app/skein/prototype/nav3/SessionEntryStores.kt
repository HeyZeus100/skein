// skein-xtov.24.4 (AL-05, throwaway): T3 (§7.1) — one ViewModelStore per
// entry under a session owner that the LOCK clears (SECURITY_REVIEW_D7.md
// M12), not composition. The stock `rememberViewModelStoreNavEntryDecorator`
// cannot do that: its `ViewModelStoreProvider` only clears a store once the
// entry has left composition (refcounted), and Compose pauses while the
// Activity is stopped — exactly when a screen-off lock lands. See
// `Nav3GateLockTest.StockDecorator`.
package app.skein.prototype.nav3

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

/** Activity-scoped, so it survives recreation (§7.1); cleared by the lock sequence. */
class SessionEntryStores : ViewModel() {
    private val stores = HashMap<Any, ViewModelStore>()

    /** Bumped by [clearAll] so composed entries pick up fresh stores if they recompose before leaving. */
    var generation by mutableIntStateOf(0)
        private set

    internal fun storeFor(contentKey: Any): ViewModelStore = stores.getOrPut(contentKey) { ViewModelStore() }

    internal fun clear(contentKey: Any) {
        stores.remove(contentKey)?.clear()
    }

    /** The lock's session-closed hook (B4): every entry ViewModel gets `onCleared` now, composed or not. */
    fun clearAll() {
        val all = stores.values.toList()
        stores.clear()
        all.forEach(ViewModelStore::clear)
        generation++
    }

    val size: Int get() = stores.size

    override fun onCleared() = clearAll()
}

/** The T3 entry decorator over [SessionEntryStores]; a popped entry's store is cleared (`onPop`). */
fun <T : Any> sessionEntryDecorator(stores: SessionEntryStores): NavEntryDecorator<T> =
    NavEntryDecorator(
        onPop = { contentKey -> stores.clear(contentKey) },
        decorate = { entry ->
            val generation = stores.generation
            val owner =
                remember(entry.contentKey, generation) {
                    val store = stores.storeFor(entry.contentKey)
                    object : ViewModelStoreOwner {
                        override val viewModelStore: ViewModelStore = store
                    }
                }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { entry.Content() }
        },
    )
