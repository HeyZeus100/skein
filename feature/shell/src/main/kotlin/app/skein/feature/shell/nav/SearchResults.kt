package app.skein.feature.shell.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.testing.ShellTestTags
import us.aherrera.skein.core.model.DocumentKind

/**
 * Kind glyph for a search result row (spec §8.2: "results list with kind
 * glyphs"). Reuses [TabKind]'s existing three glyphs — a search hit and a
 * tab are the same underlying document kinds — rather than minting a
 * second glyph set; `AIOUT` has no [TabKind] counterpart yet (no AI-output
 * tab content exists before `E6.I22`/`E7.I7`), so it gets its own.
 */
private val DocumentKind.searchGlyph: String
    get() =
        when (this) {
            DocumentKind.NOTE -> TabKind.NOTE.glyph
            DocumentKind.CHAT -> TabKind.CHAT.glyph
            DocumentKind.ATTACHMENT -> TabKind.ATTACHMENT.glyph
            DocumentKind.AIOUT -> "🤖"
        }

/**
 * Plain-text search results (spec §8.2/§8.4): [results] is already ordered
 * title-hits-before-body-hits by [CommandBarState] — this composable only
 * renders that order, one row per hit with its kind glyph, and dispatches
 * a tap to [onResultClick] (Enter opens the *top* row the same way; see
 * [CommandBarState.onSubmit]).
 */
@Composable
fun SearchResults(
    results: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(ShellTestTags.SEARCH_RESULTS),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        LazyColumn {
            items(results, key = { it.docId }) { result ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onResultClick(result) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(text = result.kind.searchGlyph, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
