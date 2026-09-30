package app.skein.feature.editor.notetab

import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.feature.editor.entries.DraftNoteRepository
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteDeletionRegistryTest {
    @Test
    fun `commit discards dirty body and title and blocks dispose and lock flushes`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val note = vault.createDocument(NewDocument(DocumentKind.NOTE, "Keep title", "Original"))
            var writes = 0
            val writer =
                object : VaultRepository by vault {
                    override suspend fun replaceBody(
                        id: String,
                        bodyMd: String,
                    ): Document {
                        writes++
                        return vault.replaceBody(id, bodyMd)
                    }
                }
            val state = NoteTabState(note.id, writer, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            val registry = NoteDeletionRegistry()
            val registration = registry.register(state)
            state.editorState.onValueChange(TextFieldValue("Do not save"))
            state.onTitleChange("Pending rename")
            val pending = registry.beginDelete(note.id)
            pending.awaitIdle()
            vault.deleteDocument(note.id)
            pending.commit()
            pending.commit()
            registration.dispose()
            state.flush()
            state.onTitleChange("Late rename")
            state.editorState.onValueChange(TextFieldValue("Late text"))
            advanceTimeBy(1_000)
            runCurrent()
            assertNull(vault.getDocument(note.id))
            assertEquals(0, writes)
            assertEquals("Pending rename", state.title)
        }

    @Test
    fun `rollback restores pending title and text even after the debounce ran while paused`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val note = vault.createDocument(NewDocument(DocumentKind.NOTE, "Before", "Original"))
            val state = NoteTabState(note.id, vault, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            val registry = NoteDeletionRegistry()
            registry.register(state)
            state.editorState.onValueChange(TextFieldValue("Retained dirty body"))
            state.onTitleChange("Retained dirty title")
            val pending = registry.beginDelete(note.id)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("Original", vault.getDocument(note.id)?.bodyMd)
            assertEquals("Before", vault.getDocument(note.id)?.title)
            assertTrue(registry.isDeleting(note.id))
            pending.rollback()
            assertFalse(registry.isDeleting(note.id))
            assertEquals("Retained dirty body", vault.getDocument(note.id)?.bodyMd)
            assertEquals("Retained dirty title", vault.getDocument(note.id)?.title)
            state.editorState.onValueChange(TextFieldValue("Editable again"))
            state.flush()
            assertEquals("Editable again", vault.getDocument(note.id)?.bodyMd)
        }

    @Test
    fun `deletion waits for a previously admitted save before storage can be removed`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val note = vault.createDocument(NewDocument(DocumentKind.NOTE, "Note", "Original"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer =
                object : VaultRepository by vault {
                    override suspend fun replaceBody(
                        id: String,
                        bodyMd: String,
                    ): Document {
                        entered.complete(Unit)
                        release.await()
                        return vault.replaceBody(id, bodyMd)
                    }
                }
            val state = NoteTabState(note.id, writer, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            val registry = NoteDeletionRegistry()
            registry.register(state)
            state.editorState.onValueChange(TextFieldValue("In flight"))
            val flushing = async { state.flush() }
            entered.await()
            val pending = registry.beginDelete(note.id)
            val drained = async { pending.awaitIdle() }
            runCurrent()
            assertFalse(drained.isCompleted)
            release.complete(Unit)
            flushing.await()
            drained.await()
            vault.deleteDocument(note.id)
            pending.commit()
            state.flush()
            assertNull(vault.getDocument(note.id))
        }

    @Test
    fun `a new draft that was saved and deleted cannot be resurrected by late editor work`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val id = "raw-imported-note-id"
            val draft = DraftNoteRepository(vault, id)
            val state = NoteTabState(id, draft, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            state.editorState.onValueChange(TextFieldValue("Created note"))
            state.flush()
            val registry = NoteDeletionRegistry()
            registry.register(state)
            state.editorState.onValueChange(TextFieldValue("Pending edit"))
            val pending = registry.beginDelete(id)
            pending.awaitIdle()
            vault.deleteDocument(id)
            pending.commit()
            state.flush()
            advanceTimeBy(1_000)
            runCurrent()
            assertNull(vault.getDocument(id))
            assertNull(draft.getDocument(id))
            val failure = runCatching { draft.replaceBody(id, "Late external write") }.exceptionOrNull()
            assertTrue(failure is NoSuchElementException)
            assertNull(vault.getDocument(id))
        }

    @Test
    fun `a newly registered draft editor after commit stays blocked before navigation prunes it`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val id = "saved-draft-id"
            vault.createDocument(NewDocument(DocumentKind.NOTE, "Saved draft", "Original", id = id))
            val registry = NoteDeletionRegistry()
            val pending = registry.beginDelete(id)
            pending.awaitIdle()
            vault.deleteDocument(id)
            pending.commit()
            val recreated = NoteTabState(id, DraftNoteRepository(vault, id), InMemoryIndexStore(), backgroundScope)
            registry.register(recreated)
            runCurrent()
            recreated.onTitleChange("Do not recreate")
            recreated.editorState.onValueChange(TextFieldValue("Do not recreate"))
            recreated.flush()
            advanceTimeBy(1_000)
            runCurrent()
            assertNull(vault.getDocument(id))
            assertTrue(registry.isDeleting(id))
        }

    @Test
    fun `an editor registered while rollback flushes is admitted again`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val note = vault.createDocument(NewDocument(DocumentKind.NOTE, "Note", "Original"))
            val registry = NoteDeletionRegistry()
            val lateState = NoteTabState(note.id, vault, InMemoryIndexStore(), backgroundScope)
            val writer =
                object : VaultRepository by vault {
                    override suspend fun replaceBody(
                        id: String,
                        bodyMd: String,
                    ): Document {
                        registry.register(lateState)
                        lateState.onTitleChange("Editable after rollback")
                        return vault.replaceBody(id, bodyMd)
                    }
                }
            val state = NoteTabState(note.id, writer, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            registry.register(state)
            state.editorState.onValueChange(TextFieldValue("Dirty before deletion"))
            val pending = registry.beginDelete(note.id)
            pending.awaitIdle()
            pending.rollback()
            runCurrent()
            assertEquals("Editable after rollback", lateState.title)
            assertEquals("Editable after rollback", vault.getDocument(note.id)?.title)
            assertEquals("Dirty before deletion", vault.getDocument(note.id)?.bodyMd)
        }

    @Test
    fun `opening a confirmation without beginning deletion leaves autosave working`() =
        runTest {
            val vault = InMemoryVaultRepository()
            val note = vault.createDocument(NewDocument(DocumentKind.NOTE, "Note", "Original"))
            val state = NoteTabState(note.id, vault, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            val registry = NoteDeletionRegistry()
            registry.register(state)
            state.editorState.onValueChange(TextFieldValue("Edit while confirmation was canceled"))
            state.flush()
            assertEquals("Edit while confirmation was canceled", vault.getDocument(note.id)?.bodyMd)
            assertFalse(registry.isDeleting(note.id))
        }
}
