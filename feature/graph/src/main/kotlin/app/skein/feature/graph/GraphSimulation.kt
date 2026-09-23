// `GraphSimulation` (bd `skein-67ak`): the live, continuous force
// simulation `GraphView` drives from a `withFrameNanos` loop, instead of
// the one-shot `ForceLayout.compute` this bead replaces as the *only*
// source of node positions. Pure Kotlin (no Compose import), same
// discipline as `ForceLayout` itself — everything Compose-shaped (the
// frame loop, screen<->world conversion for the pointer, `mutableStateOf`
// so the canvas recomposes) lives in `GraphView`; this class only knows
// about [Vec2] world positions and [ForceLayout.step].
//
// ## Pinning policy
//
// At any moment exactly the following ids are pinned (excluded from force
// updates by [ForceLayout.step]'s `pinned` set):
//  - whichever node is currently mid-drag ([beginDrag]/[endDrag]), if any;
//  - the center node, but *only* until the first time it is itself dragged
//    (deliverable 3: "Obsidian lets you move it too" — [centerEverDragged]
//    latches permanently once [beginDrag] names the center id).
//
// ## Cooling / idle / re-heat
//
// The constructor starts hot ([DEFAULT_TEMPERATURE]) so opening the graph
// visibly runs a few real settle frames rather than silently reusing
// `GraphState`'s already-converged `compute` output unchanged — this is
// the "live simulation" the bead asks for, not just infrastructure that
// only ever activates on a drag. [step] cools the temperature
// multiplicatively every call; once either the temperature or a step's
// [ForceLayout.StepResult.totalDisplacement] drops under threshold, the
// simulation goes idle ([isActive] `false`) and [step] returns `false` so
// `GraphView`'s frame loop can stop entirely (deliverable 4: don't keep
// waking `withFrameNanos` once nothing is moving). [beginDrag]/[dragTo]/
// [endDrag] all call [reheat], which restarts the loop on interaction.
package app.skein.feature.graph

public class GraphSimulation(
    private val nodeIds: List<String>,
    private val edges: List<GraphEdge>,
    initialPositions: Map<String, Vec2>,
    private val centerId: String,
    private val worldExtent: Float = ForceLayout.DEFAULT_WORLD_EXTENT,
) {
    private var currentPositions: Map<String, Vec2> = initialPositions
    private var temperature: Float = DEFAULT_TEMPERATURE
    private var centerEverDragged: Boolean = false
    private var draggedId: String? = null

    /** The current world-space position of every node — [GraphView] projects this to screen space every frame. */
    public val positions: Map<String, Vec2> get() = currentPositions

    /** `true` while [step] still has meaningful work to do; `false` once settled (see file header). */
    public val isActive: Boolean get() = temperature > IDLE_TEMPERATURE

    /** The id currently pinned to the pointer by a drag, or `null` between drags. */
    public val draggedNodeId: String? get() = draggedId

    /**
     * Starts pinning [nodeId] to wherever [dragTo] next places it, and
     * un-pins the center permanently if [nodeId] *is* the center
     * (deliverable 3). No-op if [nodeId] has no known position.
     */
    public fun beginDrag(nodeId: String) {
        if (nodeId !in currentPositions) return
        draggedId = nodeId
        if (nodeId == centerId) centerEverDragged = true
        reheat()
    }

    /** Pins [nodeId] to [worldPosition] — a no-op if [nodeId] isn't the currently-dragged node (see [beginDrag]). */
    public fun dragTo(
        nodeId: String,
        worldPosition: Vec2,
    ) {
        if (draggedId != nodeId) return
        currentPositions = currentPositions + (nodeId to worldPosition)
        reheat()
    }

    /** Releases [nodeId] back to the simulation — a no-op if it wasn't the currently-dragged node. */
    public fun endDrag(nodeId: String) {
        if (draggedId != nodeId) return
        draggedId = null
        reheat()
    }

    /** Restarts cooling from [DEFAULT_TEMPERATURE] — called on every drag event; see file header. */
    public fun reheat() {
        temperature = DEFAULT_TEMPERATURE
    }

    /**
     * Advances the simulation by one frame. Returns [isActive] *after*
     * stepping, so `GraphView`'s frame loop can simply `while (simulation
     * .step()) { ... }` and stop as soon as it returns `false`.
     */
    public fun step(): Boolean {
        if (!isActive) return false

        val pinned =
            buildSet {
                draggedId?.let(::add)
                if (!centerEverDragged) add(centerId)
            }
        val result = ForceLayout.step(nodeIds, edges, currentPositions, pinned, temperature, worldExtent)
        currentPositions = result.positions
        temperature *= COOLING_FACTOR
        if (result.totalDisplacement < IDLE_DISPLACEMENT_THRESHOLD) {
            temperature = 0f
        }
        return isActive
    }

    public companion object {
        /** Matches `ForceLayout.compute`'s own starting temperature — see [ForceLayout.DEFAULT_TEMPERATURE]. */
        public const val DEFAULT_TEMPERATURE: Float = ForceLayout.DEFAULT_TEMPERATURE

        /** ~2.5s to cool from [DEFAULT_TEMPERATURE] to [IDLE_TEMPERATURE] at a 60 Hz frame cap. */
        private const val COOLING_FACTOR: Float = 0.97f
        private const val IDLE_TEMPERATURE: Float = 0.01f
        private const val IDLE_DISPLACEMENT_THRESHOLD: Float = 0.05f
    }
}
