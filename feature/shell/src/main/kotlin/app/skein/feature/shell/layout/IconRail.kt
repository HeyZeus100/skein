package app.skein.feature.shell.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.LocalSkeinTokens

/** One glyph entry in the collapsed-timeline [IconRail] (spec §8.2). */
data class RailEntry(
    val glyph: String,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/**
 * Collapsed-timeline icon rail (spec §8.2: "40 px icon rail"). Width comes
 * from [app.skein.feature.shell.theme.SkeinTokens.railWidth] — never
 * hardcoded here — so the shell's one source of truth for that dimension
 * stays in `feature/shell/theme`.
 *
 * Default entries per this issue: expand the timeline back out (`▸`),
 * jump to the timeline (`◐`), the local graph (`✦`), personas (`◈`), and
 * settings (`⚹`). Callers of [AdaptivePaneHost] may override the list;
 * real navigation destinations land in their own issues (`E6.I4`–`E6.I14`).
 */
@Composable
fun IconRail(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onTimeline: () -> Unit = {},
    onGraph: () -> Unit = {},
    onPersonas: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val tokens = LocalSkeinTokens.current
    val entries =
        listOf(
            RailEntry("▸", "Expand timeline", onExpand),
            RailEntry("◐", "Timeline", onTimeline),
            RailEntry("✦", "Graph", onGraph),
            RailEntry("◈", "Personas", onPersonas),
            RailEntry("⚹", "Settings", onSettings),
        )
    Column(
        modifier =
            modifier
                .width(tokens.railWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(tokens.railWidth)
                        .clickable(onClick = entry.onClick)
                        .semantics { contentDescription = entry.contentDescription },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.glyph,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
