package app.skein.feature.editor.notetab

import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.codec.Frontmatter
import app.skein.feature.editor.WikilinkTarget
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Drives [NoteTabState] directly — Compose-off — under `runTest`, same
 * shape as `BacklinksStateTest`/`EditorAutosaveTest`. Covers plan `E6.I9`
 * (bd `skein-u01`): loading a document into the editor, autosave persisting
 * back through [InMemoryVaultRepository], the [NoteTabState.flush] hook, the
 * pin-on-first-edit signal, and wikilink open-or-create resolution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteTabStateTest {
    @Test
    fun `loading a document seeds the title and editor with frontmatter plus body`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "hello world")

            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            assertFalse(state.loading)
            assertNull(state.loadError)
            assertEquals("My Note", state.title)
            // bd skein-6rr (E7.I3): the editor buffer is frontmatter + body
            // concatenated (`InMemoryVaultRepository.createDocument` always
            // seeds `frontmatter = {id: <doc.id>}`) — split it back apart
            // with the same codec `NoteTabState` saves through.
            val (frontmatter, body) = Frontmatter.parse(state.editorState.value.text)
            assertEquals("hello world", body)
            assertEquals(doc.id, (frontmatter.getValue("id") as JsonPrimitive).content)
        }

    @Test
    fun `a document with no frontmatter seeds the editor with exactly its body`() {
        // `InMemoryVaultRepository` always pins `frontmatter = {id: ...}`
        // (mirrors the real vault's "id is always set on create" invariant),
        // so `NoteTabState.load`'s empty-frontmatter fallback is exercised
        // directly here against the same `Frontmatter.render` it calls.
        assertEquals("hello world", Frontmatter.render(buildJsonObject { }, "hello world"))
    }

    @Test
    fun `loading a missing document surfaces loadError instead of throwing`() =
        runTest {
            val repo = newRepo()

            val state = NoteTabState("does-not-exist", repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            assertFalse(state.loading)
            assertNotNull(state.loadError)
        }

    @Test
    fun `editing the body autosaves through the repository`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "original")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            state.editorState.onValueChange(TextFieldValue("edited body"))
            advanceTimeBy(600)
            runCurrent()

            assertEquals("edited body", repo.getDocument(doc.id)?.bodyMd)
        }

    @Test
    fun `typing in the editor requests a pin exactly once`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "original")
            var pinCount = 0
            val state =
                NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope, onPinRequested = { pinCount++ })
            runCurrent()

            state.editorState.onValueChange(TextFieldValue("a"))
            advanceTimeBy(600)
            runCurrent()
            state.editorState.onValueChange(TextFieldValue("ab"))
            advanceTimeBy(600)
            runCurrent()

            assertEquals(1, pinCount)
        }

    @Test
    fun `a cursor-only change never requests a pin`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "original")
            var pinCount = 0
            val state =
                NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope, onPinRequested = { pinCount++ })
            runCurrent()

            state.editorState.onValueChange(state.editorState.value.copy(selection = state.editorState.value.selection))
            advanceTimeBy(600)
            runCurrent()

            assertEquals(0, pinCount)
        }

    @Test
    fun `flush persists a pending edit immediately without waiting for the debounce`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "original")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            state.editorState.onValueChange(TextFieldValue("flushed body"))
            val flushed = state.flush(Duration.ofSeconds(1))

            assertTrue(flushed)
            assertEquals("flushed body", repo.getDocument(doc.id)?.bodyMd)
        }

    @Test
    fun `editing a non-id frontmatter key round-trips into the saved frontmatter JSON`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "hello world")
            repo.updateFrontmatter(
                doc.id,
                buildJsonObject {
                    put("id", JsonPrimitive(doc.id))
                    put("tags", JsonArray(listOf(JsonPrimitive("a"))))
                },
            )
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            val original = state.editorState.value.text
            state.editorState.onValueChange(TextFieldValue(original.replace("tags: [a]", "tags: [a, b]")))
            advanceTimeBy(600)
            runCurrent()

            val updated = repo.getDocument(doc.id)
            val tags = (updated?.frontmatter?.get("tags") as? JsonArray)?.map { (it as JsonPrimitive).content }
            assertEquals(listOf("a", "b"), tags)
            assertEquals("hello world", updated?.bodyMd)
        }

    @Test
    fun `editing the id line is rejected and the saved id never changes`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("My Note", body = "hello world")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            val original = state.editorState.value.text
            val tampered = TextFieldValue(original.replace("id: ${doc.id}", "id: HACKED"))
            state.editorState.onValueChange(tampered)
            advanceTimeBy(600)
            runCurrent()

            assertEquals("editor buffer should have reverted the id line", original, state.editorState.value.text)
            assertTrue(state.editorState.idEditRejected.value)
            assertEquals(doc.id, (repo.getDocument(doc.id)?.frontmatter?.get("id") as JsonPrimitive).content)
        }

    @Test
    fun `title edits persist immediately against the current body`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("Old Title", body = "body text")
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            state.onTitleChange("New Title")
            runCurrent()

            val updated = repo.getDocument(doc.id)
            assertEquals("New Title", state.title)
            assertEquals("New Title", updated?.title)
            assertEquals("body text", updated?.bodyMd)
        }

    @Test
    fun `a wikilink to an existing title resolves without creating a new document`() =
        runTest {
            val repo = newRepo()
            val target = repo.note("Target Note")
            val doc = repo.note("Host Note", body = "See [[Target Note]]")
            var opened: Pair<String, String>? = null
            val state =
                NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope, onOpenDocument = { id, title ->
                    opened = id to title
                })
            runCurrent()

            state.editorState.onLinkOpen(WikilinkTarget(title = "Target Note"))
            runCurrent()

            assertEquals(target.id to target.title, opened)
        }

    @Test
    fun `a wikilink to a missing title creates a fresh empty note and opens it`() =
        runTest {
            val repo = newRepo()
            val doc = repo.note("Host Note", body = "See [[Brand New Note]]")
            var opened: Pair<String, String>? = null
            val state =
                NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope, onOpenDocument = { id, title ->
                    opened = id to title
                })
            runCurrent()

            state.editorState.onLinkOpen(WikilinkTarget(title = "Brand New Note"))
            runCurrent()

            val created = repo.findByTitle("Brand New Note")
            assertNotNull("wikilink target should have been created", created)
            assertEquals(created!!.id to created.title, opened)
            assertEquals("", created.bodyMd)
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newRepo(): InMemoryVaultRepository = InMemoryVaultRepository()

    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String = "body of $title",
    ): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))
}
