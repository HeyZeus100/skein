package app.skein.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.core.model.EngineState

/** Session-only display data. No model ids, paths, or default-model assumptions reach the header. */
public sealed interface ChatModelStatus {
    /** A standalone host has no engine observation; this does not mean the engine is unloaded. */
    public data object Unavailable : ChatModelStatus

    public data class Observed(
        public val state: EngineState,
        public val displayName: String? = null,
    ) : ChatModelStatus
}

public const val CHAT_MODEL_STATUS_TEST_TAG: String = "chat_model_status"

/** Only the session controller's active per-chat states establish ownership of generation. */
internal fun ChatModelStatus.Observed.activity(turn: ChatTurnState?): String =
    when (state) {
        EngineState.UNLOADED -> "No model loaded"
        EngineState.LOADING -> "Loading"
        EngineState.READY -> "Loaded"
        EngineState.GENERATING ->
            if (turn is ChatTurnState.Thinking || turn is ChatTurnState.Streaming) "Answering" else "Busy"
        EngineState.ERROR -> "Model unavailable"
    }

/** Compact secondary header; the full name and unambiguous activity remain available to TalkBack. */
@Composable
internal fun ChatModelIndicator(
    status: ChatModelStatus,
    modifier: Modifier = Modifier,
    ownedTurn: ChatTurnState? = null,
) {
    val observed = status as? ChatModelStatus.Observed ?: return
    val activity = observed.activity(ownedTurn)
    val name = observed.displayName.takeUnless { observed.state == EngineState.UNLOADED }
    val description = if (name == null) activity else "$name. $activity"
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CHAT_MODEL_STATUS_TEST_TAG)
                .clearAndSetSemantics { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (name != null) {
            Text(
                text = name.removeSuffix(".gguf"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = activity,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
