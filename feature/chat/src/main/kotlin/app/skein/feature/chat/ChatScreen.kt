// skein-6as (E6.I8). `ChatScreen(docId)` — spec §8.4: message list, error
// banner, bottom bar, under the host's own top bar. Ties `ChatViewModel` to the
// Composables in this module; owns no business logic of its own.
package app.skein.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.skein.core.model.VaultRepository
import app.skein.feature.editor.autocomplete.Suggestion

public const val CHAT_SCREEN_TEST_TAG: String = "app.skein.feature.chat.ChatScreen"
public const val ERROR_BANNER_TEST_TAG: String = "app.skein.feature.chat.ErrorBanner"
public const val RETRY_BUTTON_TEST_TAG: String = "app.skein.feature.chat.RetryButton"

public const val SERVICE_DIED_BANNER_TEXT: String = "Couldn't finish the answer. The model had to restart."
public const val ENGINE_ERROR_BANNER_TEXT: String = "Couldn't finish the answer."
public const val CONTEXT_FULL_BANNER_TEXT: String =
    "The request is too large for this model. Shorten your message and send again."
public const val REQUEST_TOO_LARGE_BANNER_TEXT: String =
    "The request is too large to send. Shorten your message and send again."
public const val MODEL_CHANGED_BANNER_TEXT: String = "The model changed before the answer started. Try again."

/**
 * @param wikilinkSuggest backs the bottom bar's `[[` popup — typically
 *   `{ query -> vaultRepository.searchTitles(query).map { Suggestion(it.title) } }`.
 * @param onSlashCommand see [ChatBottomBar]'s doc — the command-palette seam.
 * @param onOpenSource a citation tap: opens the cited document.
 * @param topBar the NavDisplay shell entry's own bar; the context inspector is
 *   an entry of its own (skein-xtov.24.8).
 * @param initialMessage sent once, when this screen's state is first created:
 *   the landing's first message, handed over when its chat was just created.
 */
@Composable
public fun ChatScreen(
    docId: DocId,
    vaultRepository: VaultRepository,
    sendPipeline: SendPipeline,
    onOpenSource: (DocId) -> Unit,
    wikilinkSuggest: suspend (String) -> List<Suggestion>,
    modifier: Modifier = Modifier,
    importService: ImportService? = null,
    // UX-P0-14: the `[[` popup's `Create "x"` row must create the note it
    // names (it used to create nothing); an existing title is left alone.
    onCreateWikilink: suspend (String) -> Unit = { title ->
        if (vaultRepository.findByTitle(title) == null) {
            vaultRepository.createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = ""))
        }
    },
    onSlashCommand: () -> Unit = {},
    topBar: @Composable () -> Unit = {},
    contextChip: @Composable () -> Unit = {},
    initialMessage: String? = null,
) {
    val scope = rememberCoroutineScope()
    val viewModel =
        remember(docId, vaultRepository, sendPipeline, onOpenSource) {
            ChatViewModel(
                chatDocId = docId,
                vaultRepository = vaultRepository,
                sendPipeline = sendPipeline,
                onOpenSource = onOpenSource,
                scope = scope,
                importService = importService,
            )
        }

    LaunchedEffect(viewModel) { initialMessage?.let(viewModel::send) }

    Column(modifier = modifier.fillMaxSize().testTag(CHAT_SCREEN_TEST_TAG)) {
        topBar()

        if (viewModel.banner != ChatBanner.NONE) {
            ChatErrorBanner(viewModel.banner, viewModel.canRetry, viewModel::retry)
        }

        MessageList(
            messages = viewModel.messages,
            turnState = viewModel.turnState,
            streamingCitations = viewModel.streamingCitations,
            isExcerptExpanded = viewModel::isExcerptExpanded,
            onCitationTap = viewModel::onCitationTap,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        contextChip()

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

/** Fixed product copy only; transport diagnostics and user content never become error text. */
@Composable
internal fun ChatErrorBanner(
    banner: ChatBanner,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag(ERROR_BANNER_TEST_TAG),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text =
                    when (banner) {
                        ChatBanner.SERVICE_DIED -> SERVICE_DIED_BANNER_TEXT
                        ChatBanner.CONTEXT_FULL -> CONTEXT_FULL_BANNER_TEXT
                        ChatBanner.REQUEST_TOO_LARGE -> REQUEST_TOO_LARGE_BANNER_TEXT
                        ChatBanner.MODEL_CHANGED -> MODEL_CHANGED_BANNER_TEXT
                        else -> ENGINE_ERROR_BANNER_TEXT
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (canRetry) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End).testTag(RETRY_BUTTON_TEST_TAG),
                ) { Text("Try again") }
            }
        }
    }
}
