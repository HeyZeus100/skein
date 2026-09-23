// `GraphModels` (bd `skein-z2u`, plan `E6.I11`, spec §8.6): the plain-data
// vocabulary `GraphState` produces and `GraphView` renders. Deliberately
// free of Compose and `:core:vault` imports — only `:core:model`'s
// `DocumentKind`/`EdgeKind`, so every type here is usable from a plain JVM
// unit test with no Android/Compose runtime on the classpath.
//
// ## Node-id convention and the sentinel decision
//
// `IndexStore.neighborhood` (`core/model/.../Vault.kt`) walks a plain
// string-keyed graph: documents are bare UUIDv7 ids; entities are
// `"entity:<entities.id>"`; tags are `"tag:<lowercased-name>"`; an
// unresolved wikilink target is `"title:<lowercased-title>"` (see that
// file's `Edge` kdoc, and `core/rag`'s `GraphRecall` for the precedent of
// walking this same convention). `GraphRecall` *filters these sentinel
// nodes out* before hydrating chunks — they're graph waypoints, never chunk
// sources, for a recall pass.
//
// This local graph *view* makes the opposite call, per the plan's own
// description of `E6.I11` (spec §8.6): "nodes (documents; entity/tag nodes
// shown as small diamonds)". A 2-hop neighborhood is small and dense enough
// that hiding the entity/tag waypoints would silently disconnect otherwise-
// related documents in the drawing (two notes tagged `#project` would show
// no edge between them at all). So sentinel nodes are rendered as a
// distinct [GraphNodeKind] (a diamond, not a circle, per [GraphView]) rather
// than filtered — the graph stays connected and the shape difference keeps
// them visually subordinate to real documents.
package app.skein.feature.graph

import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind

/** Which of the four node-id shapes [Edge]'s convention produces a given node id is. */
public enum class GraphNodeKind {
    DOCUMENT,
    ENTITY,
    TAG,
    UNRESOLVED_TITLE,
}

/** Parses/labels a raw `edges` node id per the convention documented in the file header. */
public object GraphNodeIds {
    private const val ENTITY_PREFIX: String = "entity:"
    private const val TAG_PREFIX: String = "tag:"
    private const val UNRESOLVED_TITLE_PREFIX: String = "title:"

    public fun kindOf(nodeId: String): GraphNodeKind =
        when {
            nodeId.startsWith(ENTITY_PREFIX) -> GraphNodeKind.ENTITY
            nodeId.startsWith(TAG_PREFIX) -> GraphNodeKind.TAG
            nodeId.startsWith(UNRESOLVED_TITLE_PREFIX) -> GraphNodeKind.UNRESOLVED_TITLE
            else -> GraphNodeKind.DOCUMENT
        }

    public fun isDocument(nodeId: String): Boolean = kindOf(nodeId) == GraphNodeKind.DOCUMENT

    /**
     * Best-effort display label for a sentinel node id. Tag and unresolved-title
     * ids embed their own display text; an entity id is only ever
     * `"entity:<numeric id>"` — `IndexStore` has no reverse `getEntity(id)`
     * lookup, so the canonical name can't be resolved from this id alone
     * (a real name would need a new `IndexStore` accessor, out of scope
     * here). Document node labels are resolved via `VaultRepository` in
     * [GraphState], never derived from the id.
     */
    public fun sentinelLabel(nodeId: String): String =
        when (kindOf(nodeId)) {
            GraphNodeKind.TAG -> "#" + nodeId.removePrefix(TAG_PREFIX)
            GraphNodeKind.UNRESOLVED_TITLE -> nodeId.removePrefix(UNRESOLVED_TITLE_PREFIX)
            GraphNodeKind.ENTITY -> "Entity #" + nodeId.removePrefix(ENTITY_PREFIX)
            GraphNodeKind.DOCUMENT -> nodeId
        }
}

/**
 * One node in the rendered graph.
 *
 * @param hopDistance BFS distance from the center document (0 for the
 *   center itself), computed the same way `core/rag`'s `GraphRecall` does
 *   over the edges `IndexStore.neighborhood` returned.
 * @param documentKind only set for [GraphNodeKind.DOCUMENT] nodes whose
 *   document could still be resolved via `VaultRepository.getDocument` —
 *   `null` for sentinel nodes and for a document deleted after the edge was
 *   indexed.
 */
public data class GraphNode(
    val id: String,
    val kind: GraphNodeKind,
    val label: String,
    val hopDistance: Int,
    val documentKind: DocumentKind? = null,
) {
    public val isCenter: Boolean get() = hopDistance == 0
}

/** One edge in the rendered graph — the [GraphView]-facing projection of `core/model`'s `Edge`. */
public data class GraphEdge(
    val srcId: String,
    val dstId: String,
    val kind: EdgeKind,
    val weight: Double,
)
