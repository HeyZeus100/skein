package app.skein.feature.editor.entries

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.Destination
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.fakeVault
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KnowledgePreparationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `Knowledge reports actual running progress and distinguishes pending semantic work from completion`() {
        val vault = fakeVault { note("Indexed note", "Text") }
        val progress = MutableStateFlow(KnowledgePreparation())
        lateinit var shell: SkeinShellState
        composeRule.setContent {
            SkeinTheme { KnowledgeHost(vault, COMPACT, { shell = it }, preparation = progress) }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } }
        composeRule.onNodeWithTag(KnowledgeEntryTestTags.PREPARATION_STATUS).assertDoesNotExist()

        progress.value = KnowledgePreparation(running = true)
        composeRule.onNodeWithContentDescription("Preparing for search…").assertIsDisplayed()
        progress.value = KnowledgePreparation(running = true, processed = 1)
        composeRule
            .onNodeWithText(
                "Preparing for search… · 1 document processed",
                useUnmergedTree = true,
            ).assertExists()
        progress.value = KnowledgePreparation(running = true, processed = 2)
        composeRule
            .onNodeWithText(
                "Preparing for search… · 2 documents processed",
                useUnmergedTree = true,
            ).assertExists()

        progress.value = KnowledgePreparation(processed = 2, awaitingMeaningSearch = 1)
        composeRule.onNodeWithContentDescription("Preparing for search…").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("1 document awaiting search by meaning").assertIsDisplayed()
        progress.value = KnowledgePreparation(processed = 3, awaitingMeaningSearch = 3)
        composeRule.onNodeWithContentDescription("3 documents awaiting search by meaning").assertIsDisplayed()
        progress.value = KnowledgePreparation(processed = 3)
        composeRule.onNodeWithTag(KnowledgeEntryTestTags.PREPARATION_STATUS).assertDoesNotExist()
    }
}
