// `PersonalizedPageRank` (skein-dxj, plan `E5.I12`) tests.
//
// The fixtures here are hand-built edge lists, not store fixtures: the
// class is pure arithmetic over `Edge` values, so its fixed points can be
// solved on paper and asserted exactly rather than eyeballed.
//
// ## The hand-computed 3-node example (bd `E5.I12`: "α and convergence
// pinned by a hand-computed 3-node example")
//
// Path `A — B — C`, every edge `WIKILINK` (weight 1.0), personalization
// entirely on `A`. Incident weights are `A: 1`, `B: 2`, `C: 1`, so the
// undirected transition matrix is
//
//     A → B                 B → A (½), B → C (½)          C → B
//
// and the fixed point of `r = 0.15·e_A + 0.85·Mᵀr` solves as
//
//     r(B) = 0.85·(r(A) + r(C)),  r(A) = 0.15 + 0.425·r(B),  r(C) = 0.425·r(B)
//     ⇒ r(B)·(1 − 0.7225) = 0.1275 ⇒ r(B) = 0.1275 / 0.2775 = 0.459459…
//     ⇒ r(A) = 0.345270…,  r(C) = 0.195270…      (sum = 1)
//
// Two tests pin that: one raises the iteration budget and lands on the
// closed form to 1e-9, the other runs the plan's *actual* 20-iteration
// budget at α = 0.85 and pins the exact iterate it produces — which is
// what makes both constants regression-proof (change α or the cap and the
// 20th iterate moves).

package app.skein.core.rag.rank

import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class PersonalizedPageRankTest {
    @Test
    fun `three node path converges to the hand computed fixed point`() {
        val converged = RankerConfig(maxIterations = 500, epsilon = 1e-15)
        val result = PersonalizedPageRank(converged).rank(path(), mapOf("a" to 1.0))

        assertThat(result.getValue("a")).isWithin(1e-9).of(0.34527027027027024)
        assertThat(result.getValue("b")).isWithin(1e-9).of(0.45945945945945943)
        assertThat(result.getValue("c")).isWithin(1e-9).of(0.19527027027027025)
    }

    @Test
    fun `the plan's twenty iterations at alpha 0-85 land on the hand computed iterate`() {
        val result = PersonalizedPageRank().rank(path(), mapOf("a" to 1.0))

        assertThat(result.getValue("a")).isWithin(1e-12).of(0.35417448687076686)
        assertThat(result.getValue("b")).isWithin(1e-12).of(0.44165102625846631)
        assertThat(result.getValue("c")).isWithin(1e-12).of(0.20417448687076684)
    }

    @Test
    fun `the pinned budget is within four percent of the fixed point`() {
        // 0.85^20 ≈ 3.9 % contraction of the initial residual — the bound
        // `RankerConfig.maxIterations`' kdoc claims.
        val budgeted = PersonalizedPageRank().rank(path(), mapOf("a" to 1.0))
        val converged =
            PersonalizedPageRank(RankerConfig(maxIterations = 500, epsilon = 1e-15))
                .rank(path(), mapOf("a" to 1.0))

        val l1 = budgeted.keys.sumOf { kotlin.math.abs(budgeted.getValue(it) - converged.getValue(it)) }
        assertThat(l1).isLessThan(0.04)
    }

    @Test
    fun `epsilon stops the iteration after the first sweep`() {
        // The first iterate of the 3-node path is exactly (0.15, 0.85, 0):
        // all of A's mass moves to B, and 1 − α teleports back onto A. Its
        // L1 delta from the starting vector is 1.7, so an epsilon above
        // that must end the iteration right there.
        val result = PersonalizedPageRank(RankerConfig(epsilon = 2.0)).rank(path(), mapOf("a" to 1.0))

        assertThat(result.getValue("a")).isWithin(1e-12).of(0.15)
        assertThat(result.getValue("b")).isWithin(1e-12).of(0.85)
        assertThat(result.getValue("c")).isWithin(1e-12).of(0.0)
    }

    @Test
    fun `damping of zero returns the normalized personalization vector`() {
        val result = PersonalizedPageRank(RankerConfig(damping = 0.0)).rank(path(), mapOf("a" to 3.0, "c" to 1.0))

        assertThat(result.getValue("a")).isWithin(1e-12).of(0.75)
        assertThat(result.getValue("b")).isWithin(1e-12).of(0.0)
        assertThat(result.getValue("c")).isWithin(1e-12).of(0.25)
    }

    @Test
    fun `an edgeless graph teleports every node's mass back onto the personalization vector`() {
        val result = PersonalizedPageRank().rank(emptyList(), mapOf("a" to 2.0, "b" to 2.0))

        assertThat(result.getValue("a")).isWithin(1e-12).of(0.5)
        assertThat(result.getValue("b")).isWithin(1e-12).of(0.5)
    }

    @Test
    fun `a document linked by two strong candidates outranks an isolated document`() {
        // bd `E5.I12`'s first acceptance criterion, at the PPR layer: the
        // hub and the isolate carry identical personalization mass, so the
        // only thing that can separate them is the graph.
        val edges =
            listOf(
                edge("s1", "hub"),
                edge("s2", "hub"),
            )
        val personalization = mapOf("s1" to 0.4, "s2" to 0.4, "hub" to 0.1, "isolate" to 0.1)

        val result = PersonalizedPageRank().rank(edges, personalization)

        assertThat(result.getValue("hub")).isGreaterThan(result.getValue("isolate"))
    }

    @Test
    fun `a heavier edge kind routes more mass than a lighter one`() {
        // WIKILINK (1.0) versus TAG (0.4) from the same seed: the seed's
        // mass splits in proportion to the incident weights.
        val edges =
            listOf(
                Edge(srcId = "seed", dstId = "linked", kind = EdgeKind.WIKILINK, createdAt = 0L),
                Edge(srcId = "seed", dstId = "tagged", kind = EdgeKind.TAG, createdAt = 0L),
            )

        val result = PersonalizedPageRank().rank(edges, mapOf("seed" to 1.0))

        assertThat(result.getValue("linked")).isGreaterThan(result.getValue("tagged"))
        assertThat(result.getValue("linked") / result.getValue("tagged")).isWithin(1e-9).of(1.0 / 0.4)
    }

    @Test
    fun `an explicit edge weight overrides its kind's default`() {
        // `EdgeUpserter` stores an unresolved wikilink at 0.5 rather than
        // WIKILINK's 1.0 — reading `Edge.weight` honours that.
        val edges =
            listOf(
                Edge(srcId = "seed", dstId = "strong", kind = EdgeKind.WIKILINK, createdAt = 0L),
                Edge(srcId = "seed", dstId = "weak", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 0L),
            )

        val result = PersonalizedPageRank().rank(edges, mapOf("seed" to 1.0))

        assertThat(result.getValue("strong") / result.getValue("weak")).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `the rank vector sums to one`() {
        val result = PersonalizedPageRank().rank(path(), mapOf("a" to 1.0))

        assertThat(result.values.sum()).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `ranking is bit-identical regardless of the order edges arrive in`() {
        val edges = denseGraph()
        val personalization = mapOf("n0" to 1.0, "n3" to 0.5)
        val shuffled = edges.shuffled(Random(7))

        val a = PersonalizedPageRank().rank(edges, personalization)
        val b = PersonalizedPageRank().rank(shuffled, personalization)

        assertThat(b.keys.toList()).isEqualTo(a.keys.toList())
        for (node in a.keys) {
            assertThat(b.getValue(node)).isEqualTo(a.getValue(node))
        }
    }

    @Test
    fun `only the nodes it was handed are ranked`() {
        // The bounded-neighbourhood guarantee at this layer: nothing but
        // the given edges and personalization keys enters the graph.
        val result = PersonalizedPageRank().rank(listOf(edge("a", "b")), mapOf("a" to 1.0, "far" to 0.0))

        assertThat(result.keys).containsExactly("a", "b", "far")
    }

    @Test
    fun `an empty graph and empty personalization rank to nothing`() {
        assertThat(PersonalizedPageRank().rank(emptyList(), emptyMap())).isEmpty()
    }

    @Test
    fun `an all-zero personalization falls back to a uniform vector`() {
        val result = PersonalizedPageRank().rank(path(), mapOf("a" to 0.0, "b" to 0.0, "c" to 0.0))

        assertThat(result.values.sum()).isWithin(1e-12).of(1.0)
        // The uniform vector is the stationary distribution's teleport
        // target; B, the higher-degree node, still ends up ahead.
        assertThat(result.getValue("b")).isGreaterThan(result.getValue("a"))
        assertThat(result.getValue("a")).isWithin(1e-12).of(result.getValue("c"))
    }

    @Test
    fun `a self loop does not inflate its own node`() {
        val withLoop = PersonalizedPageRank().rank(path() + edge("c", "c"), mapOf("a" to 1.0))
        val without = PersonalizedPageRank().rank(path(), mapOf("a" to 1.0))

        for (node in without.keys) {
            assertThat(withLoop.getValue(node)).isEqualTo(without.getValue(node))
        }
    }

    // ------------------------------------------------------------------

    /** `a — b — c`, all WIKILINK (weight 1.0). See the file header. */
    private fun path(): List<Edge> = listOf(edge("a", "b"), edge("b", "c"))

    private fun denseGraph(): List<Edge> =
        buildList {
            for (i in 0 until 6) {
                add(edge("n$i", "n${(i + 1) % 6}"))
                add(Edge(srcId = "n$i", dstId = "tag:t${i % 2}", kind = EdgeKind.TAG, createdAt = 0L))
            }
        }

    private fun edge(
        src: String,
        dst: String,
    ): Edge = Edge(srcId = src, dstId = dst, kind = EdgeKind.WIKILINK, createdAt = 0L)
}
