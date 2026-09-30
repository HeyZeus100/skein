package app.skein.feature.chat.entries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.skein.core.designsystem.components.SkeinDropdownMenu
import app.skein.core.designsystem.components.rememberSkeinMenuAnchor
import app.skein.core.designsystem.components.skeinMenuAnchor
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.model.DocId
import app.skein.feature.chat.ChatTurnController
import app.skein.feature.chat.ChatTurnState
import app.skein.feature.shell.host.EntryAction

internal const val CHAT_DELETE_BUSY_REASON = "Stop the answer to delete this chat."

internal fun chatDeleteDisabledReason(turn: ChatTurnState?): String? =
    when (turn) {
        ChatTurnState.Queued, is ChatTurnState.Thinking, is ChatTurnState.Streaming -> CHAT_DELETE_BUSY_REASON
        null, ChatTurnState.Done, is ChatTurnState.Interrupted, is ChatTurnState.Failed -> null
    }

@Composable
internal fun rememberChatDeleteDisabledReason(
    turns: ChatTurnController?,
    chatId: DocId,
): String? {
    val state = remember(turns, chatId) { turns?.state(chatId) }?.collectAsState()
    return chatDeleteDisabledReason(state?.value?.turn)
}

/** Check again at activation: an answer may have queued while the menu was open. */
internal fun requestChatDelete(
    chatId: DocId,
    turns: ChatTurnController?,
    request: (DocId) -> Unit,
) {
    if (chatDeleteDisabledReason(turns?.state(chatId)?.value?.turn) == null) request(chatId)
}

/** Only stored chat routes compose this menu; the action requests confirmation. */
@Composable
internal fun ChatDeleteMenu(
    disabledReason: String?,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberSkeinMenuAnchor()
    val enabled = disabledReason == null
    val color =
        if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(Modifier.skeinMenuAnchor(anchor)) {
        EntryAction(SkeinIcons.More, "Chat options", Modifier.testTag(ChatEntryTestTags.DELETE_MENU)) {
            expanded = true
        }
        SkeinDropdownMenu(expanded, { expanded = false }, anchor.boundsInWindow) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Delete…", style = MaterialTheme.typography.bodyLarge, color = color)
                        disabledReason?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                enabled = enabled,
                leadingIcon = { Icon(painterResource(SkeinIcons.Delete), contentDescription = null, tint = color) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
