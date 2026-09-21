package app.skein.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import us.aherrera.skein.core.model.Document

/** Plan `E6.I7`: "in rail mode the timeline shows only kind glyphs for the 20 most recent items". */
public const val RAIL_ITEMS: Int = 20

/**
 * Collapsed-timeline rendering for the shell's 40 px icon rail (spec
 * §8.2): one glyph per document for the [RAIL_ITEMS] most recent entries
 * of the current [TimelineState] window, no text, tap opens. The host
 * decides the rail's width (`SkeinTokens.railWidth`); cells here are
 * square at [RAIL_CELL].
 */
@Composable
public fun TimelineRail(
    state: TimelineState,
    onEntryClick: (Document) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by state.entries.collectAsState()
    val visible = remember(entries) { entries.take(RAIL_ITEMS) }
    LazyColumn(
        modifier = modifier.fillMaxHeight().testTag(TimelineTestTags.RAIL),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = visible, key = { it.id }) { document ->
            val label = kindLabel(document.kind)
            Box(
                modifier =
                    Modifier
                        .size(RAIL_CELL)
                        .clickable { onEntryClick(document) }
                        .testTag(TimelineTestTags.railEntry(document.id))
                        .clearAndSetSemantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = kindGlyph(document.kind),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val RAIL_CELL = 40.dp
