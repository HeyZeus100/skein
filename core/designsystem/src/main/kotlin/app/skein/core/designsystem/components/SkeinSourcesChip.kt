// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §10.7, §10.18): the answer
// footer's sources chip ("3 sources ›").
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize

/**
 * The answer footer's "3 sources ›" chip (§10.7, §10.18): a leading
 * `format_quote` mark, the count in words (never "1 sources"), a trailing
 * chevron. Tap opens the context inspector at this answer's sources.
 */
@Composable
fun SkeinSourcesChip(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (count == 1) "1 source" else "$count sources"
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .skeinFocusRing(cornerRadius = SkeinRadius.radiusSm),
        leadingIcon = {
            Icon(
                painter = painterResource(SkeinIcons.Sources),
                contentDescription = null,
                modifier = Modifier.size(SkeinSize.iconChip),
            )
        },
        trailingIcon = {
            Icon(
                painter = painterResource(SkeinIcons.Expand),
                contentDescription = null,
                modifier = Modifier.size(SkeinSize.iconChip),
            )
        },
        shape = RoundedCornerShape(SkeinRadius.radiusSm),
        colors =
            AssistChipDefaults.assistChipColors(
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        border =
            AssistChipDefaults.assistChipBorder(
                enabled = true,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                borderWidth = SkeinSize.hairline,
            ),
    )
}
