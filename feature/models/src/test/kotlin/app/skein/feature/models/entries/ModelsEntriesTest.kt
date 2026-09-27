// skein-xtov.24.9 (AL-09b): Models list→details — a single pane on Compact
// (selecting a model replaces the list with its details) and both panes at
// once on Expanded (list-detail scene), with unchanged set-default/delete.
package app.skein.feature.models.entries

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import app.skein.core.designsystem.components.SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG
import app.skein.core.navigation.Destination
import app.skein.core.navigation.ModelDetailsKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.models.MODELS_LIST_PANE_TEST_TAG
import app.skein.feature.models.ModelListItem
import app.skein.feature.shell.host.SkeinShellState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val QWEN =
    ModelListItem(
        id = "0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6b",
        displayName = "Qwen 2.5 3B",
        sizeBytes = 2_000_000_000L,
        licenseSpdx = "Apache-2.0",
        isDefault = true,
        isLoaded = false,
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelsEntriesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: SkeinShellState
    private val deleteCalls = mutableListOf<String>()

    @Test
    fun `Compact — selecting a model pushes ModelDetailsKey and replaces the list with its details`() {
        setHost(COMPACT)
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()

        composeRule.onNodeWithText(QWEN.displayName).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertDoesNotExist()
        val details = shell.nav.stack(Destination.MODELS).last() as ModelDetailsKey
        assertEquals(SkeinId.of(QWEN.id), details.modelId)
    }

    @Test
    fun `Expanded — list and details are both on screen at once, and Delete still fires`() {
        setHost(EXPANDED)
        composeRule.onNodeWithTag(MODELS_LIST_PANE_TEST_TAG).assertExists()

        composeRule.onNodeWithText(QWEN.displayName).performClick()
        composeRule.waitForIdle()

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

    private fun setHost(size: DpSize) {
        val deps =
            ModelsEntryDeps(
                models = listOf(QWEN),
                onSetDefault = {},
                onDelete = { deleteCalls += it },
            )
        composeRule.setContent { ModelsHost(deps, size) { shell = it } }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.MODELS) } }
        composeRule.waitForIdle()
    }
}
