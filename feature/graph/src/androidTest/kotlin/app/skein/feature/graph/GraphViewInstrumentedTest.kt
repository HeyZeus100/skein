package app.skein.feature.graph

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.navigation.Destination
import app.skein.core.navigation.GraphNodeKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.destination
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.graph.entries.GraphEntry
import app.skein.feature.graph.entries.GraphEntryDeps
import app.skein.feature.graph.entries.GraphEntryTestTags
import app.skein.feature.shell.host.PlaceholderEntry
import app.skein.feature.shell.host.SkeinShellHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.navKindsOf
import app.skein.feature.shell.host.rememberSkeinShellState
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** The production Graph entries: select a node, explicitly open it, and deselect with system Back. */
@RunWith(AndroidJUnit4::class)
class GraphViewInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: SkeinShellState

    @Test
    fun seeded_graph_renders_the_canvas_and_legend() {
        showGraph()
        composeRule.onNodeWithTag(GraphTestTags.CANVAS).assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTestTags.LEGEND).assertIsDisplayed()
    }

    @Test
    fun tapping_a_node_selects_it_then_open_routes_to_knowledge() {
        val id = showGraph()
        composeRule.onNodeWithTag(GraphTestTags.CANVAS).performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.GRAPH, shell.nav.topLevel)
        assertEquals(SkeinId.of(id), (shell.nav.stack(Destination.GRAPH).last() as GraphNodeKey).nodeDocId)
        composeRule.onNodeWithTag(GraphEntryTestTags.NODE_DETAIL_OPEN).performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.KNOWLEDGE, shell.nav.topLevel)
        assertEquals(NoteKey(SkeinId.of(id)), shell.nav.stack(Destination.KNOWLEDGE).last())
    }

    @Test
    fun long_press_selects_a_node_without_opening_a_tab() {
        val id = showGraph()
        composeRule.onNodeWithTag(GraphTestTags.CANVAS).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(Destination.GRAPH, shell.nav.topLevel)
        assertEquals(SkeinId.of(id), (shell.nav.stack(Destination.GRAPH).last() as GraphNodeKey).nodeDocId)
    }

    @Test
    fun system_back_deselects_the_node_without_leaving_graph() {
        showGraph()
        composeRule.onNodeWithTag(GraphTestTags.CANVAS).performClick()
        composeRule.waitForIdle()
        assertTrue(shell.nav.stack(Destination.GRAPH).last() is GraphNodeKey)
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(Destination.GRAPH, shell.nav.topLevel)
        composeRule.onNodeWithTag(GraphEntryTestTags.NODE_DETAIL_OPEN).assertDoesNotExist()
        composeRule.onNodeWithTag(GraphTestTags.CANVAS).assertIsDisplayed()
    }

    private fun showGraph(): String {
        val vault = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val note = runBlocking { vault.createDocument(NewDocument(DocumentKind.NOTE, "Alpha", "Alpha")) }
        composeRule.setContent {
            val manager =
                remember {
                    UnlockManager(
                        keyProvider =
                            Proxy.newProxyInstance(
                                VaultKeyProvider::class.java.classLoader,
                                arrayOf(VaultKeyProvider::class.java),
                            ) { _, method, _ ->
                                if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                            } as VaultKeyProvider,
                    )
                }
            shell = rememberSkeinShellState(manager)
            val deps = remember { GraphEntryDeps(vault, index) }
            SkeinShellHost(shell = shell, resolveKinds = navKindsOf(vault)) { key ->
                if (key.destination == Destination.GRAPH) GraphEntry(key, shell, deps) else PlaceholderEntry(key)
            }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.GRAPH) } }
        composeRule.waitForIdle()
        return note.id
    }
}
