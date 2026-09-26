// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §10.11, §14.17).
package app.skein.core.designsystem.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinDestructiveDialogTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    private var confirmed = false
    private var dismissed = false

    private fun show(title: String = "Delete “Skein UX redesign”?") {
        confirmed = false
        dismissed = false
        composeRule.setContent {
            SkeinTheme {
                SkeinDestructiveDialog(
                    title = title,
                    consequence = "This removes the conversation and its messages from Skein.",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `shows the title and consequence`() {
        show()

        composeRule.onNodeWithText("Delete “Skein UX redesign”?").assertExists()
        composeRule
            .onNodeWithText("This removes the conversation and its messages from Skein.", substring = true)
            .assertExists()
    }

    @Test
    fun `cancel is focused by default, so Enter never confirms a delete`() {
        show()

        // The mechanism §14.17 relies on: keyboard focus starts on Cancel, so
        // a hardware Enter (which activates whatever currently has focus)
        // never lands on Delete.
        composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG).assertIsFocused()
    }

    @Test
    fun `confirming calls onConfirm, not onDismiss`() {
        show()

        composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG).performClick()

        assertTrue("expected onConfirm to fire", confirmed)
        assertFalse("onDismiss should not fire on confirm", dismissed)
    }

    @Test
    fun `cancelling calls onDismiss, not onConfirm`() {
        show()

        composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG).performClick()

        assertTrue("expected onDismiss to fire", dismissed)
        assertFalse("onConfirm should not fire on cancel", confirmed)
    }

    @Test
    fun `carries a TalkBack pane title naming the dialog`() {
        val title = "Delete “Skein UX redesign”?"
        show(title = title)

        composeRule
            .onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))
    }
}
