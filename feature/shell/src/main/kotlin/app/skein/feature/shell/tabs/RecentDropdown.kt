package app.skein.feature.shell.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Folded-phone replacement for [TabStrip] (spec §8.3: "Folded phone: tabs →
 * 'Recent ▾' dropdown"). Collapses the whole tab list into a single header
 * row showing the active tab; tapping it opens a menu of every open tab,
 * most-recently-activated first (see [TabsState.recentDropdown]).
 */
@Composable
fun RecentDropdown(
    tabs: List<Tab>,
    activeId: TabId?,
    onSelect: (TabId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val tokens = LocalSkeinTokens.current
    val active = tabs.firstOrNull { it.id == activeId }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(tokens.tabHeight)
                    .clickable(enabled = tabs.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp),
        ) {
            Text(
                text = active?.let { "${it.kind.glyph} ${it.title}" } ?: "Recent",
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontStyle = if (active != null && !active.pinned) FontStyle.Italic else FontStyle.Normal,
                    ),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(" ▾", color = MaterialTheme.colorScheme.onSurfaceVariant) // ▾
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tabs.forEach { tab ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${tab.kind.glyph} ${tab.title}",
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = if (tab.pinned) FontStyle.Normal else FontStyle.Italic,
                                ),
                        )
                    },
                    onClick = {
                        onSelect(tab.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
