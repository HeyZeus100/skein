// `PersonalizedPageRank` (skein-dxj, E5.I12, spec §7.2 rank step). Power
// iteration over the *recalled* neighbourhood — never the whole vault: the
// caller ([PprRanker]) hands in exactly the edge list
// `IndexStore.neighborhood` returned for the candidate documents, and this
// class ranks only the nodes that edge list (plus the personalization
// vector) mentions.
//
// Pure Kotlin over `:core:model` value types (`Edge`, `EdgeKind`). No I/O,
// no coroutines, no store access: give it edges and a personalization
// vector, get a rank vector. That is what makes the hand-computed fixed
// points in `PersonalizedPageRankTest` possible to assert exactly.
//
// ## The recurrence
//
//     r(v) = (1 - α)·p(v)  +  α·[ Σ_{u~v} w(u,v)/W(u) · r(u)  +  D·p(v) ]
//
// where `p` is the personalization vector (normalized to sum 1), `W(u)` is
// `u`'s total incident weight, and `D` is the mass sitting on *dangling*
// nodes (nodes with no incident edge at all) in the current iterate. Both
// the teleport term and the dangling term land on `p`, not on a uniform
// vector — that is the "personalized" in personalized PageRank, and it is
// what makes a document that no candidate links to decay towards zero
// instead of towards `1/n`.
//
// ## Three choices worth stating explicitly
//
// 1. **Undirected.** `edges` rows are directed (`src_id → dst_id`), but
//    retrieval cares about association, not authorship: a note *linked by*
//    two strong candidates is as relevant as one *linking to* them (bd
//    `E5.I12`'s own acceptance criterion is phrased "linked by"). Both
//    `IndexStore.neighborhood` and `GraphRecall`'s BFS already walk this
//    graph undirected, so ranking it directed would rank a different graph
//    than the one recall expanded. Each edge therefore contributes its
//    weight in both directions.
// 2. **`Edge.weight`, not `EdgeKind.weight`.** `Edge.weight` *defaults* to
//    `kind.weight` (WIKILINK 1.0, CITE 0.8, ENTITY 0.6, TAG 0.4) but a
//    writer may override it — `EdgeUpserter` stores an unresolved wikilink
//    at 0.5, for instance. Reading the row's own weight honours the kind
//    weights bd asks for *and* those overrides.
// 3. **Self-loops are dropped.** A `src_id == dst_id` row would otherwise
//    contribute its weight twice (once per direction) into a node's own
//    mass, inflating exactly the node that needs no help.
//
// ## Determinism
//
// Node ids are sorted before anything else happens, and each node's
// adjacency slice is sorted by target index, so every floating-point
// accumulation happens in the same order regardless of the order the
// caller's `edges` list arrived in. Two runs over the same graph return
// bit-identical values, not merely values within a tolerance.
//
// ## Allocation
//
// The graph is held in flat CSR arrays (`offsets`/`targets`/`weights`) plus
// two `DoubleArray` iterate buffers that are swapped, not reallocated — for
// the plan's 90-candidate + 200-neighbour budget that is ~300 nodes and a
// handful of KB, and the iteration loop allocates nothing at all.

package app.skein.core.rag.rank

import us.aherrera.skein.core.model.Edge
import kotlin.math.abs

/**
 * Personalized PageRank over an explicit, bounded edge list. See the file
 * header for the recurrence, the undirected/weight/self-loop choices, and
 * the determinism guarantee.
 *
 * @param config supplies `damping` (α), `maxIterations` and `epsilon`.
 *   Defaults to the plan's values — see [RankerConfig].
 */
public class PersonalizedPageRank(
    private val config: RankerConfig = RankerConfig.DEFAULT,
) {
    /**
     * Ranks the graph induced by [edges] together with every node named in
     * [personalization].
     *
     * The returned map is keyed by node id in ascending id order and its
     * values sum to 1 (up to floating-point error). Node ids follow the
     * `edges` convention from `Edge`'s kdoc — bare document UUIDs plus the
     * `entity:` / `tag:` / `title:` sentinels — and are returned exactly as
     * they came in: this class does not know or care which is which, it
     * just ranks the graph it was handed. [PprRanker] is what reads
     * document nodes back out of the result.
     *
     * Edge cases:
     *   - empty [edges] **and** empty [personalization] → `emptyMap()`;
     *   - empty [edges] with a non-empty [personalization] → the
     *     normalized personalization vector itself (every node is
     *     dangling, so all mass teleports straight back onto `p`);
     *   - a [personalization] that is empty, all-zero, or all-negative
     *     while [edges] is not → a uniform `p` over the graph's nodes,
     *     i.e. an ordinary (non-personalized) PageRank rather than a
     *     divide-by-zero. Negative entries are clamped to 0.
     */
    public fun rank(
        edges: List<Edge>,
        personalization: Map<String, Double>,
    ): Map<String, Double> {
        val nodes = collectNodes(edges, personalization)
        if (nodes.isEmpty()) return emptyMap()

        val n = nodes.size
        val indexOf = HashMap<String, Int>(n * 2)
        for (i in 0 until n) indexOf[nodes[i]] = i

        val graph = buildCsr(edges, indexOf, n)
        val p = personalizationVector(nodes, personalization)

        var current = p.copyOf()
        var next = DoubleArray(n)

        for (iteration in 0 until config.maxIterations) {
            java.util.Arrays.fill(next, 0.0)
            var dangling = 0.0
            for (u in 0 until n) {
                val mass = current[u]
                val outWeight = graph.outWeight[u]
                if (outWeight == 0.0) {
                    dangling += mass
                    continue
                }
                if (mass == 0.0) continue
                val share = mass / outWeight
                for (e in graph.offsets[u] until graph.offsets[u + 1]) {
                    next[graph.targets[e]] += share * graph.weights[e]
                }
            }

            // Teleport + dangling redistribution both land on `p`.
            val teleport = (1.0 - config.damping) + config.damping * dangling
            var delta = 0.0
            for (v in 0 until n) {
                val value = config.damping * next[v] + teleport * p[v]
                delta += abs(value - current[v])
                next[v] = value
            }

            val swap = current
            current = next
            next = swap

            if (delta < config.epsilon) break
        }

        val out = LinkedHashMap<String, Double>(n * 2)
        for (i in 0 until n) out[nodes[i]] = current[i]
        return out
    }

    /** Sorted union of every edge endpoint and every personalized node — the determinism anchor. */
    private fun collectNodes(
        edges: List<Edge>,
        personalization: Map<String, Double>,
    ): List<String> {
        val nodes = HashSet<String>(edges.size * 2 + personalization.size)
        for (edge in edges) {
            nodes += edge.srcId
            nodes += edge.dstId
        }
        nodes += personalization.keys
        return nodes.sorted()
    }

    /**
     * Flattens the undirected, weight-summed adjacency into CSR arrays.
     * Parallel edges between the same pair accumulate; self-loops are
     * dropped (file header).
     */
    private fun buildCsr(
        edges: List<Edge>,
        indexOf: Map<String, Int>,
        n: Int,
    ): Csr {
        val degree = IntArray(n)
        var kept = 0
        for (edge in edges) {
            val u = indexOf[edge.srcId] ?: continue
            val v = indexOf[edge.dstId] ?: continue
            if (u == v) continue
            degree[u]++
            degree[v]++
            kept++
        }

        val offsets = IntArray(n + 1)
        for (i in 0 until n) offsets[i + 1] = offsets[i] + degree[i]
        val targets = IntArray(kept * 2)
        val weights = DoubleArray(kept * 2)
        val cursor = offsets.copyOf(n)
        for (edge in edges) {
            val u = indexOf[edge.srcId] ?: continue
            val v = indexOf[edge.dstId] ?: continue
            if (u == v) continue
            targets[cursor[u]] = v
            weights[cursor[u]] = edge.weight
            cursor[u]++
            targets[cursor[v]] = u
            weights[cursor[v]] = edge.weight
            cursor[v]++
        }

        // Sort each node's slice by target so accumulation order — and so
        // the last bit of every sum — is independent of the input order.
        for (u in 0 until n) {
            sortSliceByTarget(targets, weights, offsets[u], offsets[u + 1])
        }

        val outWeight = DoubleArray(n)
        for (u in 0 until n) {
            var total = 0.0
            for (e in offsets[u] until offsets[u + 1]) total += weights[e]
            outWeight[u] = total
        }
        return Csr(offsets, targets, weights, outWeight)
    }

    /**
     * Insertion sort over one adjacency slice, moving [weights] with
     * [targets]. Slices are tiny (a node's degree inside a ≤ 300-node
     * neighbourhood), and this keeps the CSR allocation-free — no boxed
     * pair list to sort.
     */
    private fun sortSliceByTarget(
        targets: IntArray,
        weights: DoubleArray,
        from: Int,
        to: Int,
    ) {
        for (i in from + 1 until to) {
            val target = targets[i]
            val weight = weights[i]
            var j = i - 1
            while (j >= from && targets[j] > target) {
                targets[j + 1] = targets[j]
                weights[j + 1] = weights[j]
                j--
            }
            targets[j + 1] = target
            weights[j + 1] = weight
        }
    }

    /** Clamped, sum-normalized `p`; uniform when the caller gave nothing usable (see [rank]'s kdoc). */
    private fun personalizationVector(
        nodes: List<String>,
        personalization: Map<String, Double>,
    ): DoubleArray {
        val n = nodes.size
        val p = DoubleArray(n)
        var total = 0.0
        for (i in 0 until n) {
            val raw = personalization[nodes[i]] ?: 0.0
            val value = if (raw.isFinite() && raw > 0.0) raw else 0.0
            p[i] = value
            total += value
        }
        if (total <= 0.0) {
            java.util.Arrays.fill(p, 1.0 / n)
            return p
        }
        for (i in 0 until n) p[i] /= total
        return p
    }

    /** Flat adjacency: `targets`/`weights` sliced by `offsets`, plus each node's total incident weight. */
    private class Csr(
        val offsets: IntArray,
        val targets: IntArray,
        val weights: DoubleArray,
        val outWeight: DoubleArray,
    )
}
