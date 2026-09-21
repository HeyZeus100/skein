// `EntityIndexer` (skein-aq6, E5.I9, spec plan text verbatim). Turns a
// document's GLiNER spans into `ENTITY` `edges` rows, the same shape
// `EdgeUpserter` (skein-ys2, E5.I8) uses for `WIKILINK`/`TAG`/`CITE`: a
// pure-over-`IndexStore` upsert+`replaceEdges` pass, kind-scoped so other
// edge kinds on the same source are untouched.
//
// Canonicalization (plan `E5.I9`): `trim`, collapse internal whitespace,
// NFKC-normalize, then lowercase *only* for the matching key — the display
// name kept in `entities.canonical_name` is the NFKC/whitespace-collapsed
// form of whichever mention was encountered first (reading order), casing
// intact. `"Ada Lovelace"` and `"ada  lovelace"` therefore share a matching
// key (`"ada lovelace"`) and collapse to one `upsertEntity` call; whichever
// of the two appears earliest in the document supplies the display casing
// that call carries into the (write-once, `IndexStoreImpl`/
// `InMemoryIndexStore`) `entities` row.
//
// Threshold vs. weight (both from the plan text, deliberately distinct
// numbers): GLiNER's own decode threshold (0.5, `E5.I4`/`skein-eq1`) keeps
// candidates loose; only spans scoring >= `EDGE_SCORE_THRESHOLD` (0.6) here
// create an edge at all. Frequency weighting then only applies to the
// mentions that clear that bar: an entity mentioned (at that score) >=
// `FREQUENT_MENTION_THRESHOLD` (3) times in the document gets
// `FREQUENT_ENTITY_WEIGHT` (0.8); otherwise it keeps `EdgeKind.ENTITY`'s
// default weight (0.6). Both are `<= EdgeKind.WIKILINK.weight` (1.0) per the
// plan's explicit "still <= WIKILINK" callout.
//
// `createdAt` preservation and kind-scoped `replaceEdges` mirror
// `EdgeUpserter.replaceKind` exactly: an edge whose `(dstId, weight)` is
// unchanged from the prior index pass keeps its original `createdAt`
// instead of being stamped "now" on every re-index; edges are sorted by the
// underlying `Entity.id` before writing so the emitted list is
// deterministic regardless of the input `spans` order or `HashMap`
// iteration.
//
// `EntitySpan` (span input shape) is the canonical contract type from
// `core/model` (skein-1su, E0.I17 / plan § 4.6). `start`/`end` are character
// offsets into `Document.bodyMd` (end-exclusive, like `String.substring`) —
// GLiNER's own word-span decoding maps back to character offsets before this
// indexer ever sees a span (see `E5.I4`'s description).

package app.skein.core.vault.extract

import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.EntitySpan
import us.aherrera.skein.core.model.IndexStore
import java.text.Normalizer

/**
 * Computes and applies the `ENTITY` edge delta for a document from its
 * decoded [EntitySpan]s.
 *
 * @param indexStore used for `upsertEntity`, the pre-upsert `edgesFrom` read
 *   (to preserve `createdAt` on edges that didn't change), and the
 *   `replaceEdges` write.
 * @param clock injectable for deterministic tests; defaults to wall clock.
 */
public class EntityIndexer(
    private val indexStore: IndexStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Replaces exactly [document]'s `ENTITY` edges from [spans]; edges of
     * any other kind (`WIKILINK`/`TAG`/`CITE`) are untouched, matching
     * `IndexStore.replaceEdges`'s kind-scoped delete. Spans scoring below
     * [EDGE_SCORE_THRESHOLD] are dropped before canonicalization and never
     * reach `upsertEntity`.
     */
    public suspend fun index(
        document: Document,
        spans: List<EntitySpan>,
    ) {
        val now = clock()
        val body = document.bodyMd.orEmpty()

        // First-occurrence display casing depends on reading order, not the
        // caller's `spans` list order, so sort by position before grouping.
        val candidates =
            spans
                .filter { it.score >= EDGE_SCORE_THRESHOLD }
                .filter { it.start in 0..body.length && it.end in it.start..body.length }
                .sortedWith(compareBy({ it.start }, { it.end }))

        val mentions = LinkedHashMap<MentionKey, Mention>()
        for (span in candidates) {
            val canonical = canonicalize(body.substring(span.start, span.end))
            if (canonical.isEmpty()) continue
            val key = MentionKey(matching = canonical.lowercase(), label = span.label)
            val mention = mentions.getOrPut(key) { Mention(displayName = canonical) }
            mention.count++
        }

        val existingByDst =
            indexStore
                .edgesFrom(document.id)
                .filter { it.kind == EdgeKind.ENTITY }
                .associateBy { it.dstId }

        val desired =
            mentions
                .map { (key, mention) ->
                    val entity = indexStore.upsertEntity(mention.displayName, key.label, now)
                    val frequent = mention.count >= FREQUENT_MENTION_THRESHOLD
                    val weight = if (frequent) FREQUENT_ENTITY_WEIGHT else EdgeKind.ENTITY.weight
                    entity.id to weight
                }.sortedBy { (entityId, _) -> entityId }
                .map { (entityId, weight) ->
                    val dstId = entityNode(entityId)
                    val prior = existingByDst[dstId]
                    val createdAt = if (prior != null && prior.weight == weight) prior.createdAt else now
                    Edge(
                        srcId = document.id,
                        dstId = dstId,
                        kind = EdgeKind.ENTITY,
                        weight = weight,
                        createdAt = createdAt,
                    )
                }

        indexStore.replaceEdges(document.id, setOf(EdgeKind.ENTITY), desired)
    }

    /** `trim`, collapse internal whitespace, then NFKC-normalize — matching-key lowercasing happens separately. */
    private fun canonicalize(raw: String): String {
        val collapsed = raw.trim().replace(WHITESPACE, " ")
        return Normalizer.normalize(collapsed, Normalizer.Form.NFKC)
    }

    private data class MentionKey(
        val matching: String,
        val label: String,
    )

    private class Mention(
        val displayName: String,
        var count: Int = 0,
    )

    public companion object {
        /** GLiNER's own decode threshold (0.5, `E5.I4`) keeps candidates; edges require this stricter score. */
        public const val EDGE_SCORE_THRESHOLD: Float = 0.6f

        /** An entity mentioned this many times (at [EDGE_SCORE_THRESHOLD]+) in a document gets [FREQUENT_ENTITY_WEIGHT]. */
        public const val FREQUENT_MENTION_THRESHOLD: Int = 3

        /** Frequency-weighted edge weight — still `<= EdgeKind.WIKILINK.weight` (1.0) per the plan text. */
        public const val FREQUENT_ENTITY_WEIGHT: Double = 0.8

        private val WHITESPACE: Regex = Regex("\\s+")

        /** `edges.dst_id` convention for entity nodes (see `Edge`'s kdoc). */
        public fun entityNode(entityId: Long): String = "entity:$entityId"
    }
}
