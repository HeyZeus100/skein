package app.skein.feature.shell.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.LocalSkeinTokens
import app.skein.feature.shell.theme.SkeinTokens

/** One hamburger-drawer entry: its [Destination], display glyph, and label (spec §8.2 order). */
private data class DrawerEntry(
    val destination: Destination,
    val glyph: String,
    val label: String,
)

private fun drawerEntries(glyphs: SkeinTokens.Glyphs): List<DrawerEntry> =
    listOf(
        DrawerEntry(Destination.TIMELINE, glyphs.timeline, "Timeline"),
        DrawerEntry(Destination.NOTES, glyphs.notes, "Notes"),
        DrawerEntry(Destination.GRAPH, glyphs.graph, "Graph"),
        DrawerEntry(Destination.PERSONAS, glyphs.personas, "Personas"),
        // Reuses the context-panel glyph (⚹) — IconRail already does the same for Settings (spec §8.2).
        DrawerEntry(Destination.SETTINGS, glyphs.context, "Settings"),
    )

/**
 * `ModalNavigationDrawer` (Material 3) hosting the five hamburger
 * destinations (spec §8.2, plan `E6.I3`): Timeline · Notes · Graph ·
 * Personas · Settings, in that order. Modal at every width — there is no
 * `PermanentNavigationDrawer` variant, so the dual-pane 30/70 split (spec
 * §8.2) is never squeezed by a persistent drawer rail.
 *
 * State-hoisted: [open] and [activeDestination] are owned by the caller
 * (typically [NavState]); this composable only renders and reports intent
 * via [onNavigate] / [onDismiss].
 *
 * @param content the app content the drawer sits above — the command bar +
 *   [app.skein.feature.shell.layout.AdaptivePaneHost] in [app.skein.feature.shell.SkeinApp].
 */
@Composable
fun NavDrawer(
    open: Boolean,
    activeDestination: Destination,
    onNavigate: (Destination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalSkeinTokens.current
    val drawerState = rememberDrawerState(initialValue = if (open) DrawerValue.Open else DrawerValue.Closed)

    LaunchedEffect(open) {
        if (open) drawerState.open() else drawerState.close()
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }
            .collect { value -> if (value == DrawerValue.Closed) onDismiss() }
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                drawerEntries(tokens.glyphs).forEach { entry ->
                    NavigationDrawerItem(
                        label = { Text("${entry.glyph}  ${entry.label}") },
                        selected = entry.destination == activeDestination,
                        onClick = { onNavigate(entry.destination) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
        content = content,
    )
}
