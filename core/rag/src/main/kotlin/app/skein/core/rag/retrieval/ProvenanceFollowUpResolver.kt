package app.skein.core.rag.retrieval

import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository

/** Source-identity resolution, not semantic pronoun resolution or an answer-support oracle. */
internal class ProvenanceFollowUpResolver(
    private val repository: VaultRepository,
    private val legacyPersonaId: PersonaId? = null,
) {
    suspend fun resolve(request: ContextualRetrievalRequest): ResolvedFollowUp {
        val context = request.followUp ?: return result(request, FollowUpResolution.DIRECT)
        if (context.priorUserQuery.isBlank() || context.citedSources.isEmpty()) {
            return result(request, FollowUpResolution.MISSING_CONTEXT)
        }
        if (context.priorUserQuery.toByteArray().size > MAX_QUERY_BYTES ||
            request.query.toByteArray().size > MAX_QUERY_BYTES ||
            context.citedSources.size > MAX_PINS
        ) {
            return result(request, FollowUpResolution.CONTEXT_TOO_LARGE)
        }
        if (context.citedSources.any { it.personaId != request.personaId }) {
            return result(request, FollowUpResolution.OUT_OF_SCOPE)
        }
        val pins = context.citedSources.distinct()
        if (pins.size != 1) return result(request, FollowUpResolution.AMBIGUOUS_CONTEXT)
        val pin = pins.single()
        val document =
            repository.getDocument(pin.documentId)
                ?: return result(request, FollowUpResolution.SOURCE_UNAVAILABLE)
        if (!document.isEvidenceInScope(request.personaId, legacyPersonaId)) {
            return result(request, FollowUpResolution.OUT_OF_SCOPE)
        }
        if (!document.isAuthoritativeEvidence()) return result(request, FollowUpResolution.UNSUPPORTED_SOURCE)
        val revision = repository.currentRevision(pin.documentId)
        if (document.contentHash != pin.revisionHash || revision?.revisionHash != pin.revisionHash) {
            return result(request, FollowUpResolution.SOURCE_CHANGED)
        }
        if (document.title.toByteArray().size > MAX_TITLE_BYTES) {
            return result(request, FollowUpResolution.CONTEXT_TOO_LARGE)
        }
        // The prior user question and verified title aid recall only. No assistant text is accepted.
        return ResolvedFollowUp(
            originalQuery = request.query,
            recallQuery = "${request.query}\n${document.title}\n${context.priorUserQuery}",
            resolution = FollowUpResolution.RESOLVED,
            anchorIds = setOf(pin.documentId),
        )
    }

    private fun result(
        request: ContextualRetrievalRequest,
        resolution: FollowUpResolution,
    ) = ResolvedFollowUp(request.query, request.query, resolution)

    private companion object {
        const val MAX_QUERY_BYTES = 4_096
        const val MAX_TITLE_BYTES = 512
        const val MAX_PINS = 16
    }
}
