// skein-6as (E6.I8). The small "[N]" badge used by `ContextPanel` rows.
// Inline citation markers *inside* assistant answer text are styled by
// `core/markdown`'s `MarkdownRenderer` (`MarkdownStyle.citationStyle`,
// applied via its `TAG_CITATION` annotation range — see
// `MarkdownWithCitations` in `AssistantBubble.kt`) rather than this
// composable, since that text has to stay inline with the surrounding
// Markdown-rendered prose; this file is the standalone badge used wherever
// a citation needs its own box (the context panel).
package app.skein.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag for the citation chip badge at [marker] (context panel row, or in-bubble excerpt toggle). */
public fun citationChipTestTag(marker: Int): String = "app.skein.feature.chat.CitationChip.$marker"

@Composable
public fun CitationChip(
    marker: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "[$marker]",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .testTag(citationChipTestTag(marker))
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(CHIP_CORNER_RADIUS))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private val CHIP_CORNER_RADIUS = 4.dp
