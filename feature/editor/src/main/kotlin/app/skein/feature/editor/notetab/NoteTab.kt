// `NoteTab` (plan `E6.I9`, bd `skein-u01`, spec §8.5): the note tab's
// content — header (title, share menu, ✦ graph button), the editor, and the
// collapsible backlinks drawer. Pure rendering over [NoteTabState]; every
// vault read/write happens in that state holder, not here (same discipline
// `BacklinksDrawer`/`SkeinEditor` already follow).
//
// The share menu (bd `skein-fay`, plan `E6.I16`) is the one place in this
// file that reaches past [NoteTabState] for something Android-shaped: intent
// *construction* stays in the pure `app.skein.feature.editor.share` helpers
// (`ShareIntents`/`SaveAsIntents`, both driven from [NoteTabState]), but
// firing them (`Context.startActivity`, the `ACTION_CREATE_DOCUMENT`
// activity-result launcher, and `PrintManager`) needs a live `Context`/
// `ActivityResultRegistry`, which only exists here, in the composable.

package app.skein.feature.editor.notetab

import android.app.Activity
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinNotice
import app.skein.core.designsystem.theme.LocalSkeinEditorColors
import app.skein.core.designsystem.theme.LocalSkeinTokens
import app.skein.core.designsystem.theme.rememberSkeinMarkdownStyle
import app.skein.core.export.pdf.PdfExportService
import app.skein.core.markdown.render.MarkdownStyle
import app.skein.core.model.DocId
import app.skein.core.model.IndexStore
import app.skein.core.model.VaultRepository
import app.skein.core.vault.session.UnlockState
import app.skein.feature.editor.SkeinEditor
import app.skein.feature.editor.backlinks.BacklinksDrawer
import app.skein.feature.editor.share.SaveAsFormat
import app.skein.feature.editor.share.ShareIntents
import app.skein.feature.shell.input.SecureTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Hosts [SkeinEditor] + [BacklinksDrawer] for [docId]: the Knowledge
 * destination's note entry (`app.skein.feature.editor.entries.KnowledgeEntry`).
 *
 * @param onOpenDocument a backlink tap or wikilink click resolved to a
 *   document — the host decides where it opens (mirrors `BacklinksState.onOpen`'s shape).
 * @param onOpenGraph the ✦ button (plan `E6.I9`: "navigates to
 *   `Route.Graph(docId)`") — callback only. The graph view itself is
 *   `E6.I11`; this issue only needs the affordance and the wired callback.
 * @param registerFlush called once, when this tab's [NoteTabState] is
 *   ready, with a `suspend (Duration) -> Boolean` flush handle — the host
 *   registers it as a session pending writer, which the lock runs while the
 *   vault is still open (`docs/design/LOCK_POLICY_INDEXING.md` §4.3).
 *   [unregisterFlush] is called on disposal.
 * @param unlockState bd `skein-fay`: when supplied, the share menu (glyph
 *   button in the header) is disabled while this is anything other than
 *   [UnlockState.Unlocked] — sharing/saving while locked is already
 *   impossible by construction (`VaultRepository`/`ExportServiceImpl` calls
 *   fail once the vault is closed), but the affordance itself should not
 *   invite a tap that can only fail. Defaults to `null` ("always enabled").
 * @param navigationIcon the header's leading ☰/←/✕ (skein-xtov.24.8).
 *
 * Pending edits are also flushed when this note leaves composition and when
 * the Activity stops.
 */
@Composable
public fun NoteTab(
    docId: DocId,
    vaultRepository: VaultRepository,
    indexStore: IndexStore,
    modifier: Modifier = Modifier,
    onOpenDocument: (DocId, String) -> Unit = { _, _ -> },
    onOpenGraph: (DocId) -> Unit = {},
    registerFlush: (suspend (Duration) -> Boolean) -> Unit = {},
    unregisterFlush: () -> Unit = {},
    markdownStyle: MarkdownStyle = rememberSkeinMarkdownStyle(),
    unlockState: StateFlow<UnlockState>? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val state =
        remember(docId, vaultRepository, indexStore) {
            NoteTabState(
                docId = docId,
                vaultRepository = vaultRepository,
                indexStore = indexStore,
                scope = scope,
                onOpenDocument = onOpenDocument,
            )
        }

    DisposableEffect(state) {
        registerFlush(state::flush)
        onDispose {
            unregisterFlush()
            // UX-P0-11: `scope` dies with this composition (tab close, note →
            // chat, lock, fold) and takes the debounce and any in-flight write
            // with it, so the last edits are flushed on a scope that outlives it.
            flushScope.launch { state.flush() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flushScope.launch { state.flush() } }

    var unlocked by remember { mutableStateOf(true) }
    if (unlockState != null) {
        DisposableEffect(unlockState) {
            val job =
                scope.launch {
                    unlockState.collectLatest { unlocked = it is UnlockState.Unlocked }
                }
            onDispose { job.cancel() }
        }
    }

    // bd `skein-jit3`: the tab pane this composable renders in has no
    // `Surface` of its own (it sits directly on `ColorScheme.background`),
    // and `SkeinEditor`'s `BasicTextField` does not consult `LocalContentColor`
    // the way `Text` does — an unspecified color there silently renders
    // opaque black (Compose Foundation's own default), which read as
    // dark-on-dark on the pane background. Wrapping the editor in its own
    // `Surface`, painted with the editor's dedicated surface token, gives it
    // somewhere real to sit. `markdownStyle`'s default is now
    // `rememberSkeinMarkdownStyle()` (skein-xtov.23.10), which already reads
    // `onSurface` — the same value `editorColors.onSurface` forwards
    // (§6.4: the editor's pair folded into the page colours) — so the text
    // is themed correctly without this composable substituting into an
    // explicit caller override the way the pre-DS10 `===
    // MarkdownStyle.Default` check did.
    val editorColors = LocalSkeinEditorColors.current

    val context = LocalContext.current
    var pendingSaveAsFormat by remember { mutableStateOf<SaveAsFormat?>(null) }
    val saveAsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val format = pendingSaveAsFormat
            pendingSaveAsFormat = null
            val uri = result.data?.data
            if (format != null && result.resultCode == Activity.RESULT_OK && uri != null) {
                scope.launch {
                    // UX-P0-12: a destination that can't be opened or written must not crash the app.
                    val saved =
                        try {
                            val out = context.contentResolver.openOutputStream(uri)
                            out?.use { state.writeSaveAs(format, it) }
                            out != null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    if (!saved) Toast.makeText(context, SAVE_AS_FAILED_MESSAGE, Toast.LENGTH_LONG).show()
                }
            }
        }

    Column(
        modifier = modifier.fillMaxSize().testTag(NoteTabTestTags.ROOT),
    ) {
        NoteTabHeader(
            navigationIcon = navigationIcon,
            title = state.title,
            onTitleChange = state::onTitleChange,
            onOpenGraph = { onOpenGraph(docId) },
            shareMenuEnabled = unlocked,
            onShareAsText = {
                context.startActivity(ShareIntents.chooser(state.shareAsTextIntent(), title = state.title))
            },
            onSaveAs = { format ->
                pendingSaveAsFormat = format
                saveAsLauncher.launch(state.saveAsDocumentIntent(format))
            },
            onExportPdf = {
                scope.launch {
                    state.flush()
                    val printManager = context.getSystemService(PrintManager::class.java) ?: return@launch
                    val printAdapter = PdfExportService(context, vaultRepository).printAdapter(docId)
                    printManager.print("Skein – ${state.title}", printAdapter, PrintAttributes.Builder().build())
                }
            },
        )
        HorizontalDivider()
        state.linkNotice?.let { notice ->
            SkeinNotice(
                title = "Link unavailable",
                body = notice,
                action = SkeinAction("Dismiss", onClick = state::dismissLinkNotice),
                modifier = Modifier.padding(16.dp).testTag(NoteTabTestTags.LINK_NOTICE),
            )
        }
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
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    color = editorColors.surface,
                    contentColor = editorColors.onSurface,
                ) {
                    SkeinEditor(
                        state = state.editorState,
                        modifier = Modifier.fillMaxWidth(),
                        markdownStyle = markdownStyle,
                        // bd skein-pnqo: without these two, SkeinEditor takes its
                        // wikilinkSuggest == null branch and never composes the
                        // `[[` popup at all (hardware-verified — see that bead).
                        // NoteTabState is the one place in this module allowed to
                        // touch VaultRepository, so both lambdas just forward.
                        wikilinkSuggest = state::searchWikilinkSuggestions,
                        onCreateWikilink = state::createWikilink,
                    )
                }
        }
        BacklinksDrawer(state = state.backlinksState, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * UX-P0-11: where [NoteTab] flushes on dispose and on stop — process-lived,
 * unlike the tab's `rememberCoroutineScope`. [NoteTabState.flush] is bounded
 * and never throws: a vault that already closed only marks the save failed.
 */
private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

internal const val SAVE_AS_FAILED_MESSAGE: String = "Couldn't save the file. Try another location."

@Composable
private fun NoteTabHeader(
    navigationIcon: (@Composable () -> Unit)?,
    title: String,
    onTitleChange: (String) -> Unit,
    onOpenGraph: () -> Unit,
    shareMenuEnabled: Boolean,
    onShareAsText: () -> Unit,
    onSaveAs: (SaveAsFormat) -> Unit,
    onExportPdf: () -> Unit,
) {
    val glyphs = LocalSkeinTokens.current.glyphs
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon?.invoke()
            SecureTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.weight(1f).testTag(NoteTabTestTags.TITLE_FIELD),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
            )
            ShareMenuButton(
                enabled = shareMenuEnabled,
                onShareAsText = onShareAsText,
                onSaveAs = onSaveAs,
                onExportPdf = onExportPdf,
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

/**
 * bd `skein-fay` (plan `E6.I16`): the note header's "share source" menu —
 * "Share as text" (`ACTION_SEND`/`EXTRA_TEXT`), "Save as .md"/"Save as
 * .docx" (`ACTION_CREATE_DOCUMENT`), and "Export as PDF" (the already-shipped
 * `PrintManager` path, `E2.I11`). One glyph button local to this module
 * (not `LocalSkeinTokens.Glyphs`, which is owned by `:feature:shell` — see
 * this bead's non-negotiables) opens the menu; each item just forwards to
 * the callback [NoteTab] wired against real platform calls.
 */
@Composable
private fun ShareMenuButton(
    enabled: Boolean,
    onShareAsText: () -> Unit,
    onSaveAs: (SaveAsFormat) -> Unit,
    onExportPdf: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.size(40.dp).testTag(NoteTabTestTags.SHARE_BUTTON),
        ) {
            // Local glyph, not from `LocalSkeinTokens.Glyphs` — that registry
            // lives in `:feature:shell`, off-limits to this bead (another
            // agent is editing shell/app concurrently). "↗" reads as
            // "send/export out" without pulling in a Material icon asset,
            // matching the restrained-glyph convention above.
            Text(text = "↗", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Share as text") },
                onClick = {
                    expanded = false
                    onShareAsText()
                },
                modifier = Modifier.testTag(NoteTabTestTags.SHARE_MENU_TEXT),
            )
            DropdownMenuItem(
                text = { Text("Save as Markdown (.md)") },
                onClick = {
                    expanded = false
                    onSaveAs(SaveAsFormat.MARKDOWN)
                },
                modifier = Modifier.testTag(NoteTabTestTags.SHARE_MENU_SAVE_MARKDOWN),
            )
            DropdownMenuItem(
                text = { Text("Save as Word (.docx)") },
                onClick = {
                    expanded = false
                    onSaveAs(SaveAsFormat.DOCX)
                },
                modifier = Modifier.testTag(NoteTabTestTags.SHARE_MENU_SAVE_DOCX),
            )
            DropdownMenuItem(
                text = { Text("Export as PDF") },
                onClick = {
                    expanded = false
                    onExportPdf()
                },
                modifier = Modifier.testTag(NoteTabTestTags.SHARE_MENU_EXPORT_PDF),
            )
        }
    }
}

/** Stable test tags for [NoteTab], [NoteTabHeader], and [ShareMenuButton]. */
public object NoteTabTestTags {
    public const val ROOT: String = "app.skein.feature.editor.notetab.NoteTab"
    public const val TITLE_FIELD: String = "app.skein.feature.editor.notetab.NoteTab.title"
    public const val GRAPH_BUTTON: String = "app.skein.feature.editor.notetab.NoteTab.graphButton"
    public const val LOADING: String = "app.skein.feature.editor.notetab.NoteTab.loading"
    public const val ERROR: String = "app.skein.feature.editor.notetab.NoteTab.error"
    public const val LINK_NOTICE: String = "app.skein.feature.editor.notetab.NoteTab.linkNotice"
    public const val SHARE_BUTTON: String = "app.skein.feature.editor.notetab.NoteTab.shareButton"
    public const val SHARE_MENU_TEXT: String = "app.skein.feature.editor.notetab.NoteTab.shareMenu.text"
    public const val SHARE_MENU_SAVE_MARKDOWN: String =
        "app.skein.feature.editor.notetab.NoteTab.shareMenu.saveMarkdown"
    public const val SHARE_MENU_SAVE_DOCX: String = "app.skein.feature.editor.notetab.NoteTab.shareMenu.saveDocx"
    public const val SHARE_MENU_EXPORT_PDF: String = "app.skein.feature.editor.notetab.NoteTab.shareMenu.exportPdf"
}
