package app.skein

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.Capability
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.DocumentKind
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelRecord
import app.skein.core.model.NewDocument
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.session.LockReason
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.editor.entries.KnowledgeEntryTestTags
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.graph.entries.GraphEntryTestTags
import app.skein.feature.models.entries.ModelsEntryTestTags
import app.skein.feature.shell.container.SkeinNavContainerTestTags
import app.skein.feature.shell.host.WorkspaceTestTags
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Activity/vault gate/workspace entries; fake repository and keys, never a second turn controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1006dp-h1043dp-port-330dpi", application = TestSkeinApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityWorkspaceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    @Before
    fun registerComposerModel() {
        // The fake engine does not read model bytes; a default registry entry enables the real composer.
        val model =
            Model(
                id = "workspace-composer-model",
                name = "Workspace test model",
                path = File(app.filesDir, "workspace-model.gguf").absolutePath,
                sha256 = "a".repeat(64),
                format = ModelFormat.GGUF,
                capabilities = setOf(Capability.TEXT),
                sizeBytes = 1000L,
            )
        runBlocking {
            app.modelRegistry.upsert(ModelRecord(model, blake3 = "b".repeat(64)))
            app.modelRegistry.setDefault(model.id)
        }
    }

    @Test
    fun `independent root drafts and carets survive split collapse and Activity recreation without entering Bundle`() {
        app.enableSessionChat = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag(WorkspaceTestTags.TOGGLE_SPLIT)
            typeDraft(WorkspaceTestTags.PRIMARY_PANE, LEFT_DRAFT, 6)
            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            awaitComposer(WorkspaceTestTags.SECONDARY_PANE)
            typeDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            assertDraft(WorkspaceTestTags.PRIMARY_PANE, LEFT_DRAFT, 6)
            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            assertDraft(WorkspaceTestTags.PRIMARY_PANE, LEFT_DRAFT, 6)
            assertDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            assertPrivateBundle(scenario)
            scenario.recreate()
            awaitComposer(WorkspaceTestTags.SECONDARY_PANE)
            assertDraft(WorkspaceTestTags.PRIMARY_PANE, LEFT_DRAFT, 6)
            assertDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            assertPrivateBundle(scenario)
        }
    }

    @Test
    fun `stopped lock flushes both independent root drafts before removing the workspace`() {
        app.enableSessionChat = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag(WorkspaceTestTags.TOGGLE_SPLIT)
            typeDraft(WorkspaceTestTags.PRIMARY_PANE, LEFT_DRAFT, 6)
            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            awaitComposer(WorkspaceTestTags.SECONDARY_PANE)
            typeDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            val succeed = app.keyProvider.nextUnlock
            app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }
            scenario.moveToState(Lifecycle.State.CREATED)
            runBlocking { app.vault.unlockManager.lockAndAwait(LockReason.USER_REQUESTED) }
            val space = runBlocking { app.personaService.default().id }
            val left = runBlocking { app.repository.readDraft(ChatDraftKey.New(space, PRIMARY_ROOT)) }
            val right = runBlocking { app.repository.readDraft(ChatDraftKey.New(space, SECONDARY_ROOT)) }
            assertEquals(LEFT_DRAFT, left?.text)
            assertEquals(6, left?.selectionStart)
            assertEquals(RIGHT_DRAFT, right?.text)
            assertEquals(9, right?.selectionStart)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            composeRule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).assertDoesNotExist()
            composeRule.onNodeWithTag(WorkspaceTestTags.SECONDARY_PANE).assertDoesNotExist()
            app.keyProvider.nextUnlock = succeed
            composeRule
                .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
                .performSemanticsAction(SemanticsActions.OnClick)
            awaitComposer(WorkspaceTestTags.SECONDARY_PANE)
            assertDraft(WorkspaceTestTags.PRIMARY_PANE, LEFT_DRAFT, 6)
            assertDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            assertPrivateBundle(scenario)
        }
    }

    @Test
    fun `notification reveals primary pane while keeping the secondary draft`() {
        app.enableSessionChat = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag(WorkspaceTestTags.TOGGLE_SPLIT)
            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            awaitComposer(WorkspaceTestTags.SECONDARY_PANE)
            typeDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            activate(WorkspaceTestTags.SECONDARY_PANE)
            scenario.onActivity {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                    it,
                    Intent(Intent.ACTION_MAIN, Uri.parse("app://skein/models")),
                )
            }
            composeRule.waitUntil("notification in primary work area", 30_000) {
                composeRule
                    .onAllNodes(
                        hasTestTag(ModelsEntryTestTags.IMPORT_ACTION) and
                            hasAnyAncestor(hasTestTag(WorkspaceTestTags.PRIMARY_PANE)),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertDraft(WorkspaceTestTags.SECONDARY_PANE, RIGHT_DRAFT, 9)
            assertTrue(
                composeRule
                    .onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Selected],
            )
        }
    }

    @Test
    fun `production work areas select note with chat then different notes then graph without replacing the sibling`() {
        app.enableSessionChat = true
        runBlocking {
            app.repository.createDocument(NewDocument(DocumentKind.NOTE, LEFT_NOTE, "Left fictional note."))
            app.repository.createDocument(NewDocument(DocumentKind.NOTE, RIGHT_NOTE, "Right fictional note."))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(WorkspaceTestTags.TOGGLE_SPLIT)
            composeRule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
            awaitComposer(WorkspaceTestTags.SECONDARY_PANE)
            openNote(WorkspaceTestTags.PRIMARY_PANE, LEFT_NOTE)
            paneNode(WorkspaceTestTags.PRIMARY_PANE, NoteTabTestTags.TITLE_FIELD).assertTextEquals(LEFT_NOTE)
            composer(WorkspaceTestTags.SECONDARY_PANE).assertExists()
            capture("note-chat")
            openNote(WorkspaceTestTags.SECONDARY_PANE, RIGHT_NOTE)
            paneNode(WorkspaceTestTags.PRIMARY_PANE, NoteTabTestTags.TITLE_FIELD).assertTextEquals(LEFT_NOTE)
            paneNode(WorkspaceTestTags.SECONDARY_PANE, NoteTabTestTags.TITLE_FIELD).assertTextEquals(RIGHT_NOTE)
            capture("two-notes")
            // This destination switch targets only the explicitly active right work area.
            navigate("Graph")
            awaitPaneTag(WorkspaceTestTags.SECONDARY_PANE, GraphEntryTestTags.CANVAS)
            paneNode(WorkspaceTestTags.PRIMARY_PANE, NoteTabTestTags.TITLE_FIELD).assertTextEquals(LEFT_NOTE)
            capture("note-graph")
        }
    }

    private fun activate(pane: String) {
        composeRule
            .onNodeWithTag(pane)
            .performSemanticsAction(SemanticsActions.CustomActions) { actions ->
                assertTrue(actions.single().action())
            }
    }

    private fun navigate(label: String) {
        composeRule
            .onNode(hasText(label) and hasAnyAncestor(hasTestTag(SkeinNavContainerTestTags.RAIL)))
            .performClick()
    }

    private fun openNote(
        pane: String,
        title: String,
    ) {
        activate(pane)
        navigate("Knowledge")
        awaitPaneTag(pane, KnowledgeEntryTestTags.LIST)
        composeRule.onNode(hasText(title) and hasAnyAncestor(hasTestTag(pane))).performClick()
        awaitPaneTag(pane, NoteTabTestTags.TITLE_FIELD)
    }

    private fun paneNode(
        pane: String,
        tag: String,
    ) = composeRule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(pane)))

    private fun awaitPaneTag(
        pane: String,
        tag: String,
    ) {
        composeRule.waitUntil("$tag in $pane", 30_000) {
            composeRule
                .onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(pane)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun composer(pane: String) =
        composeRule.onNode(hasTestTag(COMPOSER_TEST_TAG) and hasAnyAncestor(hasTestTag(pane)))

    private fun typeDraft(
        pane: String,
        text: String,
        caret: Int,
    ) {
        awaitComposer(pane)
        // A real pointer selects the workspace without consuming the composer's own focus gesture.
        composer(pane).performTouchInput { click() }
        composer(pane).performTextInput(text)
        composer(pane).performSemanticsAction(SemanticsActions.SetSelection) { it(caret, caret, false) }
        assertDraft(pane, text, caret)
    }

    private fun assertDraft(
        pane: String,
        text: String,
        caret: Int,
    ) {
        composer(pane).assertTextEquals(text)
        assertEquals(
            TextRange(caret),
            composer(pane).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
    }

    private fun awaitComposer(pane: String) {
        composeRule.waitUntil("composer in $pane", 30_000) {
            composeRule
                .onAllNodes(hasTestTag(COMPOSER_TEST_TAG) and hasAnyAncestor(hasTestTag(pane)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil("production tag $tag", 30_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Optional review artifacts contain only this test's fictional fixtures, never device content. */
    private fun capture(name: String) {
        val directory = System.getProperty("skein.workspace.captureDir")?.let(::File) ?: return
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$name.png")
        check(!target.exists()) { "Review captures must not overwrite earlier evidence" }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun assertPrivateBundle(scenario: ActivityScenario<MainActivity>) {
        val saved = Bundle()
        scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            val bytes = parcel.marshall()
            assertTrue("workspace saved state exceeds 64 KiB", bytes.size < 64 * 1024)
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                val text = String(bytes, charset)
                for (draft in listOf(LEFT_DRAFT, RIGHT_DRAFT)) {
                    assertFalse("draft content reached the OS saved-state Parcel", text.contains(draft))
                }
            }
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val LEFT_NOTE = "left-workspace-note-5049"
        const val RIGHT_NOTE = "right-workspace-note-293d"
        const val LEFT_DRAFT = "left-workspace-private-draft-f5386"
        const val RIGHT_DRAFT = "right-workspace-private-draft-18a7c"
        const val PRIMARY_ROOT = "00000000-0000-0000-0000-000000000001"
        const val SECONDARY_ROOT = "00000000-0000-0000-0000-000000000002"
    }
}
