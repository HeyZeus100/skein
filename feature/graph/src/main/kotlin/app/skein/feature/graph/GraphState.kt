// `GraphState` (bd `skein-z2u`, plan `E6.I11`, spec §8.6): state holder for
// the local 2-hop graph view. Same shape as `TimelineState`/`BacklinksState`
// — pure Kotlin (no Compose import), driven by an already-open
// `IndexStore`/`VaultRepository` pair the caller injects; this module never
// opens or unlocks a vault itself (same guardrail every other
// `:feature:*` state holder documents).
//
// ## Query shape
//
// `IndexStore.neighborhood({docId}, hops = 2, maxNodes = 80)` (plan `E6.I11`
// defaults) returns the undirected edge list of everything within two hops.
// Node ids are BFS-distanced from the center the same way `core/rag`'s
// `GraphRecall.bfsHopDistances` does (duplicated here rather than shared —
// `GraphRecall` lives in `:core:rag`, which this module has no reason to
// depend on for one 20-line BFS). The resulting node set is every id that
// BFS reached (always includes the center itself, at distance 0, even if it
// has no edges at all — same as `GraphRecall`'s seed handling).
//
// Document nodes get their title/kind hydrated via
// `VaultRepository.getDocument`; sentinel nodes (`entity:`/`tag:`/`title:`)
// get [GraphNodeIds.sentinelLabel] instead — see [GraphModels]'s file header
// for why they're kept in the graph rather than filtered.
//
// ## Live refresh via `IndexStore.observeChanges()` (bd `skein-rkxi`)
//
// Same shape as `:feature:editor`'s `BacklinksState` (see that file's
// header, "Live updates: two merged invalidation signals"): a graph is
// re-queried on every [us.aherrera.skein.core.model.IndexChange.EdgesReplaced]
// event, unfiltered by `srcId`/`kinds`. The event names only the rewritten
// edges' *source*, never their destination, so — per `IndexStore
// .observeChanges`'s own kdoc — a consumer "cannot tell from the event
// alone whether its own document was affected"; `BacklinksState` resolves
// this by re-querying and letting the result speak rather than trying to
// pre-filter by relevance, and this class does the same. Unlike
// `BacklinksState` (which filters to `WIKILINK` only, since only wikilinks
// feed backlinks), this graph renders every edge kind, so no `kinds` filter
// is applied here at all — the whole point of [GraphLegend] is showing
// every one of them. `ChunksReplaced`/`EmbeddingsUpdated`
// are not subscribed: neither changes what `neighborhood` or a node's
// title/kind would return. `onStart { emit(Unit) }` seeds the very first
// load, since `observeChanges` has no replay by contract (same as
// `VaultRepositoryImpl.changeTicks`).
package app.skein.feature.graph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.IndexChange
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.VaultRepository

/**
 * @param docId the document this graph is centered on.
 * @param indexStore already-open; backs `neighborhood` and the live-refresh
 *   `observeChanges` stream (see file header).
 * @param vaultRepository already-open; backs document title/kind hydration.
 * @param scope owner of the load + live-refresh collection — a Compose
 *   `rememberCoroutineScope()` in the app, `backgroundScope` in tests.
 * @param seed passed straight through to [ForceLayout.compute] — same seed,
 *   same neighborhood, same positions (acceptance criterion: "positions
 *   deterministic for a fixed seed").
 */
public class GraphState(
    docId: DocId,
    private val indexStore: IndexStore,
    private val vaultRepository: VaultRepository,
    scope: CoroutineScope,
    private val seed: Long = DEFAULT_SEED,
    private val hops: Int = DEFAULT_HOPS,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {
    /** The document this graph is centered on — also the id pinned at the origin by [ForceLayout]. */
    public val centerDocId: DocId = docId

    private val _nodes = MutableStateFlow<List<GraphNode>>(emptyList())
    public val nodes: StateFlow<List<GraphNode>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow<List<GraphEdge>>(emptyList())
    public val edges: StateFlow<List<GraphEdge>> = _edges.asStateFlow()

    /** Every id in [nodes] has an entry here once [loading] goes false. */
    private val _positions = MutableStateFlow<Map<String, Vec2>>(emptyMap())
    public val positions: StateFlow<Map<String, Vec2>> = _positions.asStateFlow()

    private val _loading = MutableStateFlow(true)
    public val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        invalidationTicks().onEach { load() }.launchIn(scope)
    }

    /** See the file header: every `EdgesReplaced` event re-triggers [load]; the initial tick seeds it. */
    private fun invalidationTicks(): Flow<Unit> =
        indexStore
            .observeChanges()
            .filter { it is IndexChange.EdgesReplaced }
            .map { }
            .onStart { emit(Unit) }

    private suspend fun load() {
        // Only the very first load shows the spinner; a live-refresh
        // reload (triggered by [invalidationTicks]) swaps the rendered
        // state in place instead of flashing back to [GraphTestTags.LOADING]
        // — same "keep the previous rows until the new ones are ready"
        // behavior `BacklinksState.backlinks` gets for free from
        // `flatMapLatest` never resetting to an intermediate empty value.
        if (_nodes.value.isEmpty()) _loading.value = true

        val rawEdges = indexStore.neighborhood(setOf(centerDocId), hops = hops, maxNodes = maxNodes)
        val hopDistances = bfsHopDistances(setOf(centerDocId), rawEdges)
        // `hopDistances` is a `LinkedHashMap` seeded with `centerDocId` first
        // (see `bfsHopDistances`), so this preserves that ordering.
        val nodeIds = hopDistances.keys.toList()

        val docsById =
            nodeIds
                .filter(GraphNodeIds::isDocument)
                .mapNotNull { id -> vaultRepository.getDocument(id)?.let { id to it } }
                .toMap()

        val graphNodes =
            nodeIds.map { id ->
                val doc = docsById[id]
                GraphNode(
                    id = id,
                    kind = GraphNodeIds.kindOf(id),
                    label = doc?.title?.takeIf { it.isNotBlank() } ?: GraphNodeIds.sentinelLabel(id),
                    hopDistance = hopDistances.getValue(id),
                    documentKind = doc?.kind,
                )
            }
        val graphEdges =
            rawEdges.map {
                GraphEdge(
                    srcId = it.srcId,
                    dstId = it.dstId,
                    kind = it.kind,
                    weight = it.weight,
                )
            }
        val laidOut =
            ForceLayout.compute(
                nodeIds = nodeIds,
                edges = graphEdges,
                centerId = centerDocId,
                seed = seed,
            )

        _nodes.value = graphNodes
        _edges.value = graphEdges
        _positions.value = laidOut
        _loading.value = false
    }

    /**
     * Undirected BFS over [edges], capped at [hops] — identical shape to
     * `core/rag`'s `GraphRecall.bfsHopDistances`. Every entry in [seeds]
     * starts at distance 0 regardless of whether it has any edges, so a
     * center document with no neighbors still produces a one-node graph
     * instead of an empty one.
     */
    private fun bfsHopDistances(
        seeds: Set<String>,
        edges: List<Edge>,
    ): Map<String, Int> {
        val adjacency = HashMap<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.srcId) { mutableListOf() } += edge.dstId
            adjacency.getOrPut(edge.dstId) { mutableListOf() } += edge.srcId
        }

        val distance = LinkedHashMap<String, Int>()
        val queue = ArrayDeque<String>()
        for (seed in seeds) {
            if (distance.putIfAbsent(seed, 0) == null) queue += seed
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val d = distance.getValue(node)
            if (d >= hops) continue
            for (next in adjacency[node].orEmpty()) {
                if (next !in distance) {
                    distance[next] = d + 1
                    queue += next
                }
            }
        }
        return distance
    }

    public companion object {
        /** Fixed default seed — replaying the same neighborhood always lays out the same way. */
        public const val DEFAULT_SEED: Long = 20260920L
        public const val DEFAULT_HOPS: Int = 2
        public const val DEFAULT_MAX_NODES: Int = 80
    }
}
