// `ForceLayout` (bd `skein-z2u`/`skein-67ak`, plan `E6.I11`, spec §8.6): a
// Fruchterman-Reingold-style force simulation, computed in pure Kotlin —
// no third-party graph/physics library (non-negotiable), no Compose or
// Android import. [Vec2] stands in for `androidx.compose.ui.geometry.Offset`
// so this file (and its JVM unit test) never needs the Compose runtime.
//
// Algorithm (plan text verbatim): repulsion `k²/d` between every pair,
// attraction `d²/k × weight` along each edge, cooling over [DEFAULT_ITERATIONS]
// iterations then idle. [compute]'s `centerId` is pinned at the origin for
// the whole call — "force-directed layout centered on the selected document"
// (spec §8.6) is implemented literally as a fixed anchor, which is also
// what makes the center node's screen position stable across recompositions
// for [GraphView]'s highlight ring.
//
// bd `skein-67ak`: [compute] (one-shot, [DEFAULT_ITERATIONS] iterations then
// idle forever) is now built out of two smaller pieces so a caller can also
// run the simulation *continuously*, frame by frame: [step] advances the
// layout by exactly one Fruchterman-Reingold iteration given a caller-chosen
// `pinned` set (not hardcoded to a single center id — [GraphSimulation] pins
// whichever node is mid-drag instead) and `temperature` (the caller owns the
// cooling schedule, since a live per-frame loop cools/idles/re-heats on its
// own clock, not a fixed iteration count). [compute] itself is now just
// "seed, then call [step] [iterations] times, cooling as it goes" — the
// exact same arithmetic as before, so its existing callers/tests are
// unaffected bit-for-bit.
//
// Determinism: the only randomness is the seeded initial placement of every
// non-center node ([kotlin.random.Random] seeded by [compute]'s `seed`
// argument); every iteration after that ([step]) is a deterministic function
// of the current positions, walked in [nodeIds] order — never a `HashMap`'s
// iteration order — so two calls with the same arguments produce
// bit-identical output. [step] itself takes no seed and uses no randomness
// at all.
package app.skein.feature.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A plain 2D vector in the layout's own "world" coordinate space (arbitrary
 * units — [GraphTransform] maps it to actual pixels for drawing/hit-testing).
 */
public data class Vec2(
    val x: Float,
    val y: Float,
) {
    public operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    public operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    public operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)

    public fun length(): Float = sqrt(x * x + y * y)
}

public object ForceLayout {
    public const val DEFAULT_ITERATIONS: Int = 300

    /** Side length of the square "world" the simulation lays nodes out in. */
    public const val DEFAULT_WORLD_EXTENT: Float = 600f

    private const val MIN_DISTANCE: Float = 0.01f

    /** The initial per-frame "temperature" a live, continuous simulation should (re)start hot at — see [GraphSimulation]. */
    public const val DEFAULT_TEMPERATURE: Float = DEFAULT_WORLD_EXTENT / 10f

    /**
     * One [step] call's result: the advanced positions, plus the total
     * magnitude of movement every non-pinned node underwent this iteration —
     * a live simulation's idle-detection signal (see [GraphSimulation]):
     * once this falls under a threshold, the layout has settled and the
     * per-frame loop can stop.
     */
    public data class StepResult(
        val positions: Map<String, Vec2>,
        val totalDisplacement: Float,
    )

    /**
     * Lays out [nodeIds] given the (undirected) [edges] between them, by
     * seeding an initial placement and then calling [step] [iterations]
     * times with [centerId] pinned and a fixed cooling schedule (temperature
     * starts at [DEFAULT_TEMPERATURE], evenly cooling to `0` over
     * [iterations] calls). [seed] drives only the initial placement of every
     * other node — see the file header for the determinism argument.
     *
     * An [edges] endpoint not present in [nodeIds] is ignored rather than
     * throwing (callers pass exactly the edge list `IndexStore.neighborhood`
     * returned, which is defined purely in terms of node ids — this keeps
     * the function total over any subset of those ids).
     */
    public fun compute(
        nodeIds: List<String>,
        edges: List<GraphEdge>,
        centerId: String,
        seed: Long,
        iterations: Int = DEFAULT_ITERATIONS,
        worldExtent: Float = DEFAULT_WORLD_EXTENT,
    ): Map<String, Vec2> {
        if (nodeIds.isEmpty()) return emptyMap()
        if (nodeIds.size == 1) return mapOf(nodeIds.single() to Vec2(0f, 0f))

        var positions: Map<String, Vec2> = seedInitialPositions(nodeIds, centerId, seed, worldExtent)
        val pinned = setOf(centerId)

        var temperature = worldExtent / 10f
        val cooling = temperature / iterations.coerceAtLeast(1)

        repeat(iterations) {
            positions = step(nodeIds, edges, positions, pinned, temperature, worldExtent).positions
            temperature = (temperature - cooling).coerceAtLeast(0f)
        }

        return positions
    }

    /**
     * The seeded initial placement [compute] starts from: [centerId] at the
     * origin, every other node scattered uniformly at random inside a disc
     * of radius `worldExtent / 2`. Exposed on its own so a live simulation
     * (bd `skein-67ak`) can re-seed a fresh graph the same deterministic way
     * `compute` always has, without re-running the settle loop.
     */
    public fun seedInitialPositions(
        nodeIds: List<String>,
        centerId: String,
        seed: Long,
        worldExtent: Float = DEFAULT_WORLD_EXTENT,
    ): Map<String, Vec2> {
        if (nodeIds.isEmpty()) return emptyMap()
        if (nodeIds.size == 1) return mapOf(nodeIds.single() to Vec2(0f, 0f))

        val random = Random(seed)
        val positions = LinkedHashMap<String, Vec2>()
        for (id in nodeIds) {
            positions[id] =
                if (id == centerId) {
                    Vec2(0f, 0f)
                } else {
                    val angle = random.nextFloat() * (2f * PI.toFloat())
                    val radius = random.nextFloat() * (worldExtent / 2f)
                    Vec2(cos(angle) * radius, sin(angle) * radius)
                }
        }
        return positions
    }

    /**
     * Advances [positions] by exactly one Fruchterman-Reingold iteration:
     * repulsion `k²/d` between every pair in [nodeIds], attraction `d²/k ×
     * weight` along each of [edges], capped by [temperature]. Every id in
     * [pinned] keeps its exact current position — unlike [compute], which
     * only ever pins a single fixed `centerId`, this accepts an arbitrary
     * set so a live simulation can pin whichever node is mid-drag (bd
     * `skein-67ak`; see [GraphSimulation]) in addition to (or instead of)
     * the center. Pure and deterministic — no randomness, no caller-visible
     * mutable state; the same inputs always produce the same [StepResult].
     *
     * A [positions] entry missing for some id in [nodeIds] (or an [edges]
     * endpoint outside [positions]) is skipped rather than throwing, same
     * total-over-any-subset discipline as [compute].
     */
    public fun step(
        nodeIds: List<String>,
        edges: List<GraphEdge>,
        positions: Map<String, Vec2>,
        pinned: Set<String>,
        temperature: Float,
        worldExtent: Float = DEFAULT_WORLD_EXTENT,
    ): StepResult {
        if (nodeIds.size <= 1) return StepResult(positions, 0f)

        val displacement = computeForces(nodeIds, edges, positions, worldExtent)

        val next = LinkedHashMap<String, Vec2>()
        var totalDisplacement = 0f
        for (id in nodeIds) {
            val current = positions[id] ?: continue
            if (id in pinned) {
                next[id] = current
                continue
            }
            val disp = displacement.getValue(id)
            val dist = disp.length().coerceAtLeast(MIN_DISTANCE)
            val capped = disp * (minOf(dist, temperature) / dist)
            next[id] = current + capped
            totalDisplacement += capped.length()
        }

        return StepResult(next, totalDisplacement)
    }

    /**
     * The raw Fruchterman-Reingold force on every node in [nodeIds] — the
     * same repulsion `k²/d` (every pair) plus attraction `d²/k × weight`
     * (each of [edges]) math [step] itself uses, extracted so a caller can
     * apply it under a different integration scheme entirely. [step]
     * literally calls this and then caps/applies the result by its own
     * `temperature`; bd `skein-8g4c`'s live, continuous simulation
     * ([GraphSimulation]) calls this directly instead and integrates it as a
     * damped velocity (semi-implicit Euler) rather than a temperature-capped
     * displacement — [step]'s hard temperature cap is exactly what made a
     * held drag's every pointer event re-inject a full jump of energy into
     * its neighbours and made them vibrate; velocity+damping smooths that
     * out while reusing the identical force computation. Not itself capped
     * or applied to [positions] — purely the force vectors, unpinned.
     *
     * Same total-over-any-subset discipline as [step]/[compute]: a
     * [positions] entry missing for some id in [nodeIds] (or an [edges]
     * endpoint outside [positions]) is skipped rather than throwing.
     */
    public fun computeForces(
        nodeIds: List<String>,
        edges: List<GraphEdge>,
        positions: Map<String, Vec2>,
        worldExtent: Float = DEFAULT_WORLD_EXTENT,
    ): Map<String, Vec2> {
        val displacement = LinkedHashMap<String, Vec2>()
        for (id in nodeIds) displacement[id] = Vec2(0f, 0f)
        if (nodeIds.size <= 1) return displacement

        val area = worldExtent * worldExtent
        val k = sqrt(area / nodeIds.size)
        val relevantEdges = edges.filter { it.srcId in positions && it.dstId in positions && it.srcId != it.dstId }

        // Repulsion: every pair, force = k² / d.
        for (i in nodeIds.indices) {
            val v = nodeIds[i]
            val vPos = positions[v] ?: continue
            var disp = displacement.getValue(v)
            for (j in nodeIds.indices) {
                if (i == j) continue
                val u = nodeIds[j]
                val uPos = positions[u] ?: continue
                val delta = vPos - uPos
                val dist = delta.length().coerceAtLeast(MIN_DISTANCE)
                val force = (k * k) / dist
                disp += delta * (force / dist)
            }
            displacement[v] = disp
        }

        // Attraction: each edge, force = d² / k × weight.
        for (edge in relevantEdges) {
            val delta = positions.getValue(edge.srcId) - positions.getValue(edge.dstId)
            val dist = delta.length().coerceAtLeast(MIN_DISTANCE)
            val force = (dist * dist / k) * edge.weight.toFloat()
            val shift = delta * (force / dist)
            displacement[edge.srcId] = displacement.getValue(edge.srcId) - shift
            displacement[edge.dstId] = displacement.getValue(edge.dstId) + shift
        }

        return displacement
    }
}
