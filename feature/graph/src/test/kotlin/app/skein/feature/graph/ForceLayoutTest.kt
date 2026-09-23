package app.skein.feature.graph

import app.skein.core.model.EdgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // bd skein-67ak: ForceLayout.step — the single-iteration primitive a
    // live, continuous simulation (GraphSimulation) drives frame by frame.

    @Test
    fun `step leaves every pinned node's position exactly unchanged`() {
        val nodeIds = listOf("center", "a", "b")
        val edges = listOf(GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0))
        val positions =
            mapOf(
                "center" to Vec2(0f, 0f),
                "a" to Vec2(10f, 0f),
                "b" to Vec2(5f, 5f),
            )

        val result =
            ForceLayout.step(
                nodeIds = nodeIds,
                edges = edges,
                positions = positions,
                pinned = setOf("center", "a"),
                temperature = 60f,
            )

        assertEquals(Vec2(0f, 0f), result.positions.getValue("center"))
        assertEquals(Vec2(10f, 0f), result.positions.getValue("a"))
    }

    @Test
    fun `step moves a connected pair closer together over successive iterations (energy decreases)`() {
        val nodeIds = listOf("center", "a")
        val edges = listOf(GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0))
        var positions: Map<String, Vec2> = mapOf("center" to Vec2(0f, 0f), "a" to Vec2(500f, 0f))
        val pinned = setOf("center")
        var temperature = 60f

        val edgeLength = { (positions.getValue("a") - positions.getValue("center")).length() }
        val startingLength = edgeLength()

        repeat(20) {
            val result = ForceLayout.step(nodeIds, edges, positions, pinned, temperature)
            positions = result.positions
            temperature *= 0.9f
        }

        assertTrue(
            "expected edge length to shrink from $startingLength toward equilibrium, ended at ${edgeLength()}",
            edgeLength() < startingLength,
        )
    }

    @Test
    fun `step reports near-zero total displacement once the layout is already settled (idle detection)`() {
        val nodeIds = listOf("center", "a", "b")
        val edges =
            listOf(
                GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0),
                GraphEdge(srcId = "a", dstId = "b", kind = EdgeKind.WIKILINK, weight = 1.0),
            )
        val settled = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 9L)

        val result = ForceLayout.step(nodeIds, edges, settled, pinned = setOf("center"), temperature = 0.1f)

        assertTrue(
            "expected near-zero displacement once settled, got ${result.totalDisplacement}",
            result.totalDisplacement < 1f,
        )
    }

    @Test
    fun `step run compute's own schedule iteration-by-iteration reproduces compute exactly`() {
        // compute is now just "seed, then call step DEFAULT_ITERATIONS times
        // with a fixed cooling schedule" — this pins that refactor down by
        // reimplementing the loop in the test and asserting bit-identical
        // output.
        val nodeIds = listOf("center", "a", "b", "c")
        val edges =
            listOf(
                GraphEdge(srcId = "center", dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0),
                GraphEdge(srcId = "a", dstId = "b", kind = EdgeKind.TAG, weight = 0.4),
            )

        var positions: Map<String, Vec2> =
            ForceLayout.seedInitialPositions(nodeIds, centerId = "center", seed = 20260920L)
        var temperature = ForceLayout.DEFAULT_WORLD_EXTENT / 10f
        val cooling = temperature / ForceLayout.DEFAULT_ITERATIONS
        repeat(ForceLayout.DEFAULT_ITERATIONS) {
            positions = ForceLayout.step(nodeIds, edges, positions, setOf("center"), temperature).positions
            temperature = (temperature - cooling).coerceAtLeast(0f)
        }

        val expected = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = "center", seed = 20260920L)
        assertEquals(expected.keys, positions.keys)
        for (id in nodeIds) {
            assertEquals("x for $id", expected.getValue(id).x, positions.getValue(id).x, 0.0f)
            assertEquals("y for $id", expected.getValue(id).y, positions.getValue(id).y, 0.0f)
        }
    }
}
