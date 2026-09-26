// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §10.13).
package app.skein.core.designsystem.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class SkeinSnackbarTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `shows the message with no action button when none is given`() {
        val hostState = SnackbarHostState()
        composeRule.setContent {
            SkeinTheme {
                SkeinSnackbarHost(hostState)
                LaunchedEffect(Unit) { hostState.showSnackbar(message = "Deleted “Skein UX redesign”") }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Deleted “Skein UX redesign”").assertExists()
        composeRule.onNodeWithTag(SKEIN_SNACKBAR_ACTION_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `shows an action button and reports the result when tapped`() {
        val hostState = SnackbarHostState()
        var result: SnackbarResult? = null
        composeRule.setContent {
            SkeinTheme {
                SkeinSnackbarHost(hostState)
                LaunchedEffect(Unit) {
                    result =
                        hostState.showSnackbar(
                            message = "Deleted “Skein UX redesign”",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Indefinite,
                        )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_SNACKBAR_ACTION_TEST_TAG).assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        assertEquals(SnackbarResult.ActionPerformed, result)
    }
}
