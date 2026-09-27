// skein-xtov.24.6 (AL-07, UX Wave 3): `SkeinDrawerContent`'s own coverage —
// the §8a ⋮ rule wired through real history rows (grouping is covered by
// `ChatHistoryGroupingTest`; `SkeinListRow`'s own semantics are covered
// where it lives), destination selection, and the Space switcher's
// two-or-more-Spaces gate (IA §8b).
package app.skein.feature.shell.container

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.skein.core.designsystem.theme.SkeinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val NOW = 1_800_000_000_000L

private fun row(
    id: String,
    title: String,
    selected: Boolean,
    onOpen: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
): ChatHistoryItem =
    ChatHistoryItem(
        id = id,
        title = title,
        lastMessageAtMillis = NOW,
        timeLabel = "9:41",
        isSelected = selected,
        onOpen = onOpen,
        onRename = onRename,
        onDelete = onDelete,
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinDrawerContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the More button shows only on the selected row, but every row keeps its custom actions`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinDrawerContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = {},
                    onNewChat = {},
                    onSearch = {},
                    history =
                        listOf(
                            row("a", "Selected chat", selected = true),
                            row("b", "Other chat", selected = false),
                        ),
                    spaces = emptyList(),
                    now = { NOW },
                )
            }
        }

        // The history rows sit below 5 destination rows in the LazyColumn;
        // scroll them into the (small Robolectric) viewport before asserting.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Other chat"))

        // §10.9/§8a: the ⋮ button itself is visible only on the selected row.
        composeRule.onNodeWithContentDescription("More options for “Selected chat”").assertExists()
        composeRule.onNodeWithContentDescription("More options for “Other chat”").assertDoesNotExist()

        // But TalkBack/long-press reach Rename and Delete on *every* row regardless.
        val hasCustomActions =
            SemanticsMatcher("has Rename/Delete custom actions") { node ->
                val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
                actions.any { it.label == "Rename" } && actions.any { it.label == "Delete" }
            }
        composeRule.onNodeWithText("Selected chat").assert(hasCustomActions)
        composeRule.onNodeWithText("Other chat").assert(hasCustomActions)
    }

    @Test
    fun `Rename and Delete are callbacks only, invoked from the row's custom actions`() {
        var renamed = false
        var deleted = false
        composeRule.setContent {
            SkeinTheme {
                SkeinDrawerContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = {},
                    onNewChat = {},
                    onSearch = {},
                    history =
                        listOf(
                            row(
                                "a",
                                "My chat",
                                selected = false,
                                onRename = { renamed = true },
                                onDelete = { deleted = true },
                            ),
                        ),
                    spaces = emptyList(),
                    now = { NOW },
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("My chat"))
        val node = composeRule.onNodeWithText("My chat").fetchSemanticsNode()
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        actions.single { it.label == "Rename" }.action.invoke()
        actions.single { it.label == "Delete" }.action.invoke()

        assertTrue(renamed)
        assertTrue(deleted)
    }

    @Test
    fun `tapping a destination row invokes onNavigate with that destination`() {
        var navigatedTo: SkeinDestination? = null
        composeRule.setContent {
            SkeinTheme {
                SkeinDrawerContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = { navigatedTo = it },
                    onNewChat = {},
                    onSearch = {},
                    history = emptyList(),
                    spaces = emptyList(),
                    now = { NOW },
                )
            }
        }

        composeRule.onNodeWithText("Knowledge").performClick()

        assertEquals(SkeinDestination.KNOWLEDGE, navigatedTo)
    }

    @Test
    fun `the Space switcher is absent below two Spaces and present at two or more`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinDrawerContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = {},
                    onNewChat = {},
                    onSearch = {},
                    history = emptyList(),
                    spaces = listOf(SkeinSpace("s1", "Personal", isSelected = true, onSelect = {})),
                    now = { NOW },
                )
            }
        }
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.SPACE_SWITCHER).assertDoesNotExist()
    }

    @Test
    fun `the Space switcher is present at two or more Spaces`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinDrawerContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = {},
                    onNewChat = {},
                    onSearch = {},
                    history = emptyList(),
                    spaces =
                        listOf(
                            SkeinSpace("s1", "Personal", isSelected = true, onSelect = {}),
                            SkeinSpace("s2", "Work", isSelected = false, onSelect = {}),
                        ),
                    now = { NOW },
                )
            }
        }
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.SPACE_SWITCHER).assertExists()
        composeRule.onNodeWithText("Personal").assertExists()
    }
}
