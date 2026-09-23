// `DanglingResolver` (bd `skein-ax9.1`, `E5.I8b` — the follow-up to
// `skein-ys2`/`E5.I8` this bead was discovered from). `EdgeUpserter` records
// an unresolved wikilink target as a sentinel edge
// (`dst_id = "title:<lowercased target>"`, kind `WIKILINK`, weight
// `EdgeUpserter.UNRESOLVED_WIKILINK_WEIGHT` = 0.5 — see that file's header
// for the full rationale) so the link resolves retroactively once a
// document with that title exists. This file is the retroactive-resolution
// half of that mechanism.
//
// `IndexStore` has no in-place edge UPDATE (`001_initial.sql`'s `edges`
// table has no such API — see `IndexStore.replaceEdges`'s kind-scoped
// delete+insert shape), so resolving a sentinel means: per source document
// that has one, read its current `WIKILINK` edges, swap the one row whose
// `dst_id` is the sentinel to the real document id at the resolved weight
// (`EdgeKind.WIKILINK.weight` = 1.0), and `replaceEdges` that source scoped
// to `{WIKILINK}`. Every other `WIKILINK` edge on that source (a resolved
// link, or a sentinel for some other still-missing title) passes through
// untouched, and every other-kind edge (`TAG`/`CITE`/`ENTITY`) is
// unaffected by construction — `replaceEdges`'s `kinds` parameter only ever
// touches `WIKILINK` here.
//
// Only `bd skein-ax9.1`'s acceptance criteria are in scope: resolving a
// sentinel on document creation (or equivalently, on retitle — `resolveFor`
// always resolves against the [Document] it's handed, so a caller passing
// the post-retitle document picks up sentinels written against the new
// title with no special-casing needed here) and a batch `resolveAll` repair
// pass. The bead does not require an inverse ("un-resolve" a real edge back
// to a sentinel on delete or retitle-away), so this file does not implement
// one — see `skein-ax9.1`'s close reason for this explicitly noted
// follow-up.
//
// Wiring this into `IngestWorker`'s `created`-ingest step (`E5.I10`,
// bd `skein-7v3`) is out of this bead's scope; it doesn't exist yet.

package app.skein.core.vault.extract

import app.skein.core.model.Document
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexStore
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.flow.first

/**
 * Rewrites `EdgeUpserter`'s unresolved-wikilink sentinel edges to the real
 * document id once a document with the sentinel's title exists.
 *
 * @param vaultRepository used only for [resolveAll]'s document enumeration
 *   (paging `observeTimeline`) — `resolveFor` itself needs no repository
 *   access beyond the [Document] the caller hands it.
 * @param indexStore used for the `edgesTo`/`edgesFrom` reads and the
 *   `replaceEdges` write that apply a resolution.
 */
public class DanglingResolver(
    private val vaultRepository: VaultRepository,
    private val indexStore: IndexStore,
) {
    /**
     * Resolves every dangling wikilink sentinel that targets [document]'s
     * current title (`EdgeUpserter.unresolvedTarget(document.title)`),
     * rewriting each to `dst_id = document.id` at
     * `EdgeKind.WIKILINK.weight`, preserving `createdAt`.
     *
     * No-op when nothing targets that title (including when a prior call
     * already resolved it) — safe to call speculatively on every `created`
     * ingest, and idempotent under repeated calls or a `resolveAll` sweep.
     */
    public suspend fun resolveFor(document: Document) {
        val sentinel = EdgeUpserter.unresolvedTarget(document.title)
        val dangling = indexStore.edgesTo(sentinel, EdgeKind.WIKILINK)
        if (dangling.isEmpty()) return

        val resolvedWeight = EdgeKind.WIKILINK.weight
        for (srcId in dangling.map { it.srcId }.distinct()) {
            val currentWikilinks = indexStore.edgesFrom(srcId).filter { it.kind == EdgeKind.WIKILINK }
            val rewritten =
                currentWikilinks.map { edge ->
                    if (edge.dstId == sentinel) {
                        edge.copy(dstId = document.id, weight = resolvedWeight)
                    } else {
                        edge
                    }
                }
            indexStore.replaceEdges(srcId, setOf(EdgeKind.WIKILINK), rewritten)
        }
    }

    /**
     * Startup/repair pass: calls [resolveFor] for every document in the
     * vault. Idempotent — a second call resolves nothing new, since the
     * first already rewrote every sentinel that had a matching document.
     *
     * Pages through [VaultRepository.observeTimeline] (the only
     * document-enumeration surface `VaultRepository` exposes) rather than
     * adding a new listing method, per this bead's no-`core/model`-changes
     * constraint.
     */
    public suspend fun resolveAll() {
        var before: Long? = null
        while (true) {
            val page =
                vaultRepository
                    .observeTimeline(TimelineFilter(), limit = REPAIR_PAGE_SIZE, before = before)
                    .first()
            if (page.isEmpty()) break
            for (document in page) resolveFor(document)
            if (page.size < REPAIR_PAGE_SIZE) break
            before = page.last().updatedAt
        }
    }

    private companion object {
        /** Page size for the [resolveAll] repair-pass scan over `observeTimeline`. */
        const val REPAIR_PAGE_SIZE: Int = 500
    }
}
