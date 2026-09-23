// skein-6as (E6.I8). Previews at phone (~412 dp) and unfolded Fold
// (~840 dp) widths (bead requirement). Renders the same layout `ChatScreen`
// composes (header, context panel, message list) fed with static fixture
// data, rather than a live `ChatScreen` + `ChatViewModel` + `SendPipeline` —
// a real `ChatBottomBar` calls `rememberLauncherForActivityResult`, which
// throws outside a real `ActivityResultRegistryOwner` (Compose preview
// hosts don't provide one), so the bottom bar here is a static visual
// stand-in; every interactive path is covered by `ChatScreenTest` instead.
//
// Preview names (bead: "state the preview names"):
//   - "Chat — phone (412dp)"
//   - "Chat — Fold unfolded (840dp)"
package app.skein.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.feature.shell.theme.LocalSkeinTokens
import app.skein.feature.shell.theme.SkeinTheme

private fun sampleMessages(): List<ChatMessageUi> =
    listOf(
        ChatMessageUi(
            id = "m1",
            role = Role.USER,
            displayText = "What did my notes say about the Fold launch plan?",
            interrupted = false,
            citations = emptyMap(),
            createdAt = 0L,
        ),
        ChatMessageUi(
            id = "m2",
            role = Role.ASSISTANT,
            displayText = "The launch plan targets M2 for the ask path [1].",
            interrupted = false,
            citations =
                mapOf(
                    1 to
                        ChatCitation(
                            marker = 1,
                            docId = "doc-1",
                            label = "Launch Plan",
                            excerpt = "targets M2 for the ask path",
                        ),
                ),
            createdAt = 1L,
        ),
    )

private fun sampleRetrieved(): List<Retrieved> =
    listOf(
        Retrieved(
            chunkId = 1L,
            docId = "doc-1",
            docTitle = "Launch Plan",
            text = "targets M2 for the ask path",
            score = 0.87,
            sourceKind = DocumentKind.NOTE,
            recalledBy = setOf(RecallSource.VECTOR, RecallSource.LEXICAL),
            revisionHash = "abc123",
            locator = Locator(byteStart = 0, byteEnd = 28),
        ),
    )

@Composable
private fun PreviewChatLayout(showContextPanel: Boolean) {
    SkeinTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "chat", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {}) { Text("${LocalSkeinTokens.current.glyphs.context} context") }
            }
            if (showContextPanel) {
                ContextPanel(items = sampleRetrieved(), tabController = TabController { _, _, _ -> "tab-1" })
            }
            MessageList(
                messages = sampleMessages(),
                turnState = ChatTurnState.Streaming("The launch plan targets M2"),
                streamingCitations = emptyMap(),
                isExcerptExpanded = { _, _ -> false },
                onCitationTap = { _, _ -> },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = LocalSkeinTokens.current.glyphs.searchPrompt, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "reply…",
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("📎")
                Text("⏎", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Preview(name = "Chat — phone (412dp)", widthDp = 412, heightDp = 800, showBackground = true)
@Composable
private fun ChatScreenPhonePreview() {
    PreviewChatLayout(showContextPanel = false)
}

@Preview(name = "Chat — Fold unfolded (840dp)", widthDp = 840, heightDp = 1000, showBackground = true)
@Composable
private fun ChatScreenFoldUnfoldedPreview() {
    PreviewChatLayout(showContextPanel = true)
}
