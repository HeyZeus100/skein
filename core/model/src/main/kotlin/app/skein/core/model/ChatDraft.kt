package app.skein.core.model

/** Identity only; never use a draft's text as a navigation or saved-state key. */
public sealed interface ChatDraftKey {
    /** Existing chats retain their own draft even when the selected Space changes. */
    public data class Existing(
        val chatId: DocId,
    ) : ChatDraftKey {
        init {
            require(chatId.isNotBlank()) { "Invalid chat draft key" }
        }

        override fun toString(): String = "ChatDraftKey.Existing"
    }

    /** Unsaved chats have no documents row. The same draft id in another Space is a different draft. */
    public data class New(
        val spaceId: PersonaId,
        val draftId: String,
    ) : ChatDraftKey {
        init {
            require(spaceId.isNotBlank() && draftId.isNotBlank()) { "Invalid new chat draft key" }
        }

        override fun toString(): String = "ChatDraftKey.New"
    }
}

/**
 * Unsent text and UTF-16 selection, stored only in session memory or encrypted vault rows (D7 M6).
 * Pending wikilinks are part of [text]. No IME composition or plaintext fallback is persisted.
 * A reversed selection is valid. This object is never placed in a Bundle or SavedStateHandle.
 */
public data class ChatDraft(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
) {
    init {
        require(selectionStart in 0..text.length && selectionEnd in 0..text.length) { "Invalid draft selection" }
    }

    override fun toString(): String = "ChatDraft(length=${text.length})"
}
