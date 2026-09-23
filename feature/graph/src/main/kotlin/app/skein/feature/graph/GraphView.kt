// `GraphView` (bd `skein-z2u`/`skein-67ak`, plan `E6.I11`, spec §8.6): pure
// rendering over [GraphState] on a `Canvas` — no vault/index access happens
// in this file (same guardrail as `SkeinEditor`/`BacklinksDrawer`); every
// read goes through the state holder. Pan/zoom is `Canvas`-local UI state
// (not part of [GraphState], which is data-only) via `Modifier.transformable`;
// tap and long-press hit-testing reuse the pure [GraphHitTest]/[GraphTransform]
// math so the coordinate arithmetic itself has a JVM unit test independent
// of any actual gesture.
//
// bd `skein-67ak`: node positions are no longer `GraphState.positions`
// rendered verbatim — that one-shot layout now only *seeds* a per-composable
// [GraphSimulation], which a `withFrameNanos` loop below steps continuously
// (capped at 60 Hz, stopping when idle — see that class's file header) and
// which a per-node drag gesture pins directly. See [detectNodeDrag] for how
// a press starting on a node claims the gesture (moving that node, not
// panning) while a press on empty space is left completely unconsumed for
// `Modifier.transformable` — declared right after it in the modifier chain —
// to keep handling pan/zoom exactly as before.
package app.skein.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.VaultRepository

private val CENTER_RADIUS = 18.dp
private val NODE_RADIUS = 12.dp
private val SENTINEL_HALF_SIZE = 8.dp
private val RING_GAP = 3.dp
private val RING_WIDTH = 1.dp
private val CENTER_RING_WIDTH = 3.dp
private val HIT_RADIUS = 24.dp
private const val MAX_LABELED_NODES = 40
private const val MIN_ZOOM = 0.4f
private const val MAX_ZOOM = 4f

/** Edge stroke width scales linearly with `weight` (plan `E6.I11` AC: 1.0 → 3 dp, 0.4 → 1.2 dp). */
private const val STROKE_WIDTH_PER_WEIGHT_DP = 3f

/** bd `skein-67ak` deliverable 4: caps the live simulation's frame loop at 60 Hz regardless of display refresh rate. */
private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L

/**
 * The graph `Canvas`: edges (kind-styled), nodes (label, hop ring), the
 * center node highlighted, pan/zoom, tap → [openPreview], long-press →
 * [openPinned]. Labels are only drawn while `nodes.size <= 40` (plan text).
 *
 * @param openPreview a document node's tap payload — mirrors
 *   `TabsState.openPreview`'s Cursor-style single-click semantics.
 * @param openPinned a document node's long-press payload — mirrors
 *   `TabsState.openPinned`'s double-click/pin semantics. Sentinel
 *   (entity/tag/unresolved-title) nodes never invoke either callback —
 *   they have no document to open.
 */
@Composable
public fun GraphView(
    state: GraphState,
    modifier: Modifier = Modifier,
    openPreview: (DocId) -> Unit = {},
    openPinned: (DocId) -> Unit = {},
) {
    val nodes by state.nodes.collectAsState()
    val edges by state.edges.collectAsState()
    val initialPositions by state.positions.collectAsState()
    val loading by state.loading.collectAsState()
    val centerDocId = state.centerDocId

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
            pan += panChange
        }
    val textMeasurer = rememberTextMeasurer()
    val nodeById = remember(nodes) { nodes.associateBy { it.id } }

    // bd skein-67ak: one `GraphSimulation` per graph "generation". `nodes`/
    // `edges` are freshly built `List`s on every `GraphState.load()` call
    // (initial load, or a live `EdgesReplaced` reload) but compare
    // structurally equal when nothing actually changed, so keying `remember`
    // on them only resets the running simulation (re-seeding it from the
    // newly computed `initialPositions`) when the graph itself really
    // changed — never on every recomposition, and never once per animation
    // frame (that key is `positions`, deliberately excluded here).
    val simulation =
        remember(nodes, edges) {
            GraphSimulation(
                nodeIds = nodes.map { it.id },
                edges = edges,
                initialPositions = initialPositions,
                centerId = centerDocId,
            )
        }
    var positions by remember(simulation) { mutableStateOf(simulation.positions) }
    // Bumped once, right when a drag transitions the simulation from idle to
    // active (see [detectNodeDrag]) — restarts the frame loop below, which
    // has otherwise already exited (deliverable 4: the loop actually stops,
    // it doesn't just skip work, once idle).
    var restartPulse by remember(simulation) { mutableIntStateOf(0) }

    LaunchedEffect(simulation, restartPulse) {
        var lastFrameNanos = -1L
        var active = true
        while (active) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos < 0 || frameTimeNanos - lastFrameNanos >= FRAME_INTERVAL_NANOS) {
                    lastFrameNanos = frameTimeNanos
                    active = simulation.step()
                    positions = simulation.positions
                }
            }
        }
    }

    val colors = rememberGraphColors()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)

    Box(modifier = modifier.fillMaxSize()) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).testTag(GraphTestTags.LOADING),
            )
        } else {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag(GraphTestTags.CANVAS)
                        // Declared *before* `.transformable(...)`: a press that
                        // hits a node consumes the gesture here (moving the
                        // node, never panning); a press on empty space leaves
                        // every event unconsumed so `.transformable(...)`
                        // right after it handles pan/zoom exactly as before.
                        .pointerInput(nodes, simulation) {
                            val hitRadiusPx = HIT_RADIUS.toPx()
                            detectNodeDrag(
                                simulation = simulation,
                                zoom = { zoom },
                                pan = { pan },
                                hitRadiusPx = hitRadiusPx,
                                // Every pointer move during a drag: redraw
                                // immediately so the dragged node feels
                                // pinned to the finger (don't wait for the
                                // physics loop's own next frame).
                                onPositionsChanged = { positions = simulation.positions },
                                // Exactly once, right as a press turns into
                                // an actual drag: the physics loop may have
                                // already gone idle and stopped (deliverable
                                // 4), so this bumps the key that restarts it.
                                onDragStarted = { restartPulse++ },
                            )
                        }.transformable(transformState)
                        .pointerInput(nodes, simulation) {
                            val hitRadiusPx = HIT_RADIUS.toPx()
                            detectTapGestures(
                                onTap = { offset ->
                                    tappedDocument(simulation.positions, nodeById, size, zoom, pan, offset, hitRadiusPx)
                                        ?.let(openPreview)
                                },
                                onLongPress = { offset ->
                                    tappedDocument(simulation.positions, nodeById, size, zoom, pan, offset, hitRadiusPx)
                                        ?.let(openPinned)
                                },
                            )
                        },
            ) {
                val canvasCenter = Vec2(size.width / 2f, size.height / 2f)
                val baseScale = GraphTransform.baseScale(size.width, size.height, ForceLayout.DEFAULT_WORLD_EXTENT)
                val screenPositions =
                    positions.mapValues { (_, world) ->
                        GraphTransform.worldToScreen(world, canvasCenter, baseScale, zoom, Vec2(pan.x, pan.y))
                    }

                edges.forEach { edge -> drawGraphEdge(edge, screenPositions, colors) }
                nodes.forEach { node ->
                    val pos = screenPositions[node.id] ?: return@forEach
                    drawGraphNode(node, pos, node.id == centerDocId, colors)
                    if (nodes.size <= MAX_LABELED_NODES) {
                        drawNodeLabel(textMeasurer, node, pos, labelStyle)
                    }
                }
            }
        }
    }
}

/** Ties [GraphState] to the composition — same shape as `:feature:editor`'s `rememberBacklinksState`. */
@Composable
public fun rememberGraphState(
    docId: DocId,
    vaultRepository: VaultRepository,
    indexStore: IndexStore,
    seed: Long = GraphState.DEFAULT_SEED,
): GraphState {
    val scope = rememberCoroutineScope()
    return remember(docId, vaultRepository, indexStore) {
        GraphState(
            docId = docId,
            indexStore = indexStore,
            vaultRepository = vaultRepository,
            scope = scope,
            seed = seed,
        )
    }
}

private fun tappedDocument(
    positions: Map<String, Vec2>,
    nodeById: Map<String, GraphNode>,
    canvasSize: IntSize,
    zoom: Float,
    pan: Offset,
    tapOffset: Offset,
    hitRadiusPx: Float,
): DocId? {
    val canvasCenter = Vec2(canvasSize.width / 2f, canvasSize.height / 2f)
    val baseScale =
        GraphTransform.baseScale(
            canvasSize.width.toFloat(),
            canvasSize.height.toFloat(),
            ForceLayout.DEFAULT_WORLD_EXTENT,
        )
    val screenPositions =
        positions.mapValues { (_, world) ->
            GraphTransform.worldToScreen(world, canvasCenter, baseScale, zoom, Vec2(pan.x, pan.y))
        }
    val hitId = GraphHitTest.nodeAt(screenPositions, Vec2(tapOffset.x, tapOffset.y), hitRadiusPx) ?: return null
    val node = nodeById[hitId] ?: return null
    return if (node.kind == GraphNodeKind.DOCUMENT) node.id else null
}

/**
 * bd `skein-67ak` (reopened): per-node drag, coexisting with
 * `Modifier.transformable`'s own pan/zoom detection over the very same
 * pointer stream (see the file header for the modifier-order argument). The
 * trick is [androidx.compose.ui.input.pointer.PointerInputChange.consume]: a
 * press that lands on a node ([GraphHitTest]) is tracked here pointer-move
 * by pointer-move, and only once it exceeds touch slop do we `consume()`
 * each change — from that point on, `transformable`'s own gesture detector
 * (which only reacts to *unconsumed* changes) sees nothing to pan with. A
 * press that never hits a node returns immediately without consuming
 * anything, so `transformable` — and, for a plain tap/long-press that never
 * exceeds slop, `detectTapGestures` in the sibling `pointerInput` block —
 * see the exact same raw, unconsumed events they always have.
 *
 * ## Why this has to run on [PointerEventPass.Initial] (device-verified miss)
 *
 * Compose dispatches one round of pointer input in three passes:
 * [PointerEventPass.Initial] top-down (outer modifier to inner), then
 * [PointerEventPass.Main] bottom-up (inner to outer), then
 * [PointerEventPass.Final] top-down again. This `pointerInput` block is
 * declared *before* `.transformable(transformState)` in the modifier chain
 * (see the `Canvas`'s `modifier =` below), which makes `transformable` the
 * *inner* pointer input node. On the default `Main` pass — what the first
 * version of this function used — `transformable`'s own gesture detector
 * therefore sees every move *before* this one does, consumes it once it
 * exceeds its own slop, and this block would then read
 * [androidx.compose.ui.input.pointer.PointerInputChange.positionChange],
 * which is always `Offset.Zero` for an already-consumed change. Slop here
 * never accumulates, [GraphSimulation.beginDrag] never fires, and a drag
 * that starts on a node falls through to `transformable` and pans the whole
 * canvas as a rigid picture instead — this is the bug a real finger hit on
 * the Fold (bd note, 2026-09-22) despite the original `GraphViewDragTest`
 * passing (that test used a one-node fixture, where a rigid pan and a true
 * per-node drag move the only node by the identical amount and are
 * indistinguishable by a screen-position assertion — see the rewritten
 * test's kdoc).
 *
 * Running Initial-pass-first (top-down) instead makes this block — the
 * *outer* node — see every down/move *before* `transformable` gets a
 * chance to, the same look-first-consume-conditionally pattern
 * `SkeinEditor`'s wikilink-tap routing uses on `PointerEventPass.Initial`
 * (see that file's "Wikilink tap routing" kdoc section) to run ahead of
 * `BasicTextField`'s own cursor-placement gesture. Slop is accumulated from
 * [androidx.compose.ui.input.pointer.PointerInputChange.positionChangeIgnoreConsumed]
 * rather than `positionChange()` — nothing has consumed anything yet this
 * early, but `IgnoreConsumed` makes that independent of pass ordering, same
 * defensive habit as reading a raw value before deciding whether to act on
 * it. Once the hit and slop are both established, `change.consume()` is
 * called right here, in the Initial pass — that consumed flag is visible to
 * every later pass on the same [androidx.compose.ui.input.pointer.PointerInputChange]
 * instance, so when `transformable`'s Main-pass detector runs immediately
 * after, it finds nothing left to pan with. A press on empty space, or one
 * that never leaves slop, consumes nothing at any pass, so `transformable`
 * (pan/zoom) and `detectTapGestures` (tap/long-press) in the sibling
 * `pointerInput` block below see the exact same raw, unconsumed events they
 * always have.
 *
 * The dragged node is pinned to the pointer *in world space*
 * ([GraphTransform.screenToWorld]) rather than screen space, so it tracks
 * correctly under the current pan/zoom and stays put if either changes
 * mid-drag (they can't, in practice, since this gesture consumes the
 * pointer `transformable` would otherwise use — but computing in world
 * space is what "the node follows the pointer" means for [GraphSimulation],
 * which knows nothing about screen coordinates).
 */
private suspend fun PointerInputScope.detectNodeDrag(
    simulation: GraphSimulation,
    zoom: () -> Float,
    pan: () -> Offset,
    hitRadiusPx: Float,
    onPositionsChanged: () -> Unit,
    onDragStarted: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    val canvasCenter = Vec2(size.width / 2f, size.height / 2f)
    val baseScale =
        GraphTransform.baseScale(
            size.width.toFloat(),
            size.height.toFloat(),
            ForceLayout.DEFAULT_WORLD_EXTENT,
        )

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val screenPositions =
            simulation.positions.mapValues { (_, world) ->
                GraphTransform.worldToScreen(world, canvasCenter, baseScale, zoom(), Vec2(pan().x, pan().y))
            }
        val hitId = GraphHitTest.nodeAt(screenPositions, Vec2(down.position.x, down.position.y), hitRadiusPx)
        // No node under the press: don't consume anything — this is either
        // a pan/zoom (transformable) or a tap/long-press on empty space
        // (which today's `tappedDocument` already treats as a no-op).
        if (hitId == null) return@awaitEachGesture

        val pointerId = down.id
        var accumulatedSlop = Offset.Zero
        var dragging = false

        while (true) {
            val change =
                awaitPointerEvent(pass = PointerEventPass.Initial).changes.firstOrNull { it.id == pointerId }
            if (change == null || !change.pressed) {
                if (dragging) simulation.endDrag(hitId)
                break
            }
            if (!dragging) {
                accumulatedSlop += change.positionChangeIgnoreConsumed()
                if (accumulatedSlop.getDistance() <= touchSlop) continue
                dragging = true
                simulation.beginDrag(hitId)
                onDragStarted()
            }
            change.consume()
            val worldPosition =
                GraphTransform.screenToWorld(
                    Vec2(change.position.x, change.position.y),
                    canvasCenter,
                    baseScale,
                    zoom(),
                    Vec2(pan().x, pan().y),
                )
            simulation.dragTo(hitId, worldPosition)
            onPositionsChanged()
        }
    }
}

/** Precomputed [MaterialTheme] colors — resolved once in composition, then read from the (non-composable) draw scope. */
internal data class GraphColors(
    val note: Color,
    val chat: Color,
    val attachment: Color,
    val aiout: Color,
    val sentinel: Color,
    val centerRing: Color,
    val hopRingNear: Color,
    val hopRingFar: Color,
    val wikilink: Color,
    val entity: Color,
    val tag: Color,
    val cite: Color,
    val label: Color,
)

@Composable
internal fun rememberGraphColors(): GraphColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        GraphColors(
            note = scheme.primary,
            chat = scheme.secondary,
            attachment = scheme.tertiary,
            aiout = scheme.error,
            sentinel = scheme.outline,
            centerRing = scheme.primary,
            hopRingNear = scheme.onSurface,
            hopRingFar = scheme.onSurfaceVariant,
            wikilink = scheme.onSurface,
            entity = scheme.tertiary,
            tag = scheme.secondary,
            cite = scheme.outlineVariant,
            label = scheme.onSurface,
        )
    }
}

private fun GraphColors.documentFill(kind: DocumentKind?): Color =
    when (kind) {
        DocumentKind.NOTE -> note
        DocumentKind.CHAT -> chat
        DocumentKind.ATTACHMENT -> attachment
        DocumentKind.AIOUT -> aiout
        null -> sentinel
    }

private fun GraphColors.edgeColor(kind: EdgeKind): Color =
    when (kind) {
        EdgeKind.WIKILINK -> wikilink
        EdgeKind.ENTITY -> entity
        EdgeKind.TAG -> tag
        EdgeKind.CITE -> cite
    }

/** Hop-distance ring opacity: the center's own ring is drawn at full opacity by [drawGraphNode] directly. */
private fun hopRingAlpha(hopDistance: Int): Float =
    when (hopDistance) {
        0 -> 1f
        1 -> 0.7f
        else -> 0.4f
    }

private fun DrawScope.drawGraphEdge(
    edge: GraphEdge,
    screenPositions: Map<String, Vec2>,
    colors: GraphColors,
) {
    val src = screenPositions[edge.srcId] ?: return
    val dst = screenPositions[edge.dstId] ?: return
    drawLine(
        color = colors.edgeColor(edge.kind),
        start = Offset(src.x, src.y),
        end = Offset(dst.x, dst.y),
        strokeWidth = (edge.weight.toFloat() * STROKE_WIDTH_PER_WEIGHT_DP).dp.toPx(),
    )
}

private fun DrawScope.drawGraphNode(
    node: GraphNode,
    pos: Vec2,
    isCenter: Boolean,
    colors: GraphColors,
) {
    val center = Offset(pos.x, pos.y)
    val fill = if (node.kind == GraphNodeKind.DOCUMENT) colors.documentFill(node.documentKind) else colors.sentinel
    val ringColor = if (isCenter) colors.centerRing else colors.hopRingFor(node.hopDistance)
    val ringAlpha = if (isCenter) 1f else hopRingAlpha(node.hopDistance)
    val ringWidth = (if (isCenter) CENTER_RING_WIDTH else RING_WIDTH).toPx()

    if (node.kind == GraphNodeKind.DOCUMENT) {
        val radius = (if (isCenter) CENTER_RADIUS else NODE_RADIUS).toPx()
        drawCircle(color = fill, radius = radius, center = center)
        drawCircle(
            color = ringColor.copy(alpha = ringAlpha),
            radius = radius + RING_GAP.toPx(),
            center = center,
            style = Stroke(width = ringWidth),
        )
    } else {
        drawDiamond(center, SENTINEL_HALF_SIZE.toPx(), fill, ringColor.copy(alpha = ringAlpha), ringWidth)
    }
}

private fun GraphColors.hopRingFor(hopDistance: Int): Color = if (hopDistance <= 1) hopRingNear else hopRingFar

private fun DrawScope.drawDiamond(
    center: Offset,
    halfSize: Float,
    fill: Color,
    ringColor: Color,
    ringWidth: Float,
) {
    val path =
        Path().apply {
            moveTo(center.x, center.y - halfSize)
            lineTo(center.x + halfSize, center.y)
            lineTo(center.x, center.y + halfSize)
            lineTo(center.x - halfSize, center.y)
            close()
        }
    drawPath(path, color = fill)
    drawPath(path, color = ringColor, style = Stroke(width = ringWidth))
}

private fun DrawScope.drawNodeLabel(
    textMeasurer: TextMeasurer,
    node: GraphNode,
    pos: Vec2,
    style: TextStyle,
) {
    val layout = textMeasurer.measure(text = node.label, style = style)
    val labelOffset =
        Offset(
            x = pos.x - layout.size.width / 2f,
            y = pos.y + NODE_RADIUS.toPx() + RING_GAP.toPx() + 2f,
        )
    drawText(textLayoutResult = layout, topLeft = labelOffset)
}
