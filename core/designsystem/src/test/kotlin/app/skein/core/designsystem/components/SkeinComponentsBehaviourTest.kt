// skein-xtov.23.8 (DS8): behaviour and semantics of the structural
// components — the ⋮ visibility rule, long-press/right-click → the same
// menu, TalkBack custom actions, the top bar's subtitle button and
// font-scale growth, segmented radio semantics, the search field's clear and
// IME actions, and the status live region.
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinComponentsBehaviourTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    private val calls = mutableListOf<String>()

    private val actions =
        listOf(
            SkeinAction("Rename…") { calls += "rename" },
            SkeinAction("Delete…", destructive = true) { calls += "delete" },
        )

    private fun setContent(content: @Composable () -> Unit) = composeRule.setContent { SkeinTheme(content = content) }

    private fun more(title: String) = composeRule.onNodeWithContentDescription("More options for “$title”")

    // --- SkeinListRow: the ⋮ rule (IA §8a) ---

    @Test
    fun `an unselected row shows its meta, not the more button`() {
        setContent { SkeinListRow(title = "Beta", onClick = {}, trailingMeta = "2h", menuActions = actions) }

        more("Beta").assertDoesNotExist()
        composeRule.onNodeWithText("2h").assertExists()
    }

    @Test
    fun `the selected row shows the more button in place of its meta, and is selected`() {
        setContent {
            SkeinListRow(
                title = "Beta",
                onClick = {},
                trailingMeta = "2h",
                selected = true,
                menuActions = actions,
            )
        }

        more("Beta").assertExists()
        composeRule.onNodeWithText("2h").assertDoesNotExist()
        composeRule.onNodeWithText("Beta").assertIsSelected()
    }

    @Test
    fun `hovering a row reveals the more button`() {
        setContent { SkeinListRow(title = "Beta", onClick = {}, menuActions = actions) }

        composeRule.onNodeWithText("Beta").performMouseInput { moveTo(center) }

        more("Beta").assertExists()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `keyboard focus on a row reveals the more button`() {
        lateinit var inputModeManager: InputModeManager
        setContent {
            inputModeManager = LocalInputModeManager.current
            SkeinListRow(title = "Beta", onClick = {}, menuActions = actions)
        }
        composeRule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }

        composeRule.onNodeWithText("Beta").requestFocus()

        more("Beta").assertExists()
    }

    @Test
    fun `a row without actions never shows a more button`() {
        setContent { SkeinListRow(title = "Beta", onClick = {}, selected = true) }

        more("Beta").assertDoesNotExist()
    }

    // --- SkeinListRow: one menu, three ways in ---

    @Test
    fun `long-press opens the row menu, destructive item last`() {
        setContent { SkeinListRow(title = "Beta", onClick = { calls += "open" }, menuActions = actions) }

        composeRule.onNodeWithText("Beta").performTouchInput { longClick() }

        composeRule.onNodeWithText("Rename…").assertExists()
        composeRule.onNodeWithText("Delete…").performClick()
        composeRule.onNodeWithText("Rename…").assertDoesNotExist()
        assertEquals(listOf("delete"), calls)
    }

    @Test
    fun `right-click opens the same menu and does not open the row`() {
        setContent { SkeinListRow(title = "Beta", onClick = { calls += "open" }, menuActions = actions) }

        composeRule.onNodeWithText("Beta").performMouseInput { rightClick() }

        composeRule.onNodeWithText("Rename…").assertExists()
        assertTrue("right-click must not also open the row", "open" !in calls)
    }

    @Test
    fun `the more button opens the same menu`() {
        setContent { SkeinListRow(title = "Beta", onClick = {}, selected = true, menuActions = actions) }

        more("Beta").performClick()

        composeRule.onNodeWithText("Rename…").performClick()
        assertEquals(listOf("rename"), calls)
    }

    @Test
    fun `a tap opens the row`() {
        setContent { SkeinListRow(title = "Beta", onClick = { calls += "open" }, menuActions = actions) }

        composeRule.onNodeWithText("Beta").performClick()

        assertEquals(listOf("open"), calls)
    }

    // --- SkeinListRow: TalkBack ---

    @Test
    fun `every menu action is a custom action, announced without the ellipsis`() {
        setContent { SkeinListRow(title = "Beta", onClick = {}, menuActions = actions) }

        val row = composeRule.onNodeWithText("Beta").fetchSemanticsNode()
        val custom = row.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Rename", "Delete"), custom.map { it.label })
        assertEquals("Show options", row.config[SemanticsActions.OnLongClick].label)

        custom.first { it.label == "Delete" }.action()
        assertEquals(listOf("delete"), calls)
    }

    // --- SkeinTopAppBar ---

    @Test
    fun `the subtitle is one labelled button that runs its action, and the title stays a heading`() {
        setContent {
            SkeinTopAppBar(
                title = "Skein UX redesign",
                subtitle = "Qwen 2.5 3B",
                subtitleDetail = "Local",
                onSubtitleClick = { calls += "model" },
                subtitleClickLabel = "Model: Qwen 2.5 3B, local. Change model.",
            )
        }

        composeRule.onNodeWithText("Skein UX redesign").assert(isHeading())
        composeRule
            .onNode(hasContentDescription("Model: Qwen 2.5 3B, local. Change model."))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithText("Qwen 2.5 3B", useUnmergedTree = true).performClick()

        assertEquals(listOf("model"), calls)
    }

    @Test
    fun `without a click handler the subtitle is plain text`() {
        setContent { SkeinTopAppBar(title = "Chat", subtitle = "Qwen 2.5 3B", subtitleDetail = "Local") }

        val subtitle = composeRule.onNodeWithText("Qwen 2.5 3B", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse(subtitle.config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `at font scale 2 the bar grows past 64 dp and no text is clipped`() {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SkeinTopAppBar(
                    title = "Skein UX redesign",
                    subtitle = "Qwen 2.5 3B",
                    subtitleDetail = "Local",
                    onSubtitleClick = {},
                    modifier = Modifier.testTag("bar"),
                )
            }
        }

        composeRule.onNodeWithTag("bar").assertHeightIsAtLeast(80.dp)
        for (text in listOf("Skein UX redesign", "Qwen 2.5 3B", "Local")) {
            assertFalse(
                "“$text” overflows its height",
                composeRule.onNodeWithText(text, useUnmergedTree = true).layout().didOverflowHeight,
            )
        }
    }

    // --- SkeinSegmentedControl ---

    @Test
    fun `segments are radio buttons in a selectable group, one selected`() {
        var selected by mutableStateOf("Light")
        setContent {
            SkeinSegmentedControl(
                options = listOf("System", "Light", "Dark"),
                selected = selected,
                onSelect = { selected = it },
                label = { it },
                modifier = Modifier.testTag("segments"),
            )
        }

        composeRule.onNodeWithTag("segments").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        for (label in listOf("System", "Light", "Dark")) {
            composeRule
                .onNodeWithText(
                    label,
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        composeRule.onNodeWithText("Light").assertIsSelected()
        composeRule.onNodeWithText("Dark").assertIsNotSelected()

        composeRule.onNodeWithText("Dark").performClick()

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("Light").assertIsNotSelected()
    }

    // --- SkeinSearchField ---

    @Test
    fun `the search field is 48 dp, clears, and runs the IME search action`() {
        val searched = mutableListOf<String>()
        setContent {
            var query by remember { mutableStateOf("") }
            SkeinSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search chats",
                onSearch = { searched += it },
                modifier = Modifier.testTag("search"),
            )
        }

        composeRule.onNodeWithTag("search").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Clear search").assertDoesNotExist()

        composeRule.onNodeWithTag("search").performTextInput("fold")
        composeRule.onNodeWithTag("search").performImeAction()
        assertEquals(listOf("fold"), searched)

        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        composeRule.onNodeWithText("Search chats", useUnmergedTree = true).assertExists()
    }

    // --- SkeinStatus ---

    @Test
    fun `status is a polite live region speaking the announcement, not the ticking label`() {
        setContent {
            SkeinStatus(
                kind = SkeinStatusKind.Loading,
                label = "Starting… 42%",
                announcement = "Starting… 25%",
                modifier = Modifier.testTag("status"),
            )
        }

        val node = composeRule.onNodeWithTag("status").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        assertEquals(listOf("Starting… 25%"), node.config[SemanticsProperties.ContentDescription])
        assertEquals(null, node.config.getOrNull(SemanticsProperties.Text))
    }

    // --- SkeinEmptyState / SkeinNotice ---

    @Test
    fun `empty state headline is a heading and its actions run`() {
        setContent {
            Column {
                SkeinEmptyState(
                    headline = "Your knowledge starts here",
                    body = "Write a note or import a file.",
                    primaryAction = SkeinAction("New note") { calls += "new" },
                    secondaryActions = listOf(SkeinAction("Import file") { calls += "import" }),
                    modifier = Modifier.weight(1f),
                )
                SkeinNotice(
                    title = "Couldn't import “notes.pdf”",
                    tone = SkeinNoticeTone.Error,
                    action = SkeinAction("Choose another file") { calls += "choose" },
                )
            }
        }

        composeRule.onNodeWithText("Your knowledge starts here").assert(isHeading())
        composeRule.onNodeWithText("New note").performClick()
        composeRule.onNodeWithText("Import file").performClick()
        composeRule.onNodeWithText("Choose another file").performClick()
        assertEquals(listOf("new", "import", "choose"), calls)
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }
}
