// `NoteTab` (plan `E6.I9`, bd `skein-u01`, spec §8.5): the note tab's
// content — header (title, ✦ graph button), the editor, and the
// collapsible backlinks drawer. Pure rendering over [NoteTabState]; every
// vault read/write happens in that state holder, not here (same discipline
// `BacklinksDrawer`/`SkeinEditor` already follow).

package app.skein.feature.editor.notetab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.markdown.render.MarkdownStyle
import app.skein.feature.editor.SkeinEditor
import app.skein.feature.editor.backlinks.BacklinksDrawer
import app.skein.feature.shell.input.SecureTextField
import app.skein.feature.shell.theme.LocalSkeinTokens
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.VaultRepository
import java.time.Duration

/**
 * Hosts [SkeinEditor] + [BacklinksDrawer] for [docId], wired as the shell's
 * tab content for `TabKind.NOTE` (`app.skein.feature.shell.SkeinApp`'s
 * `noteTabContent` slot — `:feature:shell` can't depend on this module
 * directly, see that file's doc, so the host supplies this composable as a
 * callback instead).
 *
 * @param onPin plan `E6.I9` acceptance: called once, the first time the
 *   editor's text actually changes. The host wires this to
 *   `TabsState.pin(tabId)`.
 * @param onOpenDocument a backlink tap or wikilink click resolved to a
 *   document — the host decides whether that means a preview tab, a pinned
 *   tab, or something else (mirrors `BacklinksState.onOpen`'s shape).
 * @param onOpenGraph the ✦ button (plan `E6.I9`: "navigates to
 *   `Route.Graph(docId)`") — callback only. The graph view itself is
 *   `E6.I11`; this issue only needs the affordance and the wired callback.
 * @param registerFlush called once, when this tab's [NoteTabState] is
 *   ready, with a `suspend (Duration) -> Boolean` flush handle — the host
 *   registers it against `app.skein.feature.shell.tabs.FlushRegistry`
 *   keyed by this tab's id (`docs/design/LOCK_POLICY_INDEXING.md` §4.3).
 *   [unregisterFlush] is called on disposal (tab closed / composable left).
 */
@Composable
public fun NoteTab(
    docId: DocId,
    vaultRepository: VaultRepository,
    indexStore: IndexStore,
    modifier: Modifier = Modifier,
    onPin: () -> Unit = {},
    onOpenDocument: (DocId, String) -> Unit = { _, _ -> },
    onOpenGraph: (DocId) -> Unit = {},
    registerFlush: (suspend (Duration) -> Boolean) -> Unit = {},
    unregisterFlush: () -> Unit = {},
    markdownStyle: MarkdownStyle = MarkdownStyle.Default,
) {
    val scope = rememberCoroutineScope()
    val state =
        remember(docId, vaultRepository, indexStore) {
            NoteTabState(
                docId = docId,
                vaultRepository = vaultRepository,
                indexStore = indexStore,
                scope = scope,
                onPinRequested = onPin,
                onOpenDocument = onOpenDocument,
            )
        }

    DisposableEffect(state) {
        registerFlush(state::flush)
        onDispose { unregisterFlush() }
    }

    Column(
        modifier = modifier.fillMaxSize().testTag(NoteTabTestTags.ROOT),
    ) {
        NoteTabHeader(
            title = state.title,
            onTitleChange = state::onTitleChange,
            onOpenGraph = { onOpenGraph(docId) },
        )
        HorizontalDivider()
        when {
            state.loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag(NoteTabTestTags.LOADING))
                }
            state.loadError != null ->
                Text(
                    text = state.loadError.orEmpty(),
                    modifier = Modifier.padding(16.dp).testTag(NoteTabTestTags.ERROR),
                    color = MaterialTheme.colorScheme.error,
                )
            else ->
                SkeinEditor(
                    state = state.editorState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    markdownStyle = markdownStyle,
                )
        }
        BacklinksDrawer(state = state.backlinksState, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NoteTabHeader(
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenGraph: () -> Unit,
) {
    val glyphs = LocalSkeinTokens.current.glyphs
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecureTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.weight(1f).testTag(NoteTabTestTags.TITLE_FIELD),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
            )
            // Restrained glyph set (spec §8.1): the ✦ glyph rendered as
            // plain Text inside the IconButton rather than a Material
            // Icon/ImageVector — keeps every module free of an icon asset
            // dependency for one character, matching how the command bar
            // and tab strip already render their own glyphs.
            IconButton(
                onClick = onOpenGraph,
                modifier = Modifier.size(40.dp).testTag(NoteTabTestTags.GRAPH_BUTTON),
            ) {
                Text(text = glyphs.graph, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Stable test tags for [NoteTab] and [NoteTabHeader]. */
public object NoteTabTestTags {
    public const val ROOT: String = "app.skein.feature.editor.notetab.NoteTab"
    public const val TITLE_FIELD: String = "app.skein.feature.editor.notetab.NoteTab.title"
    public const val GRAPH_BUTTON: String = "app.skein.feature.editor.notetab.NoteTab.graphButton"
    public const val LOADING: String = "app.skein.feature.editor.notetab.NoteTab.loading"
    public const val ERROR: String = "app.skein.feature.editor.notetab.NoteTab.error"
}
