// skein-2qv (E6.I7): state layer for the timeline surface — the mixed
// chronological list with persona / tag / kind filters from spec §3.1 and
// §8.2. Pure Kotlin (no Compose imports) so `TimelineStateTest` drives it
// under `runTest` on the JVM, the same shape as `EditorAutosaveTest`.
//
// The class never opens a vault, unlocks a key, or reaches for a
// repository singleton: callers hand in an already-open `VaultRepository`
// (unlock is `BiometricUnlockScreen`'s job, skein-ugo). Nothing here logs.

package app.skein.feature.timeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository

/**
 * State holder for [TimelineScreen] and [TimelineRail].
 *
 * [entries] mirrors `VaultRepository.observeTimeline(filter, limit)` for
 * the current [window]. Every mutator funnels through one
 * `MutableStateFlow<Window>`; `flatMapLatest` re-subscribes the vault flow
 * whenever the window actually changes, and `MutableStateFlow`'s
 * equality-based conflation means a mutation that leaves the window
 * structurally equal (re-selecting the current persona, clearing already
 * clear filters) never re-issues the query.
 *
 * ## Paging
 *
 * The plan's "windowed LazyColumn (loads the next 50 when within 10 items
 * of the end)" is implemented by widening `limit` rather than stitching
 * `before`-cursor pages together: one live query always backs the list,
 * so a document updated while the user is scrolled deep simply moves to
 * the top instead of being lost between two cursor windows. [loadMore]
 * only widens when the current page came back full ([hasMore]); a filter
 * change snaps the window back to the first page.
 *
 * ## Tags
 *
 * `TimelineFilter.tag` is handed to the repository verbatim — the real
 * `VaultRepositoryImpl` resolves it through TAG edges. Chip *candidates*
 * ([tags]) come from the frontmatter `tags:` arrays of the entries
 * currently loaded, plus whatever the caller supplies via
 * [setAvailableTags] (the seam for the plan's eventual
 * `VaultRepository.listTags()` extension) and the selected tag itself, so
 * a chip never disappears from under the finger that selected it.
 *
 * @param repo an already-open [VaultRepository]; held only for subscription.
 * @param scope owner of the shared flows — `rememberCoroutineScope()` in
 *   the app (see [rememberTimelineState]), `backgroundScope` in tests.
 * @param initial the starting filter (a persona deep-link seeds it here).
 * @param personaSource the persona list for the persona dropdown; normally
 *   `PersonaService.observeAll()`.
 * @param availableTags initial tag chip candidates beyond what the loaded
 *   entries declare.
 * @param pageSize the `limit` step; the default matches the plan's 50.
 */
public class TimelineState(
    private val repo: VaultRepository,
    scope: CoroutineScope,
    initial: TimelineFilter = TimelineFilter(),
    personaSource: Flow<List<Persona>> = flowOf(emptyList()),
    availableTags: Set<String> = emptySet(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    /** The exact `(filter, limit)` pair currently handed to `observeTimeline`. */
    public data class Window(
        val filter: TimelineFilter,
        val limit: Int,
    )

    private val windowState = MutableStateFlow(Window(filter = initial, limit = pageSize))

    /** Observable window — the screen reads `filter` off it for chip selection state. */
    public val window: StateFlow<Window> = windowState.asStateFlow()

    /** Snapshot of the active filter (non-observable convenience; observe [window] from Compose). */
    public val filter: TimelineFilter get() = windowState.value.filter

    /** Snapshot of the active page limit. */
    public val limit: Int get() = windowState.value.limit

    private val extraTags = MutableStateFlow(availableTags)

    /** Documents matching [window], newest first, as `observeTimeline` returns them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val entries: StateFlow<List<Document>> =
        windowState
            .flatMapLatest { w -> repo.observeTimeline(filter = w.filter, limit = w.limit) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** True while the last page came back full — i.e. widening the window may reveal more. */
    public val hasMore: StateFlow<Boolean> =
        combine(entries, windowState) { docs, w -> docs.size >= w.limit }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /** Personas offered by the persona dropdown. */
    public val personas: StateFlow<List<Persona>> =
        personaSource.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** Sorted tag chip candidates — see the class KDoc for where they come from. */
    public val tags: StateFlow<List<String>> =
        combine(entries, windowState, extraTags) { docs, w, extra ->
            buildSet {
                docs.forEach { addAll(frontmatterTags(it)) }
                addAll(extra)
                w.filter.tag?.let(::add)
            }.sorted()
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    // ------------------------------------------------------------------
    // Filter mutators — an unchanged result is a no-op (no re-subscribe)
    // ------------------------------------------------------------------

    /** Persona dropdown: `null` is "all personas". */
    public fun setPersona(personaId: PersonaId?) {
        updateFilter { it.copy(personaId = personaId) }
    }

    /** Set (or with `null` clear) the single tag filter `TimelineFilter` speaks in. */
    public fun setTag(tag: String?) {
        updateFilter { it.copy(tag = normalizeTag(tag)) }
    }

    /** Tag chip tap: selects [tag], or clears it when it is already selected. */
    public fun toggleTag(tag: String) {
        val normalized = normalizeTag(tag) ?: return
        updateFilter { it.copy(tag = if (it.tag == normalized) null else normalized) }
    }

    /**
     * Kind chip tap. Toggling the last remaining kind off would render an
     * empty timeline and lose the "all kinds" meaning, so that case resets
     * to every kind instead.
     */
    public fun toggleKind(kind: DocumentKind) {
        updateFilter { current ->
            val next = if (kind in current.kinds) current.kinds - kind else current.kinds + kind
            current.copy(kinds = next.ifEmpty { DocumentKind.entries.toSet() })
        }
    }

    /** Reset every filter axis (the empty state's "Clear filters" action). */
    public fun clearFilters() {
        updateFilter { TimelineFilter() }
    }

    /** Widen the window by one page if the current page came back full; otherwise a no-op. */
    public fun loadMore() {
        windowState.update { w -> if (entries.value.size >= w.limit) w.copy(limit = w.limit + pageSize) else w }
    }

    /** Replace the caller-supplied tag chip candidates (see the class KDoc). */
    public fun setAvailableTags(tags: Set<String>) {
        extraTags.value = tags
    }

    private fun updateFilter(transform: (TimelineFilter) -> TimelineFilter) {
        windowState.update { w ->
            val next = transform(w.filter)
            if (next == w.filter) w else Window(filter = next, limit = pageSize)
        }
    }

    private fun normalizeTag(tag: String?): String? = tag?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }

    public companion object {
        /** Plan `E6.I7`: "loads the next 50 when within 10 items of the end". */
        public const val DEFAULT_PAGE_SIZE: Int = 50

        // Five seconds without a subscriber before the vault query tears
        // down — a configuration change or a brief tab switch does not
        // re-query, a screen that is genuinely gone releases the reader.
        internal const val STOP_TIMEOUT_MILLIS: Long = 5_000L
    }
}
