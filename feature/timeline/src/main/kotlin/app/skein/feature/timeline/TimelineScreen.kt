// skein-2qv (E6.I7): the Compose surface for the timeline — mixed
// chronological list with persona / tag / kind filter chips, sticky day
// headers, windowed paging and an empty state. Since skein-xtov.24.23 it is
// the Knowledge list's body; creation actions live in the entries' top bars.
//
// Non-negotiables:
//   • Compose-only; no View system, no third-party UI library.
//   • Takes an already-open repository via `TimelineState` — never opens
//     or unlocks the vault itself.
//   • No user content is logged anywhere in this module.

package app.skein.feature.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Persona
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Plan `E6.I7`: "loads the next 50 when within 10 items of the end". */
private const val LOAD_MORE_THRESHOLD: Int = 10

/**
 * The timeline surface. See [TimelineState] for the data contract.
 *
 * @param state the state holder; build it once with [rememberTimelineState]
 *   (or directly with a real scope when hoisting) and pass the same
 *   instance on every recomposition.
 * @param onEntryClick a tap → the host opens the entry by kind.
 * @param zone the zone used for day sections and dates.
 * @param now clock for the relative timestamps; injectable for previews and tests.
 */
@Composable
public fun TimelineScreen(
    state: TimelineState,
    onEntryClick: (Document) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
    bottomContentPadding: Dp = 0.dp,
    menuActions: (Document) -> List<SkeinAction> = { emptyList() },
) {
    val entries by state.entries.collectAsState()
    val window by state.window.collectAsState()
    val personas by state.personas.collectAsState()
    val tags by state.tags.collectAsState()
    val hasMore by state.hasMore.collectAsState()
    val personaNames = remember(personas) { personas.associate { it.id to it.name } }

    Box(modifier = modifier.fillMaxSize().testTag(TimelineTestTags.ROOT)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HingeSafeFilters {
                FilterBar(
                    kinds = state.kinds,
                    filter = window.filter,
                    personas = personas,
                    tags = tags,
                    onPersona = state::setPersona,
                    onKind = state::toggleKind,
                    onTag = state::toggleTag,
                )
            }
            if (entries.isEmpty()) {
                EmptyState(
                    filtered = window.filter != state.unfiltered,
                    onClearFilters = state::clearFilters,
                    modifier = Modifier.weight(1f).padding(bottom = bottomContentPadding),
                )
            } else {
                EntryList(
                    entries = entries,
                    hasMore = hasMore,
                    onLoadMore = state::loadMore,
                    onEntryClick = onEntryClick,
                    menuActions = menuActions,
                    personaNames = personaNames,
                    zone = zone,
                    nowMillis = now(),
                    bottomContentPadding = bottomContentPadding,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Convenience factory tying the state's scope to the composition. Hosts
 * that need the state outside a composition (a coordinator holding the
 * filter across navigation) construct [TimelineState] directly instead.
 */
@Composable
public fun rememberTimelineState(
    repo: VaultRepository,
    personaSource: Flow<List<Persona>> = flowOf(emptyList()),
    initial: TimelineFilter = TimelineFilter(),
    kinds: Set<DocumentKind> = DocumentKind.entries.toSet(),
): TimelineState {
    val scope = rememberCoroutineScope()
    return remember(repo, personaSource) {
        TimelineState(repo = repo, scope = scope, initial = initial, personaSource = personaSource, kinds = kinds)
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<Document>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onEntryClick: (Document) -> Unit,
    menuActions: (Document) -> List<SkeinAction>,
    personaNames: Map<String, String>,
    zone: ZoneId,
    nowMillis: Long,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val sections = remember(entries, zone) { groupByDay(entries, zone) }
    val today = remember(nowMillis, zone) { localDay(nowMillis, zone) }
    // Observable read (lint NonObservableLocale): a locale change recomposes
    // the day headers and relative times instead of leaving them stale.
    val locale = LocalLocale.current.platformLocale

    // Windowed paging: ask for the next page once the viewport is within
    // LOAD_MORE_THRESHOLD items of the end. `hasMore` is keyed so the
    // watcher is torn down as soon as the vault says the window is
    // exhausted, and restarts (re-checking the current position) when a
    // fresh full page arrives.
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onLoadMore() }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag(TimelineTestTags.LIST),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + bottomContentPadding),
    ) {
        sections.forEach { section ->
            stickyHeader(key = "day:${section.day}", contentType = "day") { _ ->
                DayHeader(day = section.day, today = today, locale = locale)
            }
            items(items = section.documents, key = { it.id }, contentType = { "entry" }) { document ->
                TimelineRow(
                    document = document,
                    personaName = document.personaId?.let { personaNames[it] ?: it },
                    relativeTime = relativeTime(document.updatedAt, nowMillis, zone, locale),
                    onClick = { onEntryClick(document) },
                    menuActions = menuActions(document),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: LocalDate,
    today: LocalDate,
    locale: Locale,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().testTag(TimelineTestTags.dayHeader(day)),
    ) {
        Text(
            text = dayLabel(day, today, locale),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(
    filtered: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp).testTag(TimelineTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = if (filtered) "Nothing matches these filters" else "Nothing here yet",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = if (filtered) "Try clearing a filter." else "New notes, chats, and AI outputs show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filtered) {
            TextButton(
                onClick = onClearFilters,
                modifier = Modifier.testTag(TimelineTestTags.CLEAR_FILTERS),
            ) {
                Text("Clear filters")
            }
        }
    }
}
