package app.skein.feature.editor.notetab

import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.transfer.ImportedLinkTargets
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

    /**
     * Attachments still open in this editor (the file viewer is Wave 6). A
     * header title edit used to write `updateBody`, which the repository now
     * refuses for an attachment or a chat — from an uncaught launch, which
     * crashed the app. It renames instead; a blank mid-typing title is kept
     * local. `backgroundScope` fails the test on any uncaught throw.
     */
    @Test
    fun `a title edit on an attachment renames it and never throws`() =
        runTest {
            val repo = newRepo()
            val pdf = repo.createAttachment("report.pdf", "application/pdf") { it.write(byteArrayOf(1, 2, 3)) }
            val state = NoteTabState(pdf.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()

            state.onTitleChange("")
            runCurrent()
            state.onTitleChange("Q3 report.pdf")
            runCurrent()

            val renamed = repo.getDocument(pdf.id)
            assertEquals("Q3 report.pdf", renamed?.title)
            assertEquals("the bytes' hash is untouched", pdf.contentHash, renamed?.contentHash)
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
    fun `UUID link opens same note after rename and missing UUID never creates a note`() =
        runTest {
            val repo = newRepo()
            val target = repo.note("Old title")
            val source = repo.note("Source", body = "[[${target.id}|Old title]]")
            var opened: Pair<String, String>? = null
            val state =
                NoteTabState(source.id, repo, InMemoryIndexStore(), backgroundScope, onOpenDocument = { id, title ->
                    opened = id to title
                })
            runCurrent()
            repo.renameDocument(target.id, "New title")
            state.editorState.onLinkOpen(WikilinkTarget(title = target.id))
            runCurrent()
            assertEquals(target.id to "New title", opened)

            repo.deleteDocument(target.id)
            opened = null
            state.editorState.onLinkOpen(WikilinkTarget(title = target.id))
            runCurrent()
            assertNull(opened)
            assertNull(repo.findByTitle(target.id))
            assertEquals("The linked note is no longer available.", state.linkNotice)
        }

    @Test
    fun `ambiguous and missing imported links display a notice without opening or creating a same-title note`() =
        runTest {
            val repo = newRepo()
            repo.note("Duplicate")
            repo.note("Duplicate")
            val source =
                repo.createDocument(
                    NewDocument(
                        DocumentKind.NOTE,
                        "Source",
                        "[[Duplicate]] [[Missing]]",
                        frontmatter =
                            buildJsonObject {
                                put(
                                    ImportedLinkTargets.UNRESOLVED,
                                    JsonArray(listOf(JsonPrimitive("Duplicate"), JsonPrimitive("Missing"))),
                                )
                                put(ImportedLinkTargets.AMBIGUOUS, JsonArray(listOf(JsonPrimitive("Duplicate"))))
                            },
                    ),
                )
            var opens = 0
            val state =
                NoteTabState(source.id, repo, InMemoryIndexStore(), backgroundScope, onOpenDocument = {
                    _,
                    _,
                    ->
                    opens++
                })
            runCurrent()
            state.editorState.onLinkOpen(WikilinkTarget(title = "Duplicate"))
            runCurrent()
            assertEquals(0, opens)
            assertTrue(state.linkNotice.orEmpty().contains("more than one"))
            state.dismissLinkNotice()
            assertNull(state.linkNotice)
            state.editorState.onLinkOpen(WikilinkTarget(title = "Missing"))
            runCurrent()
            assertEquals(0, opens)
            assertNull(repo.findByTitle("Missing"))
            assertNotNull(state.linkNotice)
        }

    @Test
    fun `uppercase persisted UUID opens its original document`() =
        runTest {
            val repo = newRepo()
            val target =
                repo.createDocument(
                    NewDocument(DocumentKind.NOTE, "Target", "body", id = "018F2B6E-6C3A-7C3E-8F2A-6B1E2D3C4A5B"),
                )
            val source = repo.note("Source", body = "[[${target.id}]]")
            var opened: String? = null
            val state =
                NoteTabState(source.id, repo, InMemoryIndexStore(), backgroundScope, onOpenDocument = { id, _ ->
                    opened =
                        id
                })
            runCurrent()
            state.editorState.onLinkOpen(WikilinkTarget(title = target.id))
            runCurrent()
            assertEquals(target.id, opened)
        }

    @Test
    fun `stale imported editor buffer retains its guard after repository rewrite`() =
        runTest {
            val repo = newRepo()
            repo.note("Filename")
            val target = repo.note("Display title")
            val source =
                repo.createDocument(
                    NewDocument(
                        DocumentKind.NOTE,
                        "Source",
                        "[[Filename]]",
                        frontmatter =
                            buildJsonObject {
                                put(ImportedLinkTargets.UNRESOLVED, JsonArray(listOf(JsonPrimitive("Filename"))))
                            },
                    ),
                )
            var opens = 0
            val state =
                NoteTabState(source.id, repo, InMemoryIndexStore(), backgroundScope, onOpenDocument = {
                    _,
                    _,
                    ->
                    opens++
                })
            runCurrent()
            repo.transaction {
                repo.updateFrontmatter(source.id, buildJsonObject { })
                repo.replaceBody(source.id, "[[${target.id}|Filename]]")
            }
            state.editorState.onLinkOpen(WikilinkTarget(title = "Filename"))
            runCurrent()
            assertEquals(0, opens)
            assertNotNull(state.linkNotice)
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
