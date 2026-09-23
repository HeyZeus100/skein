package app.skein.feature.shell.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode
import us.aherrera.skein.core.model.DocumentKind

/**
 * Shared preview scaffold: [NavDrawer] wrapping [CommandBar] over a
 * placeholder content region naming the active destination (real screens
 * are out of scope for `E6.I3`, per plan). [navState] drives everything, so
 * each `@Preview` below only needs to construct a different starting state.
 */
@Composable
private fun NavPreviewScaffold(navState: NavState) {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        NavDrawer(
            open = navState.drawerOpen,
            activeDestination = navState.destination,
            onNavigate = navState::navigate,
            onDismiss = navState::closeDrawer,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CommandBar(
                    query = navState.query,
                    onQueryChange = navState::setQuery,
                    onMenuClick = navState::openDrawer,
                    modelName = "qwen",
                    modelActive = true,
                )
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Text(
                        text = "Active destination: ${navState.destination.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Closed drawer, no command bar query — the everyday resting state. */
@Preview(name = "Closed drawer + empty command bar — 1000dp", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun ClosedDrawerPreview() {
    NavPreviewScaffold(navState = remember { NavState() })
}

@Preview(name = "Open drawer — unfolded 1000dp", widthDp = 1000, heightDp = 900, showBackground = true)
@Composable
private fun OpenDrawerUnfoldedPreview() {
    NavPreviewScaffold(navState = remember { NavState(initialDrawerOpen = true) })
}

/** Folded/phone width — the drawer is modal at every width (spec §8.2), so it should look identical in shape. */
@Preview(name = "Open drawer — folded 400dp", widthDp = 400, heightDp = 800, showBackground = true)
@Composable
private fun OpenDrawerFoldedPreview() {
    NavPreviewScaffold(navState = remember { NavState(initialDrawerOpen = true) })
}

/** A leading `/` in the query — [CommandBar] shows the "press ⏎ to run" hint; nothing executes. */
@Preview(name = "Command bar with active query — 700dp", widthDp = 700, heightDp = 500, showBackground = true)
@Composable
private fun ActiveQueryPreview() {
    NavPreviewScaffold(navState = remember { NavState(initialQuery = "/new note") })
}

// ---- E6.I4 slice A (skein-ps0): `/` palette + plain-text search results --------

/** Sample palette content: [CommandPalette] itself needs no `VaultRepository` — [Command.run] never fires from a preview tap. */
private val samplePaletteCommands =
    listOf(
        Command(keyword = "new note", hint = "[title] — create a note and open it pinned") {},
    )

private val sampleSearchResults =
    listOf(
        SearchResult(docId = "1", title = "Quarterly planning", kind = DocumentKind.NOTE),
        SearchResult(docId = "2", title = "Chat about quarterly goals", kind = DocumentKind.CHAT),
    )

/** Typing `/` shows the filtered palette below the bar (spec §8.2) — phone width. */
@Preview(name = "Command palette — folded 400dp", widthDp = 400, heightDp = 500, showBackground = true)
@Composable
private fun CommandPaletteFoldedPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommandBar(query = "/new", onQueryChange = {}, onMenuClick = {}, modelName = "qwen", modelActive = true)
            CommandPalette(commands = samplePaletteCommands, onSelect = {})
        }
    }
}

/** Same palette — unfolded/dual-pane width. */
@Preview(name = "Command palette — unfolded 1000dp", widthDp = 1000, heightDp = 500, showBackground = true)
@Composable
private fun CommandPaletteUnfoldedPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommandBar(query = "/new", onQueryChange = {}, onMenuClick = {}, modelName = "qwen", modelActive = true)
            CommandPalette(commands = samplePaletteCommands, onSelect = {})
        }
    }
}

/** Plain-text search: title hits before body hits, each with a kind glyph (spec §8.2) — phone width. */
@Preview(name = "Search results — folded 400dp", widthDp = 400, heightDp = 500, showBackground = true)
@Composable
private fun SearchResultsFoldedPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommandBar(
                query = "quarterly",
                onQueryChange = {},
                onMenuClick = {},
                modelName = "qwen",
                modelActive = true,
            )
            SearchResults(results = sampleSearchResults, onResultClick = {})
        }
    }
}

/** Same search results — unfolded/dual-pane width. */
@Preview(name = "Search results — unfolded 1000dp", widthDp = 1000, heightDp = 500, showBackground = true)
@Composable
private fun SearchResultsUnfoldedPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommandBar(
                query = "quarterly",
                onQueryChange = {},
                onMenuClick = {},
                modelName = "qwen",
                modelActive = true,
            )
            SearchResults(results = sampleSearchResults, onResultClick = {})
        }
    }
}
