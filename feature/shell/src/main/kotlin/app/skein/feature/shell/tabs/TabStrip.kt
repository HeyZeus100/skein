package app.skein.feature.shell.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Wide-mode horizontal tab strip (spec §8.3, plan `E6.I5`): one row, italic
 * names for the (at most one) preview tab, solid for pinned tabs, `×` to
 * close, tap-and-hold for [TabContextMenu]. Scrolls horizontally on overflow
 * rather than wrapping or shrinking below a readable width.
 *
 * Touch has no reliable hover state, so — unlike the desktop-only "close on
 * hover" in the spec's Cursor inspiration — the `×` is always visible here;
 * long-press is the touch equivalent of Cursor's right-click menu.
 *
 * The `⧉` button at the strip's trailing edge (spec §8.3, plan `E6.I6`) is
 * the other way into split view besides the long-press menu's "Open in
 * split" item — it acts on whichever tab is currently [activeId]. Both call
 * the same [onOpenInSplit]; [splitAvailable] (Expanded width only — see
 * [app.skein.feature.shell.layout.AdaptiveLayoutResult]) gates both
 * affordances identically, so the button and the menu item never disagree
 * about whether split is currently offered.
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
    Row(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
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
        if (splitAvailable) {
            SplitButton(
                enabled = activeId != null,
                onClick = { activeId?.let(onOpenInSplit) },
            )
        }
    }
}

/**
 * `⧉` — opens the active tab into the secondary split pane (spec §8.3, plan
 * `E6.I6`). Disabled rather than hidden when there's no active tab, so the
 * strip's trailing edge doesn't jump around as tabs open and close.
 */
@Composable
private fun SplitButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalSkeinTokens.current
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "Open in split" },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = tokens.glyphs.split,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                ),
        )
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
