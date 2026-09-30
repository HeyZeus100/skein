package app.skein.feature.chat.entries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.skein.core.model.DocId
import app.skein.core.model.ImportService
import app.skein.core.model.VaultRepository
import app.skein.feature.chat.ChatTurnController
import app.skein.feature.chat.ChatViewModel
import app.skein.feature.chat.SendPipeline

/** T3: retained by this navigation entry, with its store synchronously cleared when the vault locks. */
internal class ChatEntryPresentation(
    chatId: DocId,
    repository: VaultRepository,
    pipeline: SendPipeline,
    importService: ImportService?,
    turns: ChatTurnController?,
    initialMessage: String?,
) : ViewModel() {
    // Updated by the current composition so retained collectors never capture a dead scene's scope.
    var openSource: (DocId) -> Unit = {}
    val model = ChatViewModel(chatId, repository, pipeline, { openSource(it) }, viewModelScope, importService, turns)

    init {
        initialMessage?.let(model::send)
    }

    override fun onCleared() {
        model.dispose()
        openSource = {}
    }
}
