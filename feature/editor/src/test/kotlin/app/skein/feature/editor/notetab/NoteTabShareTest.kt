package app.skein.feature.editor.notetab

import android.content.Intent
import androidx.compose.ui.text.input.TextFieldValue
import app.skein.feature.editor.share.SaveAsFormat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.ByteArrayOutputStream

/**
 * bd `skein-fay` (plan `E6.I16`): [NoteTabState]'s share-source surface —
 * [NoteTabState.shareAsTextIntent], [NoteTabState.saveAsDocumentIntent], and
 * the [NoteTabState.writeSaveAs] `OutputStream` round trip. Drives the state
 * holder directly (Compose-off), same shape as `NoteTabStateTest`; split
 * into its own file because building the `Intent`s these methods return
 * needs Robolectric (the compile `android.jar` stub throws on every method
 * body otherwise) while the rest of `NoteTabStateTest` does not.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteTabShareTest {
    @Test
    fun `shareAsTextIntent carries the current title and the body split out of the live editor buffer`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.note("My Note", body = "hello world")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            val intent = state.shareAsTextIntent()

            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("My Note", intent.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals("hello world", intent.getStringExtra(Intent.EXTRA_TEXT))
        }

    @Test
    fun `shareAsTextIntent reflects an unsaved in-flight edit, not the last-flushed vault row`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.note("My Note", body = "original")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            state.editorState.onValueChange(TextFieldValue("edited body"))

            val intent = state.shareAsTextIntent()

            assertEquals("edited body", intent.getStringExtra(Intent.EXTRA_TEXT))
        }

    @Test
    fun `saveAsDocumentIntent builds an ACTION_CREATE_DOCUMENT intent titled after the current note title`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.note("My Note", body = "hello world")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            val intent = state.saveAsDocumentIntent(SaveAsFormat.MARKDOWN)

            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals("text/markdown", intent.type)
            assertEquals("My Note.md", intent.getStringExtra(Intent.EXTRA_TITLE))
        }

    @Test
    fun `writeSaveAs MARKDOWN streams the flushed note body into the given OutputStream`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.note("My Note", body = "hello world")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            val out = ByteArrayOutputStream()
            state.writeSaveAs(SaveAsFormat.MARKDOWN, out)

            val text = out.toString(Charsets.UTF_8.name())
            assertTrue("expected exported markdown to contain the body, was: $text", text.contains("hello world"))
        }

    @Test
    fun `writeSaveAs flushes a pending edit before streaming so nothing typed is silently dropped`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.note("My Note", body = "original")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            state.editorState.onValueChange(TextFieldValue("freshly typed"))

            val out = ByteArrayOutputStream()
            state.writeSaveAs(SaveAsFormat.MARKDOWN, out)

            val text = out.toString(Charsets.UTF_8.name())
            assertTrue(text.contains("freshly typed"))
        }

    @Test
    fun `writeSaveAs DOCX produces a non-empty OOXML package`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.note("My Note", body = "hello world")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            val out = ByteArrayOutputStream()
            state.writeSaveAs(SaveAsFormat.DOCX, out)

            assertTrue(out.size() > 0)
        }

    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String = "body of $title",
    ): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))
}
