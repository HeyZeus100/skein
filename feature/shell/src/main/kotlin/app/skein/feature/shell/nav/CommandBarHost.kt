package app.skein.feature.shell.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

/**
 * Wires [NavState] (drawer/destination/query) to [CommandBarState] (search
 * + `/` palette) around a single [CommandBar] (spec §8.2, plan `E6.I4`
 * slice A): every keystroke updates both — [NavState.setQuery] so the
 * field's own text stays hoisted the way it always has been, and
 * [CommandBarState.onQueryChanged] so the debounced search / palette
 * filtering reacts — and Enter dispatches to [CommandBarState.onSubmit],
 * clearing the query only if something actually ran (a no-op Enter, e.g. an
 * unrecognized command or an empty result list, leaves the text for the
 * user to keep editing).
 *
 * Renders the `/` palette ([CommandPalette]) or the plain-text
 * [SearchResults] list directly below the bar, in the same [Column]
 * `SkeinApp` already gives the bar — never both at once, since
 * [NavState.isCommand] and "has search results" are mutually exclusive by
 * construction ([CommandBarState.onQueryChanged] clears [CommandBarState.results]
 * the moment the query starts with `/`).
 */
@Composable
fun CommandBarHost(
    navState: NavState,
    commandBarState: CommandBarState,
    modelName: String,
    modelActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        CommandBar(
            query = navState.query,
            onQueryChange = { text ->
                navState.setQuery(text)
                commandBarState.onQueryChanged(text)
            },
            onMenuClick = navState::openDrawer,
            modelName = modelName,
            modelActive = modelActive,
            onSubmit = {
                scope.launch {
                    if (commandBarState.onSubmit()) {
                        navState.clear()
                        commandBarState.onQueryChanged("")
                    }
                }
            },
        )
        if (navState.isCommand) {
            CommandPalette(
                commands = commandBarState.paletteCommands,
                // UX-P0-16: a tapped row runs its command (it used to only
                // fill "/chat " and then show "No matching commands").
                onSelect = { command ->
                    scope.launch {
                        command.run("")
                        navState.clear()
                        commandBarState.onQueryChanged("")
                        focusManager.clearFocus()
                    }
                },
            )
        } else if (commandBarState.results.isNotEmpty()) {
            SearchResults(
                results = commandBarState.results,
                onResultClick = { result ->
                    commandBarState.openResult(result)
                    navState.clear()
                    commandBarState.onQueryChanged("")
                },
            )
        }
    }
}
