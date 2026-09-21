// `GraphScreen` (bd `skein-z2u`, plan `E6.I11`): the top-level composable
// `:app` mounts for the ✦ button — [GraphView] plus [GraphLegend] and a
// close affordance. Self-contained `SkeinTheme` wrapper because this screen
// is opened as an overlay *alongside* `SkeinApp` (see `MainActivity`'s
// `UnlockedShell`), not nested inside it, so it can't rely on inheriting
// `SkeinApp`'s own `SkeinTheme` — same pattern `MainActivity.VaultGate`
// already uses for its own screens rendered outside `SkeinApp`.
package app.skein.feature.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.SkeinTheme
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.VaultRepository

/**
 * @param onOpenPreview see [GraphView.openPreview].
 * @param onOpenPinned see [GraphView.openPinned].
 * @param onClose the close/back affordance — the host dismisses the overlay.
 */
@Composable
public fun GraphScreen(
    docId: DocId,
    vaultRepository: VaultRepository,
    indexStore: IndexStore,
    modifier: Modifier = Modifier,
    onOpenPreview: (DocId) -> Unit = {},
    onOpenPinned: (DocId) -> Unit = {},
    onClose: () -> Unit = {},
) {
    val state = rememberGraphState(docId = docId, vaultRepository = vaultRepository, indexStore = indexStore)

    SkeinTheme {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                GraphView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    openPreview = onOpenPreview,
                    openPinned = onOpenPinned,
                )
                GraphLegend(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                IconButton(
                    onClick = onClose,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(40.dp)
                            .testTag(GraphTestTags.CLOSE),
                ) {
                    // Plain glyph, not a Material Icon — same restrained-glyph
                    // convention `NoteTabHeader` uses for its ✦ button.
                    Text(text = "✕", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
