// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §5.3, §7.2): a shadow-free
// tooltip wrapper. §5.3: "`DropdownMenu` and `PlainTooltip` take
// `shadowElevation` (set 0)"; §5.3 floating layers also get their level's
// container colour plus a 1 dp `outlineVariant` border.
package app.skein.core.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize

/**
 * Wraps [content] with a plain tooltip reading [text] (§7.2: hover, 500 ms
 * on icon buttons, then instant on neighbours — Material's own
 * `TooltipState`/[TooltipBox] timing). Shadow-free per §5.3: `shadowElevation
 * = 0.dp`, a 1 dp `outlineVariant` border and the theme's own
 * `surfaceContainer`/`onSurface` pair rather than Material's tinted
 * defaults. `TooltipBox`/`PlainTooltip` are still `@ExperimentalMaterial3Api`
 * in 1.4.0 (like `ModalBottomSheet`, §10.10) — absorbed here once rather
 * than pushed onto every call site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkeinTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(
                modifier =
                    Modifier.border(
                        SkeinSize.hairline,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(SkeinRadius.radiusXs),
                    ),
                shape = RoundedCornerShape(SkeinRadius.radiusXs),
                shadowElevation = 0.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}
