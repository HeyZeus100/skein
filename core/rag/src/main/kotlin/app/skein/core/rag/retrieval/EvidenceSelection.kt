package app.skein.core.rag.retrieval

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.PersonaId
import app.skein.core.model.Retrieved

/** Fixed diagnostic categories; no query, source text, title or model output is logged. */
public enum class EvidenceExclusion {
    MISSING_SOURCE,
    UNSUPPORTED_SOURCE,
    OUT_OF_SCOPE,
    MISSING_REVISION,
    STALE_REVISION,
    INVALID_ANCHOR,
    INCOMPLETE_UNIT,
    UNIT_TOO_LARGE,
    DUPLICATE,
    OVERLAP_TOO_LARGE,
}

/** One complete structural source unit with every original recall member retained. */
public data class EvidenceUnit(
    val source: Retrieved,
    val members: List<Retrieved>,
)

public data class EvidenceSelection(
    val units: List<EvidenceUnit>,
    val exclusions: Map<EvidenceExclusion, Int>,
) {
    public val evidence: List<Retrieved> get() = units.map { it.source }
}

internal fun Document.isEvidenceInScope(
    personaId: PersonaId?,
    legacyPersonaId: PersonaId?,
): Boolean =
    if (legacyPersonaId != null) {
        (this.personaId ?: legacyPersonaId) == personaId
    } else {
        this.personaId == null || this.personaId == personaId
    }

internal fun Document.isAuthoritativeEvidence(): Boolean = kind == DocumentKind.NOTE

internal data class ResolvedFollowUp(
    val originalQuery: String,
    val recallQuery: String,
    val resolution: app.skein.core.model.FollowUpResolution,
    val anchorIds: Set<DocId> = emptySet(),
)
