package app.skein.feature.models

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skein.feature.shell.theme.SkeinTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziActivity
import com.github.takahirom.roborazzi.registerRoborazziActivityToRobolectricIfNeeded
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LC-27 (Stage H, skein-xtov.22): `/models` Delete used to delete on one tap.
 * It now asks, in product language, and only [ModelsScreen]'s `onDelete`
 * fires once the user confirms.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDeleteConfirmationTest {
    // This module registers no test activity of its own; reuse Roborazzi's.
    @get:Rule(order = 0)
    val registerActivity =
        object : ExternalResource() {
            override fun before() = registerRoborazziActivityToRobolectricIfNeeded()
        }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    private val deleted = mutableListOf<String>()

    private fun showModels() {
        val model =
            ModelListItem(
                id = "qwen",
                displayName = "Qwen 2.5 3B",
                sizeBytes = 2_040_109_466L,
                licenseSpdx = "Apache-2.0",
                isDefault = true,
                isLoaded = false,
            )
        composeRule.setContent {
            SkeinTheme {
                ModelsScreen(listOf(model), onSetDefault = {}, onDelete = { deleted += it }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Delete").performClick()
    }

    private fun dialogButton(label: String) = composeRule.onNode(hasText(label) and hasAnyAncestor(isDialog()))

    @Test
    fun `delete asks first and cancel keeps the model`() {
        showModels()

        composeRule.onNodeWithText("Delete “Qwen 2.5 3B”?").assertExists()
        composeRule
            .onNodeWithText("This frees 1.9 GB. To use it again, you'll need to import it again.", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Chats will need another model.", substring = true).assertExists()

        dialogButton("Cancel").performClick()

        composeRule.onNodeWithText("Delete “Qwen 2.5 3B”?").assertDoesNotExist()
        assertThat(deleted).isEmpty()
    }

    @Test
    fun `confirming deletes the model`() {
        showModels()

        dialogButton("Delete").performClick()

        assertThat(deleted).containsExactly("qwen")
    }
}
