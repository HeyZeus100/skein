// `EdgeUpserter` (skein-ys2, E5.I8, spec §7.1 steps 5-6). Turns a document's
// fresh `WikilinkExtractor`/`TagExtractor` output into `edges` rows via the
// injected `IndexStore`/`VaultRepository` — the only I/O-performing piece of
// this bead; the extractors themselves stay pure.
//
// Target resolution (spec §7.1 step 5, plan `E5.I8`): a wikilink's target is
// a title, but `edges.dst_id` is a node identifier. `Edge`'s kdoc
// (`core/model/.../Vault.kt`) documents the `edges` node-id convention:
// documents are bare UUIDv7s, tags are `"tag:<lowercased-name>"`. The plan
// text for this bead extends that convention to *unresolved* wikilinks:
// `"title:<lowercased target>"`, so the edge resolves retroactively once a
// document with that title is created (a resolver pass, out of this bead's
// scope, rewrites `dst_id = 'title:<new title>'` rows on `created` ingest).
// The `001_initial.sql` schema backs this: `edges.dst_id` is `TEXT NOT
// NULL` with no separate nullable-dst/target-title column, so the sentinel
// string *is* the schema's mechanism for "unresolved" — nothing to change
// in `IndexStoreImpl` or the migration for this bead.
//
// Unresolved wikilink edges carry weight 0.5 (below the resolved default of
// `EdgeKind.WIKILINK.weight` = 1.0) per the plan text; resolved edges and
// tag edges keep their `EdgeKind` default weight.

package app.skein.core.vault.extract

import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.VaultRepository

/**
 * Computes and applies the `WIKILINK`/`TAG` edge delta for a document.
 *
 * @param vaultRepository used only for `findByTitle` target resolution.
 * @param indexStore used for both the pre-upsert `edgesFrom` read (to
 *   preserve `createdAt` on edges that didn't change) and the
 *   `replaceEdges` write.
 * @param clock injectable for deterministic tests; defaults to wall clock.
 */
public class EdgeUpserter(
    private val vaultRepository: VaultRepository,
    private val indexStore: IndexStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Re-extracts nothing itself — [wikilinks] and [tags] are the caller's
     * fresh `WikilinkExtractor.extract` / `TagExtractor.extract` output for
     * [docId]'s current body. Replaces exactly the `WIKILINK` and `TAG`
     * edges for [docId]; edges of any other kind (e.g. `ENTITY`, `CITE`)
     * are untouched, matching `IndexStore.replaceEdges`'s kind-scoped
     * delete.
     */
    public suspend fun upsert(
        docId: DocId,
        wikilinks: List<Wikilink>,
        tags: Set<String>,
    ) {
        val now = clock()
        // One read of the current edge set serves both kinds' deltas.
        val existing = indexStore.edgesFrom(docId)
        upsertWikilinks(docId, wikilinks, existing, now)
        upsertTags(docId, tags, existing, now)
    }

    /**
     * Convenience over [upsert] for a whole [document]: runs both extractors
     * over `bodyMd` (body-only — the vault stores the frontmatter separately
     * as `Document.frontmatter`, so tags come from that `JsonObject` rather
     * than a re-parse of a `---` header). An `ATTACHMENT` (`bodyMd == null`)
     * contributes no body links; only its frontmatter `tags:` survive.
     */
    public suspend fun upsert(document: Document) {
        val body = document.bodyMd.orEmpty()
        upsert(
            docId = document.id,
            wikilinks = WikilinkExtractor.extract(body),
            tags = TagExtractor.extract(body, document.frontmatter),
        )
    }

    private suspend fun upsertWikilinks(
        docId: DocId,
        wikilinks: List<Wikilink>,
        existing: List<Edge>,
        now: Long,
    ) {
        // Multiple wikilinks to the same title collapse to one edge — the
        // `edges` primary key is `(src_id, dst_id, kind)`.
        val byTarget = LinkedHashMap<String, Wikilink>()
        for (link in wikilinks) {
            byTarget.putIfAbsent(link.target.lowercase(), link)
        }
        val desired =
            byTarget.values.map { link ->
                val resolved = vaultRepository.findByTitle(link.target)
                if (resolved != null) {
                    Edge(srcId = docId, dstId = resolved.id, kind = EdgeKind.WIKILINK, createdAt = now)
                } else {
                    Edge(
                        srcId = docId,
                        dstId = unresolvedTarget(link.target),
                        kind = EdgeKind.WIKILINK,
                        weight = UNRESOLVED_WIKILINK_WEIGHT,
                        createdAt = now,
                    )
                }
            }
        replaceKind(docId, EdgeKind.WIKILINK, existing, desired)
    }

    private suspend fun upsertTags(
        docId: DocId,
        tags: Set<String>,
        existing: List<Edge>,
        now: Long,
    ) {
        val desired =
            tags.map { tag ->
                Edge(srcId = docId, dstId = tagTarget(tag), kind = EdgeKind.TAG, createdAt = now)
            }
        replaceKind(docId, EdgeKind.TAG, existing, desired)
    }

    /**
     * Applies [desired] for [kind], preserving `createdAt` on any edge
     * whose `(dstId, weight)` is unchanged from the existing row — an edge
     * that survives re-indexing keeps its original creation time rather
     * than being stamped "now" on every re-index.
     */
    private suspend fun replaceKind(
        docId: DocId,
        kind: EdgeKind,
        existing: List<Edge>,
        desired: List<Edge>,
    ) {
        val existingByDst =
            existing
                .filter { it.kind == kind }
                .associateBy { it.dstId }
        val merged =
            desired.map { edge ->
                val prior = existingByDst[edge.dstId]
                if (prior != null && prior.weight == edge.weight) edge.copy(createdAt = prior.createdAt) else edge
            }
        indexStore.replaceEdges(docId, setOf(kind), merged)
    }

    public companion object {
        /** Below `EdgeKind.WIKILINK.weight` (1.0) — a link to a not-yet-existing note ranks lower than a resolved one. */
        public const val UNRESOLVED_WIKILINK_WEIGHT: Double = 0.5

        /** `edges.dst_id` sentinel for a wikilink target with no matching document yet. */
        public fun unresolvedTarget(title: String): String = "title:${title.lowercase()}"

        /** `edges.dst_id` convention for tag nodes (see `Edge`'s kdoc). */
        public fun tagTarget(tag: String): String = "tag:${tag.lowercase()}"
    }
}
