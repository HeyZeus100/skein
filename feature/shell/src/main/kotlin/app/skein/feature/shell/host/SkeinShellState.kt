// skein-xtov.24.7 (AL-08): what the shell hoists ABOVE `VaultGate`
// (ADAPTIVE_LAYOUT_SPEC.md §7.1, §8.1): T1 (the navigation state, ids and
// enums only, saved through `:core:navigation`'s total codec — never
// `rememberNavBackStack`, §8.9 item 1), T2 (one entry `SaveableStateHolder`
// per destination) and T3 (`SessionEntryStores`). A lock disposes the entries
// but keeps all three, so unlocking lands the user where they were (§7.7).
package app.skein.feature.shell.host

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.Destination
import app.skein.core.navigation.Navigator
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinNavCodec
import app.skein.core.navigation.SkeinNavigationState
import app.skein.core.vault.session.UnlockManager

/**
 * The hoisted shell state. [nav] is T1: every transition goes through
 * [navigate], so the [Navigator]'s rules are the only way it changes. The
 * transient raw ids it may hold and [sheets] are cleared by the lock (M1a, M12).
 */
@Stable
class SkeinShellState internal constructor(
    initial: SkeinNavigationState,
    internal val entryState: Map<Destination, SaveableStateHolder>,
    val stores: SessionEntryStores,
    private val onReset: () -> Unit = {},
) {
    internal val navigator = Navigator()

    var nav: SkeinNavigationState by mutableStateOf(initial)
        private set

    val sheets = SheetPresentation()

    /** The search overlay (`SkeinSearch.kt`) is open. Composition-scoped (T8): the lock closes it. */
    var searchOpen: Boolean by mutableStateOf(false)
        private set

    /** A content-free, unsaved event for the container's composition-owned drawer. */
    internal var drawerCloseRequest: Int by mutableIntStateOf(0)
        private set

    fun openSearch() {
        searchOpen = true
    }

    fun closeSearch() {
        searchOpen = false
    }

    /** External navigation must reveal its destination even when a drawer, search or sheet is open. */
    fun dismissTransientSurfaces() {
        closeSearch()
        sheets.collapseAll()
        drawerCloseRequest++
    }

    /** Applies one [Navigator] transition; a null result (Back not consumed) changes nothing. */
    fun navigate(transition: Navigator.(SkeinNavigationState) -> SkeinNavigationState?) {
        navigator.transition(nav)?.let { nav = it }
    }

    /** The lock's hook: transient entries close and their raw ids leave memory; sheets fall back to the peek. */
    internal fun onLocked() {
        nav = navigator.dropTransient(nav)
        sheets.collapseAll()
        searchOpen = false
        drawerCloseRequest = 0
    }

    /**
     * M4e (SECURITY_REVIEW_D7.md): the vault was reset, so no id from it may
     * survive. Every stack returns to its root and every session store is
     * cleared now; [rememberSkeinShellState] then replaces this state with a
     * fresh generation, which drops these T2 holders and unregisters this
     * generation's saved-state entries, so the next saved Bundle holds none of them.
     */
    fun resetForNewVault() {
        nav = SkeinNavigationState.initial()
        sheets.collapseAll()
        searchOpen = false
        drawerCloseRequest = 0
        stores.clearAll()
        onReset()
    }
}

/**
 * Call once, above `VaultGate`, in the Activity's content (§8.8). Restores T1
 * from the saved-state Bundle only (M4b), never from the Intent.
 */
@Composable
fun rememberSkeinShellState(unlockManager: UnlockManager): SkeinShellState {
    val stores = viewModel { SessionEntryStores(unlockManager) }
    // M4e: a vault reset starts a new generation. Everything saveable below is keyed by it, so the old
    // generation's T1 and T2 leave composition and their saved-state entries are unregistered with them.
    var generation by rememberSaveable { mutableIntStateOf(0) }
    return key(generation) { rememberShellGeneration(stores) { generation++ } }
}

@Composable
private fun rememberShellGeneration(
    stores: SessionEntryStores,
    onReset: () -> Unit,
): SkeinShellState {
    val entryState = Destination.entries.associateWith { key(it) { rememberSaveableStateHolder() } }
    val shell =
        rememberSaveable(saver = shellSaver(entryState, stores, onReset)) {
            SkeinShellState(SkeinNavigationState.initial(), entryState, stores, onReset)
        }
    DisposableEffect(shell) {
        val hook = stores.doOnLocked(shell::onLocked)
        onDispose { hook.dispose() }
    }
    return shell
}

private fun shellSaver(
    entryState: Map<Destination, SaveableStateHolder>,
    stores: SessionEntryStores,
    onReset: () -> Unit,
): Saver<SkeinShellState, Bundle> =
    Saver(
        save = { bundleOf(SkeinNavCodec.encode(it.nav)) },
        // Total (M4c): an unreadable Bundle restores the root stacks.
        restore = { saved ->
            val nav = runCatching { SkeinNavCodec.decode(treeOf(saved)) }.getOrElse { SkeinNavigationState.initial() }
            SkeinShellState(nav, entryState, stores, onReset)
        },
    )

/** The codec's saved form, 1:1: a map is a Bundle, a list an `ArrayList<Bundle>`, leaves `String`/`Int`. */
private fun bundleOf(tree: Map<*, *>): Bundle =
    Bundle().apply {
        for ((k, v) in tree) {
            val name = k as String
            when (v) {
                is String -> putString(name, v)
                is Int -> putInt(name, v)
                is List<*> -> putParcelableArrayList(name, v.mapTo(ArrayList()) { bundleOf(it as Map<*, *>) })
            }
        }
    }

@Suppress("DEPRECATION") // Bundle.get: the codec, not the Bundle, decides what a value may be.
private fun treeOf(value: Any?): Any? =
    when (value) {
        is Bundle -> value.keySet().associateWith { treeOf(value.get(it)) }
        is List<*> -> value.map(::treeOf)
        else -> value
    }

/**
 * B8's content-free batch kind lookup, with [spaceIds] read once for the
 * current Space. Missing or changed document kinds are sanitised by Navigator.
 * ponytail: a message focus degrades to the latest answer and model details
 * fall back to their root, until B8/B9 answer them.
 */
fun navKindsOf(
    repository: VaultRepository,
    spaceIds: suspend () -> Collection<String> = { emptyList() },
): suspend (Set<SkeinId>) -> Map<SkeinId, ObjectKind> =
    { ids ->
        val kinds = repository.kindsOf(ids.mapTo(linkedSetOf()) { it.value })
        val spaces = spaceIds().toSet()
        ids
            .mapNotNull { id ->
                kinds[id.value]?.let { id to it.objectKind }
                    ?: id.takeIf { it.value in spaces }?.let { it to ObjectKind.SPACE }
            }.toMap()
    }
