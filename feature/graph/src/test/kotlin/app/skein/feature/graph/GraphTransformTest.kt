package app.skein.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Test

/** `GraphTransform` (bd `skein-z2u`, plan `E6.I11`) tests — pure world↔screen math, no Compose. */
class GraphTransformTest {
    @Test
    fun `baseScale picks the smaller dimension so the world square never clips`() {
        // 300x600 viewport, 600-wide world: the narrower (300) dimension wins.
        val scale = GraphTransform.baseScale(canvasWidth = 300f, canvasHeight = 600f, worldExtent = 600f)

        assertEquals(0.5f, scale, 0.0f)
    }

    @Test
    fun `baseScale is symmetric in width and height`() {
        val a = GraphTransform.baseScale(canvasWidth = 200f, canvasHeight = 800f, worldExtent = 400f)
        val b = GraphTransform.baseScale(canvasWidth = 800f, canvasHeight = 200f, worldExtent = 400f)

        assertEquals(a, b, 0.0f)
    }

    @Test
    fun `the origin maps to the canvas center at zoom 1 with no pan`() {
        val screen =
            GraphTransform.worldToScreen(
                world = Vec2(0f, 0f),
                canvasCenter = Vec2(400f, 300f),
                baseScale = 1f,
                zoom = 1f,
                pan = Vec2(0f, 0f),
            )

        assertEquals(Vec2(400f, 300f), screen)
    }

    @Test
    fun `a world point is scaled by baseScale times zoom before re-centering`() {
        val screen =
            GraphTransform.worldToScreen(
                world = Vec2(100f, 0f),
                canvasCenter = Vec2(0f, 0f),
                baseScale = 0.5f,
                zoom = 2f,
                pan = Vec2(0f, 0f),
            )

        // 100 * (0.5 * 2) = 100
        assertEquals(Vec2(100f, 0f), screen)
    }

    @Test
    fun `pan is applied after scaling and centering`() {
        val screen =
            GraphTransform.worldToScreen(
                world = Vec2(10f, 10f),
                canvasCenter = Vec2(50f, 50f),
                baseScale = 1f,
                zoom = 1f,
                pan = Vec2(5f, -5f),
            )

        assertEquals(Vec2(65f, 55f), screen)
    }

    // bd skein-67ak: screenToWorld backs per-node drag ("pin the node to the
    // pointer in world space") — it must be the exact inverse of worldToScreen.

    @Test
    fun `screenToWorld is the exact inverse of worldToScreen`() {
        val world = Vec2(37f, -114f)
        val canvasCenter = Vec2(400f, 300f)
        val baseScale = 0.8f
        val zoom = 1.6f
        val pan = Vec2(12f, -30f)

        val screen = GraphTransform.worldToScreen(world, canvasCenter, baseScale, zoom, pan)
        val roundTripped = GraphTransform.screenToWorld(screen, canvasCenter, baseScale, zoom, pan)

        assertEquals(world.x, roundTripped.x, 0.001f)
        assertEquals(world.y, roundTripped.y, 0.001f)
    }

    @Test
    fun `screenToWorld maps the canvas center back to the world origin at zoom 1 with no pan`() {
        val world =
            GraphTransform.screenToWorld(
                screen = Vec2(400f, 300f),
                canvasCenter = Vec2(400f, 300f),
                baseScale = 1f,
                zoom = 1f,
                pan = Vec2(0f, 0f),
            )

        assertEquals(Vec2(0f, 0f), world)
    }

    @Test
    fun `two points that differ in world space stay distinct on screen`() {
        val a = GraphTransform.worldToScreen(Vec2(10f, 0f), Vec2(0f, 0f), baseScale = 1f, zoom = 1f, pan = Vec2(0f, 0f))
        val b =
            GraphTransform.worldToScreen(
                Vec2(-10f, 0f),
                Vec2(0f, 0f),
                baseScale = 1f,
                zoom = 1f,
                pan = Vec2(0f, 0f),
            )

        assertEquals(20f, a.x - b.x, 0.0f)
    }
}
