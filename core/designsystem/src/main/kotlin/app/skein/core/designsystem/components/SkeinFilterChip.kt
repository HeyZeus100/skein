// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §10.7): the Knowledge scope
// filter chip ("All · Notes · Files · AI outputs") — single-select, built on
// Material's `FilterChip`.
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
 * Single-select filter chip (§10.7): 1 dp `outlineVariant` and muted label
 * unselected; `secondaryContainer`/`onSecondaryContainer` with a leading
 * check when [selected]. 32 dp visual height, 48 dp touch
 * ([minimumInteractiveComponentSize]), keyboard focus ring from
 * [Modifier.skeinFocusRing]. The caller enforces single-select (at most one
 * chip in a row reports `selected = true`) — this composable only renders
 * one chip.
 */
@Composable
fun SkeinFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .skeinFocusRing(cornerRadius = SkeinRadius.radiusSm),
        enabled = enabled,
        leadingIcon =
            if (selected) {
                {
                    Icon(
                        painter = painterResource(SkeinIcons.Check),
                        contentDescription = null,
                        modifier = Modifier.size(SkeinSize.iconChip),
                    )
                }
            } else {
                null
            },
        shape = RoundedCornerShape(SkeinRadius.radiusSm),
        colors =
            FilterChipDefaults.filterChipColors(
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = enabled,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                borderWidth = SkeinSize.hairline,
            ),
    )
}
