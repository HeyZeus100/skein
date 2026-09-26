// skein-6as (E6.I8). `ChatScreen(docId)` — spec §8.4: message list, header
// toggle `⚹ context`, error banner, bottom bar. Ties `ChatViewModel` to the
// Composables in this module; owns no business logic of its own.
package app.skein.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.ImportService
import app.skein.core.model.NewDocument
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.shell.theme.LocalSkeinTokens

public const val CHAT_SCREEN_TEST_TAG: String = "app.skein.feature.chat.ChatScreen"
public const val CONTEXT_TOGGLE_TEST_TAG: String = "app.skein.feature.chat.ContextToggle"
public const val ERROR_BANNER_TEST_TAG: String = "app.skein.feature.chat.ErrorBanner"
public const val RETRY_BUTTON_TEST_TAG: String = "app.skein.feature.chat.RetryButton"

public const val SERVICE_DIED_BANNER_TEXT: String = "model process restarted, retry"
public const val ENGINE_ERROR_BANNER_TEXT: String = "generation failed, retry"

/**
 * @param wikilinkSuggest backs the bottom bar's `[[` popup — typically
 *   `{ query -> vaultRepository.searchTitles(query).map { Suggestion(it.title) } }`.
 * @param currentPersonaId read by 📎 attach's [ImportService] calls; `null`
 *   defers to whatever persona `sendPipeline` was wired with.
 * @param onSlashCommand see [ChatBottomBar]'s doc — the command-palette seam.
 */
@Composable
public fun ChatScreen(
    docId: DocId,
    vaultRepository: VaultRepository,
    sendPipeline: SendPipeline,
    tabController: TabController,
    wikilinkSuggest: suspend (String) -> List<Suggestion>,
    modifier: Modifier = Modifier,
    importService: ImportService? = null,
    currentPersonaId: () -> PersonaId? = { null },
    // UX-P0-14: the `[[` popup's `Create "x"` row must create the note it
    // names (it used to create nothing); an existing title is left alone.
    onCreateWikilink: suspend (String) -> Unit = { title ->
        if (vaultRepository.findByTitle(title) == null) {
            vaultRepository.createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = ""))
        }
    },
    onSlashCommand: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewModel =
        remember(docId, vaultRepository, sendPipeline, tabController) {
            ChatViewModel(
                chatDocId = docId,
                vaultRepository = vaultRepository,
                sendPipeline = sendPipeline,
                tabController = tabController,
                scope = scope,
                importService = importService,
                currentPersonaId = currentPersonaId,
            )
        }

    Column(modifier = modifier.fillMaxSize().testTag(CHAT_SCREEN_TEST_TAG)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "chat", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::toggleContextPanel, modifier = Modifier.testTag(CONTEXT_TOGGLE_TEST_TAG)) {
                Text("${LocalSkeinTokens.current.glyphs.context} context")
            }
        }

        if (viewModel.contextPanelOpen) {
            ContextPanel(
                items = viewModel.contextItems,
                tabController = tabController,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (viewModel.banner != ChatBanner.NONE) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().testTag(ERROR_BANNER_TEST_TAG),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            if (viewModel.banner ==
                                ChatBanner.SERVICE_DIED
                            ) {
                                SERVICE_DIED_BANNER_TEXT
                            } else {
                                ENGINE_ERROR_BANNER_TEXT
                            },
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = viewModel::retry, modifier = Modifier.testTag(RETRY_BUTTON_TEST_TAG)) {
                        Text("retry")
                    }
                }
            }
        }

        MessageList(
            messages = viewModel.messages,
            turnState = viewModel.turnState,
            streamingCitations = viewModel.streamingCitations,
            isExcerptExpanded = viewModel::isExcerptExpanded,
            onCitationTap = viewModel::onCitationTap,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        ChatBottomBar(
            isGenerating = viewModel.isGenerating,
            onSend = viewModel::send,
            onCancel = viewModel::cancel,
            wikilinkSuggest = wikilinkSuggest,
            onAttach = viewModel::attach,
            onCreateWikilink = onCreateWikilink,
            onSlashCommand = onSlashCommand,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
