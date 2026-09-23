// `CitationRecords.fromStream` (skein-n5q, E5.I16). Builds the
// citation-record-v1 payload — `core/model` `CitationRecord`/
// `CitationRecordJson`, locked by skein-uo5n per
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 — that the chat layer
// (`E6.I8`) hands to `NewMessage.citations` once a turn's `Token.Done`
// arrives. The bead's third AC ("persisted JSON shape `{ cited, retrieved }`
// pinned by test") is citation-record-v1 itself, not a second shape: this
// class only assembles the `CitationRecord` value; `CitationRecordJson`
// (already landed) does the encoding.
//
// ## `retrieved` vs `cited`
//
// - `retrieved` is every item [AssembledPrompt.citations] offered — the
//   full context-panel list (`E6.I5`), regardless of whether the model ever
//   mentioned it.
// - `cited` is the distinct markers the parsed [Segment] stream actually
//   turned into a [Segment.Citation] — i.e. only markers `CitationParser`
//   validated against that same offer — in order of first appearance.
//
// ## Degrading a non-anchored offer, and why this is not a `core/model`
// conflict
//
// `Retrieved.revisionHash`/`Retrieved.locator` are nullable
// (`core/model` `Retrieval.kt`): "an implementation running against a
// pre-003 vault returns null and the citation record it feeds degrades to a
// non-anchored citation." But `core/model`'s locked `Citation` requires a
// non-null `revisionHash: RevisionHash` and `locator: Locator` — there is no
// "non-anchored `Citation`" the locked schema can express (every entry in
// citation-record-v1's `retrieved` array has a required `revision_hash` and
// `locator`, `CitationRecordJson.validate`). So an offered item that lacks
// either is left out of the persisted `retrieved` list entirely (and, since
// `CitationRecordJson.encode` requires every `cited` marker to name a
// `retrieved` entry, out of `cited` too if the model happened to cite it).
// This is not a widening of the locked record shape — it is the natural
// reading of "degrades to a non-anchored citation": the degraded citation is
// simply not anchorable in a schema that has no field for "unknown", so it
// is not persisted as one. In the current pipeline (`RetrievedAssembler`,
// skein-g32i) every chunk from a fully-migrated (003 + 008) vault carries
// both fields, so this path is defensive, not a live v1 scenario.
package app.skein.core.rag.chat

import app.skein.core.model.AssembledPrompt
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationRecordJson
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved

/** Builds the citation-record-v1 [CitationRecord] for a finished streamed turn. */
public object CitationRecords {
    /**
     * @param assembled the same [AssembledPrompt] the turn's prompt was built
     *   from; [AssembledPrompt.citations] is the full retrieved-item offer.
     * @param segments every [Segment] `CitationParser` emitted for the turn
     *   (every `push` call's result, plus `flush()`'s, concatenated in
     *   order).
     */
    public fun fromStream(
        assembled: AssembledPrompt,
        segments: List<Segment>,
    ): CitationRecord {
        val retrieved =
            assembled.citations.entries
                .sortedBy { it.key }
                .mapNotNull { (marker, item) -> toCitation(marker, item) }
        val anchoredMarkers = retrieved.mapTo(HashSet()) { it.marker }
        val cited = LinkedHashSet<Int>()
        for (segment in segments) {
            if (segment is Segment.Citation && segment.marker in anchoredMarkers) cited += segment.marker
        }
        return CitationRecord(retrieved = retrieved, cited = cited.toList())
    }

    /** Null when [item] cannot be anchored — see the file header. */
    private fun toCitation(
        marker: Int,
        item: Retrieved,
    ): Citation? {
        val revisionHash = item.revisionHash ?: return null
        val locator = item.locator ?: return null
        return Citation(
            marker = marker,
            documentId = item.docId,
            revisionHash = revisionHash,
            locator = locator,
            excerpt = item.text.take(CitationRecordJson.MAX_EXCERPT_CHARS),
            sourceKind = sourceKindOf(item),
        )
    }

    /**
     * The `citation-record-v1` `source_kind` for [item], via the one locked
     * bridge from recall provenance to persisted source kind
     * ([RecallSource.citationSourceKind]). [Retrieved.recalledBy] is a set —
     * a chunk found by more than one recall stage — so ties break in
     * [RecallSource]'s declared order (`VECTOR`, `LEXICAL`, `GRAPH`). An item
     * recalled by no stage at all (not reachable through the current
     * pipeline, since every candidate in `RetrievedAssembler` originates in
     * some recall stage's result) defaults to `VECTOR` rather than throwing.
     */
    private fun sourceKindOf(item: Retrieved): CitationSourceKind =
        RecallSource.entries.firstOrNull { it in item.recalledBy }?.citationSourceKind
            ?: CitationSourceKind.VECTOR
}
