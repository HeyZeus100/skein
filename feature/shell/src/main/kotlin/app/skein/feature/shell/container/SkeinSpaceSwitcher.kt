// skein-xtov.24.6 (AL-07, UX Wave 3): the Space switcher slot
// (`INFORMATION_ARCHITECTURE.md` §8b) at the top of the drawer and the
// rail.
package app.skein.feature.shell.container

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpRect
import app.skein.core.designsystem.components.SkeinDropdownMenu
import app.skein.core.designsystem.components.skeinFocusRing
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * The current [SkeinSpace], tap to switch (§8b Level 2). Renders nothing
 * while [spaces] has fewer than two entries — with one Space (the
 * default), nothing about Spaces shows (§8b Level 1).
 *
 * [compact] trims the row to a circular initial for the 80 dp collapsed
 * rail, where a full name would not fit; the drawer and the expanded rail
 * use the full name.
 */
@Composable
fun SkeinSpaceSwitcher(
    spaces: List<SkeinSpace>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (spaces.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<DpRect?>(null) }
    val density = LocalDensity.current
    val current = spaces.firstOrNull { it.isSelected } ?: spaces.first()

    // compact (the 80 dp collapsed rail): a fixed touch-target square, no
    // `fillMaxWidth` — inside `NavigationRail`'s `header` slot, a
    // fill-width child forces the rail itself to measure (and paint) at
    // the Row's full remaining width instead of its own 80 dp, squeezing
    // the content slot beside it to zero (this bead's own regression, see
    // `SkeinNavigationContainerScreenshotTest`'s `rail` golden).
    val sizing =
        if (compact) {
            Modifier.size(SkeinSize.touchTarget)
        } else {
            Modifier.fillMaxWidth().heightIn(min = SkeinSize.touchTarget).padding(horizontal = SkeinSpacing.space16)
        }

    Row(
        modifier =
            modifier
                .then(sizing)
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = { expanded = true })
                .skeinFocusRing()
                .testTag(SkeinNavContainerTestTags.SPACE_SWITCHER)
                .onGloballyPositioned { coordinates ->
                    anchorBounds =
                        with(density) {
                            coordinates.boundsInWindow().run {
                                DpRect(
                                    left.toDp(),
                                    top.toDp(),
                                    right.toDp(),
                                    bottom.toDp(),
                                )
                            }
                        }
                },
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (compact) current.name.take(1).uppercase() else current.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = !compact),
        )
        if (!compact) {
            Icon(
                painter = painterResource(SkeinIcons.Expand),
                contentDescription = null,
                modifier = Modifier.size(SkeinSize.iconChip),
            )
        }
        SkeinDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, anchorBounds = anchorBounds) {
            spaces.forEach { space ->
                DropdownMenuItem(
                    text = { Text(space.name) },
                    onClick = {
                        expanded = false
                        space.onSelect()
                    },
                )
            }
        }
    }
}
