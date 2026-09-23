// `GraphSimulation` (bd `skein-67ak`, damping/jitter fix bd `skein-8g4c`):
// the live, continuous force simulation `GraphView` drives from a
// `withFrameNanos` loop, instead of the one-shot `ForceLayout.compute` this
// bead replaces as the *only* source of node positions. Pure Kotlin (no
// Compose import), same discipline as `ForceLayout` itself — everything
// Compose-shaped (the frame loop, screen<->world conversion for the
// pointer, `mutableStateOf` so the canvas recomposes) lives in `GraphView`;
// this class only knows about [Vec2] world positions and
// [ForceLayout.computeForces].
//
// ## Pinning policy
//
// At any moment exactly the following ids are pinned (kept at their exact
// current position, velocity zeroed, excluded from [step]'s integration):
//  - whichever node is currently mid-drag ([beginDrag]/[endDrag]), if any —
//    [dragTo] sets its position directly to the pointer's world position
//    every event, so it *leads* the simulation instantly rather than being
//    integrated like every other node;
//  - the center node, but *only* until the first time it is itself dragged
//    (deliverable 3: "Obsidian lets you move it too" — [centerEverDragged]
//    latches permanently once [beginDrag] names the center id).
//
// ## bd `skein-8g4c`: damped velocity integration, not a temperature cap
//
// The Fold's owner report: dragging a node made its neighbours "vibrate and
// flicker" instead of following smoothly. Root cause was [ForceLayout.step]'s
// per-iteration displacement being capped *only* by a `temperature` that
// [dragTo] reset to its maximum (`DEFAULT_TEMPERATURE`) on *every single*
// pointer-move event during a drag — dozens of times a second, each one
// re-injecting the same maximum-displacement allowance into the next
// physics frame. A neighbour close to its equilibrium distance would
// overshoot past it by (up to) the *entire* temperature every frame, then
// get shoved back the other way just as hard the next — a discrete,
// unbounded ping-pong with no notion of momentum to smooth it out.
//
// This class now integrates every non-pinned node with damped semi-implicit
// Euler instead ([integrate]): `velocity = (velocity + clampedForce) *
// VELOCITY_DAMPING`, `position += velocity`. [FRAME_UNIT] is deliberately
// `1f`, not a small wall-clock fraction of a second: [ForceLayout]'s force
// formulas (`k²/d` repulsion, `d²/k × weight` attraction) were designed
// from the start to be used directly as a per-iteration displacement — that
// is exactly what the old temperature cap did, one call to [step] being one
// relaxation iteration, not a real-time physics tick. Reusing that same
// per-iteration unit here (rather than inventing a real `1/60s` timestep
// that would shrink every force to a crawl relative to [ForceLayout]'s
// existing k~hundreds-of-units scale) keeps the live loop's motion the same
// order of magnitude as the settle animation always had, while damping now
// carries genuine momentum between frames instead of a hard reset. The raw
// force is still clamped to [MAX_FORCE_MAGNITUDE] and the resulting
// velocity to [MAX_VELOCITY_MAGNITUDE] so a sudden close approach (the
// dragged node landing right on top of a neighbour) can't spike either past
// a sane ceiling — but nothing caps a frame's *position* by a global energy
// budget any more, and velocity is never hard-reset. A neighbour still
// closing in on its dragged leader keeps closing in smoothly instead of
// snapping past it and back — no per-frame energy re-injection, no
// overshoot-and-bounce.
//
// `reheat()`, the old "hard-reset to max" method, is gone: [beginDrag]/
// [dragTo]/[endDrag] instead call [holdDragEnergy], which only ensures
// [isActive] stays `true` (the frame loop keeps running) for as long as the
// drag is held — it does not touch any node's velocity or force budget, so
// the neighbours' own momentum is exactly what makes them "lead" the drag
// with visible, physical lag rather than snapping to a new position
// discretely every event. [endDrag] leaves [energy] exactly where it was
// (still [DRAG_ENERGY]) and lets ordinary per-frame [COOLING_FACTOR] decay
// carry it down to [IDLE_ENERGY] from there — combined with
// [VELOCITY_DAMPING] pulling every node's speed toward zero on the same
// clock, the whole graph visibly settles within roughly a second of frames
// at the 60 Hz cap (see `GraphSimulationTest`'s scripted-release test),
// matching Obsidian's own "settle on release" feel.
package app.skein.feature.graph

public class GraphSimulation(
    private val nodeIds: List<String>,
    private val edges: List<GraphEdge>,
    initialPositions: Map<String, Vec2>,
    private val centerId: String,
    private val worldExtent: Float = ForceLayout.DEFAULT_WORLD_EXTENT,
) {
    private var currentPositions: Map<String, Vec2> = initialPositions
    private var velocities: Map<String, Vec2> = nodeIds.associateWith { Vec2(0f, 0f) }

    /**
     * Drives [isActive] only — *not* a per-step displacement cap any more
     * (see file header). Starts at [DRAG_ENERGY] so opening the graph runs a
     * few real settle frames, same spirit as the old "starts hot" behavior;
     * decays by [COOLING_FACTOR] each [step] (or snaps to [IDLE_ENERGY]
     * early once every node's speed is already negligible) until it drops
     * under [IDLE_ENERGY] and the frame loop can stop.
     */
    private var energy: Float = DRAG_ENERGY
    private var centerEverDragged: Boolean = false
    private var draggedId: String? = null

    /** The current world-space position of every node — [GraphView] projects this to screen space every frame. */
    public val positions: Map<String, Vec2> get() = currentPositions

    /** `true` while [step] still has meaningful work to do; `false` once settled (see file header). */
    public val isActive: Boolean get() = energy > IDLE_ENERGY || draggedId != null

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
        velocities = velocities + (nodeId to Vec2(0f, 0f))
        holdDragEnergy()
    }

    /** Pins [nodeId] to [worldPosition] — a no-op if [nodeId] isn't the currently-dragged node (see [beginDrag]). */
    public fun dragTo(
        nodeId: String,
        worldPosition: Vec2,
    ) {
        if (draggedId != nodeId) return
        currentPositions = currentPositions + (nodeId to worldPosition)
        holdDragEnergy()
    }

    /** Releases [nodeId] back to the simulation — a no-op if it wasn't the currently-dragged node. */
    public fun endDrag(nodeId: String) {
        if (draggedId != nodeId) return
        draggedId = null
    }

    /**
     * Keeps the frame loop running at a moderate, constant level for as
     * long as a drag is held — bd `skein-8g4c`: unlike the old `reheat()`
     * this replaced, this never resets any node's velocity or force budget,
     * only [energy] (which now gates [isActive] alone). Neighbours keep
     * whatever momentum their own [integrate] step already gave them, which
     * is what makes them visibly *lag* behind the leader instead of
     * snapping to a new position every event.
     */
    private fun holdDragEnergy() {
        energy = DRAG_ENERGY
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
        val forces = ForceLayout.computeForces(nodeIds, edges, currentPositions, worldExtent)
        val integrated = integrate(pinned, forces)
        currentPositions = integrated.positions
        velocities = integrated.velocities

        if (draggedId == null) {
            energy *= COOLING_FACTOR
            if (integrated.totalSpeed < IDLE_SPEED_THRESHOLD) energy = 0f
        }
        return isActive
    }

    /**
     * One damped semi-implicit-Euler step for every non-pinned node (see
     * file header for [FRAME_UNIT] and why this replaced [ForceLayout.step]'s
     * temperature cap): `velocity = (velocity + clampedForce) *
     * VELOCITY_DAMPING`, `position += velocity * FRAME_UNIT`. Pinned nodes
     * (the drag leader, and the center until its first drag) keep their
     * exact current position with velocity zeroed, so they never carry
     * leftover momentum into the moment they're released.
     */
    private fun integrate(
        pinned: Set<String>,
        forces: Map<String, Vec2>,
    ): IntegrationResult {
        val nextPositions = LinkedHashMap<String, Vec2>(nodeIds.size)
        val nextVelocities = LinkedHashMap<String, Vec2>(nodeIds.size)
        var totalSpeed = 0f

        for (id in nodeIds) {
            val current = currentPositions[id] ?: continue
            if (id in pinned) {
                nextPositions[id] = current
                nextVelocities[id] = Vec2(0f, 0f)
                continue
            }
            val force = clampMagnitude(forces[id] ?: Vec2(0f, 0f), MAX_FORCE_MAGNITUDE)
            val previousVelocity = velocities[id] ?: Vec2(0f, 0f)
            val velocity = clampMagnitude((previousVelocity + force) * VELOCITY_DAMPING, MAX_VELOCITY_MAGNITUDE)
            nextVelocities[id] = velocity
            nextPositions[id] = current + velocity * FRAME_UNIT
            totalSpeed += velocity.length()
        }

        return IntegrationResult(nextPositions, nextVelocities, totalSpeed)
    }

    private data class IntegrationResult(
        val positions: Map<String, Vec2>,
        val velocities: Map<String, Vec2>,
        val totalSpeed: Float,
    )

    private fun clampMagnitude(
        v: Vec2,
        maxMagnitude: Float,
    ): Vec2 {
        val length = v.length()
        if (length <= maxMagnitude || length <= 0f) return v
        return v * (maxMagnitude / length)
    }

    public companion object {
        /** Matches `ForceLayout.compute`'s own starting temperature — see [ForceLayout.DEFAULT_TEMPERATURE]. */
        public const val DEFAULT_TEMPERATURE: Float = ForceLayout.DEFAULT_TEMPERATURE

        /**
         * One relaxation iteration per rendered frame — see the file header's
         * "damped velocity integration" section for why this is `1f` rather
         * than a `1/60s` wall-clock fraction.
         */
        private const val FRAME_UNIT: Float = 1f

        /**
         * Per-frame velocity retention (bd `skein-8g4c`: "damping ~0.85-0.92
         * per frame" — the smaller the value, the more a node's motion is
         * dominated by the *current* frame's force rather than its own
         * momentum, damping oscillation fastest; the larger, the more fluid
         * and "heavier" the follow-through feels). Also what makes energy
         * decay to idle within about a second of frames at the 60 Hz cap —
         * see `GraphSimulationTest`'s scripted-release test.
         */
        private const val VELOCITY_DAMPING: Float = 0.88f

        /** Caps a single frame's raw Fruchterman-Reingold force so a sudden close approach can't spike velocity. */
        private const val MAX_FORCE_MAGNITUDE: Float = ForceLayout.DEFAULT_TEMPERATURE * 1.5f

        /** Caps a node's speed (world units moved per frame at [FRAME_UNIT]), independent of how large a clamped force pushes it. */
        private const val MAX_VELOCITY_MAGNITUDE: Float = ForceLayout.DEFAULT_TEMPERATURE

        /** [energy] level held for as long as a drag is in progress — moderate, never the old "reheat to max". */
        private const val DRAG_ENERGY: Float = 1f

        /** Below this, [isActive] is `false` and `GraphView`'s frame loop stops. */
        private const val IDLE_ENERGY: Float = 0.01f

        /** Per-frame [energy] decay once a drag ends — reaches [IDLE_ENERGY] within roughly a second of frames at the 60 Hz cap. */
        private const val COOLING_FACTOR: Float = 0.90f

        /** Below this summed node speed, the layout is already visually still — snap [energy] straight to idle rather than waiting out [COOLING_FACTOR]'s tail. */
        private const val IDLE_SPEED_THRESHOLD: Float = 0.5f
    }
}
