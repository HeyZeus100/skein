// skein-xtov.24.9 (AL-09b): Graph's default centering (most recent note,
// empty state) and select-then-open (GraphNodeKey selection, then "Open"
// following rule 1 into Knowledge's `NoteKey`).
package app.skein.feature.graph.entries

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.navigation.Destination
import app.skein.core.navigation.GraphNodeKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphEntriesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: SkeinShellState

    @Test
    fun `an empty vault shows the empty state, and a new note becomes the default centre`() {
        val vault = InMemoryVaultRepository()
        setHost(vault)
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.GRAPH) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GraphEntryTestTags.EMPTY_STATE).assertIsDisplayed()

        runBlocking { vault.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Target", bodyMd = "")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GraphEntryTestTags.CANVAS).assertIsDisplayed()
    }

    @Test
    fun `select-then-open — tapping a node selects it, then Open routes to Knowledge's NoteKey`() {
        val vault = InMemoryVaultRepository()
        val note =
            runBlocking { vault.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Target", bodyMd = "")) }
        setHost(vault)
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.GRAPH) } }
        composeRule.waitForIdle()

        // A single-node graph centres the node at the canvas centre; a tap there selects it.
        composeRule.onNodeWithTag(GraphEntryTestTags.CANVAS).performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals("selecting a node stays on Graph", Destination.GRAPH, shell.nav.topLevel)
        val selected = shell.nav.stack(Destination.GRAPH).last()
        assertTrue("the selection is a GraphNodeKey", selected is GraphNodeKey)
        assertEquals(SkeinId.of(note.id), (selected as GraphNodeKey).nodeDocId)

        composeRule.onNodeWithTag(GraphEntryTestTags.NODE_DETAIL_OPEN).performClick()
        composeRule.waitForIdle()
        assertEquals("Open goes to Knowledge (rule 1)", Destination.KNOWLEDGE, shell.nav.topLevel)
        assertEquals(NoteKey(SkeinId.of(note.id)), shell.nav.stack(Destination.KNOWLEDGE).last())
    }

    private fun setHost(vault: InMemoryVaultRepository) {
        composeRule.setContent {
            GraphHost(vault, InMemoryIndexStore(), size = null) { shell = it }
        }
        composeRule.waitForIdle()
    }
}
