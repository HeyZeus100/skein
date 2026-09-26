package app.skein.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.testing.FakeRetrievalService
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX-P0-14 / CMS-P0-07 (Stage H, skein-xtov.22): the chat composer's `[[`
 * popup offers `Create "x"`, which used to insert the link and create
 * nothing. It now creates the note it names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerWikilinkCreateRowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `createsTheNoteOrIsHidden - the Create row creates the note it names`() {
        lateinit var chat: Document
        val vault = fakeVault { chat = chat("Chat") }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(emptyList()),
                promptAssembler = SimplePromptAssembler(),
                engine = scriptedEngine(),
                personaProvider = { null },
                budgetFor = { _, _ -> error("no send in this test") },
                countTokens = { it.length / 4 },
            )
        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    docId = chat.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = TabController { _, _, _ -> "tab" },
                    wikilinkSuggest = { emptyList() },
                )
            }
        }

        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("[[Groceries")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Create \"Groceries\"").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Create \"Groceries\"").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { runBlocking { vault.findByTitle("Groceries") } != null }
        assertThat(runBlocking { vault.findByTitle("Groceries") }?.kind).isEqualTo(DocumentKind.NOTE)
    }
}
