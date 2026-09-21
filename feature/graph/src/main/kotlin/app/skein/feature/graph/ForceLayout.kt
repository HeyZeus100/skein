// `ForceLayout` (bd `skein-z2u`, plan `E6.I11`, spec §8.6): a
// Fruchterman-Reingold-style force simulation, computed in pure Kotlin —
// no third-party graph/physics library (non-negotiable), no Compose or
// Android import. [Vec2] stands in for `androidx.compose.ui.geometry.Offset`
// so this file (and its JVM unit test) never needs the Compose runtime.
//
// Algorithm (plan text verbatim): repulsion `k²/d` between every pair,
// attraction `d²/k × weight` along each edge, cooling over [DEFAULT_ITERATIONS]
// iterations then idle. [centerId] is pinned at the origin for the whole
// simulation — "force-directed layout centered on the selected document"
// (spec §8.6) is implemented literally as a fixed anchor, which is also
// what makes the center node's screen position stable across recompositions
// for [GraphView]'s highlight ring.
//
// Determinism: the only randomness is the seeded initial placement of every
// non-center node ([kotlin.random.Random] seeded by [compute]'s `seed`
// argument); every iteration after that is a deterministic function of the
// current positions, walked in [nodeIds] order — never a `HashMap`'s
// iteration order — so two calls with the same arguments produce
// bit-identical output.
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

    /**
     * Lays out [nodeIds] given the (undirected) [edges] between them.
     * [centerId] must be a member of [nodeIds]; it is pinned at `(0, 0)` for
     * every iteration. [seed] drives only the initial placement of every
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

        val random = Random(seed)
        val area = worldExtent * worldExtent
        val k = sqrt(area / nodeIds.size)

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

        val relevantEdges = edges.filter { it.srcId in positions && it.dstId in positions && it.srcId != it.dstId }

        var temperature = worldExtent / 10f
        val cooling = temperature / iterations.coerceAtLeast(1)

        repeat(iterations) {
            val displacement = LinkedHashMap<String, Vec2>()
            for (id in nodeIds) displacement[id] = Vec2(0f, 0f)

            // Repulsion: every pair, force = k² / d.
            for (i in nodeIds.indices) {
                val v = nodeIds[i]
                var disp = displacement.getValue(v)
                val vPos = positions.getValue(v)
                for (j in nodeIds.indices) {
                    if (i == j) continue
                    val u = nodeIds[j]
                    val delta = vPos - positions.getValue(u)
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

            for (id in nodeIds) {
                if (id == centerId) continue
                val disp = displacement.getValue(id)
                val dist = disp.length().coerceAtLeast(MIN_DISTANCE)
                val capped = disp * (minOf(dist, temperature) / dist)
                positions[id] = positions.getValue(id) + capped
            }
            temperature = (temperature - cooling).coerceAtLeast(0f)
        }

        return positions
    }
}
