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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.LocalSkeinColors
import app.skein.core.model.Role
import app.skein.core.navigation.SkeinId
import kotlinx.coroutines.launch

public const val LATEST_MESSAGES_TEST_TAG: String = "app.skein.feature.chat.LatestMessages"

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
    // Restore by message identity, not the numeric index (new turns prepend in reverse layout).
    // Only a canonical id, an offset and a flag enter saved state; transcript text never does.
    val position = rememberSaveable(saver = TranscriptPosition.Saver) { TranscriptPosition() }
    val listState = remember { LazyListState() }
    val scope = rememberCoroutineScope()
    val alreadyPersisted = messages.lastOrNull()?.role == Role.ASSISTANT
    val liveKey =
        when {
            alreadyPersisted -> null
            turnState is ChatTurnState.Streaming -> "streaming"
            turnState is ChatTurnState.Thinking || turnState is ChatTurnState.Queued -> "thinking"
            else -> null
        }
    val itemKeys = listOfNotNull(liveKey) + messages.asReversed().map { it.id }
    LaunchedEffect(itemKeys) {
        if (itemKeys.isEmpty()) return@LaunchedEffect
        val index = if (position.following) 0 else itemKeys.indexOf(position.messageId).coerceAtLeast(0)
        listState.scrollToItem(index, if (position.following) 0 else position.offset)
        snapshotFlow {
            val first =
                listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.index == listState.firstVisibleItemIndex
                }
            // Ignore the previous layout while a new dataset is being measured.
            if (first != null &&
                listState.layoutInfo.totalItemsCount == itemKeys.size &&
                itemKeys.getOrNull(first.index) == first.key
            ) {
                Triple(first.key as String, listState.firstVisibleItemScrollOffset, first.index == 0)
            } else {
                null
            }
        }.collect { anchor ->
            if (anchor != null) {
                position.messageId = anchor.first
                position.offset = anchor.second
                position.following = anchor.third && anchor.second == 0
            }
        }
    }

    val lastUserId = messages.lastOrNull { it.role == Role.USER }?.id

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag(MESSAGE_LIST_TEST_TAG),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Guarded by `messages.lastOrNull()?.role != Role.ASSISTANT`, not just
            // `turnState`: `VaultRepository.observeMessages` and `turnState` are
            // updated by two independent coroutines (see `ChatViewModel.init`
            // and `send`), so there is no ordering guarantee between "the
            // persisted assistant row appears" and "`turnState` reaches `Done`".
            // Without this guard a single composition frame could render both
            // the trailing live bubble AND the now-persisted `AssistantBubble`
            // for the same turn — this keeps them mutually exclusive regardless
            // of which coroutine wins the race.
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
            } else if (!alreadyPersisted &&
                (turnState is ChatTurnState.Thinking || turnState is ChatTurnState.Queued)
            ) {
                item(key = "thinking") {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.align(Alignment.CenterStart)) {
                            ThinkingPlaceholder()
                        }
                    }
                }
            }
            items(messages.asReversed(), key = { it.id }) { message ->
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
        }
        if (!position.following) {
            Button(
                onClick = {
                    position.following = true
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag(LATEST_MESSAGES_TEST_TAG),
            ) { Text("Latest messages") }
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
            // skein-xtov.23.2: boxed in its own colour so it stays distinct from the
            // unboxed assistant answer now that `surface` = the page (§6.3, §10.15).
            SentMessageState.SETTLED, null -> LocalSkeinColors.current.userMessageContainer
        }
    Surface(
        modifier = modifier.testTag(userBubbleTestTag(state)),
        color = containerColor,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSkeinColors.current.onUserMessage,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Saveable projection deliberately contains no message content or arbitrary imported identifiers. */
internal class TranscriptPosition(
    messageId: String? = null,
    offset: Int = 0,
    following: Boolean = true,
) {
    var messageId: String? = messageId
    var offset: Int = offset
    var following: Boolean by mutableStateOf(following)

    companion object {
        val Saver =
            listSaver<TranscriptPosition, Any>(
                save = { listOf(SkeinId.parse(it.messageId)?.value ?: "", it.offset, it.following) },
                restore = { TranscriptPosition(SkeinId.parse(it[0] as String)?.value, it[1] as Int, it[2] as Boolean) },
            )
    }
}
