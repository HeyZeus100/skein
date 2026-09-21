package app.skein.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `GraphHitTest` (bd `skein-z2u`, plan `E6.I11`) tests — pure coordinate math, no Compose. */
class GraphHitTestTest {
    private val positions =
        linkedMapOf(
            "center" to Vec2(0f, 0f),
            "a" to Vec2(100f, 0f),
            "b" to Vec2(0f, 100f),
        )

    @Test
    fun `a tap exactly on a node returns its id`() {
        val hit = GraphHitTest.nodeAt(positions, point = Vec2(0f, 0f), hitRadius = 24f)

        assertEquals("center", hit)
    }

    @Test
    fun `a tap within the hit radius but not exactly on the node still hits it`() {
        val hit = GraphHitTest.nodeAt(positions, point = Vec2(105f, 3f), hitRadius = 24f)

        assertEquals("a", hit)
    }

    @Test
    fun `a tap farther than every node's hit radius returns null`() {
        val hit = GraphHitTest.nodeAt(positions, point = Vec2(500f, 500f), hitRadius = 24f)

        assertNull(hit)
    }

    @Test
    fun `the nearest node wins when two are within radius of each other`() {
        val crowded =
            linkedMapOf(
                "far" to Vec2(20f, 0f),
                "near" to Vec2(5f, 0f),
            )

        val hit = GraphHitTest.nodeAt(crowded, point = Vec2(0f, 0f), hitRadius = 30f)

        assertEquals("near", hit)
    }

    @Test
    fun `an exact tie is broken by iteration order — the first-encountered node wins`() {
        val tied =
            linkedMapOf(
                "first" to Vec2(10f, 0f),
                "second" to Vec2(0f, 10f),
            )

        val hit = GraphHitTest.nodeAt(tied, point = Vec2(0f, 0f), hitRadius = 30f)

        assertEquals("first", hit)
    }

    @Test
    fun `a point exactly at the hit radius boundary still counts as a hit`() {
        val hit = GraphHitTest.nodeAt(positions, point = Vec2(24f, 0f), hitRadius = 24f)

        assertEquals("center", hit)
    }

    @Test
    fun `an empty position map never hits anything`() {
        val hit = GraphHitTest.nodeAt(emptyMap(), point = Vec2(0f, 0f), hitRadius = 100f)

        assertNull(hit)
    }
}
