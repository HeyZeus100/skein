package app.skein.feature.shell.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * `@Stable` state holder for the tab system (spec §8.3, plan `E6.I5`).
 * Ordered [tabs] drive [TabStrip]'s stable left-to-right layout; a separate
 * recency order (most-recently-activated first) drives [recentDropdown] so
 * the folded-phone "Recent ▾" list surfaces what was just used without
 * reordering the strip itself.
 *
 * Invariant: at most one [TabState.PREVIEW] tab exists at a time. Opening a
 * second preview replaces the first in place (same strip position); opening
 * a preview for the doc that's already previewed reuses that tab instead of
 * duplicating it.
 */
@Stable
class TabsState private constructor(
    initialTabs: List<Tab>,
    initialActiveId: TabId?,
    initialRecency: List<TabId>,
) {
    constructor(
        initialTabs: List<Tab> = emptyList(),
        initialActiveId: TabId? = initialTabs.firstOrNull()?.id,
    ) : this(
        initialTabs = initialTabs,
        initialActiveId = initialActiveId,
        initialRecency = initialActiveId?.let { listOf(it) } ?: emptyList(),
    )

    var tabs: List<Tab> by mutableStateOf(initialTabs)
        private set

    var activeId: TabId? by mutableStateOf(initialActiveId)
        private set

    private var recencyOrder: List<TabId> by mutableStateOf(initialRecency)

    /** The tab currently shown by [TabHost]'s content region, or `null` if there are no tabs. */
    val activeTab: Tab? get() = activeId?.let { id -> tabs.firstOrNull { it.id == id } }

    /**
     * Single-click semantics: replaces the current preview tab (if any) with
     * [tab], reusing it in place if it's already previewing the same
     * [Tab.docId]. Always activates the result.
     */
    fun openPreview(tab: Tab): TabId {
        val incoming = tab.copy(state = TabState.PREVIEW)
        val existingIndex = tabs.indexOfFirst { it.state == TabState.PREVIEW }
        if (existingIndex >= 0 && tabs[existingIndex].docId == incoming.docId) {
            val existingId = tabs[existingIndex].id
            activate(existingId)
            return existingId
        }
        tabs =
            if (existingIndex >= 0) {
                tabs.toMutableList().apply { set(existingIndex, incoming) }
            } else {
                tabs + incoming
            }
        activate(incoming.id)
        return incoming.id
    }

    /**
     * Double-click / "open in new tab" semantics: opens [tab] already
     * pinned. Reuses an existing pinned tab for the same [Tab.docId] instead
     * of duplicating it.
     */
    fun openPinned(tab: Tab): TabId {
        val incoming = tab.copy(state = TabState.PINNED)
        val existing = tabs.firstOrNull { it.pinned && it.docId == incoming.docId }
        if (existing != null) {
            activate(existing.id)
            return existing.id
        }
        tabs = tabs + incoming
        activate(incoming.id)
        return incoming.id
    }

    /** Promotes a preview to pinned (double-click on the tab, or the editor's first-edit callback). Idempotent. */
    fun pin(tabId: TabId) {
        tabs = tabs.map { if (it.id == tabId) it.copy(state = TabState.PINNED) else it }
    }

    /**
     * Closes [tabId]. If it was active, activates a neighbor (the tab that
     * slid into its old index, or the new last tab). Closing the only tab
     * clears [activeId] entirely — [TabHost] treats that as "return focus to
     * the timeline" (spec §8.3).
     */
    fun close(tabId: TabId) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val wasActive = activeId == tabId
        tabs = tabs.filterNot { it.id == tabId }
        recencyOrder = recencyOrder.filterNot { it == tabId }
        if (wasActive) {
            if (tabs.isEmpty()) {
                activeId = null
            } else {
                val neighborIndex = index.coerceAtMost(tabs.size - 1)
                activate(tabs[neighborIndex].id)
            }
        }
    }

    /** Closes every tab except [tabId], and activates it. */
    fun closeOthers(tabId: TabId) {
        val keep = tabs.firstOrNull { it.id == tabId } ?: return
        tabs = listOf(keep)
        recencyOrder = listOf(tabId)
        activeId = tabId
    }

    /** Switches the active tab and bumps it to the front of the recency order. No-op if [tabId] isn't open. */
    fun activate(tabId: TabId) {
        if (tabs.none { it.id == tabId }) return
        activeId = tabId
        recencyOrder = listOf(tabId) + recencyOrder.filterNot { it == tabId }
    }

    /** [tabs] reordered most-recently-activated first, for the folded-phone "Recent ▾" dropdown. */
    fun recentDropdown(): List<Tab> {
        val byId = tabs.associateBy { it.id }
        val recentIds = recencyOrder.toSet()
        val ordered = recencyOrder.mapNotNull { byId[it] }
        val remaining = tabs.filterNot { it.id in recentIds }
        return ordered + remaining
    }

    companion object {
        private const val FIELD_SEP = "\u0001"

        private fun encode(tab: Tab): String =
            listOf(tab.id.value, tab.docId, tab.title, tab.kind.name, tab.state.name).joinToString(FIELD_SEP)

        private fun decode(encoded: String): Tab {
            val (id, docId, title, kind, state) = encoded.split(FIELD_SEP)
            return Tab(TabId(id), docId, title, TabKind.valueOf(kind), TabState.valueOf(state))
        }

        val Saver: Saver<TabsState, List<Any?>> =
            Saver(
                save = { state ->
                    listOf(
                        state.tabs.map(::encode),
                        state.activeId?.value,
                        state.recencyOrder.map { it.value },
                    )
                },
                restore = { values ->
                    @Suppress("UNCHECKED_CAST")
                    val tabs = (values[0] as List<String>).map(::decode)
                    val activeId = (values[1] as String?)?.let(::TabId)

                    @Suppress("UNCHECKED_CAST")
                    val recency = (values[2] as List<String>).map(::TabId)
                    TabsState(initialTabs = tabs, initialActiveId = activeId, initialRecency = recency)
                },
            )
    }
}

@Composable
fun rememberTabsState(
    initialTabs: List<Tab> = emptyList(),
    initialActiveId: TabId? = initialTabs.firstOrNull()?.id,
): TabsState =
    rememberSaveable(saver = TabsState.Saver) {
        TabsState(initialTabs = initialTabs, initialActiveId = initialActiveId)
    }
