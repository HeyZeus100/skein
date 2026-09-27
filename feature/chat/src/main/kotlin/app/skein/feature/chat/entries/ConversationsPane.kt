// skein-xtov.24.8 (AL-09a): the Chat root on a rail window, the
// Conversations list (ADAPTIVE_LAYOUT_SPEC.md §4.1–4.2; CHAT_UX_SPEC.md §12):
// the drawer's history rows and date groups, in a pane.
package app.skein.feature.chat.entries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.skein.core.designsystem.components.SkeinEmptyState
import app.skein.core.designsystem.components.SkeinSectionHeader
import app.skein.core.designsystem.theme.SkeinLayout
import app.skein.core.navigation.ChatHomeKey
import app.skein.feature.shell.container.ChatHistoryItem
import app.skein.feature.shell.container.ChatHistoryRow
import app.skein.feature.shell.container.groupChatHistory
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.SkeinShellState
import java.time.ZoneId

/** ponytail: no "⌕ Search chats" field until chat search exists (§12.4, ask B7); the palette is AL-12's. */
@Composable
internal fun ConversationsPane(
    shell: SkeinShellState,
    history: List<ChatHistoryItem>,
    nowMillis: Long,
    zone: ZoneId,
) {
    Column(Modifier.fillMaxSize().testTag(ChatEntryTestTags.CONVERSATIONS)) {
        shell.EntryTopBar(ChatHomeKey, "Chats")
        if (history.isEmpty()) {
            // §11.2: the one next action is ✎, in the rail beside this pane.
            SkeinEmptyState(
                headline = "No chats yet",
                body = "Chats are stored encrypted on this device.",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        val groups = groupChatHistory(history, nowMillis, zone)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            groups.forEach { group ->
                item(key = "group-${group.label}") { SkeinSectionHeader(group.label) }
                items(group.items, key = { it.id }) { row ->
                    ChatHistoryRow(row, Modifier.padding(horizontal = SkeinLayout.expandedListPaneGutter))
                }
            }
        }
    }
}
