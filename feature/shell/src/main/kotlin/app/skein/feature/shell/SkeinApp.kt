package app.skein.feature.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.skein.feature.shell.layout.AdaptivePaneHost
import app.skein.feature.shell.layout.rememberAdaptiveLayoutState
import app.skein.feature.shell.nav.CommandBar
import app.skein.feature.shell.nav.NavDrawer
import app.skein.feature.shell.nav.rememberNavState
import app.skein.feature.shell.tabs.TabHost
import app.skein.feature.shell.tabs.rememberTabsState
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode

/**
 * The app shell (plan `E6.I3`): [SkeinTheme] wrapping the persistent nav
 * layer — [NavDrawer] around the [CommandBar] + [AdaptivePaneHost] stack.
 * [NavState] owns drawer open/closed, the active destination, and the
 * command bar query; none of the five drawer destinations are wired to real
 * screens yet (`E6.I8`+ / `E7.I3`+ own that) — the pane content below the
 * command bar is a placeholder naming the active destination.
 */
@Composable
fun SkeinApp(themeMode: SkeinThemeMode = SkeinThemeMode.SYSTEM) {
    SkeinTheme(mode = themeMode) {
        val navState = rememberNavState()
        val layoutState = rememberAdaptiveLayoutState()
        val tabsState = rememberTabsState()

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
                AdaptivePaneHost(
                    layoutState = layoutState,
                    modifier = Modifier.weight(1f),
                    timeline = { DestinationPlaceholder(label = "Timeline") },
                    primary = {
                        TabHost(
                            tabsState = tabsState,
                            content = { DestinationPlaceholder(label = navState.destination.name) },
                        )
                    },
                )
            }
        }
    }
}

/**
 * Stand-in for the real destination screens (out of scope for `E6.I3`).
 * Only names the active destination so the nav wiring is visibly correct.
 */
@Composable
private fun DestinationPlaceholder(label: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
