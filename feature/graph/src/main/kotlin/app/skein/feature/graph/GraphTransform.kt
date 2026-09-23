// `GraphTransform` (bd `skein-z2u`, plan `E6.I11`): the world-space ↔
// screen-space math `GraphView`'s `Canvas` draw pass and tap/long-press
// hit-testing share. Pure Kotlin (no Compose import) so it's exercised by a
// plain JVM unit test — [GraphView] is the only caller that ever touches an
// actual `androidx.compose.ui.geometry.Offset`, converting at the boundary.
package app.skein.feature.graph

public object GraphTransform {
    /**
     * The scale factor that fits [ForceLayout]'s `worldExtent`-side square
     * inside a `canvasWidth` × `canvasHeight` viewport at zoom = 1 — the
     * smaller of the two dimensions wins so the whole world square is
     * visible without clipping on the narrower axis.
     */
    public fun baseScale(
        canvasWidth: Float,
        canvasHeight: Float,
        worldExtent: Float,
    ): Float = minOf(canvasWidth, canvasHeight) / worldExtent

    /**
     * Projects a [ForceLayout] [world] position to screen pixels: scaled by
     * [baseScale] × [zoom] (pinch-to-zoom, ≥ 1 by default), then re-centered
     * on [canvasCenter] and offset by the accumulated [pan] drag.
     */
    public fun worldToScreen(
        world: Vec2,
        canvasCenter: Vec2,
        baseScale: Float,
        zoom: Float,
        pan: Vec2,
    ): Vec2 {
        val effectiveScale = baseScale * zoom
        return Vec2(world.x * effectiveScale, world.y * effectiveScale) + canvasCenter + pan
    }

    /**
     * The exact inverse of [worldToScreen] — bd `skein-67ak`: per-node drag
     * pins a node to "the pointer in world space", which means every drag
     * delta (a screen-space pointer position) must be converted back into
     * the same world coordinates [ForceLayout]/[GraphSimulation] work in.
     */
    public fun screenToWorld(
        screen: Vec2,
        canvasCenter: Vec2,
        baseScale: Float,
        zoom: Float,
        pan: Vec2,
    ): Vec2 {
        val effectiveScale = baseScale * zoom
        val unpanned = screen - canvasCenter - pan
        return Vec2(unpanned.x / effectiveScale, unpanned.y / effectiveScale)
    }
}
