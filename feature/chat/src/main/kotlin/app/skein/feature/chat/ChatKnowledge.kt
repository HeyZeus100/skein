package app.skein.feature.chat

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.VaultRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Per-chat automatic search preference. Existing chats default to Knowledge on. */
public object ChatKnowledge {
    public const val KEY: String = "skein_knowledge_enabled"

    public fun enabled(document: Document): Boolean =
        (document.frontmatter[KEY] as? JsonPrimitive)?.booleanOrNull ?: true

    /** Update only this preference, preserving concurrent frontmatter changes. */
    public suspend fun setEnabled(
        repository: VaultRepository,
        chatId: String,
        enabled: Boolean,
    ) {
        repository.transaction {
            val chat = repository.getDocument(chatId) ?: return@transaction
            require(chat.kind == DocumentKind.CHAT)
            repository.updateFrontmatter(chatId, JsonObject(chat.frontmatter + (KEY to JsonPrimitive(enabled))))
        }
    }
}
