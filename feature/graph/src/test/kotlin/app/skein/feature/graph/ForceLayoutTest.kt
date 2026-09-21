package app.skein.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.EdgeKind

/**
 * `ForceLayout` (bd `skein-z2u`, plan `E6.I11`) tests. Pure Kotlin, no
 * Compose/Android runtime needed — see that file's header for the
 * determinism argument these tests exercise directly.
 */
class ForceLayoutTest {
    @Test
    fun `connected nodes end up closer together than unconnected ones`() {
        // Six-node fixture: a chain center-a-b, and two isolated nodes c, d
        // with no edges to anything. `center` is directly linked to `a`;
        // `c` and `d` have no edges at all, so repulsion is the only force
        // acting on them and they should not end up any closer to `center`
        // than the actually-linked `a` does.
        val nodeIds = listOf("center", "a", "b", "c", "d", "e")
        val edges =
            listOf(
                GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0),
                GraphEdge(srcId = "a", dstId = "b", kind = EdgeKind.WIKILINK, weight = 1.0),
            )

        val positions = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 42L)

        val center = positions.getValue("center")
        val distA = (positions.getValue("a") - center).length()
        val distC = (positions.getValue("c") - center).length()
        val distD = (positions.getValue("d") - center).length()
        val distE = (positions.getValue("e") - center).length()

        assertTrue("connected node 'a' ($distA) should be closer than unconnected 'c' ($distC)", distA < distC)
        assertTrue("connected node 'a' ($distA) should be closer than unconnected 'd' ($distD)", distA < distD)
        assertTrue("connected node 'a' ($distA) should be closer than unconnected 'e' ($distE)", distA < distE)
    }

    @Test
    fun `positions are deterministic for a fixed seed`() {
        val nodeIds = listOf("center", "a", "b", "c")
        val edges =
            listOf(
                GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0),
                GraphEdge(srcId = "a", dstId = "b", kind = EdgeKind.TAG, weight = 0.4),
            )

        val first = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 20260920L)
        val second = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 20260920L)

        assertEquals(first.keys, second.keys)
        for (id in nodeIds) {
            assertEquals("x for $id", first.getValue(id).x, second.getValue(id).x, 0.0f)
            assertEquals("y for $id", first.getValue(id).y, second.getValue(id).y, 0.0f)
        }
    }

    @Test
    fun `a different seed produces a different non-center layout`() {
        val nodeIds = listOf("center", "a", "b", "c")
        val edges = listOf(GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0))

        val first = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 1L)
        val second = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 2L)

        assertNotEquals(first.getValue("b"), second.getValue("b"))
    }

    @Test
    fun `the center node is always pinned at the origin`() {
        val nodeIds = listOf("center", "a", "b")
        val edges = listOf(GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0))

        val positions = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 7L)

        assertEquals(Vec2(0f, 0f), positions.getValue("center"))
    }

    @Test
    fun `a single node is placed at the origin without iterating`() {
        val positions = ForceLayout.compute(nodeIds = listOf("solo"), edges = emptyList(), centerId = "solo", seed = 1L)

        assertEquals(mapOf("solo" to Vec2(0f, 0f)), positions)
    }

    @Test
    fun `an edge endpoint outside nodeIds is ignored rather than throwing`() {
        val nodeIds = listOf("center", "a")
        val edges =
            listOf(
                GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0),
                GraphEdge(srcId = "center", dstId = "not-in-nodeIds", kind = EdgeKind.WIKILINK, weight = 1.0),
            )

        val positions = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 1L)

        assertEquals(setOf("center", "a"), positions.keys)
    }
}
