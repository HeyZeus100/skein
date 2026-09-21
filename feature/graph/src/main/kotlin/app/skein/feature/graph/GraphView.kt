// `GraphView` (bd `skein-z2u`, plan `E6.I11`, spec §8.6): pure rendering
// over [GraphState] on a `Canvas` — no vault/index access happens in this
// file (same guardrail as `SkeinEditor`/`BacklinksDrawer`); every read goes
// through the state holder. Pan/zoom is `Canvas`-local UI state (not part
// of [GraphState], which is data-only) via `Modifier.transformable`; tap and
// long-press hit-testing reuse the pure [GraphHitTest]/[GraphTransform] math
// so the coordinate arithmetic itself has a JVM unit test independent of
// any actual gesture.
package app.skein.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
    val positions by state.positions.collectAsState()
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
                        .transformable(transformState)
                        .pointerInput(nodes, positions) {
                            val hitRadiusPx = HIT_RADIUS.toPx()
                            detectTapGestures(
                                onTap = { offset ->
                                    tappedDocument(positions, nodeById, size, zoom, pan, offset, hitRadiusPx)
                                        ?.let(openPreview)
                                },
                                onLongPress = { offset ->
                                    tappedDocument(positions, nodeById, size, zoom, pan, offset, hitRadiusPx)
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
