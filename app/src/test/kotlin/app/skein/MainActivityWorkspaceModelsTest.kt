package app.skein

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.components.SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG
import app.skein.core.inference.models.ImportOutcome
import app.skein.core.inference.models.ImportProgress
import app.skein.core.inference.models.ImportSource
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.models.MODELS_EMPTY_TEST_TAG
import app.skein.feature.models.entries.ModelsEntryTestTags
import app.skein.feature.shell.container.SkeinNavContainerTestTags
import app.skein.feature.shell.host.WorkspaceTestTags
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Shared registry changes must refresh a sibling without navigating or recreating its chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1006dp-h1043dp-port-330dpi", application = TestSkeinApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityWorkspaceModelsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `deleting the last model in secondary refreshes the unchanged primary conversation`() {
        app.enableSessionChat = true
        val chat =
            runBlocking {
                app.repository.createDocument(NewDocument(DocumentKind.CHAT, CHAT_TITLE, "")).also {
                    app.repository.appendMessage(it.id, NewMessage(Role.USER, CHAT_MESSAGE))
                }
            }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(WorkspaceTestTags.TOGGLE_SPLIT)
            navigate("Models")
            awaitPaneTag(PRIMARY, ModelsEntryTestTags.IMPORT_ACTION)

            // Use the real store/manager with the fixture's tiny GGUF, so UI deletion cannot
            // accidentally exercise a refusal caused by a registry-only fake model.
            val services = requireNotNull(requireNotNull(app.vault.session.value).models)
            val imported =
                runBlocking {
                    withTimeout(WAIT_MILLIS) {
                        val done =
                            services.manager
                                .import(ImportSource.Picked(Uri.parse("content://test/model.gguf")))
                                .last()
                        val outcome = (done as ImportProgress.Done).outcome
                        val result = outcome as? ImportOutcome.Imported ?: error("Fixture import refused: $outcome")
                        services.manager.setDefault(result.record.model.id)
                        services.manifestCache.refresh()
                        result
                    }
                }
            navigate("Chat")
            awaitPaneTag(PRIMARY, ChatEntryTestTags.CONVERSATIONS)
            composeRule
                .onNode(
                    hasText(CHAT_TITLE) and inPane(PRIMARY) and
                        hasAnyAncestor(hasTestTag(ChatEntryTestTags.CONVERSATIONS)),
                ).performClick()
            awaitPaneTag(PRIMARY, COMPOSER_TEST_TAG)
            composeRule.onNode(hasText(NO_MODEL) and inPane(PRIMARY)).assertDoesNotExist()
            composer().performTextInput(DRAFT)
            composer().performSemanticsAction(SemanticsActions.SetSelection) { setSelection ->
                setSelection(CARET, CARET, false)
            }

            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            composeRule.onNodeWithTag(SECONDARY).performSemanticsAction(SemanticsActions.CustomActions) { actions ->
                assertTrue(actions.single().action())
            }
            navigate("Models")
            awaitPaneTag(SECONDARY, ModelsEntryTestTags.IMPORT_ACTION)
            composeRule.waitUntil("imported model in secondary", WAIT_MILLIS) {
                composeRule
                    .onAllNodes(hasText(imported.record.model.name) and inPane(SECONDARY))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNode(hasText("Delete") and inPane(SECONDARY)).performClick()
            awaitTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG)
            composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG).performClick()

            // No primary-pane activation, navigation, refresh action or recreation after Delete.
            composeRule.waitUntil("primary observes the removed default model", WAIT_MILLIS) {
                composeRule
                    .onAllNodes(hasText(NO_MODEL) and inPane(PRIMARY))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            awaitPaneTag(SECONDARY, MODELS_EMPTY_TEST_TAG)
            composeRule.onNode(hasText(CHAT_TITLE) and inPane(PRIMARY)).assertExists()
            composeRule.onNode(hasText(CHAT_MESSAGE) and inPane(PRIMARY)).assertExists()
            composer().assertTextEquals(DRAFT)
            assertEquals(
                TextRange(CARET),
                composer().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
            )
            assertTrue(
                composeRule
                    .onNodeWithTag(SECONDARY)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Selected],
            )
            runBlocking {
                assertTrue(app.modelRegistry.list().isEmpty())
                assertNull(app.modelRegistry.default())
                assertEquals(
                    listOf(CHAT_MESSAGE),
                    app.repository.listMessages(chat.id).map { message -> message.contentMd },
                )
            }
        }
    }

    private fun inPane(pane: String) = hasAnyAncestor(hasTestTag(pane))

    private fun composer() = composeRule.onNode(hasTestTag(COMPOSER_TEST_TAG) and inPane(PRIMARY))

    private fun navigate(label: String) {
        composeRule
            .onNode(hasText(label) and hasAnyAncestor(hasTestTag(SkeinNavContainerTestTags.RAIL)))
            .performClick()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil("production tag $tag", WAIT_MILLIS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitPaneTag(
        pane: String,
        tag: String,
    ) {
        composeRule.waitUntil("$tag in $pane", WAIT_MILLIS) {
            composeRule.onAllNodes(hasTestTag(tag) and inPane(pane)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val PRIMARY = WorkspaceTestTags.PRIMARY_PANE
        const val SECONDARY = WorkspaceTestTags.SECONDARY_PANE
        const val WAIT_MILLIS = 30_000L
        const val CHAT_TITLE = "workspace-model-revision-chat"
        const val CHAT_MESSAGE = "A fictional message identifying the retained conversation."
        const val DRAFT = "Unsent draft survives model removal"
        const val CARET = 7
        const val NO_MODEL = "Add a model to start"
    }
}
