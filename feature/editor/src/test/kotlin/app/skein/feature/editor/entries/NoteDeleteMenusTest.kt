package app.skein.feature.editor.entries

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.navigation.Destination
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.timeline.TimelineTestTags
import app.skein.testing.fakeVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDeleteMenusTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `only independent notes expose Delete and menu selection only requests confirmation`() {
        lateinit var note: Document
        lateinit var derived: Document
        lateinit var file: Document
        val vault =
            fakeVault {
                note = note("Construction RFI", "Demo fixture")
                file = attachment("report.pdf", "application/pdf", byteArrayOf(1))
                derived =
                    note(
                        "Extracted report",
                        "Text",
                        frontmatter = JsonObject(mapOf("source" to JsonPrimitive(file.id))),
                    )
            }
        var requested: String? = null
        lateinit var shell: SkeinShellState
        composeRule.setContent {
            SkeinTheme {
                KnowledgeHost(vault, COMPACT, { shell = it }, onDelete = { requested = it })
            }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } }
        composeRule.onNodeWithTag(TimelineTestTags.entryMenu(file.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(TimelineTestTags.entryMenu(derived.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(TimelineTestTags.entryMenu(note.id)).performClick()
        composeRule.onNodeWithText("Delete…").performClick()
        composeRule.runOnIdle { assertEquals(note.id, requested) }
        assertNotNull(runBlocking { vault.getDocument(note.id) })
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(note.id)).performClick()
        composeRule.onNodeWithTag(NoteTabTestTags.ACTIONS_BUTTON).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Share as text").assertIsDisplayed()
        composeRule.onNodeWithTag(NoteTabTestTags.DELETE_ACTION).performClick()
        composeRule.runOnIdle { assertEquals(note.id, requested) }
        assertNotNull(runBlocking { vault.getDocument(note.id) })
    }

    @Test
    fun `independent AI outputs and raw ids are supported while malformed sources stay gated`() =
        runBlocking {
            val vault = fakeVault { }
            val note =
                vault.createDocument(
                    NewDocument(DocumentKind.NOTE, "Raw note", "Text", id = "raw-id-from-import"),
                )
            assertTrue(note.canDeleteIndependentNote())
            assertTrue(note.copy(kind = DocumentKind.AIOUT).canDeleteIndependentNote())
            assertFalse(note.copy(kind = DocumentKind.CHAT).canDeleteIndependentNote())
            assertFalse(note.copy(kind = DocumentKind.ATTACHMENT).canDeleteIndependentNote())
            assertFalse(
                note
                    .copy(
                        frontmatter = JsonObject(mapOf("source" to JsonObject(emptyMap()))),
                    ).canDeleteIndependentNote(),
            )
            assertTrue(
                note.copy(frontmatter = JsonObject(mapOf("source" to JsonPrimitive(" ")))).canDeleteIndependentNote(),
            )
        }
}
