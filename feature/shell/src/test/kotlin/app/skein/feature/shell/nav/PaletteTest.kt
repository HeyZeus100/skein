package app.skein.feature.shell.nav

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DBP-05b / UX-P0-16 (Stage H5, skein-xtov.22): tapping a `/` palette row
 * used to fill the field with `/chat ` and then show "No matching commands"
 * (device-confirmed). A tapped row now runs its command.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaletteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapRunsTheCommand() {
        val runs = mutableListOf<String>()
        val navState = NavState()
        composeRule.setContent {
            val registry =
                remember {
                    val chat = Command("chat", "start a new chat") { runs += it }
                    CommandRegistry().apply { register(CommandScope.GLOBAL, listOf(chat)) }
                }
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    CommandBarState(
                        vaultRepository = null,
                        registry = registry,
                        onOpenPreview = { _, _ -> },
                        searchScope = scope,
                    )
                }
            CommandBarHost(navState = navState, commandBarState = state, modelName = "no model", modelActive = false)
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("/")
        composeRule.onNodeWithText("/chat start a new chat").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(""), runs)
        assertEquals("", navState.query)
        composeRule.onNodeWithText("No matching commands").assertDoesNotExist()
    }
}
