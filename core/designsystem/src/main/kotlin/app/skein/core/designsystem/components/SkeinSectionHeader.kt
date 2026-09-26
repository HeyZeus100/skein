// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.2, §10.9): the group
// header above a run of rows ("Today", "Recent", "Security").
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * A section header (§10.9): `titleSmall` in `onSurfaceVariant` — never
 * accent-coloured — at least 40 dp tall, text at the 16 dp alignment line, a
 * `heading()` for TalkBack. Not focusable, not sticky; groups are separated
 * by space and this header, not by dividers between rows.
 */
@Composable
fun SkeinSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SECTION_HEADER_MIN_HEIGHT)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = SkeinSpacing.space16)
                .semantics { heading() },
    )
}

/** §10.2: group headers are 40 dp tall — no spacing token names 40 dp as a height. */
private val SECTION_HEADER_MIN_HEIGHT = 40.dp
