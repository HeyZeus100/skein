// `RankerConfig` (skein-dxj, E5.I12, spec §7.2 rank step). Every numeric
// knob the rank step has, in one place, because bd `E5.I12`'s acceptance
// criteria require it: "Fusion weights are constants in `RankerConfig` with
// a comment pointing to `E10.I5` as the place they get tuned".
//
// None of these are measurement-derived (nothing here comes from
// `docs/MEASUREMENTS.md`, which is still `skein-5hr`): they are the plan's
// own starting values, written down so the retrieval-evaluation issue
// **`E10.I5` (RAG retrieval quality harness) is the one place they get
// tuned** — against the gold set `E5.I17` designs. Until then no caller
// should be hand-tweaking them at a call site; construct a copy of this
// config in a test if a test needs different values.

package app.skein.core.rag.rank

/**
 * Tunables for [PprRanker], [ScoreFusion] and [PersonalizedPageRank].
 *
 * @param recallWeight weight of the fused recall score in the final blend
 *   (plan `E5.I12`: `0.6 × fusedRecall + 0.4 × ppr`). Tuned in `E10.I5`.
 * @param pprWeight weight of the personalized-PageRank score of a chunk's
 *   document in the final blend. Tuned in `E10.I5`.
 * @param rrfK the `k` of reciprocal-rank fusion — `1 / (k + rank)` per
 *   recall source, summed (plan `E5.I12`: "RRF-style: `1/(60+rank)` per
 *   source, summed"). Tuned in `E10.I5`.
 * @param damping PageRank's α (plan `E5.I12`: 0.85). The remaining
 *   `1 - α` of every node's mass teleports back onto the personalization
 *   vector, which is what makes this rank *personalized* rather than
 *   global.
 * @param maxIterations hard cap on power-iteration sweeps (plan `E5.I12`:
 *   ≤ 20). At α = 0.85 the iteration error contracts by a factor α per
 *   sweep, so 20 sweeps leave ≤ `0.85^20 ≈ 3.9 %` of the initial L1
 *   residual — far inside what a *ranking* (as opposed to a numeric
 *   readout) can tell apart, and the cap is what keeps the step bounded on
 *   a phone. Raise it only in a test that wants the exact fixed point.
 * @param epsilon early-out threshold on the L1 delta between two
 *   successive iterates (plan `E5.I12`: 1e-6). Small or edge-free
 *   subgraphs — the common case for a fresh vault — hit this in a handful
 *   of sweeps and never pay for all [maxIterations].
 * @param neighborHops how far past the candidate documents the ranking
 *   graph reaches (plan `E5.I12`: "the candidate documents plus their
 *   1-hop neighbors").
 * @param maxNeighborNodes node budget for that expansion, *on top of* the
 *   candidate documents themselves. Together with [maxCandidates] this is
 *   what bounds the rank step to the recalled neighbourhood instead of the
 *   whole vault (bd `E5.I12`'s smoke budget: "90 candidates + 200
 *   neighbors").
 * @param maxCandidates defensive cap on the union of the recall lists
 *   before the graph is built (plan `E5.I12`: "the union of the three
 *   recall lists (≤ 90 chunks)" — 3 × the recall stages' own
 *   `DEFAULT_K = 30`). A caller that fans in wider than the plan's default
 *   is truncated by fused recall, not rejected.
 * @param defaultK default size of the ranked list handed back.
 *   `docs/design/VAULT_TOOL_PRIMITIVES.md` §3.4: "`k` defaults to 8
 *   (matches `retrieveContext` default)".
 */
public data class RankerConfig(
    val recallWeight: Double = DEFAULT_RECALL_WEIGHT,
    val pprWeight: Double = DEFAULT_PPR_WEIGHT,
    val rrfK: Double = DEFAULT_RRF_K,
    val damping: Double = DEFAULT_DAMPING,
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val epsilon: Double = DEFAULT_EPSILON,
    val neighborHops: Int = DEFAULT_NEIGHBOR_HOPS,
    val maxNeighborNodes: Int = DEFAULT_MAX_NEIGHBOR_NODES,
    val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    val defaultK: Int = DEFAULT_K,
) {
    init {
        require(recallWeight >= 0.0 && recallWeight.isFinite()) { "recallWeight must be finite and >= 0" }
        require(pprWeight >= 0.0 && pprWeight.isFinite()) { "pprWeight must be finite and >= 0" }
        require(recallWeight + pprWeight > 0.0) { "recallWeight + pprWeight must be > 0" }
        require(rrfK >= 0.0 && rrfK.isFinite()) { "rrfK must be finite and >= 0" }
        require(damping >= 0.0 && damping < 1.0) { "damping must be in [0, 1), was $damping" }
        require(maxIterations >= 1) { "maxIterations must be >= 1, was $maxIterations" }
        require(epsilon >= 0.0 && epsilon.isFinite()) { "epsilon must be finite and >= 0" }
        require(neighborHops >= 0) { "neighborHops must be >= 0, was $neighborHops" }
        require(maxNeighborNodes >= 0) { "maxNeighborNodes must be >= 0, was $maxNeighborNodes" }
        require(maxCandidates >= 1) { "maxCandidates must be >= 1, was $maxCandidates" }
        require(defaultK >= 1) { "defaultK must be >= 1, was $defaultK" }
    }

    public companion object {
        /** Plan `E5.I12`. Tuned in `E10.I5`. */
        public const val DEFAULT_RECALL_WEIGHT: Double = 0.6

        /** Plan `E5.I12`. Tuned in `E10.I5`. */
        public const val DEFAULT_PPR_WEIGHT: Double = 0.4

        /** Plan `E5.I12` (`1/(60+rank)`). Tuned in `E10.I5`. */
        public const val DEFAULT_RRF_K: Double = 60.0

        /** Plan `E5.I12` (α). Tuned in `E10.I5`. */
        public const val DEFAULT_DAMPING: Double = 0.85

        /** Plan `E5.I12` ("≤ 20 iterations"). */
        public const val DEFAULT_MAX_ITERATIONS: Int = 20

        /** Plan `E5.I12` ("L1 delta < 1e-6"). */
        public const val DEFAULT_EPSILON: Double = 1e-6

        /** Plan `E5.I12` ("plus their 1-hop neighbors"). */
        public const val DEFAULT_NEIGHBOR_HOPS: Int = 1

        /** Plan `E5.I12`'s smoke budget ("90 candidates + 200 neighbors"). */
        public const val DEFAULT_MAX_NEIGHBOR_NODES: Int = 200

        /** Plan `E5.I12` ("≤ 90 chunks" — 3 recall sources × `DEFAULT_K = 30`). */
        public const val DEFAULT_MAX_CANDIDATES: Int = 90

        /** `docs/design/VAULT_TOOL_PRIMITIVES.md` §3.4. */
        public const val DEFAULT_K: Int = 8

        /** The plan's values. Prefer this over re-constructing a default instance per call. */
        public val DEFAULT: RankerConfig = RankerConfig()
    }
}
