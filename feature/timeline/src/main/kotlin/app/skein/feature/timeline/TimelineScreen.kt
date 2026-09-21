// skein-2qv (E6.I7): the Compose surface for the timeline — mixed
// chronological list with persona / tag / kind filter chips, sticky day
// headers, windowed paging, empty state, and the compact FABs / expanded
// header actions for "New note" / "New chat".
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository
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
 * @param onEntryClick single tap → the host opens a preview tab
 *   (`TabsState.openPreview`).
 * @param onEntryLongPress long press → the host opens a pinned tab
 *   (`TabsState.openPinned`).
 * @param onNewNote / [onNewChat] creation actions; `null` hides the
 *   corresponding control. Rendered as compact FABs by default and as
 *   header buttons when [expanded].
 * @param expanded true in the dual-pane / expanded width tier, where the
 *   plan puts the creation actions in a header instead of FABs.
 * @param zone the zone used for day sections and dates.
 * @param now clock for the relative timestamps; injectable for previews and tests.
 */
@Composable
public fun TimelineScreen(
    state: TimelineState,
    onEntryClick: (Document) -> Unit,
    modifier: Modifier = Modifier,
    onEntryLongPress: (Document) -> Unit = {},
    onNewNote: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
    expanded: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
) {
    val entries by state.entries.collectAsState()
    val window by state.window.collectAsState()
    val personas by state.personas.collectAsState()
    val tags by state.tags.collectAsState()
    val hasMore by state.hasMore.collectAsState()
    val personaNames = remember(personas) { personas.associate { it.id to it.name } }
    val hasActions = onNewNote != null || onNewChat != null
    val showFabs = hasActions && !expanded

    Box(modifier = modifier.fillMaxSize().testTag(TimelineTestTags.ROOT)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (hasActions && expanded) {
                HeaderActions(onNewNote = onNewNote, onNewChat = onNewChat)
            }
            FilterBar(
                filter = window.filter,
                personas = personas,
                tags = tags,
                onPersona = state::setPersona,
                onKind = state::toggleKind,
                onTag = state::toggleTag,
            )
            if (entries.isEmpty()) {
                EmptyState(
                    filtered = window.filter != TimelineFilter(),
                    onClearFilters = state::clearFilters,
                    modifier = Modifier.weight(1f),
                )
            } else {
                EntryList(
                    entries = entries,
                    hasMore = hasMore,
                    onLoadMore = state::loadMore,
                    onEntryClick = onEntryClick,
                    onEntryLongPress = onEntryLongPress,
                    personaNames = personaNames,
                    reserveFabSpace = showFabs,
                    zone = zone,
                    nowMillis = now(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showFabs) {
            FloatingActions(
                onNewNote = onNewNote,
                onNewChat = onNewChat,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
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
): TimelineState {
    val scope = rememberCoroutineScope()
    return remember(repo, personaSource) {
        TimelineState(repo = repo, scope = scope, initial = initial, personaSource = personaSource)
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
    onEntryLongPress: (Document) -> Unit,
    personaNames: Map<String, String>,
    reserveFabSpace: Boolean,
    zone: ZoneId,
    nowMillis: Long,
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
        contentPadding = PaddingValues(top = 4.dp, bottom = if (reserveFabSpace) 96.dp else 16.dp),
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
                    onLongClick = { onEntryLongPress(document) },
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

// ---------------------------------------------------------------------------
// Creation actions: FABs (compact) / header buttons (expanded)
// ---------------------------------------------------------------------------

@Composable
private fun HeaderActions(
    onNewNote: (() -> Unit)?,
    onNewChat: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNewNote != null) {
            TextButton(onClick = onNewNote, modifier = Modifier.testTag(TimelineTestTags.NEW_NOTE)) {
                Text("📄 New note")
            }
        }
        if (onNewChat != null) {
            TextButton(onClick = onNewChat, modifier = Modifier.testTag(TimelineTestTags.NEW_CHAT)) {
                Text("💬 New chat")
            }
        }
    }
}

@Composable
private fun FloatingActions(
    onNewNote: (() -> Unit)?,
    onNewChat: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onNewChat != null) {
            SmallFloatingActionButton(
                onClick = onNewChat,
                modifier =
                    Modifier
                        .testTag(TimelineTestTags.NEW_CHAT)
                        .semantics { contentDescription = "New chat" },
            ) {
                Text("💬")
            }
        }
        if (onNewNote != null) {
            SmallFloatingActionButton(
                onClick = onNewNote,
                modifier =
                    Modifier
                        .testTag(TimelineTestTags.NEW_NOTE)
                        .semantics { contentDescription = "New note" },
            ) {
                Text("📄")
            }
        }
    }
}
