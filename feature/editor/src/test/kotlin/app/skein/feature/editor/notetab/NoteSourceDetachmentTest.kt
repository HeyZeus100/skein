package app.skein.feature.editor.notetab

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.core.vault.codec.Frontmatter
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteSourceDetachmentTest {
    private val vault = InMemoryVaultRepository()
    private val index = InMemoryIndexStore()
    private val registry = NoteDeletionRegistry()
    private var source = ""

    private suspend fun output(): Document {
        source = vault.createAttachment("Disposable source", "text/plain") { it.write(byteArrayOf(1)) }.id
        return vault.createDocument(
            NewDocument(
                DocumentKind.AIOUT,
                "Saved output",
                "Original body",
                frontmatter =
                    buildJsonObject {
                        put("source", source)
                        put("custom", "original")
                    },
            ),
        )
    }

    private fun TestScope.state(
        document: Document,
        repository: VaultRepository = vault,
    ): NoteTabState = NoteTabState(document.id, repository, index, backgroundScope, noteDeletions = registry)

    private suspend fun detach(document: Document) {
        val current = checkNotNull(vault.getDocument(document.id))
        vault.updateFrontmatter(document.id, JsonObject(current.frontmatter - "source"))
    }

    private fun body(state: NoteTabState) = Frontmatter.parse(state.editorState.source).second

    @Test fun `committed reload preserves dirty body title selection and user metadata while merging current fields`() =
        runTest {
            val output = output()
            val state = state(output)
            runCurrent()
            val local =
                Frontmatter.render(
                    JsonObject(output.frontmatter + ("custom" to JsonPrimitive("dirty"))),
                    "Dirty body",
                )
            state.editorState.onValueChange(TextFieldValue(local, TextRange(local.length - 4, local.length)))
            state.onTitleChange("Dirty title")
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            vault.updateFrontmatter(
                output.id,
                JsonObject(
                    checkNotNull(vault.getDocument(output.id)).frontmatter - "source" +
                        ("server" to JsonPrimitive("new")),
                ),
            )
            pending.commit()
            assertFalse(registry.isDeleting(output.id))
            assertTrue(pending.reload())
            runCurrent()
            val saved = checkNotNull(vault.getDocument(output.id))
            assertEquals("Dirty body", saved.bodyMd)
            assertEquals("Dirty title", saved.title)
            assertEquals(JsonPrimitive("dirty"), saved.frontmatter["custom"])
            assertEquals(JsonPrimitive("new"), saved.frontmatter["server"])
            assertNull(saved.frontmatter["source"])
            assertEquals(
                TextRange(state.editorState.source.length - 4, state.editorState.source.length),
                state.editorState.value.selection,
            )
            assertEquals("Dirty body", body(state))
        }

    @Test fun `unchanged buffer takes current body and title without stale overwrite`() =
        runTest {
            val output = output()
            val state = state(output)
            runCurrent()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            vault.replaceBody(output.id, "Current body from import")
            vault.renameDocument(output.id, "Current title")
            pending.commit()
            assertTrue(pending.reload())
            runCurrent()
            assertEquals("Current body from import", body(state))
            assertEquals("Current title", state.title)
            assertEquals("Current body from import", vault.getDocument(output.id)?.bodyMd)
        }

    @Test fun `old source in a late edit cannot reattach the removed file`() =
        runTest {
            val output = output()
            val state = state(output)
            runCurrent()
            val old = state.editorState.source
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            state.editorState.onValueChange(TextFieldValue(old.replace("Original body", "Late body")))
            state.flush()
            assertEquals("Late body", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
            assertNull(Frontmatter.parse(state.editorState.source).first["source"])
        }

    @Test fun `pending autosave before commit cannot later overwrite the refreshed metadata`() =
        runTest {
            val output = output()
            val state = state(output)
            runCurrent()
            state.editorState.onValueChange(
                TextFieldValue(state.editorState.source.replace("Original body", "Pending body")),
            )
            runCurrent()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("Pending body", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
        }

    @Test fun `reservation drains already admitted write before detachment`() =
        runTest {
            val output = output()
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
            val state = state(output, writer)
            runCurrent()
            state.editorState.onValueChange(TextFieldValue("In flight"))
            val flushing = async { state.flush() }
            entered.await()
            val pending = registry.beginSourceDetachment(output.id, source)
            val drained = async { pending.awaitIdle() }
            runCurrent()
            assertFalse(drained.isCompleted)
            release.complete(Unit)
            flushing.await()
            drained.await()
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            runCurrent()
            assertEquals("In flight", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
        }

    @Test fun `a pane registered during reload is included and receives current metadata`() =
        runTest {
            val output = output()
            var inject = false
            var late: NoteTabState? = null
            val reader =
                object : VaultRepository by vault {
                    override suspend fun getDocument(id: String): Document? {
                        if (inject) {
                            inject = false
                            late = state(output)
                        }
                        return vault.getDocument(id)
                    }
                }
            state(output, reader)
            runCurrent()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            inject = true
            assertTrue(pending.reload())
            runCurrent()
            val newState = checkNotNull(late)
            newState.editorState.onValueChange(TextFieldValue("Late pane edit"))
            newState.flush()
            assertEquals("Late pane edit", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
        }

    @Test fun `new pane after completed reload cannot reintroduce old source`() =
        runTest {
            val output = output()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            val state = state(output)
            runCurrent()
            state.editorState.onValueChange(TextFieldValue(Frontmatter.render(output.frontmatter, "New pane body")))
            state.flush()
            assertEquals("New pane body", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
        }

    @Test fun `new pane during another detachment retains earlier source tombstones`() =
        runTest {
            val output = output()
            val first = registry.beginSourceDetachment(output.id, source)
            first.awaitIdle()
            detach(output)
            first.commit()
            assertTrue(first.reload())
            val secondSource = vault.createAttachment("Second source", "text/plain") { it.write(byteArrayOf(2)) }.id
            vault.updateFrontmatter(
                output.id,
                JsonObject(output.frontmatter + ("source" to JsonPrimitive(secondSource))),
            )
            val second = registry.beginSourceDetachment(output.id, secondSource)
            val state = state(output)
            runCurrent()
            second.awaitIdle()
            detach(output)
            second.commit()
            assertTrue(second.reload())
            state.editorState.onValueChange(
                TextFieldValue(Frontmatter.render(output.frontmatter, "Old first source edit")),
            )
            state.flush()
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
            assertEquals("Old first source edit", vault.getDocument(output.id)?.bodyMd)
        }

    @Test fun `new pane reload has its own bound and leaves timed out writer paused`() =
        runTest {
            val output = output()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            var reads = 0
            val reader =
                object : VaultRepository by vault {
                    override suspend fun getDocument(id: String): Document? {
                        if (++reads > 1) CompletableDeferred<Unit>().await()
                        return vault.getDocument(id)
                    }
                }
            val state = state(output, reader)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertNotNull(state.loadError)
            assertFalse(state.loading)
            state.editorState.onValueChange(TextFieldValue("Rejected after timeout"))
            state.flush()
            assertEquals("Original body", vault.getDocument(output.id)?.bodyMd)
        }

    @Test fun `lock after commit preserves precommit dirty body title and metadata durably`() =
        runTest {
            val output = output()
            val stateScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val state = NoteTabState(output.id, vault, index, stateScope, noteDeletions = registry)
            runCurrent()
            state.editorState.onValueChange(
                TextFieldValue(
                    Frontmatter.render(
                        JsonObject(output.frontmatter + ("custom" to JsonPrimitive("dirty"))),
                        "Durable dirty body",
                    ),
                ),
            )
            state.onTitleChange("Durable dirty title")
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            assertTrue(pending.isReadyForCommit())
            assertEquals("Durable dirty body", vault.getDocument(output.id)?.bodyMd)
            assertEquals("Durable dirty title", vault.getDocument(output.id)?.title)
            detach(output)
            pending.commit()
            stateScope.cancel()
            assertFalse(pending.reload())
            state.onTitleChange("Rejected late title")
            state.editorState.onValueChange(
                TextFieldValue(Frontmatter.render(output.frontmatter, "Rejected late body")),
            )
            state.flush()
            val saved = checkNotNull(vault.getDocument(output.id))
            assertEquals("Durable dirty body", saved.bodyMd)
            assertEquals("Durable dirty title", saved.title)
            assertEquals(JsonPrimitive("dirty"), saved.frontmatter["custom"])
            assertNull(saved.frontmatter["source"])
        }

    @Test fun `late dirty registrant prevents commit until its original content is durably flushed`() =
        runTest {
            val output = output()
            val late = NoteTabState(output.id, vault, index, backgroundScope)
            runCurrent()
            late.editorState.onValueChange(TextFieldValue("Late dirty body"))
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            registry.register(late)
            assertFalse(pending.isReadyForCommit())
            pending.awaitIdle()
            assertTrue(pending.isReadyForCommit())
            assertEquals("Late dirty body", vault.getDocument(output.id)?.bodyMd)
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            assertEquals("Late dirty body", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
        }

    @Test fun `failed precommit save refuses readiness and retains the source and dirty buffer`() =
        runTest {
            val output = output()
            val writer =
                object : VaultRepository by vault {
                    override suspend fun replaceBody(
                        id: String,
                        bodyMd: String,
                    ): Document = error("synthetic disk full")
                }
            val state = state(output, writer)
            runCurrent()
            state.editorState.onValueChange(TextFieldValue("Retained dirty body"))
            val pending = registry.beginSourceDetachment(output.id, source)
            assertTrue(runCatching { pending.awaitIdle() }.isFailure)
            assertFalse(pending.isReadyForCommit())
            assertEquals("Retained dirty body", body(state))
            assertEquals("Original body", vault.getDocument(output.id)?.bodyMd)
            assertEquals(JsonPrimitive(source), vault.getDocument(output.id)?.frontmatter?.get("source"))
            pending.rollback()
        }

    private suspend fun TestScope.refuseConflictingDrafts(change: (NoteTabState, String) -> Unit) {
        val output = output()
        val first = state(output)
        val second = state(output)
        runCurrent()
        change(first, "First owner draft")
        change(second, "Second owner draft")
        val beforeFirst = first.editorState.value
        val beforeSecond = second.editorState.value
        val beforeFirstTitle = first.title
        val beforeSecondTitle = second.title
        val pending = registry.beginSourceDetachment(output.id, source)
        assertTrue(runCatching { pending.awaitIdle() }.isFailure)
        assertFalse(pending.isReadyForCommit())
        assertEquals(output, vault.getDocument(output.id))
        assertEquals(beforeFirst, first.editorState.value)
        assertEquals(beforeSecond, second.editorState.value)
        assertEquals(beforeFirstTitle, first.title)
        assertEquals(beforeSecondTitle, second.title)
        pending.rollback()
        assertEquals(output, vault.getDocument(output.id))
        assertEquals(beforeFirst, first.editorState.value)
        assertEquals(beforeSecond, second.editorState.value)
    }

    @Test fun `two dirty body panes refuse before forced persistence or deletion`() =
        runTest {
            refuseConflictingDrafts { state, value -> state.editorState.onValueChange(TextFieldValue(value)) }
        }

    @Test fun `two dirty title panes refuse before forced persistence or deletion`() =
        runTest {
            refuseConflictingDrafts { state, value -> state.onTitleChange(value) }
        }

    @Test fun `two dirty metadata panes refuse before forced persistence or deletion`() =
        runTest {
            refuseConflictingDrafts { state, value ->
                val (frontmatter, body) = Frontmatter.parse(state.editorState.source)
                state.editorState.onValueChange(
                    TextFieldValue(
                        Frontmatter.render(JsonObject(frontmatter + ("custom" to JsonPrimitive(value))), body),
                    ),
                )
            }
        }

    @Test fun `abandoned composition unregisters its constructor enrollment on scope completion`() =
        runTest {
            val output = output()
            val stateScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            NoteTabState(output.id, vault, index, stateScope, noteDeletions = registry)
            // Simulate abandoned composition before DisposableEffect was ever installed.
            stateScope.cancel()
            runCurrent()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            assertTrue(pending.isReadyForCommit())
            detach(output)
            pending.commit()
            assertTrue(pending.reload())
            assertEquals(output.bodyMd, vault.getDocument(output.id)?.bodyMd)
        }

    @Test fun `pane enrolled after final readiness cannot admit edits before commit and lock`() =
        runTest {
            val output = output()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            assertTrue(pending.isReadyForCommit())
            // No further readiness check: this is exactly the final-check to COMMIT gap.
            val stateScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val late = NoteTabState(output.id, vault, index, stateScope, noteDeletions = registry)
            late.onTitleChange("Must never become an admitted title draft")
            late.editorState.onValueChange(TextFieldValue("Must never become an admitted body draft"))
            assertEquals("", late.title)
            assertEquals("", late.editorState.source)
            detach(output)
            pending.commit()
            stateScope.cancel()
            assertFalse(pending.reload())
            late.flush()
            val saved = checkNotNull(vault.getDocument(output.id))
            assertEquals(output.title, saved.title)
            assertEquals(output.bodyMd, saved.bodyMd)
            assertNull(saved.frontmatter["source"])
        }

    @Test fun `initial stale read must finish before reload can resume an editor`() =
        runTest {
            val output = output()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val reader =
                object : VaultRepository by vault {
                    override suspend fun getDocument(id: String): Document? {
                        if (first) {
                            first = false
                            val captured = vault.getDocument(id)
                            entered.complete(Unit)
                            release.await()
                            return captured
                        }
                        return vault.getDocument(id)
                    }
                }
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            val state = state(output, reader)
            entered.await()
            detach(output)
            pending.commit()
            val reloading = async { pending.reload() }
            runCurrent()
            assertFalse(reloading.isCompleted)
            release.complete(Unit)
            assertTrue(reloading.await())
            assertNull(Frontmatter.parse(state.editorState.source).first["source"])
            state.editorState.onValueChange(TextFieldValue("After stale load"))
            state.flush()
            assertEquals("After stale load", vault.getDocument(output.id)?.bodyMd)
        }

    @Test fun `rollback preserves original source and pending body title and metadata`() =
        runTest {
            val output = output()
            val state = state(output)
            runCurrent()
            state.editorState.onValueChange(
                TextFieldValue(state.editorState.source.replace("Original body", "Retained")),
            )
            state.onTitleChange("Retained title")
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            advanceTimeBy(1_000)
            runCurrent()
            pending.rollback()
            assertEquals("Retained", vault.getDocument(output.id)?.bodyMd)
            assertEquals("Retained title", vault.getDocument(output.id)?.title)
            assertEquals(JsonPrimitive(source), vault.getDocument(output.id)?.frontmatter?.get("source"))
            assertEquals(JsonPrimitive("original"), vault.getDocument(output.id)?.frontmatter?.get("custom"))
        }

    @Test fun `failed reload keeps dirty buffer and writer paused until a fresh pane can reload`() =
        runTest {
            val output = output()
            var fail = false
            val reader =
                object : VaultRepository by vault {
                    override suspend fun getDocument(id: String): Document? {
                        if (fail) error("closed synthetic repository")
                        return vault.getDocument(id)
                    }
                }
            val state = state(output, reader)
            runCurrent()
            state.editorState.onValueChange(TextFieldValue("Retain dirty buffer"))
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            fail = true
            assertFalse(pending.reload())
            state.editorState.onValueChange(TextFieldValue("Rejected edit"))
            state.flush()
            assertEquals("Retain dirty buffer", body(state))
            assertEquals("Retain dirty buffer", vault.getDocument(output.id)?.bodyMd)
            assertNotNull(state.loadError)
            val fresh = state(output)
            runCurrent()
            fresh.editorState.onValueChange(TextFieldValue("Fresh edit"))
            fresh.flush()
            assertEquals("Fresh edit", vault.getDocument(output.id)?.bodyMd)
        }

    @Test fun `lock during suspended reload cannot reopen old session writer`() =
        runTest {
            val output = output()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var hold = false
            val reader =
                object : VaultRepository by vault {
                    override suspend fun getDocument(id: String): Document? {
                        if (hold) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return vault.getDocument(id)
                    }
                }
            val stateScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val state = NoteTabState(output.id, reader, index, stateScope, noteDeletions = registry)
            runCurrent()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            hold = true
            val reloading = async { pending.reload() }
            entered.await()
            stateScope.cancel()
            release.complete(Unit)
            assertFalse(reloading.await())
            state.editorState.onValueChange(TextFieldValue("Must not save"))
            state.flush()
            assertEquals("Original body", vault.getDocument(output.id)?.bodyMd)
            assertNull(vault.getDocument(output.id)?.frontmatter?.get("source"))
        }

    @Test fun `bounded reload timeout does not mark survivor deleted or admit stale writers`() =
        runTest {
            val output = output()
            var hold = false
            val reader =
                object : VaultRepository by vault {
                    override suspend fun getDocument(id: String): Document? {
                        if (hold) CompletableDeferred<Unit>().await()
                        return vault.getDocument(id)
                    }
                }
            val state = state(output, reader)
            runCurrent()
            val pending = registry.beginSourceDetachment(output.id, source)
            pending.awaitIdle()
            detach(output)
            pending.commit()
            hold = true
            assertNull(withTimeoutOrNull(50) { pending.reload() })
            assertFalse(registry.isDeleting(output.id))
            state.onTitleChange("Rejected rename")
            state.editorState.onValueChange(TextFieldValue("Rejected body"))
            state.flush()
            assertEquals("Saved output", vault.getDocument(output.id)?.title)
            assertEquals("Original body", vault.getDocument(output.id)?.bodyMd)
        }
}
