// skein-xtov.24.6 (AL-07, UX Wave 3): the modal drawer's contents
// (ADAPTIVE_LAYOUT_SPEC.md §3.3, INFORMATION_ARCHITECTURE.md §3.4): the
// Space switcher (§8b, only ≥ 2 Spaces), New chat, "Search or run a
// command", the five destinations, then Chats grouped by date (§12.2)
// with the §8a ⋮ rule. [SkeinListRow] already owns that rule (⋮ only on
// the selected row, or a hovered/keyboard-focused one, with long-press and
// TalkBack custom actions everywhere) — this file just feeds it
// Rename/Delete as [app.skein.core.designsystem.components.SkeinAction]s.
package app.skein.feature.shell.container

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinListRow
import app.skein.core.designsystem.components.SkeinSectionHeader
import app.skein.core.designsystem.components.skeinFocusRing
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing
import java.time.ZoneId

/**
 * Drawer contents, top to bottom (spec §3.3): the Space switcher, `[✎ New
 * chat]`, `⌕ Search or run a command`, the destinations, then `Chats`
 * grouped Today / Yesterday / Previous 7 days / Previous 30 days / by
 * month.
 *
 * [onSearch] and every destination/history-row callback are the caller's
 * (typically wrapped by [SkeinNavigationContainer] to also close the
 * drawer) — this composable only renders.
 */
@Composable
fun SkeinDrawerContent(
    destination: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    history: List<ChatHistoryItem>,
    spaces: List<SkeinSpace>,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
) {
    // ponytail: re-groups on every `history` change but not on a bare clock
    // tick, so a day boundary crossed while the drawer stays open (rare)
    // waits for the next history change to move a row from "Today" to
    // "Yesterday". Re-key on a coarser clock (e.g. the current LocalDate)
    // if that turns out to matter.
    val groups = groupChatHistory(history, now(), zone)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "space-switcher") {
            SkeinSpaceSwitcher(spaces, Modifier.padding(top = SkeinSpacing.space8))
        }
        item(key = "new-chat") {
            Button(
                onClick = onNewChat,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SkeinSpacing.space16, vertical = SkeinSpacing.space8)
                        .skeinFocusRing(),
            ) {
                Icon(
                    painter = painterResource(SkeinIcons.NewChat),
                    contentDescription = null,
                    modifier = Modifier.size(SkeinSize.iconStandard),
                )
                Text(" New chat", modifier = Modifier.padding(start = SkeinSpacing.space8))
            }
        }
        item(key = "search") {
            SkeinListRow(
                title = "Search or run a command",
                leadingIcon = SkeinIcons.Search,
                onClick = onSearch,
            )
        }
        items(SkeinDestination.entries, key = { "dest-${it.name}" }) { d ->
            SkeinListRow(
                title = d.label,
                leadingIcon = if (d == destination) d.iconSelected else d.icon,
                selected = d == destination,
                onClick = { onNavigate(d) },
            )
        }
        if (history.isNotEmpty()) {
            item(key = "chats-divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = SkeinSpacing.space8),
                    thickness = SkeinSize.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            item(key = "chats-header") { SkeinSectionHeader("Chats") }
            groups.forEach { group ->
                item(key = "group-${group.label}") { SkeinSectionHeader(group.label) }
                items(group.items, key = { it.id }) { row -> ChatHistoryRow(row) }
            }
        }
    }
}

@Composable
private fun ChatHistoryRow(item: ChatHistoryItem) {
    SkeinListRow(
        title = item.title,
        onClick = item.onOpen,
        supportingText = item.preview,
        trailingMeta = if (item.isAnswering) "Answering…" else item.timeLabel,
        selected = item.isSelected,
        menuActions =
            listOf(
                SkeinAction(label = "Rename…", icon = SkeinIcons.Rename, onClick = item.onRename),
                SkeinAction(label = "Delete…", icon = SkeinIcons.Delete, destructive = true, onClick = item.onDelete),
            ),
    )
}
