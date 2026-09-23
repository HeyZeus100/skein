// `BacklinksDrawer` (bd `skein-9jj`, plan `E7.I8`, spec §8.5): the
// collapsible drawer at the bottom of every note listing documents that
// link to it. Pure rendering over [BacklinksState] — no vault/index
// access happens in this file (same guardrail as `SkeinEditor`); every
// read goes through the state holder, and every tap routes back out
// through [BacklinksState.open] / [onExpandedChange].

package app.skein.feature.editor.backlinks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.core.model.DocId
import app.skein.core.model.IndexStore
import app.skein.core.model.VaultRepository

/**
 * The backlinks drawer surface. Collapsed by default (per plan `E6.I9`'s
 * acceptance criterion), showing only the header with a count badge;
 * expanding reveals one row per [BacklinkGroup] with its excerpt. Tapping
 * a row calls [BacklinksState.open], which the host wires to a preview
 * tab.
 *
 * @param state the state holder; build it once (e.g. `remember { ... }`
 *   in the note-tab host) and pass the same instance on every
 *   recomposition — see [BacklinksState].
 * @param modifier applied to the outer column.
 * @param initiallyExpanded starting expanded state, restored across
 *   process death via `rememberSaveable`.
 */
@Composable
public fun BacklinksDrawer(
    state: BacklinksState,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val backlinks by state.backlinks.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = modifier.fillMaxWidth().testTag(BacklinksTestTags.DRAWER),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag(BacklinksTestTags.HEADER),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Backlinks", style = MaterialTheme.typography.titleSmall)
            CountBadge(count = backlinks.size)
        }
        if (expanded) {
            if (backlinks.isEmpty()) {
                Text(
                    text = "No backlinks yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(BacklinksTestTags.EMPTY),
                )
            } else {
                backlinks.forEach { group ->
                    BacklinkRow(group = group, onClick = { state.open(group.document) })
                }
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BacklinkRow(
    group: BacklinkGroup,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(BacklinksTestTags.row(group.document.id)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.document.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.linkCount > 1) {
                Text(
                    text = "×${group.linkCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (group.excerpt.isNotBlank()) {
            Text(
                text = group.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Convenience factory tying [BacklinksState]'s scope to the composition —
 * same shape as `:feature:timeline`'s `rememberTimelineState`. Re-targets
 * the existing state (via [BacklinksState.switchDocument], not a
 * recreate) whenever [docId] itself changes across recompositions, e.g. a
 * note-tab host reusing one drawer across tab switches.
 */
@Composable
public fun rememberBacklinksState(
    docId: DocId,
    vaultRepository: VaultRepository,
    indexStore: IndexStore,
    onOpen: (DocId) -> Unit = {},
): BacklinksState {
    val scope = rememberCoroutineScope()
    val state =
        remember(vaultRepository, indexStore) {
            BacklinksState(
                initialDocId = docId,
                vaultRepository = vaultRepository,
                indexStore = indexStore,
                scope = scope,
                onOpen = onOpen,
            )
        }
    LaunchedEffect(docId) { state.switchDocument(docId) }
    return state
}

/**
 * Stable test tags for Compose UI tests against [BacklinksDrawer] — same
 * convention as `TimelineTestTags` (a shared prefix constant plus an
 * id-parameterized helper for per-row tags).
 */
public object BacklinksTestTags {
    public const val DRAWER: String = "app.skein.feature.editor.backlinks.BacklinksDrawer"
    public const val HEADER: String = "app.skein.feature.editor.backlinks.BacklinksDrawer.header"
    public const val EMPTY: String = "app.skein.feature.editor.backlinks.BacklinksDrawer.empty"
    private const val ROW_PREFIX: String = "app.skein.feature.editor.backlinks.BacklinksDrawer.row."

    public fun row(docId: String): String = ROW_PREFIX + docId
}
