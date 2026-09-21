package app.skein.feature.graph

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
 * On-device Compose UI test for [GraphScreen]/[GraphView]. Runs on an
 * emulator/device — no Robolectric — matching `:feature:timeline`'s
 * `TimelineScreenInstrumentedTest` and `:feature:editor`'s
 * `SkeinEditorInstrumentedTest`. bd `skein-k3b2` tracks the CI emulator
 * lane that will run this; until then it is compile-only.
 *
 * The center document is always laid out at the world origin
 * (`ForceLayout`'s pinned anchor) and the canvas starts untranslated
 * (`zoom = 1`, `pan = 0`), so a plain [performClick]/`longClick` at the
 * `Canvas` node's own center — its default gesture target — deterministically
 * lands on the center node without needing to compute any other node's
 * on-screen position.
 */
@RunWith(AndroidJUnit4::class)
class GraphViewInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun seeded_graph_renders_the_canvas_and_legend() {
        val (repo, index, center) = seededGraph()

        showGraph(center, repo, index)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(GraphTestTags.CANVAS).assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTestTags.LEGEND).assertIsDisplayed()
    }

    @Test
    fun tapping_the_center_node_opens_it_as_a_preview() {
        val (repo, index, center) = seededGraph()
        var opened: DocId? = null

        showGraph(center, repo, index, onOpenPreview = { opened = it })
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(GraphTestTags.CANVAS).performClick()
        composeRule.waitForIdle()

        assertEquals(center, opened)
    }

    @Test
    fun long_pressing_the_center_node_opens_it_pinned() {
        val (repo, index, center) = seededGraph()
        var pinned: DocId? = null

        showGraph(center, repo, index, onOpenPinned = { pinned = it })
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(GraphTestTags.CANVAS).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(center, pinned)
    }

    @Test
    fun the_close_button_invokes_onClose() {
        val (repo, index, center) = seededGraph()
        var closed = false

        showGraph(center, repo, index, onClose = { closed = true })
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(GraphTestTags.CLOSE).performClick()
        composeRule.waitForIdle()

        assertEquals(true, closed)
    }

    private fun seededGraph(): Triple<VaultRepository, IndexStore, DocId> {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val a =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Alpha", bodyMd = "Alpha")).id
            }
        val b =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Bravo", bodyMd = "Bravo")).id
            }
        runBlocking {
            index.replaceEdges(
                a,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(srcId = a, dstId = b, kind = EdgeKind.WIKILINK, createdAt = 0L)),
            )
        }
        return Triple(repo, index, a)
    }

    private fun showGraph(
        docId: DocId,
        repo: VaultRepository,
        index: IndexStore,
        onOpenPreview: (DocId) -> Unit = {},
        onOpenPinned: (DocId) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        composeRule.setContent {
            GraphScreen(
                docId = docId,
                vaultRepository = repo,
                indexStore = index,
                onOpenPreview = onOpenPreview,
                onOpenPinned = onOpenPinned,
                onClose = onClose,
            )
        }
    }
}
