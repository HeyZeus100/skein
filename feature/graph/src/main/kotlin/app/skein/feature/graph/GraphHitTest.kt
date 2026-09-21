// `GraphHitTest` (bd `skein-z2u`, plan `E6.I11`): pure tap/long-press
// hit-test math for `GraphView`'s `pointerInput` handlers. Coordinate-space
// agnostic — [GraphView] calls it with node centers already projected to
// screen pixels via `GraphTransform.worldToScreen`, but nothing here cares
// which space [positions] is expressed in, which is what keeps it testable
// with plain [Vec2] values in a JVM unit test.
package app.skein.feature.graph

public object GraphHitTest {
    /**
     * The id whose [positions] entry is nearest [point], provided that
     * nearest distance is within [hitRadius]; `null` if every node is
     * farther than that. Ties (two nodes at the exact same distance) are
     * broken by [positions]'s iteration order — the first-encountered node
     * wins.
     */
    public fun nodeAt(
        positions: Map<String, Vec2>,
        point: Vec2,
        hitRadius: Float,
    ): String? {
        var bestId: String? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, position) in positions) {
            val distance = (position - point).length()
            if (distance <= hitRadius && distance < bestDistance) {
                bestDistance = distance
                bestId = id
            }
        }
        return bestId
    }
}
