// skein-xtov.24.9 (AL-09b): Models list→details — a single pane on Compact
// (selecting a model replaces the list with its details) and both panes at
// once on Expanded (list-detail scene), with unchanged set-default/delete.
package app.skein.feature.models.entries

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import app.skein.core.designsystem.components.SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG
import app.skein.core.navigation.Destination
import app.skein.core.navigation.ModelDetailsKey
import app.skein.core.navigation.ModelsHomeKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinNavCodec
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.feature.models.MODELS_LIST_PANE_TEST_TAG
import app.skein.feature.models.ModelListItem
import app.skein.feature.shell.host.SkeinShellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val QWEN =
    ModelListItem(
        id = "0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6b",
        displayName = "Qwen 2.5 3B",
        sizeBytes = 2_000_000_000L,
        licenseSpdx = "Apache-2.0",
        isDefault = true,
        isLoaded = false,
    )

private val IMPORTED_QWEN =
    QWEN.copy(
        id = "qwen2.5-1.5b-instruct-q4-k-m-6a1a2eb6d156",
        displayName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_117_320_736L,
        isDefault = false,
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1100dp-mdpi")
class ModelsEntriesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: SkeinShellState
    private val deleteCalls = mutableListOf<String>()
    private val defaultCalls = mutableListOf<String>()
    private var selectedRowColor = Color.Unspecified

    @Test
    fun `Compact — selecting a model pushes ModelDetailsKey and replaces the list with its details`() {
        setHost(COMPACT)
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()

        composeRule.onNodeWithText(QWEN.displayName).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertDoesNotExist()
        val details = shell.nav.stack(Destination.MODELS).last() as ModelDetailsKey
        assertEquals(SkeinId.of(QWEN.id), details.modelId)
        composeRule.onAllNodesWithText(QWEN.displayName).onLast().assertExists()
        composeRule.onNodeWithText("This model was removed.").assertDoesNotExist()
    }

    @Test
    fun `Expanded — list and details are both on screen at once, and Delete still fires`() {
        setHost(EXPANDED)
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()

        composeRule.onNodeWithText(QWEN.displayName).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(QWEN.displayName).assertCountEquals(3)
        // Both panes on screen at once (list-detail scene, ≥ 2 panes): one
        // "Delete" in the list row, one in the details pane.
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()
        composeRule.onAllNodesWithText("Delete").onLast().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete “${QWEN.displayName}”?").assertExists()
        composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(QWEN.id), deleteCalls)
    }

    @Test
    fun `Compact — imported model renders loaded details and Back removes its transient ID`() {
        setHost(COMPACT, listOf(IMPORTED_QWEN.copy(isLoaded = true)))

        composeRule.onNodeWithText(IMPORTED_QWEN.displayName).performClick()
        composeRule.waitForIdle()

        val details = shell.nav.stack(Destination.MODELS).last() as TransientKey
        assertEquals(TransientKind.MODEL, details.kind)
        assertEquals(IMPORTED_QWEN.id, shell.nav.rawIdOf(details))
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Currently loaded").assertExists()
        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("This model was removed.").assertDoesNotExist()

        val saved = SkeinNavCodec.encode(shell.nav)
        assertFalse(saved.toString().contains(IMPORTED_QWEN.id))
        assertTrue(SkeinNavCodec.violations(saved).isEmpty())
        assertEquals(listOf(ModelsHomeKey), SkeinNavCodec.decode(saved).stack(Destination.MODELS))

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()
        assertEquals(listOf(ModelsHomeKey), shell.nav.stack(Destination.MODELS))
        assertNull(shell.nav.rawIdOf(details))
        assertTrue(deleteCalls.isEmpty())
    }

    @Test
    fun `Expanded — imported model details keep exact default and confirmed delete IDs`() {
        setHost(EXPANDED, listOf(QWEN, IMPORTED_QWEN))

        composeRule.onNodeWithText(IMPORTED_QWEN.displayName).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()
        composeRule.onNodeWithText("Currently loaded").assertDoesNotExist()
        composeRule.onAllNodesWithText(IMPORTED_QWEN.displayName).assertCountEquals(3)
        composeRule.onAllNodesWithText("Set default").onLast().performClick()
        assertEquals(listOf(IMPORTED_QWEN.id), defaultCalls)
        composeRule.onAllNodesWithText("Delete").onLast().performClick()
        composeRule.onNodeWithText("Delete “${IMPORTED_QWEN.displayName}”?").assertExists()
        assertTrue(deleteCalls.isEmpty())
        composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(IMPORTED_QWEN.id), deleteCalls)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Expanded — selection highlight follows imported and canonical model details`() {
        setHost(EXPANDED, listOf(QWEN, IMPORTED_QWEN))
        assertNotEquals(selectedRowColor.toArgb(), rowBackground(IMPORTED_QWEN))

        modelRow(IMPORTED_QWEN).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        composeRule.waitForIdle()
        val details = shell.nav.stack(Destination.MODELS).last() as TransientKey
        assertEquals(IMPORTED_QWEN.id, shell.nav.rawIdOf(details))
        assertEquals(selectedRowColor.toArgb(), rowBackground(IMPORTED_QWEN))
        assertNotEquals(selectedRowColor.toArgb(), rowBackground(QWEN))

        modelRow(QWEN).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        composeRule.waitForIdle()
        assertEquals(selectedRowColor.toArgb(), rowBackground(QWEN))
        assertNotEquals(selectedRowColor.toArgb(), rowBackground(IMPORTED_QWEN))
    }

    @Test
    fun `Compact — missing imported model shows the existing removed state instead of a blank entry`() {
        setHost(COMPACT)
        composeRule.runOnIdle { shell.navigate { openModel(it, IMPORTED_QWEN.id) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("This model was removed.").assertExists()
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    private fun modelRow(model: ModelListItem) =
        composeRule.onNode(
            hasText(model.displayName) and hasClickAction() and
                hasAnyAncestor(hasTestTag(MODELS_LIST_PANE_TEST_TAG)),
        )

    private fun rowBackground(model: ModelListItem): Int =
        modelRow(model)
            .captureToImage()
            .toPixelMap()[1, 1]
            .toArgb()

    private fun setHost(
        size: DpSize,
        models: List<ModelListItem> = listOf(QWEN),
    ) {
        val deps =
            ModelsEntryDeps(
                models = models,
                onSetDefault = { defaultCalls += it },
                onDelete = { deleteCalls += it },
            )
        composeRule.setContent {
            selectedRowColor = MaterialTheme.colorScheme.secondaryContainer
            ModelsHost(deps, size) { shell = it }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.MODELS) } }
        composeRule.waitForIdle()
    }
}
