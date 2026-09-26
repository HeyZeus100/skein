// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §10.11 "Rename").
package app.skein.core.designsystem.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinRenameDialogTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    private var confirmedName: String? = null
    private var dismissed = false

    private fun show(
        initialName: String = "Skein UX redesign",
        maxLength: Int = 10,
    ) {
        confirmedName = null
        dismissed = false
        composeRule.setContent {
            SkeinTheme {
                SkeinRenameDialog(
                    title = "Rename chat",
                    initialName = initialName,
                    maxLength = maxLength,
                    onConfirm = { confirmedName = it },
                    onDismiss = { dismissed = true },
                )
            }
        }
    }

    @Test
    fun `confirm is disabled while the field is unchanged`() {
        show()

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun `confirm is disabled once the field is cleared to blank`() {
        show()

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextClearance()

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Name can't be empty").assertExists()
    }

    @Test
    fun `confirm enables once the field actually changes`() {
        show()

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextInput("New name")

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG).assertIsEnabled()
    }

    @Test
    fun `confirming calls onConfirm with the field's text`() {
        show()

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextInput("New name")
        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG).performClick()

        assertEquals("New name", confirmedName)
    }

    @Test
    fun `cancelling calls onDismiss without confirming`() {
        show()

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_CANCEL_TEST_TAG).performClick()

        assertEquals(null, confirmedName)
        assertEquals(true, dismissed)
    }

    @Test
    fun `the field is capped at maxLength`() {
        show(initialName = "abc", maxLength = 5)

        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG).performTextInput("abcdefghij")
        composeRule.onNodeWithTag(SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG).performClick()

        assertEquals("abcde", confirmedName)
    }
}
