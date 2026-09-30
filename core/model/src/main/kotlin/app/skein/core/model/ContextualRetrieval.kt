package app.skein.core.model

/** A previously cited source identity, never generated assistant prose or an oracle query. */
public data class RetrievalSourcePin(
    val documentId: DocId,
    val revisionHash: RevisionHash,
    /** Space of the previous user turn; must equal the new request's Space. */
    val personaId: PersonaId?,
)

/** The caller opts into resolution only when the user refers to earlier source context. */
public data class RetrievalFollowUpContext(
    val priorUserQuery: String,
    val citedSources: List<RetrievalSourcePin>,
)

/** Structural expansion stays an explicit experiment; chat follow-ups use verified indexed bytes. */
public enum class ContextualEvidenceMode {
    INDEXED,
    STRUCTURAL,
}

public data class ContextualRetrievalRequest(
    val query: String,
    val k: Int = 8,
    val personaId: PersonaId? = null,
    val followUp: RetrievalFollowUpContext? = null,
    val evidenceMode: ContextualEvidenceMode = ContextualEvidenceMode.STRUCTURAL,
)

/** Structural source resolution only. RESOLVED is not evidence that the requested fact is supported. */
public enum class FollowUpResolution {
    DIRECT,
    RESOLVED,
    MISSING_CONTEXT,
    AMBIGUOUS_CONTEXT,
    SOURCE_UNAVAILABLE,
    SOURCE_CHANGED,
    OUT_OF_SCOPE,
    UNSUPPORTED_SOURCE,
    CONTEXT_TOO_LARGE,
}

/**
 * Content-private request result: none of these text fields should be written to ordinary logs.
 * [originalQuery] is the support-policy input, including after prompt budgeting. [recallQuery]
 * is only a recall hint and must never be presented as an independently resolved gold query.
 * [originalCandidates] retains original rank/source signals before structural unit selection.
 */
public data class ContextualRetrievalResult(
    val originalQuery: String,
    val recallQuery: String,
    val resolution: FollowUpResolution,
    val anchorDocumentIds: Set<DocId>,
    val evidence: List<Retrieved>,
    val originalCandidates: List<Retrieved>,
    /** Original indexed members for each returned representative chunk, with unmodified source signals. */
    val evidenceMembers: Map<ChunkId, List<Retrieved>> = emptyMap(),
    val exclusionCounts: Map<String, Int> = emptyMap(),
)

/** Additive, explicitly opt-in path. Existing [RetrievalService] callers retain their current policy. */
public interface ContextualRetrievalService : RetrievalService {
    public suspend fun retrieveContext(request: ContextualRetrievalRequest): ContextualRetrievalResult

    /** Recheck pinned sources after suspendable prompt preparation. Unsupported implementations fail closed. */
    public suspend fun isContextCurrent(
        request: ContextualRetrievalRequest,
        evidence: List<Retrieved>,
    ): Boolean = false
}
