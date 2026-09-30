package app.skein.feature.chat

import app.skein.core.model.ContextualEvidenceMode
import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.DocId
import app.skein.core.model.Message
import app.skein.core.model.PersonaId
import app.skein.core.model.RetrievalFollowUpContext
import app.skein.core.model.RetrievalSourcePin
import app.skein.core.model.Role
import app.skein.core.model.StopReason

/**
 * Bounded source-reference follow-ups, not general conversation understanding. Only explicit
 * possessive/source-reference wording opts in; independent questions retain the direct path.
 *
 * v1 citations do not persist the previous turn's Space. A session-only attestation supplies that
 * missing provenance, bound to the canonical committed USER/ASSISTANT IDs. Reopened chats refuse
 * contextual resolution until a new cited answer completes in this session. No assistant text is
 * used as a query, source, or identity. The retrieval service verifies actual current source bytes.
 */
internal class ChatFollowUpContext {
    private data class Completion(
        val userId: String,
        val assistantId: String,
        val personaId: PersonaId?,
    )

    private val completions = linkedMapOf<DocId, Completion>()
    private var closed = false

    @Synchronized
    fun committed(
        snapshot: TurnAnswerSnapshot,
        message: Message?,
    ) {
        val turn = snapshot.context.turn
        completions.remove(turn.chatId)
        if (closed ||
            message == null ||
            snapshot.reason != StopReason.EOS ||
            snapshot.context.generationSkipped ||
            message.role != Role.ASSISTANT ||
            message.chatDocId != turn.chatId ||
            message.citations?.cited.isNullOrEmpty()
        ) {
            return
        }
        completions[turn.chatId] = Completion(turn.user.id, message.id, turn.persona?.id)
        while (completions.size > MAX_CHATS) completions.remove(completions.keys.first())
    }

    @Synchronized
    fun request(
        turn: PreparedChatTurn,
        priorHistory: List<Message>,
        k: Int,
    ): ContextualRetrievalRequest? {
        val query = turn.user.contentMd
        if (!SOURCE_REFERENCE.containsMatchIn(query.take(MAX_QUERY_BYTES).trimStart())) return null
        val completion = completions[turn.chatId]
        val assistant = priorHistory.lastOrNull()
        val user = priorHistory.getOrNull(priorHistory.lastIndex - 1)
        val record = assistant?.citations
        val eligible =
            !closed &&
                completion != null &&
                completion.personaId == turn.persona?.id &&
                assistant?.role == Role.ASSISTANT &&
                assistant.id == completion.assistantId &&
                assistant.chatDocId == turn.chatId &&
                user?.role == Role.USER &&
                user.id == completion.userId &&
                user.chatDocId == turn.chatId &&
                query.toByteArray().size <= MAX_QUERY_BYTES &&
                user.contentMd.toByteArray().size <= MAX_QUERY_BYTES &&
                record?.recordVersion == 1 &&
                record.cited.isNotEmpty() &&
                record.cited.size <= MAX_PINS &&
                record.retrieved.size <= MAX_PINS
        val context =
            if (eligible) {
                val citations = checkNotNull(record)
                val cited = citations.retrieved.filter { it.marker in citations.cited }
                // Incomplete/ambiguous marker wrappers cannot promote the remaining valid entry.
                val validMarkers = cited.map { it.marker }
                val pins =
                    if (validMarkers.toSet() == citations.cited.toSet() && validMarkers.distinct() == validMarkers) {
                        cited.map { RetrievalSourcePin(it.documentId, it.revisionHash, completion?.personaId) }
                    } else {
                        emptyList()
                    }
                RetrievalFollowUpContext(checkNotNull(user).contentMd, pins)
            } else {
                RetrievalFollowUpContext("", emptyList())
            }
        return ContextualRetrievalRequest(query, k, turn.persona?.id, context, ContextualEvidenceMode.INDEXED)
    }

    @Synchronized
    fun invalidate(documentId: DocId) {
        completions.remove(documentId)
    }

    @Synchronized
    fun close() {
        closed = true
        completions.clear()
    }

    private companion object {
        const val MAX_CHATS = 32
        const val MAX_QUERY_BYTES = 4_096
        const val MAX_PINS = 16

        // Deliberately narrow English source references. Bare "it", general ellipsis, and semantic
        // pronoun/entity resolution need their own independently evaluated policy.
        val SOURCE_REFERENCE =
            Regex(
                "^(?:and\\s+)?(?:(?:what|which|where|when|who|why|how)" +
                    "(?:\\s+(?:is|are|was|were|does|did|do|can|could|would|should|will|" +
                    "about|of|in|on|the|much|many))*" +
                    "\\s+)?(?:its|(?:that|this|the same) (?:note|source|document))\\b",
                RegexOption.IGNORE_CASE,
            )
    }
}
