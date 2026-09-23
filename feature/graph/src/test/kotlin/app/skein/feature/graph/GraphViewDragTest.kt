package app.skein.feature.graph

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.VaultRepository
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * bd `skein-67ak`: Robolectric Compose UI test proving [GraphView]'s per-node
 * drag and its pan fallback don't step on each other — the acceptance
 * criterion the bead names explicitly: "a drag starting on a node moves that
 * node and not the pan offset while a drag on empty space pans".
 *
 * Both tests use a single-node graph (one document, no edges) rather than
 * `GraphViewInstrumentedTest`'s two-document fixture: with only one node,
 * [ForceLayout.step] is a no-op regardless of pinning (`nodeIds.size <= 1`),
 * so there is zero residual settle-physics drift to confound the pixel
 * assertions below — any observed movement is attributable only to the
 * gesture under test, never to a connected neighbor being pulled along.
 * The center node is always laid out at the world origin with the canvas
 * starting untranslated (`zoom = 1`, `pan = 0`), so — same reasoning
 * `GraphViewInstrumentedTest`'s own header documents — a gesture at the
 * `Canvas` node's default target (its own center) deterministically starts
 * on that node.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphViewDragTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a drag starting on the node moves that node and not the pan offset`() {
        val (repo, index, centerDocId) = soloGraph()
        var opened: DocId? = null

        showGraph(centerDocId, repo, index, onOpenPreview = { opened = it })
        composeRule.waitForIdle()

        // `center` here is `TouchInjectionScope.center` — the `Canvas`
        // node's own bounds center, which is also this solo graph's only
        // node's screen position (see the class kdoc).
        val canvas = composeRule.onNodeWithTag(GraphTestTags.CANVAS)
        canvas.performTouchInput {
            down(center)
            moveTo(center + DRAG_DELTA)
            up()
        }
        composeRule.waitForIdle()

        // The node followed the pointer to its new screen position.
        canvas.performTouchInput { click(center + DRAG_DELTA) }
        composeRule.waitForIdle()
        assertEquals("expected the dragged node at its new position", centerDocId, opened)

        // Not a pan: the pointer's own delta was applied once to the node,
        // never additionally to the whole canvas. If a pan of the same
        // delta had *also* fired (double-consuming the gesture), the node
        // would really sit at `center + 2 * DRAG_DELTA`, not `center +
        // DRAG_DELTA` — so this location must miss.
        opened = null
        canvas.performTouchInput { click(center + DRAG_DELTA + DRAG_DELTA) }
        composeRule.waitForIdle()
        assertNull("expected no node at double the drag delta (would mean the canvas also panned)", opened)

        // And nothing is left behind at the original spot either — the node
        // actually relocated rather than a pan smearing a duplicate there.
        opened = null
        canvas.performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertNull("expected nothing left at the node's original position", opened)
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

    private fun soloGraph(): Triple<VaultRepository, IndexStore, DocId> {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val center =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Solo", bodyMd = "Solo")).id
            }
        return Triple(repo, index, center)
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
        val EMPTY_SPACE_OFFSET = Offset(120f, 120f)
    }
}
