package app.skein.feature.graph

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.VaultRepository
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * bd `skein-67ak`: Robolectric Compose UI test proving [GraphView]'s per-node
 * drag and its pan fallback don't step on each other.
 *
 * ## Why this file was rewritten (device miss, 2026-09-22)
 *
 * The original version of this test used a *single*-node fixture and a
 * single big `moveTo` jump, and it passed even though a real finger on the
 * Fold could never engage per-node drag at all (`detectNodeDrag` was reading
 * `positionChange()` on the default `Main` pass — always `Offset.Zero`,
 * since `Modifier.transformable`, later in the chain, consumed the change
 * first — see [detectNodeDrag]'s kdoc for the full pass-ordering argument).
 * That single-node fixture could not have caught this: with exactly one
 * node in the graph, `Modifier.transformable` panning the *whole* canvas by
 * the drag delta and [GraphSimulation] moving *that one node* by the same
 * delta land the node at the exact same screen position — indistinguishable
 * by any screen-position assertion. The bug was invisible to this test by
 * construction, regardless of how carefully the assertions were written.
 *
 * This version uses a *two*-node fixture (`Alpha` wikilinked to `Bravo`,
 * matching `GraphViewInstrumentedTest`'s own fixture shape) specifically so
 * a rigid pan and a true per-node drag are distinguishable: a pan carries
 * every node by the identical vector; a per-node drag pins only the
 * dragged node to the pointer and lets [GraphSimulation]'s spring physics
 * pull the neighbor part-way, never by the identical rigid delta. It also
 * drives the drag with several small [moveBy] steps rather than one big
 * jump, so touch-slop accumulation and pointer consumption interleave the
 * way they do on a real finger (see [detectNodeDrag]'s kdoc again — that
 * interleaving is exactly what the Main-pass version of that function got
 * wrong). `.transformable` stays in the modifier chain throughout, exactly
 * as `GraphView` builds it in production.
 *
 * Both nodes' on-screen positions (needed to assert "not a pan" and "the
 * neighbor actually moved") are computed independently via
 * [screenOffsetForWorld]/[baseScaleFor], which reuse the exact same
 * production, pure-Kotlin math ([ForceLayout.compute],
 * [GraphTransform.worldToScreen]) rather than guessing or duplicating it —
 * the same "coordinate arithmetic is independently JVM-testable" discipline
 * [GraphTransform]'s own file header describes — and those computed
 * positions are themselves asserted against (not just trusted) before the
 * drag runs, so a mismatch there fails loudly instead of silently weakening
 * the assertions that follow. Deliberately *not* used: `TouchInjectionScope
 * .center` — on this Robolectric/AGP combination it was empirically observed
 * to drift to a stale, smaller value for any gesture issued after several
 * pointer-move events had already landed in the test (exactly what several
 * small `moveBy` steps are), while `fetchSemanticsNode().size` — what
 * [screenOffsetForWorld] uses instead — stayed correct throughout. Every
 * click target below, including the drag's own down position, is built from
 * a fresh [screenOffsetForWorld] call for this reason, not from `center`.
 *
 * The main test drives `composeRule.mainClock` by hand
 * (`autoAdvance = false`), advancing zero frames until the drag steps
 * explicitly request them, and none after `up()`. Two things depend on
 * this: the initial positions above are only correct if *zero* frames have
 * run yet (otherwise they would also have to replay [GraphSimulation]'s own
 * stepping to stay bit-identical); and — since [GraphSimulation] permanently
 * un-pins the center the first time it is ever dragged (Obsidian lets you
 * move it too) — letting the frame loop free-run to a full settle after
 * `up()` (the default `waitForIdle()` behavior) lets *both* nodes keep
 * drifting for as long as the simulation stays hot, making their exact
 * post-drag pixel position unpredictable and turning every assertion below
 * flaky. Counting frames by hand keeps every position exactly attributable
 * to a specific, known gesture step.
 *
 * The center node (`Alpha`) is always laid out at the world origin, and the
 * canvas starts untranslated (`zoom = 1`, `pan = 0`), so its initial screen
 * offset is deterministic without needing any gesture to have run first —
 * same reasoning `GraphViewInstrumentedTest`'s header documents for its own
 * `center`-based targeting (which this file avoids for the reason above).
 *
 * The first test below also widens the Robolectric test window past its
 * 320x414 px default (`@Config(qualifiers = ...)`, same technique
 * `MainActivityComposeTest` uses): [ForceLayout]'s repulsion/attraction
 * balance for a *two*-node graph settles at a separation of
 * `worldExtent/sqrt(2) ≈ 424` world units (see `ForceLayoutTest`'s
 * "connected pair" test for the same `k` formula), which land partly off
 * the default window's edge; a wider window keeps both nodes on-screen so
 * the click-based hit-test assertions are meaningful.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphViewDragTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // See the class kdoc for why this window is widened and why the frame
    // clock below is driven by hand.
    @Config(sdk = [34], qualifiers = "w2400dp-h1200dp")
    @Test
    fun `a drag starting on the center node moves that node and springs its neighbor, not the pan offset`() {
        val (repo, index, alpha, bravo) = linkedPairGraph()
        var opened: DocId? = null

        composeRule.mainClock.autoAdvance = false

        showGraph(alpha, repo, index, onOpenPreview = { opened = it })
        composeRule.waitForIdle()

        val canvas = composeRule.onNodeWithTag(GraphTestTags.CANVAS)
        val nodeIds = listOf(alpha, bravo)
        val edges =
            listOf(GraphEdge(srcId = alpha, dstId = bravo, kind = EdgeKind.WIKILINK, weight = EdgeKind.WIKILINK.weight))
        // No frame has run yet (the clock is still parked before its first
        // `withFrameNanos`), so the rendered positions are still exactly
        // `ForceLayout.compute`'s raw output — no need to also replay
        // `GraphSimulation.step` here.
        val initialPositions =
            ForceLayout.compute(nodeIds = nodeIds, edges = edges, centerId = alpha, seed = GraphState.DEFAULT_SEED)
        val alphaOffset0 = screenOffsetForWorld(initialPositions.getValue(alpha), canvas)
        val bravoOffset0 = screenOffsetForWorld(initialPositions.getValue(bravo), canvas)

        // Sanity check on the independently-computed positions themselves:
        // if these don't land on the right node, the assertions below would
        // be meaningless rather than trustworthy. This also replaces
        // `TouchInjectionScope.center` for locating Alpha — see the class
        // kdoc's note on why every click target in this test is computed
        // explicitly from `fetchSemanticsNode().size` rather than `center`.
        canvas.performTouchInput { click(bravoOffset0) }
        composeRule.waitForIdle()
        assertEquals("expected Bravo at its independently-computed initial position", bravo, opened)
        opened = null
        canvas.performTouchInput { click(alphaOffset0) }
        composeRule.waitForIdle()
        assertEquals("expected Alpha (the center) at the world origin", alpha, opened)

        opened = null
        canvas.performTouchInput {
            down(alphaOffset0)
            // Several small steps, not one big jump: the reopened bug was
            // specifically about the *first* move past touch slop getting
            // consumed by `.transformable` before `detectNodeDrag` ever saw
            // it — a single big jump doesn't exercise that interleaving the
            // way a real finger's stream of small moves does.
            repeat(DRAG_STEPS) { moveBy(STEP_DELTA) }
            up()
        }
        composeRule.waitForIdle()

        // The dragged node followed the pointer to its new screen position —
        // and, because no frame has run since `up()`, it is still exactly
        // there (nothing has had a chance to pull it away yet). Alpha's new
        // *world* position is computed the same way `screenToWorld` inside
        // `detectNodeDrag` would have (the drag delta divided by the same
        // `baseScale` [GraphTransform] derives from the canvas's own size),
        // then projected back to screen space fresh — never reusing a
        // stale, pre-drag screen offset.
        val baseScale = baseScaleFor(canvas)
        val alphaWorldAfterDrag = Vec2(DRAG_DELTA.x / baseScale, DRAG_DELTA.y / baseScale)
        val alphaOffsetAfterDrag = screenOffsetForWorld(alphaWorldAfterDrag, canvas)
        canvas.performTouchInput { click(alphaOffsetAfterDrag) }
        composeRule.waitForIdle()
        assertEquals("expected the dragged (center) node at its new position", alpha, opened)

        // Not a pan: a rigid pan would carry *every* node by the identical
        // vector, landing Bravo at exactly (original + drag delta) too. A
        // true per-node drag only pins Alpha to the pointer; Bravo is only
        // pulled part-way by the spring, never by the same rigid delta.
        opened = null
        canvas.performTouchInput { click(bravoOffset0 + DRAG_DELTA) }
        composeRule.waitForIdle()
        assertNotEquals(
            "expected Bravo NOT at (original position + drag delta) - that would mean the whole " +
                "canvas panned rigidly instead of Alpha being dragged individually",
            bravo,
            opened,
        )

        // Springs: the neighbor visibly followed, with lag, rather than
        // sitting frozen at its pre-drag position — proof the simulation
        // kept stepping (and reheating) for the whole duration of the drag,
        // not just reacting once at drag-start.
        opened = null
        canvas.performTouchInput { click(bravoOffset0) }
        composeRule.waitForIdle()
        assertNotEquals(
            "expected Bravo to have moved off its original position - the simulation must keep " +
                "stepping (spring lag) for as long as the drag is held",
            bravo,
            opened,
        )
    }

    @Test
    fun `a drag starting on empty space pans instead of moving the node`() {
        val (repo, index, centerDocId) = soloGraph()
        var opened: DocId? = null

        showGraph(centerDocId, repo, index, onOpenPreview = { opened = it })
        composeRule.waitForIdle()

        val canvas = composeRule.onNodeWithTag(GraphTestTags.CANVAS)
        canvas.performTouchInput {
            val emptySpaceStart = center + EMPTY_SPACE_OFFSET
            down(emptySpaceStart)
            moveTo(emptySpaceStart + DRAG_DELTA)
            up()
        }
        composeRule.waitForIdle()

        // A pan shifts the *whole* canvas, including the only node in it —
        // so the node is now found at its original position plus the drag
        // delta, exactly like `Modifier.transformable` always behaved here.
        canvas.performTouchInput { click(center + DRAG_DELTA) }
        composeRule.waitForIdle()
        assertEquals("expected the pan to have carried the node along by the drag delta", centerDocId, opened)

        // The node itself never moved independently — nothing is left at
        // its pre-pan position.
        opened = null
        canvas.performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertNull("expected nothing at the node's pre-pan position once the canvas panned away", opened)
    }

    /**
     * `ForceLayout`'s `worldExtent`-to-pixel scale for [canvas]'s *current*
     * measured size ([GraphTransform.baseScale] — see that file's kdoc).
     * Always re-derived from `fetchSemanticsNode().size` rather than cached,
     * on purpose: unlike `TouchInjectionScope.center` (empirically observed,
     * on this Robolectric/AGP combination, to drift to a stale, smaller
     * value after several pointer-move events land within one test —
     * exactly what several small `moveBy` steps are), a fresh
     * `fetchSemanticsNode().size` read stays consistent with
     * [GraphView]'s own actual measured layout for the whole test. This is
     * why every click target below is built from this and
     * [screenOffsetForWorld] instead of `center`.
     */
    private fun baseScaleFor(canvas: SemanticsNodeInteraction): Float {
        val canvasSize = canvas.fetchSemanticsNode().size
        return GraphTransform.baseScale(
            canvasSize.width.toFloat(),
            canvasSize.height.toFloat(),
            ForceLayout.DEFAULT_WORLD_EXTENT,
        )
    }

    /**
     * Projects a [ForceLayout] world position to a screen [Offset] via the
     * exact same production math [GraphView] itself uses
     * ([GraphTransform.worldToScreen], `zoom = 1`, `pan = 0` — this graph is
     * never panned or zoomed in this test) — this never reimplements or
     * approximates the physics/geometry, only replays it.
     */
    private fun screenOffsetForWorld(
        world: Vec2,
        canvas: SemanticsNodeInteraction,
    ): Offset {
        val canvasSize = canvas.fetchSemanticsNode().size
        val canvasCenter = Vec2(canvasSize.width / 2f, canvasSize.height / 2f)
        val screen = GraphTransform.worldToScreen(world, canvasCenter, baseScaleFor(canvas), 1f, Vec2(0f, 0f))
        return Offset(screen.x, screen.y)
    }

    private fun soloGraph(): Triple<VaultRepository, IndexStore, DocId> {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val center =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Solo", bodyMd = "Solo")).id
            }
        return Triple(repo, index, center)
    }

    private data class LinkedPairGraph(
        val repo: VaultRepository,
        val index: IndexStore,
        val alpha: DocId,
        val bravo: DocId,
    )

    private fun linkedPairGraph(): LinkedPairGraph {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val alpha =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Alpha", bodyMd = "Alpha")).id
            }
        val bravo =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Bravo", bodyMd = "Bravo")).id
            }
        runBlocking {
            index.replaceEdges(
                alpha,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(srcId = alpha, dstId = bravo, kind = EdgeKind.WIKILINK, createdAt = 0L)),
            )
        }
        return LinkedPairGraph(repo, index, alpha, bravo)
    }

    private fun showGraph(
        docId: DocId,
        repo: VaultRepository,
        index: IndexStore,
        onOpenPreview: (DocId) -> Unit = {},
    ) {
        composeRule.setContent {
            val state = rememberGraphState(docId = docId, vaultRepository = repo, indexStore = index)
            GraphView(state = state, openPreview = onOpenPreview)
        }
    }

    private companion object {
        val DRAG_DELTA = Offset(90f, 0f)
        const val DRAG_STEPS = 6
        val STEP_DELTA = Offset(DRAG_DELTA.x / DRAG_STEPS, DRAG_DELTA.y / DRAG_STEPS)
        val EMPTY_SPACE_OFFSET = Offset(120f, 120f)
    }
}
