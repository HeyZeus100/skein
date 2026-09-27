// skein-xtov.24.23 (AL-09c): the retired command bar's search, kept reachable
// until the command palette (INFORMATION_ARCHITECTURE.md §3.3, Wave 10)
// replaces it: the drawer's "Search or run a command" row and Knowledge's ⌕
// open it over the shell. Same search as the bar (titles first, then bodies
// not already matched); a result opens by kind (IA §2 principle 2).
package app.skein.feature.shell.host

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.skein.core.designsystem.components.SkeinListRow
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.VaultRepository
import app.skein.feature.shell.input.SecureTextField
import kotlinx.coroutines.delay

object SkeinSearchTestTags {
    const val OVERLAY = "skein_search_overlay"
    const val FIELD = "skein_search_field"
    const val NO_MATCHES = "skein_search_no_matches"
}

/** The command bar's search over [repository]: title matches, then body matches not already listed. */
fun vaultSearch(repository: VaultRepository): suspend (String) -> List<Document> =
    { query ->
        val titles = repository.searchTitles(query)
        val ids = titles.mapTo(HashSet()) { it.id }
        titles + repository.searchBodies(query).map { it.document }.filterNot { it.id in ids }
    }

/**
 * Full height, at most [SkeinSize.paletteMaxWidth] wide, over the shell; Back
 * or ✕ closes it. The query is composition state only: typed text never goes
 * into the saved-state Bundle (spec §7.2).
 */
@Composable
internal fun SkeinSearchOverlay(
    search: suspend (String) -> List<Document>,
    onOpen: (Document) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Null while a search is pending, so "No matches" never flashes before the first answer.
    val results by produceState<List<Document>?>(emptyList(), query) {
        value = null
        if (query.isBlank()) {
            value = emptyList()
        } else {
            delay(DEBOUNCE_MILLIS)
            value = search(query.trim())
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = onDismiss,
    )
    Surface(Modifier.fillMaxSize().testTag(SkeinSearchTestTags.OVERLAY)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = SkeinSize.paletteMaxWidth).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(SkeinSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painterResource(SkeinIcons.Close),
                            contentDescription = "Close search",
                            modifier = Modifier.size(SkeinSize.iconStandard),
                        )
                    }
                    SecureTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        imeAction = ImeAction.Search,
                        keyboardActions = KeyboardActions(onSearch = { results?.firstOrNull()?.let(onOpen) }),
                        placeholder = { Text("Search notes, files and chats") },
                        modifier =
                            Modifier
                                .weight(1f)
                                .focusRequester(focus)
                                .testTag(SkeinSearchTestTags.FIELD),
                    )
                }
                val found = results
                if (query.isNotBlank() && found != null && found.isEmpty()) {
                    Text(
                        "No matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(SkeinSpacing.space16).testTag(SkeinSearchTestTags.NO_MATCHES),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(found.orEmpty(), key = { it.id }) { document ->
                        SkeinListRow(
                            title = document.title.ifBlank { "Untitled" },
                            supportingText = document.kind.label,
                            leadingIcon = document.kind.icon,
                            onClick = { onOpen(document) },
                        )
                    }
                }
            }
        }
    }
}

private val DocumentKind.label: String
    get() =
        when (this) {
            DocumentKind.CHAT -> "Chat"
            DocumentKind.NOTE -> "Note"
            DocumentKind.ATTACHMENT -> "File"
            DocumentKind.AIOUT -> "AI output"
        }

private val DocumentKind.icon: Int
    get() =
        when (this) {
            DocumentKind.CHAT -> SkeinIcons.Chat
            DocumentKind.NOTE -> SkeinIcons.Note
            DocumentKind.ATTACHMENT -> SkeinIcons.File
            DocumentKind.AIOUT -> SkeinIcons.AiOutput
        }

/** The command bar's debounce. */
private const val DEBOUNCE_MILLIS = 150L
