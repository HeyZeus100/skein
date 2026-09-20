package app.skein.feature.shell.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Wide-mode horizontal tab strip (spec §8.3, plan `E6.I5`): one row, italic
 * names for the (at most one) preview tab, solid for pinned tabs, `×` to
 * close, tap-and-hold for [TabContextMenu]. Scrolls horizontally on overflow
 * rather than wrapping or shrinking below a readable width.
 *
 * Touch has no reliable hover state, so — unlike the desktop-only "close on
 * hover" in the spec's Cursor inspiration — the `×` is always visible here;
 * long-press is the touch equivalent of Cursor's right-click menu.
 */
@Composable
fun TabStrip(
    tabs: List<Tab>,
    activeId: TabId?,
    onActivate: (TabId) -> Unit,
    onPin: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
    onCloseOthers: (TabId) -> Unit,
    onOpenInSplit: (TabId) -> Unit,
    splitAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
    ) {
        tabs.forEach { tab ->
            TabChip(
                tab = tab,
                active = tab.id == activeId,
                canCloseOthers = tabs.size > 1,
                splitAvailable = splitAvailable,
                onActivate = { onActivate(tab.id) },
                onPin = { onPin(tab.id) },
                onClose = { onClose(tab.id) },
                onCloseOthers = { onCloseOthers(tab.id) },
                onOpenInSplit = { onOpenInSplit(tab.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    tab: Tab,
    active: Boolean,
    canCloseOthers: Boolean,
    splitAvailable: Boolean,
    onActivate: () -> Unit,
    onPin: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onOpenInSplit: () -> Unit,
) {
    var menuExpanded by rememberSaveable(tab.id.value) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val titleColor = if (active) colors.onSurface else colors.onSurfaceVariant

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .combinedClickable(
                        onClick = onActivate,
                        onDoubleClick = onPin,
                        onLongClick = { menuExpanded = true },
                    ).padding(horizontal = 12.dp),
        ) {
            BasicText(text = tab.kind.glyph)
            Box(modifier = Modifier.padding(start = 6.dp, end = 6.dp)) {
                BasicText(
                    text = tab.title,
                    style =
                        MaterialTheme.typography.labelLarge.copy(
                            color = titleColor,
                            fontStyle = if (tab.pinned) FontStyle.Normal else FontStyle.Italic,
                        ),
                )
            }
            BasicText(
                text = "×", // ×
                modifier =
                    Modifier
                        .padding(PaddingValues(start = 2.dp))
                        .combinedClickable(onClick = onClose),
                style = MaterialTheme.typography.labelLarge.copy(color = colors.onSurfaceVariant),
            )
        }
        TabContextMenu(
            expanded = menuExpanded,
            tab = tab,
            canCloseOthers = canCloseOthers,
            splitAvailable = splitAvailable,
            onDismiss = { menuExpanded = false },
            onPin = { onPin() },
            onClose = { onClose() },
            onCloseOthers = { onCloseOthers() },
            onOpenInSplit = { onOpenInSplit() },
        )
    }
}
