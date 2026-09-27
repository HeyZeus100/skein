// skein-xtov.24.9 (AL-09b): the Graph destination's NavDisplay entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.2, §8.4; IA §3.2): the canvas, centred on
// `GraphKey.focusDocId` or (with none) the most recently updated note, with a
// purposeful empty state; a selected node's detail (`GraphNodeKey`,
// select-then-open — rule 2 selects, "Open" follows rule 1 into Knowledge's
// `NoteKey` via [app.skein.feature.shell.host.open]).
package app.skein.feature.graph.entries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.model.DocumentKind
import app.skein.core.model.IndexStore
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.GraphKey
import app.skein.core.navigation.GraphNodeKey
import app.skein.core.navigation.SkeinKey
import app.skein.feature.graph.GraphLegend
import app.skein.feature.graph.GraphView
import app.skein.feature.graph.rememberGraphState
import app.skein.feature.shell.host.EntryDocument
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.open
import app.skein.feature.shell.host.rememberEntryDocument

/** What the Graph entries need from the open vault. */
class GraphEntryDeps(
    val vaultRepository: VaultRepository,
    val indexStore: IndexStore,
)

object GraphEntryTestTags {
    const val EMPTY_STATE = "graph_entry_empty_state"
    const val CANVAS = "graph_entry_canvas"
    const val NODE_DETAIL_OPEN = "graph_entry_node_detail_open"
}

/** One Graph key's content (§8.2). */
@Composable
fun GraphEntry(
    key: SkeinKey,
    shell: SkeinShellState,
    deps: GraphEntryDeps,
) {
    when (key) {
        is GraphKey -> GraphRoot(key, shell, deps)
        is GraphNodeKey -> GraphNodeDetail(key, shell, deps)
        else -> Unit
    }
}

/**
 * The canvas (IA §3.2): [GraphKey.focusDocId] if set (an explicit "Centre
 * here"), else the most recently updated note (ponytail: `updated_at`
 * ordering via `observeTimeline` — this vault's only "recent" signal; a true
 * last-opened timestamp is a separate bead), else the empty state (never a
 * blank canvas).
 */
@Composable
private fun GraphRoot(
    key: GraphKey,
    shell: SkeinShellState,
    deps: GraphEntryDeps,
) {
    val mostRecentNoteId by
        produceState<String?>(initialValue = null, deps.vaultRepository) {
            deps.vaultRepository
                .observeTimeline(TimelineFilter(kinds = setOf(DocumentKind.NOTE, DocumentKind.AIOUT)), limit = 1)
                .collect { docs -> value = docs.firstOrNull()?.id }
        }
    val centerDocId = key.focusDocId?.value ?: mostRecentNoteId
    Column(Modifier.fillMaxSize()) {
        shell.EntryTopBar(key, "Graph")
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (centerDocId == null) {
                GraphEmptyState()
            } else {
                val state =
                    rememberGraphState(
                        docId = centerDocId,
                        vaultRepository = deps.vaultRepository,
                        indexStore = deps.indexStore,
                    )
                Box(Modifier.fillMaxSize().testTag(GraphEntryTestTags.CANVAS)) {
                    GraphView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        openPreview = { rawNodeId -> shell.navigate { selectGraphNode(it, rawNodeId) } },
                        openPinned = { rawNodeId -> shell.navigate { selectGraphNode(it, rawNodeId) } },
                    )
                    GraphLegend(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
            }
        }
    }
}

/** IA §3.2's purposeful empty state — never a blank canvas. */
@Composable
private fun GraphEmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(GraphEntryTestTags.EMPTY_STATE), contentAlignment = Alignment.Center) {
        Text(
            text = "Your graph grows as you link notes with [[ ]]",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/**
 * `GraphNodeKey`'s entry (spec §8.2: "selecting another node replaces it;
 * Back deselects" — the deselect is `SkeinShellHost`'s own Back handling, not
 * this composable's job): the selected node's title and, for a document node,
 * one "Open" action that follows rule 1 ([app.skein.feature.shell.host.open])
 * — the same helper Knowledge/Chat entries use, so "opening a note routes to
 * Knowledge's `NoteKey`" exactly as it does everywhere else.
 */
@Composable
private fun GraphNodeDetail(
    key: GraphNodeKey,
    shell: SkeinShellState,
    deps: GraphEntryDeps,
) {
    val document = rememberEntryDocument(deps.vaultRepository, key.nodeDocId.value)
    Column(Modifier.fillMaxSize()) {
        val title = (document as? EntryDocument.Present)?.document?.title?.takeIf { it.isNotBlank() } ?: "Untitled"
        shell.EntryTopBar(key, title)
        if (document is EntryDocument.Present) {
            Column(Modifier.padding(16.dp)) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { shell.open(document.document) },
                    modifier = Modifier.testTag(GraphEntryTestTags.NODE_DETAIL_OPEN),
                ) {
                    Text("Open")
                }
            }
        }
    }
}
