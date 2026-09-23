// skein-6as (E6.I8). The chat message list (spec §8.4): persisted turns
// from `ChatViewModel.messages`, plus a trailing live bubble driven by
// `ChatViewModel.turnState` while a turn is in flight (`Thinking`/
// `Streaming`). `messages` (from `VaultRepository.observeMessages`) and
// `turnState` are updated by two independent coroutines with no ordering
// guarantee between them, so the trailing bubble is additionally guarded by
// `messages.lastOrNull()?.role != Role.ASSISTANT` below — once the
// persisted assistant row is visible, the live bubble is suppressed
// regardless of whether `turnState` has caught up to `Done` yet. Keeps the
// two sources of truth mutually exclusive without an artificial join.
package app.skein.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.model.Role

public const val MESSAGE_LIST_TEST_TAG: String = "app.skein.feature.chat.MessageList"

/** Test tag for a message row's outer container, keyed by [ChatMessageUi.id]. */
public fun messageRowTestTag(messageId: String): String = "app.skein.feature.chat.MessageRow.$messageId"

@Composable
public fun MessageList(
    messages: List<ChatMessageUi>,
    turnState: ChatTurnState?,
    streamingCitations: Map<Int, ChatCitation>,
    isExcerptExpanded: (messageId: String, marker: Int) -> Boolean,
    onCitationTap: (messageId: String, ChatCitation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount =
        messages.size +
            if (turnState is ChatTurnState.Streaming ||
                turnState is ChatTurnState.Thinking ||
                turnState is ChatTurnState.Queued
            ) {
                1
            } else {
                0
            }
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    val lastUserId = messages.lastOrNull { it.role == Role.USER }?.id

    LazyColumn(
        state = listState,
        modifier = modifier.testTag(MESSAGE_LIST_TEST_TAG),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            Box(modifier = Modifier.fillMaxWidth().testTag(messageRowTestTag(message.id))) {
                when (message.role) {
                    Role.USER -> {
                        val sentState =
                            if (message.id ==
                                lastUserId
                            ) {
                                turnState?.toSentMessageState()
                            } else {
                                SentMessageState.SETTLED
                            }
                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            UserBubble(text = message.displayText, state = sentState)
                        }
                    }
                    Role.ASSISTANT -> {
                        Box(modifier = Modifier.align(Alignment.CenterStart)) {
                            AssistantBubble(
                                message = message,
                                isExcerptExpanded = { marker -> isExcerptExpanded(message.id, marker) },
                                onCitationTap = { citation -> onCitationTap(message.id, citation) },
                            )
                        }
                    }
                    Role.SYSTEM -> {
                        Text(
                            text = message.displayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Guarded by `messages.lastOrNull()?.role != Role.ASSISTANT`, not just
        // `turnState`: `VaultRepository.observeMessages` and `turnState` are
        // updated by two independent coroutines (see `ChatViewModel.init`
        // and `send`), so there is no ordering guarantee between "the
        // persisted assistant row appears" and "`turnState` reaches `Done`".
        // Without this guard a single composition frame could render both
        // the trailing live bubble AND the now-persisted `AssistantBubble`
        // for the same turn — this keeps them mutually exclusive regardless
        // of which coroutine wins the race.
        val alreadyPersisted = messages.lastOrNull()?.role == Role.ASSISTANT
        if (!alreadyPersisted && turnState is ChatTurnState.Streaming) {
            item(key = "streaming") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.CenterStart)) {
                        StreamingAssistantBubble(
                            text = turnState.text,
                            citations = streamingCitations,
                            isExcerptExpanded = { marker -> isExcerptExpanded(STREAMING_MESSAGE_ID, marker) },
                            onCitationTap = { citation -> onCitationTap(STREAMING_MESSAGE_ID, citation) },
                        )
                    }
                }
            }
        } else if (!alreadyPersisted && (turnState is ChatTurnState.Thinking || turnState is ChatTurnState.Queued)) {
            item(key = "thinking") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.CenterStart)) {
                        ThinkingPlaceholder()
                    }
                }
            }
        }
    }
}

public fun userBubbleTestTag(state: SentMessageState?): String = "app.skein.feature.chat.UserBubble.${state ?: "none"}"

/**
 * The sent user bubble (owner scope addition): colour is driven by [state]
 * — `SkeinTheme`'s `MaterialTheme.colorScheme` tokens, never a literal
 * `Color(...)` — never the raw enum name rendered as prose.
 */
@Composable
public fun UserBubble(
    text: String,
    state: SentMessageState?,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        when (state) {
            SentMessageState.QUEUED -> MaterialTheme.colorScheme.surfaceVariant
            SentMessageState.PICKED_UP -> MaterialTheme.colorScheme.primaryContainer
            SentMessageState.SETTLED, null -> MaterialTheme.colorScheme.surface
        }
    Surface(
        modifier = modifier.testTag(userBubbleTestTag(state)),
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}
