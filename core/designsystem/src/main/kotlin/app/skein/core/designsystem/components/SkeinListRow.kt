// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.2, §10.3, §10.9, §10.12;
// INFORMATION_ARCHITECTURE.md §8a; KNOWLEDGE_UX_SPEC.md §3.3, §3.7): the list
// row every list is built from — chat history, conversations pane,
// Knowledge, models, settings.
package app.skein.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * A list row (§10.9): optional [leadingIcon] (24 dp, `onSurfaceVariant`),
 * a one-line [title] (`bodyLarge`), optional [supportingText] (`bodyMedium`
 * `onSurfaceVariant`, up to [supportingMaxLines]) and optional
 * [trailingMeta] (`labelSmall`, e.g. "2h"). The whole row is [onClick]'s
 * target; its height starts at [minHeight] and grows with the text (§3.5
 * rule 5: taller, never vertically truncated).
 *
 * **[menuActions]** (§10.12 order: frequent first, destructive last) are
 * reachable three equivalent ways:
 * - the ⋮ button, which replaces [trailingMeta] only on the **selected**
 *   row and on a **hovered or keyboard-focused** row (IA §8a / §10.3 — no
 *   column of identical icons);
 * - **long-press** or **right-click** anywhere on the row, which opens the
 *   same menu;
 * - **TalkBack custom actions** ("Rename", "Delete"), so nobody has to find
 *   the ⋮; the long-press is labelled "Show options".
 *
 * [selected] (the open chat, the open note) paints the row
 * `secondaryContainer` with a 12 dp radius, sets the title to weight 600 and
 * exposes `selected = true` (§7.2, §14.16). Horizontal inset is the caller's
 * (`Modifier.padding(horizontal = 12.dp)` for drawer/list-pane pills).
 *
 * [moreOptionsLabel] is the ⋮'s content description (§11.4:
 * "More options for “Skein UX redesign”").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SkeinListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    supportingMaxLines: Int = 1,
    @DrawableRes leadingIcon: Int? = null,
    trailingMeta: String? = null,
    selected: Boolean = false,
    menuActions: List<SkeinAction> = emptyList(),
    moreOptionsLabel: String = "More options for “$title”",
    minHeight: Dp = if (supportingText == null) SkeinSize.rowOneLine else SkeinSize.rowTwoLine,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var hasFocus by remember { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val hasMenu = menuActions.isNotEmpty()
    val showMore = hasMenu && (selected || hovered || hasFocus || menuOpen)
    val openMenu = { menuOpen = true }

    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    val mutedColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    // hasFocus (not isFocused): stays true while focus is on the ⋮ inside the row.
                    .onFocusChanged { hasFocus = it.hasFocus }
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .onSecondaryClick(openMenu)
                    // TODO(skein-xtov.23.9 DS9): add skeinFocusRing() once it lands.
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClick = onClick,
                        onLongClick = if (hasMenu) openMenu else null,
                        onLongClickLabel = if (hasMenu) "Show options" else null,
                    ).semantics {
                        if (selected) this.selected = true
                        if (hasMenu) {
                            customActions =
                                menuActions.map { action ->
                                    CustomAccessibilityAction(action.accessibilityLabel) {
                                        action.onClick()
                                        true
                                    }
                                }
                        }
                    }.padding(start = SkeinSpacing.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    painter = painterResource(leadingIcon),
                    contentDescription = null,
                    tint = mutedColor,
                    modifier = Modifier.size(SkeinSize.iconStandard),
                )
                Spacer(Modifier.width(SkeinSpacing.space16))
            }
            Column(
                modifier = Modifier.weight(1f).padding(vertical = SkeinSpacing.space8),
            ) {
                Text(
                    text = title,
                    style = typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.W600 else null,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = typography.bodyMedium,
                        color = mutedColor,
                        maxLines = supportingMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                showMore ->
                    IconButton(onClick = openMenu) {
                        Icon(
                            painter = painterResource(SkeinIcons.More),
                            contentDescription = moreOptionsLabel,
                            tint = mutedColor,
                        )
                    }
                trailingMeta != null ->
                    Text(
                        text = trailingMeta,
                        style = typography.labelSmall,
                        color = mutedColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = SkeinSpacing.space16),
                    )
                else -> Spacer(Modifier.width(SkeinSpacing.space16))
            }
        }
        if (hasMenu) {
            // A zero-size anchor at the row's bottom end: the menu opens under the ⋮, whichever way it was opened.
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                Box {
                    RowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = menuActions)
                }
            }
        }
    }
}

/**
 * The row menu (§10.12): non-destructive items in the caller's order, then a
 * divider and the destructive items in `error`.
 *
 * TODO(skein-xtov.23.7 DS7): Material's `DropdownMenu` defaults here (it
 * still draws its 3 dp shadow); swap in DS7's shadow-free menu defaults
 * (`surfaceContainer`, radius 8, 1 dp `outlineVariant`, elevation 0).
 */
@Composable
private fun RowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<SkeinAction>,
) {
    val (destructive, regular) = actions.partition { it.destructive }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        regular.forEach { MenuItem(it, onDismiss) }
        if (destructive.isNotEmpty() && regular.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = SkeinSpacing.space8),
                thickness = SkeinSize.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        destructive.forEach { MenuItem(it, onDismiss) }
    }
}

@Composable
private fun MenuItem(
    action: SkeinAction,
    onDismiss: () -> Unit,
) {
    val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(action.label, style = MaterialTheme.typography.bodyLarge, color = color) },
        onClick = {
            onDismiss()
            action.onClick()
        },
        leadingIcon =
            action.icon?.let { icon ->
                {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = if (action.destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
    )
}

/**
 * Right-click (a secondary-button press) runs [action] and consumes the
 * press, so it never also clicks. Keyed on `Unit`: [action] must only touch
 * remembered state (it is captured once), as the row's `openMenu` does.
 */
private fun Modifier.onSecondaryClick(action: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    action()
                }
            }
        }
    }
