// skein-xtov.24.8 (AL-09a): the minimal file viewer (ADAPTIVE_LAYOUT_SPEC.md
// §8.2 `FileKey`: "extracted text at minimum; never an empty editor", K-P0-3).
// KNOWLEDGE_UX_SPEC.md §9.3's Pages tab, Save a copy and the row pairing are
// the file-viewer bead's (KN-6.9).
package app.skein.feature.editor.entries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FileLifecycle
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.Destination
import app.skein.core.navigation.SkeinKey
import app.skein.feature.shell.host.EntryAction
import app.skein.feature.shell.host.EntryDocument
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.GoneEntry
import app.skein.feature.shell.host.LocalSkeinWindowLayout
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.entryBottomPadding
import app.skein.feature.shell.host.rememberEntryDocument
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

object FileRouteTestTags {
    const val ROOT = "knowledge_entry_file"
    const val TEXT = "knowledge_entry_file_text"
    const val DELETE_MENU = "knowledge_entry_file_delete_menu"
}

/**
 * A file's name, type and size, then its text (the note `importPdf` wrote beside
 * it), read-only. [goneTo] as for the note: null when opened as a chat's source.
 */
@Composable
internal fun FileRoute(
    key: SkeinKey,
    rawId: String,
    shell: SkeinShellState,
    deps: KnowledgeEntryDeps,
    goneTo: Destination? = Destination.KNOWLEDGE,
) {
    val document =
        when (val entry = rememberEntryDocument(deps.repository, rawId)) {
            EntryDocument.Loading -> return
            EntryDocument.Gone -> {
                shell.GoneEntry(rawId, "This file was deleted.", goneTo, goneLabel(goneTo))
                return
            }
            is EntryDocument.Present -> entry.document
        }
    val onePane = LocalSkeinWindowLayout.current.maxPanes == 1
    val text by produceState<String?>(null, deps.repository, rawId) { value = fileText(deps.repository, rawId) }
    Column(Modifier.fillMaxSize().testTag(FileRouteTestTags.ROOT)) {
        shell.EntryTopBar(key, document.title.ifBlank { "Untitled file" }) {
            EntryAction(SkeinIcons.Link, "Connections") { shell.showConnections(rawId, onePane) }
            if (document.kind == DocumentKind.ATTACHMENT && deps.repository is FileLifecycle) {
                deps.onDelete?.let { request -> FileDeleteMenu { request(document.id) } }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(entryBottomPadding())
                .padding(SkeinSpacing.space16)
                .widthIn(max = SkeinSize.readingMax),
        ) {
            Text(
                fileMeta(document),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer(Modifier.padding(top = SkeinSpacing.space16).testTag(FileRouteTestTags.TEXT)) {
                Text(
                    text ?: "This file has no text Skein can read. It won't show up in search or answers.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** "PDF · 1.2 MB" from the attachment's stored MIME type and size. */
internal fun fileMeta(document: Document): String {
    val mime = (document.frontmatter["mime"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val type =
        when {
            mime == "application/pdf" -> "PDF"
            mime.startsWith("image/") -> "Image"
            mime.startsWith("text/") -> "Text"
            else -> "File"
        }
    val size = (document.frontmatter["size"] as? JsonPrimitive)?.longOrNull ?: return type
    return "$type · ${readableSize(size)}"
}

private fun readableSize(bytes: Long): String =
    when {
        bytes < KB -> "$bytes B"
        bytes < KB * KB -> "%.1f KB".format(bytes / KB.toDouble())
        else -> "%.1f MB".format(bytes / (KB * KB).toDouble())
    }

/**
 * The text of file [id]: the note whose frontmatter `source` names it
 * (KNOWLEDGE_UX_SPEC.md §3.5). ponytail: a scan of the newest notes, read once;
 * a `source` lookup replaces it with the file-viewer bead (KN-6.9).
 */
private suspend fun fileText(
    repository: VaultRepository,
    id: String,
): String? =
    repository
        .observeTimeline(TimelineFilter(kinds = setOf(DocumentKind.NOTE)), limit = TEXT_SCAN_LIMIT)
        .first()
        .firstOrNull { (it.frontmatter[FrontmatterKeys.SOURCE] as? JsonPrimitive)?.contentOrNull == id }
        ?.bodyMd
        ?.takeIf { it.isNotBlank() }

private const val KB = 1024L
private const val TEXT_SCAN_LIMIT = 500
