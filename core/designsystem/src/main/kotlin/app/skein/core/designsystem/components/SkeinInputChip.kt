// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §10.7): an item attached to
// the message being written (a note, file or chat), removable with its own
// TalkBack-labelled ✕.
package app.skein.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize

/** §10.7: the input chip's title caps at 200 dp, end-ellipsis. */
private val InputChipLabelMaxWidth = 200.dp

/**
 * An item attached to the message being written (§10.7): `surfaceContainerHigh`,
 * an optional leading kind icon, a title capped at 200 dp with an end
 * ellipsis, and a trailing remove action with its own 48 dp target and
 * [removeLabel] TalkBack description (never the bare glyph alone). [onClick]
 * is optional — a screen that adopts this chip in a later wave can wire the
 * body tap to open the attached item; left `null` it's inert except for
 * removal.
 */
@Composable
fun SkeinInputChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    removeLabel: String = "Remove $label",
    onClick: (() -> Unit)? = null,
) {
    InputChip(
        selected = false,
        onClick = { onClick?.invoke() },
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = InputChipLabelMaxWidth),
            )
        },
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .skeinFocusRing(cornerRadius = SkeinRadius.radiusSm),
        leadingIcon =
            leadingIcon?.let { icon ->
                {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(SkeinSize.iconChip),
                    )
                }
            },
        trailingIcon = {
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(SkeinIcons.Close),
                    contentDescription = removeLabel,
                    modifier = Modifier.size(SkeinSize.iconChip),
                )
            }
        },
        shape = RoundedCornerShape(SkeinRadius.radiusSm),
        colors =
            InputChipDefaults.inputChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        border = null,
    )
}
