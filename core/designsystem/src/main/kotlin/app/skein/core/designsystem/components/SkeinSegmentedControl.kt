// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.9, §7.2): the
// single-choice segmented control (Settings › Appearance "System · Light ·
// Dark"). Material 3 1.4.0's `SingleChoiceSegmentedButtonRow` is stable
// (checked with `javap`: no `ExperimentalMaterial3Api` reference) and already
// gives each segment radio semantics inside a selectable group.
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.skein.core.designsystem.icons.SkeinIcons

/**
 * A single-choice segmented control (§10.9): one segment per [options] entry,
 * labelled by [label], with [selected] marked. 40 dp visual, 48 dp touch,
 * radius 12 on the outer ends, the selected segment `secondaryContainer` with
 * a `check` icon and a weight-600 label (§7.2: never colour alone). Each
 * segment is a `Role.RadioButton` with `selected` semantics, in a
 * `selectableGroup`. Fills the width it is given; labels may wrap at large
 * font scales rather than ellipsise.
 */
@Composable
fun <T> SkeinSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size, MaterialTheme.shapes.medium),
                icon = {
                    SegmentedButtonDefaults.Icon(
                        active = isSelected,
                        activeContent = {
                            Icon(
                                painter = painterResource(SkeinIcons.Check),
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        },
                    )
                },
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.W600 else FontWeight.W500,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
