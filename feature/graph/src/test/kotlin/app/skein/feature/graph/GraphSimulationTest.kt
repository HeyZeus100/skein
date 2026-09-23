package app.skein.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.EdgeKind

/**
 * `GraphSimulation` (bd `skein-8g4c`, fixing bd `skein-67ak`'s jitter): pure
 * Kotlin/JVM tests for the live, per-frame simulation `GraphView` drives
 * from `withFrameNanos` — no Compose/Android runtime needed, same
 * discipline [ForceLayoutTest] uses.
 *
 * ## Why a 3-node chain, dragged outward
 *
 * `center - a - b`: dragging `a` (never the center, so the center stays
 * pinned throughout and can't confound the measurement) away from `b` in a
 * straight line is the same "pull the string" gesture the owner described
 * (bd note, 2026-09-23) and is the exact shape of the reported bug — every
 * `dragTo` event on the old temperature-reheating step re-injected maximum
 * displacement energy, so `b` overshot the equilibrium distance and bounced
 * back, frame after frame ("vibrate and flicker").
 *
 * The scripted-drag test doesn't assert `b`'s raw distance to `a` shrinks
 * every frame — `a` is continuously retreating throughout the whole script,
 * so that distance can legitimately keep growing the entire time. What it
 * asserts instead is the bead's own acceptance language: "a neighbour's
 * position moving monotonically toward the dragged node ... no sign flips
 * in neighbour velocity after the first few frames". That's measured as
 * `b`'s own frame-to-frame displacement projected onto the direction from
 * `b` to `a` at the *start* of that frame (before `b` moves) — a
 * geometry-independent "is the neighbour closing in on the leader, or
 * oscillating" signal that's exactly what a human eye calls "vibrate" when
 * it flips sign frame after frame.
 */
class GraphSimulationTest {
    @Test
    fun `a neighbor follows the dragged node with damped lag, not jitter, over 60 frames`() {
        val nodeIds = listOf(CENTER, "a", "b")
        val edges =
            listOf(
                GraphEdge(srcId = CENTER, dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0),
                GraphEdge(srcId = "a", dstId = "b", kind = EdgeKind.WIKILINK, weight = 1.0),
            )
        val initialPositions = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = CENTER, seed = 7L)
        val simulation = GraphSimulation(nodeIds, edges, initialPositions, centerId = CENTER)

        val dragStart = simulation.positions.getValue("a")
        val outward = unit(dragStart - simulation.positions.getValue("b"))
        val dragEnd = dragStart + outward * DRAG_DISTANCE

        simulation.beginDrag("a")

        val towardness = mutableListOf<Float>()
        var prevA = dragStart
        var prevB = simulation.positions.getValue("b")
        repeat(FRAMES) { frame ->
            val t = (frame + 1).toFloat() / FRAMES
            val pointer = dragStart + (dragEnd - dragStart) * t
            simulation.dragTo("a", pointer)
            simulation.step()

            val newB = simulation.positions.getValue("b")
            val dirToA = unit(prevA - prevB)
            val velocityB = newB - prevB
            towardness += velocityB.x * dirToA.x + velocityB.y * dirToA.y

            prevA = simulation.positions.getValue("a")
            prevB = newB
        }
        simulation.endDrag("a")

        val settled = towardness.drop(WARMUP_FRAMES)
        assertTrue(
            "expected the neighbor's per-frame motion toward the dragged node to never reverse sign " +
                "once past warmup (that reversal is exactly the vibrate/flicker bug) - got $settled",
            settled.all { it >= -TOWARDNESS_TOLERANCE },
        )
    }

    @Test
    fun `the dragged node is pinned exactly to the pointer and leads instantly, never lagging behind it`() {
        val simulation = twoNodeSimulation()

        simulation.beginDrag("a")
        val target = Vec2(999f, -123f)
        simulation.dragTo("a", target)
        assertEquals(target, simulation.positions.getValue("a"))

        // Neighbour integration happening around it must never pull the
        // dragged node itself away from the pointer.
        simulation.step()
        simulation.step()
        assertEquals(target, simulation.positions.getValue("a"))
    }

    @Test
    fun `releasing a drag lets the simulation decay to idle within about a second of frames`() {
        val simulation = twoNodeSimulation()
        val dragStart = simulation.positions.getValue("a")

        simulation.beginDrag("a")
        repeat(WARMUP_FRAMES) { frame ->
            simulation.dragTo("a", dragStart + Vec2(frame * 5f, 0f))
            simulation.step()
        }
        simulation.endDrag("a")

        var framesToIdle = 0
        while (simulation.isActive && framesToIdle < MAX_FRAMES_TO_IDLE) {
            simulation.step()
            framesToIdle++
        }

        assertTrue(
            "expected the simulation to settle to idle within $MAX_FRAMES_TO_IDLE frames " +
                "(~${MAX_FRAMES_TO_IDLE / 60f}s at the 60 Hz cap) of releasing the drag, " +
                "still active after $framesToIdle frames",
            !simulation.isActive,
        )
    }

    @Test
    fun `the center stays pinned at its position until it is itself dragged`() {
        val simulation = twoNodeSimulation()
        val centerBefore = simulation.positions.getValue(CENTER)

        repeat(30) { simulation.step() }
        assertEquals(
            "center should stay exactly put while unpinned-by-drag",
            centerBefore,
            simulation.positions.getValue(CENTER),
        )

        simulation.beginDrag(CENTER)
        simulation.dragTo(CENTER, Vec2(42f, -17f))
        simulation.endDrag(CENTER)

        assertEquals(Vec2(42f, -17f), simulation.positions.getValue(CENTER))
    }

    private fun twoNodeSimulation(): GraphSimulation {
        val nodeIds = listOf(CENTER, "a")
        val edges = listOf(GraphEdge(srcId = CENTER, dstId = "a", kind = EdgeKind.WIKILINK, weight = 1.0))
        val initialPositions = ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = CENTER, seed = 3L)
        return GraphSimulation(nodeIds, edges, initialPositions, centerId = CENTER)
    }

    private fun unit(v: Vec2): Vec2 {
        val len = v.length()
        return if (len > 0f) v * (1f / len) else Vec2(1f, 0f)
    }

    private companion object {
        const val CENTER = "center"
        const val FRAMES = 60
        const val WARMUP_FRAMES = 6
        const val DRAG_DISTANCE = 400f
        const val TOWARDNESS_TOLERANCE = 0.01f
        const val MAX_FRAMES_TO_IDLE = 120
    }
}
