// The `E0.I11` contract suite: an abstract JUnit 4 test class every
// `VaultRepository` implementation — the `InMemoryVaultRepository` here,
// the SQL-backed `VaultRepositoryImpl` in `E2.I4`, and any future
// implementation — must satisfy on the JVM (or on an instrumented device,
// for the SQL-backed impl).
//
// The six semantic tests below mirror the plan `E0.I11` acceptance criteria
// bullet list exactly.
//
// JUnit 4 (not 5) matches the surrounding codebase (see
// `InferenceEngineContractTest`, every test under `testing/src/test/**`)
// and keeps this suite consumable by downstream modules via
// `testImplementation(project(":testing"))` without pulling the Vintage
// engine.

package app.skein.testing

import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexStore
import app.skein.core.model.IngestReason
import app.skein.core.model.Locator
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.PersonaId
import app.skein.core.model.RevisionHashing
import app.skein.core.model.Role
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultQuiesceTimeoutException
import app.skein.core.model.VaultQuiescedException
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Contract suite for `VaultRepository` (plan §4.2). Concrete subclasses
 * provide the repository under test; each test exercises one AC bullet
 * from `E0.I11`.
 */
public abstract class VaultRepositoryContractTest {
    /**
     * Fresh repository per test method. The `InMemoryVaultRepository` test
     * subclass returns a new instance; the SQL-backed one will open a fresh
     * temp DB.
     */
    protected abstract fun repo(): VaultRepository

    /**
     * Precondition helper (skein-ci54): creates a `personas` row for [id]
     * against the same backing store the most recent [repo] call opened,
     * so a document created afterward with `personaId = id` satisfies
     * `documents.persona_id REFERENCES personas(id)` (`001_initial.sql`)
     * under `PRAGMA foreign_keys = ON` (skein-gg11.10 — the pragma now
     * runs on every connection, matching production). `VaultRepository`
     * itself owns no persona CRUD (that is `PersonaService`'s table), so
     * each concrete subclass seeds the row through whatever backs its own
     * [repo]: the SQL-backed `VaultRepositoryImplContractTest` inserts
     * directly on the connection it opened; `InMemoryVaultRepositoryTest`
     * is a documented no-op because `InMemoryVaultRepository` never
     * tracks or enforces this key (see that class's override for why).
     *
     * Call [repo] first — `seedPersona` targets whatever backing store the
     * most recent [repo] call opened.
     */
    protected abstract fun seedPersona(id: PersonaId): Unit

    /**
     * The `IndexStore` of the vault the most recent [repo] call opened
     * (LC-02): its chunks and edges are the ones that repository's deletes
     * and renames act on. `InMemoryVaultRepositoryTest` links an
     * `InMemoryIndexStore`; `VaultRepositoryImplContractTest` opens an
     * `IndexStoreImpl` over the same database. Call [repo] first.
     */
    protected abstract fun index(): IndexStore

    @Test
    public fun commit_acknowledgements_wait_for_outer_commit_and_never_run_on_rollback() =
        runTest {
            val repository = repo()
            val acknowledgements = mutableListOf<Int>()
            repository.transaction {
                repository.afterTransactionCommit { acknowledgements += 1 }
                repository.transaction {
                    repository.afterTransactionCommit { acknowledgements += 2 }
                }
                assertTrue(acknowledgements.isEmpty())
            }
            assertEquals(listOf(1, 2), acknowledgements)
            val failure =
                runCatching {
                    repository.transaction {
                        repository.afterTransactionCommit { acknowledgements += 3 }
                        error("rollback")
                    }
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(listOf(1, 2), acknowledgements)
        }

    @Test
    public fun commit_acknowledgement_registration_requires_this_repositorys_transaction() =
        runTest {
            val repository = repo()
            val another = repo()
            assertTrue(runCatching { repository.afterTransactionCommit {} }.exceptionOrNull() is IllegalStateException)
            another.transaction {
                assertTrue(
                    runCatching { repository.afterTransactionCommit {} }.exceptionOrNull() is IllegalStateException,
                )
            }
        }

    @Test
    public fun failing_acknowledgement_does_not_undo_commit_or_prevent_the_next_acknowledgement() =
        runTest {
            val repository = repo()
            var acknowledged = false
            val chat =
                repository.transaction {
                    repository.afterTransactionCommit { error("synthetic failure") }
                    repository.afterTransactionCommit { acknowledged = true }
                    repository.createDocument(NewDocument(DocumentKind.CHAT, "Committed", null))
                }
            assertTrue(acknowledged)
            assertNotNull(repository.getDocument(chat.id))
        }

    @Test
    public fun cancellation_immediately_after_commit_cannot_skip_acknowledgement_or_undo_the_send() =
        runBlocking {
            val repository = repo()
            val chat = repository.createDocument(NewDocument(DocumentKind.CHAT, "Atomic send", null))
            val key = ChatDraftKey.Existing(chat.id)
            repository.writeDraft(key, ChatDraft("Question"))
            var acknowledged = false
            val sender =
                launch(Dispatchers.Default) {
                    val sendJob = coroutineContext[Job]!!
                    repository.transaction {
                        repository.appendMessage(chat.id, NewMessage(Role.USER, "Question"))
                        repository.deleteDraft(key)
                        repository.afterTransactionCommit { sendJob.cancel() }
                        repository.afterTransactionCommit { acknowledged = true }
                    }
                }
            withTimeout(5_000) { sender.join() }
            assertTrue(sender.isCancelled)
            assertTrue(acknowledged)
            assertNull(repository.readDraft(key))
            assertEquals(listOf("Question"), repository.listMessages(chat.id).map { it.contentMd })
        }

    @Test
    public fun draft_roundtrip_preserves_unicode_selection_and_has_no_document_side_effects() =
        runTest {
            val repository = repo()
            val chat = repository.createDocument(NewDocument(DocumentKind.CHAT, "Draft owner", null))
            val key = ChatDraftKey.Existing(chat.id)
            val queued = repository.dequeueIngest(100)
            val draft = ChatDraft("Unsent 🌿 [[Note]]", 9, 2)
            repository.writeDraft(key, draft)
            assertEquals(draft, repository.readDraft(key))
            assertEquals(chat, repository.getDocument(chat.id))
            assertEquals(queued, repository.dequeueIngest(100))
            assertTrue(repository.searchBodies("Unsent").isEmpty())
            assertTrue(repository.searchTitles("Unsent").isEmpty())
            assertTrue(repository.listMessages(chat.id).isEmpty())
            repository.deleteDraft(key)
            assertNull(repository.readDraft(key))
        }

    @Test
    public fun new_draft_same_id_is_separate_in_each_space() =
        runTest {
            val repository = repo()
            val draftId = "11111111-1111-4111-8111-111111111111"
            val first = ChatDraftKey.New("space-a", draftId)
            val second = ChatDraftKey.New("space-b", draftId)
            repository.writeDraft(first, ChatDraft("First Space"))
            assertNull(repository.readDraft(second))
            repository.writeDraft(second, ChatDraft("Second Space"))
            repository.deleteDraft(first)
            assertEquals(ChatDraft("Second Space"), repository.readDraft(second))
            assertTrue(repository.searchBodies("Space").isEmpty())
            assertTrue(repository.observeTimeline(TimelineFilter(), 100).first().isEmpty())
            assertTrue(repository.dequeueIngest(100).isEmpty())
        }

    @Test
    public fun deleted_chat_cascades_draft_and_late_write_cannot_resurrect_it() =
        runTest {
            val repository = repo()
            val chat = repository.createDocument(NewDocument(DocumentKind.CHAT, "Draft owner", null))
            val key = ChatDraftKey.Existing(chat.id)
            repository.writeDraft(key, ChatDraft("Private unsent draft"))
            repository.deleteDocument(chat.id)
            assertNull(repository.readDraft(key))
            val error = runCatching { repository.writeDraft(key, ChatDraft("Private unsent draft")) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("Draft owner is not an available chat", error?.message)
            assertNull(repository.readDraft(key))
            assertNull(repository.getDocument(chat.id))
            val note = repository.createDocument(NewDocument(DocumentKind.NOTE, "Not chat", ""))
            assertTrue(runCatching { repository.writeDraft(ChatDraftKey.Existing(note.id), ChatDraft("x")) }.isFailure)
        }

    @Test
    public fun send_and_draft_clear_commit_together_or_both_roll_back() =
        runTest {
            val repository = repo()
            val key = ChatDraftKey.New("space-a", "11111111-1111-4111-8111-111111111111")
            val draft = ChatDraft("First message")
            repository.writeDraft(key, draft)
            var failedId: String? = null
            runCatching {
                repository.transaction {
                    val chat = repository.createDocument(NewDocument(DocumentKind.CHAT, "New conversation", null))
                    failedId = chat.id
                    repository.appendMessage(chat.id, NewMessage(Role.USER, draft.text))
                    repository.deleteDraft(key)
                    error("Injected transaction failure")
                }
            }
            assertNull(repository.getDocument(failedId!!))
            assertEquals(draft, repository.readDraft(key))
            val chat =
                repository.transaction {
                    val created = repository.createDocument(NewDocument(DocumentKind.CHAT, "New conversation", null))
                    repository.appendMessage(created.id, NewMessage(Role.USER, draft.text))
                    repository.deleteDraft(key)
                    created
                }
            assertNull(repository.readDraft(key))
            assertEquals(listOf(draft.text), repository.listMessages(chat.id).map { it.contentMd })
        }

    @Test
    public fun draft_batch_rolls_back_and_quiesce_refuses_late_writes() =
        runTest {
            val repository = repo()
            val first = ChatDraftKey.New("space-a", "11111111-1111-4111-8111-111111111111")
            val second = ChatDraftKey.New("space-b", "11111111-1111-4111-8111-111111111111")
            runCatching {
                repository.transaction {
                    repository.writeDraft(first, ChatDraft("First"))
                    repository.writeDraft(second, ChatDraft("Second"))
                    error("Injected flush failure")
                }
            }
            assertNull(repository.readDraft(first))
            assertNull(repository.readDraft(second))
            repository.quiesce()
            assertTrue(
                runCatching {
                    repository.writeDraft(
                        first,
                        ChatDraft("Late"),
                    )
                }.exceptionOrNull() is VaultQuiescedException,
            )
            assertTrue(runCatching { repository.deleteDraft(first) }.exceptionOrNull() is VaultQuiescedException)
        }

    @Test
    public fun quiesce_drains_nested_transaction_and_refuses_late_writes(): Unit =
        runBlocking {
            val r = repo()
            val entered = CompletableDeferred<String>()
            val finish = CompletableDeferred<Unit>()
            val writing =
                async {
                    r.transaction {
                        val doc =
                            r.createDocument(
                                NewDocument(kind = DocumentKind.NOTE, title = "before", bodyMd = "body"),
                            )
                        entered.complete(doc.id)
                        finish.await()
                        r.transaction { r.renameDocument(doc.id, "committed") }
                    }
                }
            val id = entered.await()
            val drain = async(start = CoroutineStart.UNDISPATCHED) { r.quiesce() }
            assertTrue(
                runCatching { r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "late", bodyMd = null)) }
                    .exceptionOrNull() is VaultQuiescedException,
            )
            assertTrue(!drain.isCompleted)
            finish.complete(Unit)
            writing.await()
            drain.await()
            assertEquals("committed", r.getDocument(id)?.title)
            r.quiesce()
            assertTrue(runCatching { r.renameDocument(id, "too late") }.exceptionOrNull() is VaultQuiescedException)
        }

    @Test
    public fun a_closed_repository_cannot_borrow_another_repositorys_transaction(): Unit =
        runBlocking {
            val closed = repo()
            closed.quiesce()
            val open = repo()
            open.transaction {
                assertTrue(runCatching { closed.transaction { } }.exceptionOrNull() is VaultQuiescedException)
            }
        }

    @Test
    public fun quiesce_timeout_rolls_back_and_never_reopens_admission(): Unit =
        runBlocking {
            val r = repo()
            val entered = CompletableDeferred<String>()
            val writing =
                async {
                    r.transaction {
                        val doc =
                            r.createDocument(
                                NewDocument(kind = DocumentKind.NOTE, title = "discard", bodyMd = "body"),
                            )
                        entered.complete(doc.id)
                        awaitCancellation()
                    }
                }
            val id = entered.await()
            val failure = runCatching { r.quiesce(timeoutMillis = 0) }.exceptionOrNull()
            assertTrue(failure is VaultQuiesceTimeoutException)
            writing.join()
            assertTrue(writing.isCancelled)
            assertNull(r.getDocument(id))
            assertTrue(
                runCatching { r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "late", bodyMd = null)) }
                    .exceptionOrNull() is VaultQuiescedException,
            )
            val nextSession = repo()
            val fresh =
                nextSession.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "new session", bodyMd = null),
                )
            assertNotNull(nextSession.getDocument(fresh.id))
        }

    @Test
    public fun kindsOf_returns_only_requested_live_kinds_in_a_large_batch(): Unit =
        runTest {
            val r = repo()
            val note =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "private title", bodyMd = "private body"),
                )
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = null))
            val deleted = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "deleted", bodyMd = null))
            r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "not requested", bodyMd = null))
            r.deleteDocument(deleted.id)
            val ids = (0..1_100).mapTo(linkedSetOf()) { "missing-$it" } + setOf(note.id, chat.id, deleted.id)
            assertEquals(mapOf(note.id to DocumentKind.NOTE, chat.id to DocumentKind.CHAT), r.kindsOf(ids))
            assertEquals(emptyMap<String, DocumentKind>(), r.kindsOf(emptySet()))
        }

    @Test
    public fun kindsOf_reads_its_own_transaction_without_deadlocking(): Unit =
        runTest {
            val r = repo()
            // The SQL implementation dispatches to real IO. Keep this deadline
            // on a real clock: runTest's scheduler can otherwise advance a
            // virtual timeout before the IO dispatcher has resumed the write.
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    r.transaction {
                        val note =
                            r.createDocument(
                                NewDocument(kind = DocumentKind.NOTE, title = "nested", bodyMd = null),
                            )
                        assertEquals(mapOf(note.id to DocumentKind.NOTE), r.kindsOf(setOf(note.id)))
                    }
                }
            }
        }

    // ------------------------------------------------------------------
    // AC: create→get round-trip preserves frontmatter `id`
    // ------------------------------------------------------------------

    @Test
    public fun create_then_get_preserves_frontmatter_id(): Unit =
        runTest {
            val r = repo()
            val fixedId = "01924a4b-4d29-7000-8000-000000000001"
            val d =
                r.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "hello",
                        bodyMd = "world",
                        id = fixedId,
                        frontmatter = buildJsonObject { put("tags", JsonPrimitive("t")) },
                    ),
                )
            val got = r.getDocument(d.id)
            assertNotNull("expected document to be retrievable by id", got)
            val fmId = got!!.frontmatter["id"]
            assertTrue("expected frontmatter to carry an `id` key, was $fmId", fmId is JsonPrimitive)
            assertEquals(fixedId, (fmId as JsonPrimitive).content)
            assertEquals(
                "original tags key must survive the round-trip",
                "t",
                (got.frontmatter["tags"] as JsonPrimitive).content,
            )
        }

    // ------------------------------------------------------------------
    // AC: updateBody bumps updated_at and changes content_hash
    // ------------------------------------------------------------------

    @Test
    public fun updateBody_bumps_updated_at_and_changes_content_hash(): Unit =
        runTest {
            val r = repo()
            val d =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "one"),
                )
            val originalUpdated = d.updatedAt
            val originalHash = d.contentHash
            // `runTest` uses virtual time — the wall clock passed to
            // `System.currentTimeMillis` may not tick between the two
            // writes on very fast machines, so we sleep briefly.
            Thread.sleep(2L)
            val d2 = r.updateBody(d.id, title = "n", bodyMd = "two")
            assertTrue("expected updated_at to advance", d2.updatedAt >= originalUpdated)
            assertNotEquals("expected content_hash to change", originalHash, d2.contentHash)
        }

    // ------------------------------------------------------------------
    // AC: observeTimeline orders newest-first and respects personaId filter
    // ------------------------------------------------------------------

    @Test
    public fun observeTimeline_orders_newest_first_and_filters_by_persona(): Unit =
        runTest {
            val r = repo()
            val alice = "01924a4b-4d29-7000-8000-00000000A1CE"
            val bob = "01924a4b-4d29-7000-8000-00000000B0B0"
            // skein-ci54: documents.persona_id REFERENCES personas(id)
            // under PRAGMA foreign_keys = ON.
            seedPersona(alice)
            seedPersona(bob)
            val d1 =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "d1", bodyMd = "1", personaId = alice),
                )
            Thread.sleep(2L)
            val d2 =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "d2", bodyMd = "2", personaId = bob),
                )
            Thread.sleep(2L)
            val d3 =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "d3", bodyMd = "3", personaId = alice),
                )

            val all = r.observeTimeline(TimelineFilter()).first()
            assertEquals(
                "expected all docs newest-first",
                listOf(d3.id, d2.id, d1.id),
                all.map { it.id },
            )

            val aliceOnly =
                r.observeTimeline(TimelineFilter(personaId = alice)).first()
            assertEquals(
                "expected only Alice's docs, newest-first",
                listOf(d3.id, d1.id),
                aliceOnly.map { it.id },
            )
        }

    // ------------------------------------------------------------------
    // AC: appendMessage re-materializes body_md containing the message text
    // ------------------------------------------------------------------

    @Test
    public fun appendMessage_rematerializes_bodymd_containing_message_text(): Unit =
        runTest {
            val r = repo()
            val chat =
                r.createDocument(
                    NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""),
                )
            r.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hello there"))
            r.appendMessage(chat.id, NewMessage(role = Role.ASSISTANT, contentMd = "well met"))
            val fetched = r.getDocument(chat.id)
            assertNotNull("chat must be retrievable after appendMessage", fetched)
            val body = fetched!!.bodyMd ?: ""
            assertTrue(
                "expected re-materialized body to contain the user message text, was: $body",
                body.contains("hello there"),
            )
            assertTrue(
                "expected re-materialized body to contain the assistant message text, was: $body",
                body.contains("well met"),
            )
        }

    // ------------------------------------------------------------------
    // AC: completeIngest is a no-op when queuedAt advanced
    // ------------------------------------------------------------------

    @Test
    public fun completeIngest_noop_when_requeued(): Unit =
        runTest {
            val r = repo()
            val d =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "x"),
                )
            val first =
                r.dequeueIngest(10).single { it.docId == d.id }
            Thread.sleep(2L)
            r.updateBody(d.id, "a", "y") // re-queues with a later queued_at
            r.completeIngest(d.id, first.queuedAt)
            val remaining = r.dequeueIngest(10).filter { it.docId == d.id }
            assertEquals(
                "expected re-queued row to remain because queued_at advanced",
                1,
                remaining.size,
            )
        }

    // ------------------------------------------------------------------
    // AC: recordIngestFailure increments a persisted counter (migration
    // 008, skein-zx15) that survives a re-fetch of the queue and is reset
    // by an ordinary re-queue (INSERT OR REPLACE semantics)
    // ------------------------------------------------------------------

    @Test
    public fun recordIngestFailure_increments_and_persists_across_dequeue(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "x"))

            assertEquals(1, r.recordIngestFailure(d.id))
            assertEquals(2, r.recordIngestFailure(d.id))
            assertEquals(2, r.dequeueIngest(10).single { it.docId == d.id }.attempts)
        }

    @Test
    public fun recordIngestFailure_returns_zero_and_writes_nothing_once_the_entry_is_gone(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "x"))
            val queued = r.dequeueIngest(10).single { it.docId == d.id }
            r.completeIngest(d.id, queued.queuedAt)

            assertEquals(0, r.recordIngestFailure(d.id))
        }

    @Test
    public fun recordIngestFailure_count_resets_when_the_document_is_re_queued(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "x"))
            r.recordIngestFailure(d.id)
            r.recordIngestFailure(d.id)

            r.updateBody(d.id, "a", "y") // re-queues via INSERT OR REPLACE

            assertEquals(0, r.dequeueIngest(10).single { it.docId == d.id }.attempts)
        }

    // ------------------------------------------------------------------
    // AC: createAttachment/openAttachment round-trip 1 MiB of random bytes
    // ------------------------------------------------------------------

    @Test
    public fun attachment_round_trip_1MiB_of_random_bytes(): Unit =
        runTest {
            val r = repo()
            val size = 1 shl 20 // 1 MiB
            // Deterministic pseudo-random via java.util.Random(seed).
            val payload = ByteArray(size)
            java.util.Random(0xC0FFEEL).nextBytes(payload)

            val doc =
                r.createAttachment(
                    title = "blob.bin",
                    mimeType = "application/octet-stream",
                    write = { out -> out.write(payload) },
                )
            assertEquals(DocumentKind.ATTACHMENT, doc.kind)

            val read =
                r.openAttachment(doc.id).use { input ->
                    val out = ByteArrayOutputStream(size)
                    input.copyTo(out)
                    out.toByteArray()
                }
            assertEquals("expected 1 MiB round-trip length", size, read.size)
            assertTrue(
                "expected round-tripped bytes to match the source payload",
                payload.contentEquals(read),
            )

            assertEquals(
                "expected recorded mime type to survive the round-trip",
                "application/octet-stream",
                r.attachmentMimeType(doc.id),
            )
        }

    // ------------------------------------------------------------------
    // POST_REVIEW_RESOLUTIONS.md §1 — citation stability across re-ingestion
    // (migration 003 `document_revisions`, citation-record-v1)
    // ------------------------------------------------------------------

    @Test
    public fun createDocument_captures_a_revision_addressing_the_content(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "one"))

            val revision = r.currentRevision(d.id)
            assertNotNull("expected migration-003 revision capture on create", revision)
            assertEquals(
                "documents.content_hash IS the revision hash for a citable document (§1.2 step 3)",
                d.contentHash,
                revision!!.revisionHash,
            )
            assertEquals(
                "the revision hash must be exactly RevisionHashing.compute of the document's content",
                RevisionHashing.compute(d.bodyMd, d.frontmatter),
                revision.revisionHash,
            )
            assertEquals(
                "the snapshot must reproduce its own hash — that is what makes the diff view trustworthy",
                revision.revisionHash,
                RevisionHashing.compute(revision.bodyMdSnapshot, revision.frontmatterSnapshot),
            )
        }

    @Test
    public fun updateBody_changes_the_revision_hash_and_keeps_the_old_revision_readable(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "the original text"))
            val before = requireNotNull(r.currentRevision(d.id))

            Thread.sleep(2L)
            val updated = r.updateBody(d.id, title = "n", bodyMd = "the replacement text")

            val after = requireNotNull(r.currentRevision(d.id))
            assertNotEquals("expected updateBody to re-address the document", before.revisionHash, after.revisionHash)
            assertEquals(updated.contentHash, after.revisionHash)
            // §1.2 step 4: the superseded revision is retained so a message
            // that cited it can still show what it cited.
            val archived = r.getRevision(d.id, before.revisionHash)
            assertNotNull("expected the superseded revision to be retained", archived)
            assertEquals("the original text", archived!!.bodyMdSnapshot)
        }

    @Test
    public fun retitling_alone_does_not_change_the_revision_hash(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "old title", bodyMd = "stable body"))
            val before = requireNotNull(r.currentRevision(d.id)).revisionHash

            Thread.sleep(2L)
            r.updateBody(d.id, title = "new title", bodyMd = "stable body")

            assertEquals(
                "§1.3 hashes body + frontmatter only — a title change must not invalidate citations",
                before,
                requireNotNull(r.currentRevision(d.id)).revisionHash,
            )
        }

    @Test
    public fun a_cosmetic_frontmatter_rewrite_does_not_change_the_revision_hash(): Unit =
        runTest {
            val r = repo()
            val d =
                r.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "n",
                        bodyMd = "body",
                        frontmatter =
                            buildJsonObject {
                                put("alpha", JsonPrimitive("a"))
                                put("zed", JsonPrimitive("z"))
                            },
                    ),
                )
            val before = requireNotNull(r.currentRevision(d.id)).revisionHash

            Thread.sleep(2L)
            // Same keys and values, different insertion order — canonicalization
            // (§1.3) folds this away.
            r.updateFrontmatter(
                d.id,
                buildJsonObject {
                    put("zed", JsonPrimitive("z"))
                    put("alpha", JsonPrimitive("a"))
                },
            )

            assertEquals(before, requireNotNull(r.currentRevision(d.id)).revisionHash)
        }

    @Test
    public fun a_real_frontmatter_edit_does_change_the_revision_hash(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "body"))
            val before = requireNotNull(r.currentRevision(d.id)).revisionHash

            Thread.sleep(2L)
            r.updateFrontmatter(d.id, buildJsonObject { put("tags", JsonPrimitive("new")) })

            assertNotEquals(before, requireNotNull(r.currentRevision(d.id)).revisionHash)
        }

    @Test
    public fun capturing_unchanged_content_is_idempotent(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "same"))
            val first = requireNotNull(r.currentRevision(d.id))

            Thread.sleep(2L)
            r.updateBody(d.id, title = "n", bodyMd = "same")

            val second = requireNotNull(r.currentRevision(d.id))
            assertEquals(
                "re-writing identical content must reuse the content address",
                first.revisionHash,
                second.revisionHash,
            )
            assertEquals("and must not append a second row for it", first.revisionOrd, second.revisionOrd)
        }

    @Test
    public fun an_undone_edit_resolves_back_to_the_original_revision(): Unit =
        runTest {
            val r = repo()
            val d = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "A"))
            val original = requireNotNull(r.currentRevision(d.id)).revisionHash

            Thread.sleep(2L)
            r.updateBody(d.id, title = "n", bodyMd = "B")
            Thread.sleep(2L)
            r.updateBody(d.id, title = "n", bodyMd = "A")

            assertEquals(
                "A -> B -> A must resolve to A's content address again",
                original,
                requireNotNull(r.currentRevision(d.id)).revisionHash,
            )
        }

    @Test
    public fun attachments_have_no_revisions(): Unit =
        runTest {
            val r = repo()
            val doc =
                r.createAttachment(
                    title = "blob.bin",
                    mimeType = "application/octet-stream",
                    write = { out -> out.write(byteArrayOf(1, 2, 3)) },
                )
            assertNull("an attachment is never a citation source (§1.3)", r.currentRevision(doc.id))
        }

    @Test
    public fun appendMessage_round_trips_a_citation_record(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = NOTE_BODY))
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            val record = citationRecordFor(note.id, requireNotNull(note.contentHash))

            r.appendMessage(
                chat.id,
                NewMessage(role = Role.ASSISTANT, contentMd = "see [1]", citations = record),
            )

            val persisted = r.listMessages(chat.id).single()
            val back = persisted.citations
            assertNotNull("expected citation-record-v1 to survive the round-trip", back)
            assertEquals(1, back!!.recordVersion)
            assertEquals(listOf(1), back.cited)
            val citation = back.retrieved.single()
            assertEquals(note.id, citation.documentId)
            assertEquals(note.contentHash, citation.revisionHash)
            assertEquals(CITED_EXCERPT, citation.excerpt)
            assertEquals(4, citation.locator.byteStart)
            assertEquals(CitationSourceKind.VECTOR, citation.sourceKind)
            assertTrue(
                "a v1 record and the legacy chunk-id list are mutually exclusive",
                persisted.retrievedChunks.isEmpty(),
            )
        }

    @Test
    public fun a_message_without_citations_still_round_trips_legacy_chunk_ids(): Unit =
        runTest {
            val r = repo()
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))

            r.appendMessage(
                chat.id,
                NewMessage(role = Role.ASSISTANT, contentMd = "legacy", retrievedChunks = listOf(147L, 812L)),
            )

            val persisted = r.listMessages(chat.id).single()
            assertEquals(listOf(147L, 812L), persisted.retrievedChunks)
            assertNull("a legacy payload carries no citation record (§1.5)", persisted.citations)
        }

    @Test
    public fun replay_detects_a_source_that_changed_under_a_citation(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = NOTE_BODY))
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            val citedHash = requireNotNull(note.contentHash)
            r.appendMessage(
                chat.id,
                NewMessage(
                    role = Role.ASSISTANT,
                    contentMd = "see [1]",
                    citations = citationRecordFor(note.id, citedHash),
                ),
            )
            val citation = requireNotNull(r.listMessages(chat.id).single().citations).retrieved.single()
            assertTrue("the citation must be live before the source is edited", r.revisionMatches(citation))

            Thread.sleep(2L)
            // This is the defect §1.1 describes: re-ingestion replaces the
            // chunks the old ids pointed at. The citation must now report
            // "source changed" instead of silently showing the new text.
            r.updateBody(note.id, title = "note", bodyMd = "an entirely different body")

            assertTrue(
                "expected the citation to stop matching once its source changed",
                !r.revisionMatches(citation),
            )
            val cited = r.getRevision(note.id, citation.revisionHash)
            assertNotNull("the cited revision must still be readable for the diff view", cited)
            assertEquals(NOTE_BODY, cited!!.bodyMdSnapshot)
            assertEquals(
                "and the current revision must be a different address",
                false,
                requireNotNull(r.currentRevision(note.id)).revisionHash == citation.revisionHash,
            )
        }

    @Test
    public fun a_citation_into_a_deleted_document_does_not_match(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = NOTE_BODY))
            val citation = citationRecordFor(note.id, requireNotNull(note.contentHash)).retrieved.single()

            r.deleteDocument(note.id)

            assertTrue("a deleted source is not a live citation", !r.revisionMatches(citation))
            assertNull("revisions cascade with their document (§1.3)", r.getRevision(note.id, citation.revisionHash))
        }

    // ------------------------------------------------------------------
    // documentRevisions_gc sweep (skein-a2yr, POST_REVIEW_RESOLUTIONS.md
    // §1.2 step 4) and the chat-snapshot bound it lands alongside.
    // ------------------------------------------------------------------

    @Test
    public fun sweep_keeps_a_superseded_revision_still_cited_by_a_message(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = NOTE_BODY))
            val citedHash = requireNotNull(note.contentHash)
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            r.appendMessage(
                chat.id,
                NewMessage(
                    role = Role.ASSISTANT,
                    contentMd = "see [1]",
                    citations = citationRecordFor(note.id, citedHash),
                ),
            )
            Thread.sleep(2L)
            r.updateBody(note.id, title = "note", bodyMd = "an entirely different body")

            // Not asserting the deleted count here: appendMessage's own
            // chat-creation revision (the empty transcript, superseded and
            // uncited the moment the first turn rewrites the body) is a
            // separate, legitimate orphan the sweep is expected to remove —
            // see `sweep_removes_an_uncited_superseded_revision` for that
            // case in isolation. This test only asserts the CITED revision's
            // survival.
            r.sweepUnreferencedRevisions()

            assertNotNull(
                "a revision still cited by a message must survive the sweep",
                r.getRevision(note.id, citedHash),
            )
        }

    @Test
    public fun sweep_removes_an_uncited_superseded_revision(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = "original"))
            val originalHash = requireNotNull(note.contentHash)
            Thread.sleep(2L)
            r.updateBody(note.id, title = "note", bodyMd = "replacement")
            assertNotNull(
                "sanity: the superseded revision exists before the sweep",
                r.getRevision(note.id, originalHash),
            )

            val deleted = r.sweepUnreferencedRevisions()

            assertEquals(1, deleted)
            assertNull("an uncited superseded revision must be swept", r.getRevision(note.id, originalHash))
        }

    @Test
    public fun sweep_never_removes_the_current_revision(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = "only version"))
            val currentHash = requireNotNull(note.contentHash)

            val deleted = r.sweepUnreferencedRevisions()

            assertEquals("an uncited but CURRENT revision must never be swept", 0, deleted)
            assertEquals(currentHash, requireNotNull(r.currentRevision(note.id)).revisionHash)
        }

    @Test
    public fun deleting_a_document_cascades_its_revisions_ahead_of_any_sweep(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = "one"))
            val hash = requireNotNull(note.contentHash)

            r.deleteDocument(note.id)

            assertNull(
                "delete must cascade the revision immediately, not wait for a sweep",
                r.getRevision(note.id, hash),
            )
            assertEquals(
                "a sweep afterward finds nothing left orphaned by the already-cascaded delete",
                0,
                r.sweepUnreferencedRevisions(),
            )
        }

    @Test
    public fun sweep_is_idempotent(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = "original"))
            Thread.sleep(2L)
            r.updateBody(note.id, title = "note", bodyMd = "replacement")

            val first = r.sweepUnreferencedRevisions()
            val second = r.sweepUnreferencedRevisions()

            assertEquals(1, first)
            assertEquals("a second sweep with no new orphans must find nothing left to remove", 0, second)
        }

    @Test
    public fun a_chat_documents_snapshots_stay_bounded_and_its_citations_still_resolve(): Unit =
        runTest {
            val r = repo()
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            repeat(20) { i ->
                r.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "turn $i " + "x".repeat(500)))
            }

            val current = requireNotNull(r.currentRevision(chat.id))
            assertEquals(
                "a chat document's archived snapshot must stay empty regardless of transcript size " +
                    "(bounds storage to O(N) turns instead of O(N^2) bytes) — skein-a2yr",
                "",
                current.bodyMdSnapshot,
            )
            // The transcript itself is untouched — only the archived copy is skipped.
            assertTrue(
                "the live document body must still hold the full transcript",
                requireNotNull(r.getDocument(chat.id)!!.bodyMd).contains("turn 19"),
            )
            val citationIntoChat =
                Citation(
                    marker = 1,
                    documentId = chat.id,
                    revisionHash = current.revisionHash,
                    locator = Locator(byteStart = 0, byteEnd = 4),
                    excerpt = "turn",
                    sourceKind = CitationSourceKind.LEXICAL,
                )
            assertTrue(
                "a citation into a chat turn must still resolve as live despite the skipped snapshot",
                r.revisionMatches(citationIntoChat),
            )
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §3.2 (LC-01): writes never resurrect
    // ------------------------------------------------------------------

    @Test
    public fun writes_to_a_deleted_document_throw_NoSuchElementException_and_write_nothing(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = "body"))
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            r.deleteDocument(note.id)
            r.deleteDocument(chat.id)

            assertThrowsNoSuchElement { r.updateBody(note.id, "note", "late body") }
            assertThrowsNoSuchElement { r.replaceBody(note.id, "late body") }
            assertThrowsNoSuchElement { r.renameDocument(note.id, "late title") }
            assertThrowsNoSuchElement { r.renameDocument(chat.id, "generated title", ifTitleIs = "chat") }
            val tags = buildJsonObject { put("tags", JsonPrimitive("t")) }
            assertThrowsNoSuchElement { r.updateFrontmatter(note.id, tags) }
            assertThrowsNoSuchElement { r.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "late")) }

            assertNull(r.getDocument(note.id))
            assertNull(r.getDocument(chat.id))
            assertTrue(r.listMessages(chat.id).isEmpty())
            assertTrue(r.dequeueIngest(10).none { it.docId == note.id || it.docId == chat.id })
        }

    @Test
    public fun a_late_autosave_never_resurrects_a_deleted_note(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "draft", bodyMd = "v1"))
            r.deleteDocument(note.id)

            assertThrowsNoSuchElement { r.updateBody(note.id, "draft", "v2 from a late autosave") }
            assertThrowsNoSuchElement { r.replaceBody(note.id, "v2 from a late autosave") }

            assertNull(r.getDocument(note.id))
            assertNull(r.findByTitle("draft"))
            assertTrue(r.observeTimeline(TimelineFilter()).first().none { it.id == note.id })
        }

    // ------------------------------------------------------------------
    // skein-mzm5 / LC-02: re-entrant transactions; the linked index
    // ------------------------------------------------------------------

    @Test
    public fun transaction_nests_writes_without_deadlock(): Unit =
        runTest {
            val r = repo()
            val doc =
                r.transaction {
                    val created = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "one"))
                    assertNotNull("a read inside the transaction sees its own write", r.getDocument(created.id))
                    r.transaction { r.updateBody(created.id, "n", "two") }
                }
            assertEquals("two", r.getDocument(doc.id)?.bodyMd)

            var createdInFailedTx: String? = null
            val failed =
                runCatching {
                    r.transaction {
                        createdInFailedTx =
                            r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "gone", bodyMd = "x")).id
                        r.updateBody(doc.id, "n", "three")
                        error("boom")
                    }
                }
            assertTrue(failed.isFailure)
            assertNull(
                "a failed transaction leaves nothing it created",
                r.getDocument(requireNotNull(createdInFailedTx)),
            )
            assertEquals("and undoes what it changed", "two", r.getDocument(doc.id)?.bodyMd)
        }

    @Test
    public fun a_failed_transaction_rolls_back_every_delete_in_it(): Unit =
        runTest {
            val r = repo()
            val a = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "a"))
            val b = r.createAttachment("b.bin", "application/octet-stream") { it.write(byteArrayOf(1, 2, 3)) }

            val failed =
                runCatching {
                    r.transaction {
                        r.deleteDocument(a.id)
                        r.deleteDocument(b.id)
                        error("boom")
                    }
                }

            assertTrue(failed.isFailure)
            assertNotNull(r.getDocument(a.id))
            assertNotNull(r.getDocument(b.id))
            assertEquals(
                "the blob outlives a rolled-back delete",
                3,
                r.openAttachment(b.id).use { it.readBytes() }.size,
            )
        }

    @Test
    public fun deleteDocument_removes_the_documents_chunks(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "zanzibarx"))
            idx.replaceChunks(note.id, listOf(NewChunk(ord = 0, text = "zanzibarx walrus", tokenCount = 2)), "fake", 1)

            r.deleteDocument(note.id)

            assertTrue(idx.chunksForDocs(listOf(note.id), limitPerDoc = 10).isEmpty())
            assertTrue("no lexical match survives the delete", idx.bm25("zanzibarx", k = 10).isEmpty())
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §3.3 (LC-03): delete, its ordering and signals
    // ------------------------------------------------------------------

    @Test
    public fun deleteDocument_removes_the_row_and_getDocument_returns_null(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "b"))

            r.deleteDocument(note.id)

            assertNull(r.getDocument(note.id))
        }

    @Test
    public fun deleteDocument_is_idempotent_for_a_missing_id(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "b"))
            r.deleteDocument(note.id)

            r.deleteDocument(note.id)
            r.deleteDocument("01924a4b-4d29-7000-8000-00000000DEAD")

            assertNull(r.getDocument(note.id))
        }

    @Test
    public fun deleteDocument_removes_messages_revisions_and_the_ingest_queue_entry(): Unit =
        runTest {
            val r = repo()
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            r.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hello"))
            val revision = requireNotNull(r.currentRevision(chat.id))
            assertTrue("sanity: the chat is queued for ingest", r.dequeueIngest(10).any { it.docId == chat.id })

            r.deleteDocument(chat.id)

            assertTrue(r.listMessages(chat.id).isEmpty())
            assertNull(r.getRevision(chat.id, revision.revisionHash))
            assertTrue(r.dequeueIngest(10).none { it.docId == chat.id })
        }

    @Test
    public fun observeDocument_emits_null_after_delete(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "v0"))
            live(r.observeDocument(note.id)) { seen ->
                seen.primeWith(poke = { i ->
                    r.updateBody(note.id, "n", "v$i")
                }, landed = { d, i -> d?.bodyMd == "v$i" })
                r.deleteDocument(note.id)
                seen.awaitMatching { it == null }
            }
        }

    /** EMU is the meaningful run: the fake ticks every flow on every write (spec §3.8). */
    @Test
    public fun observeMessages_emits_empty_after_the_chat_is_deleted(): Unit =
        runTest {
            val r = repo()
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            live(r.observeMessages(chat.id)) { seen ->
                seen.primeWith(
                    poke = { i -> r.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "turn $i")) },
                    landed = { messages, i -> messages.lastOrNull()?.contentMd == "turn $i" },
                )
                r.deleteDocument(chat.id)
                seen.awaitMatching { it.isEmpty() }
            }
        }

    @Test
    public fun observeTimeline_drops_the_deleted_document(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "v0"))
            live(r.observeTimeline(TimelineFilter())) { seen ->
                seen.primeWith(
                    poke = { i -> r.updateBody(note.id, "n", "v$i") },
                    landed = { docs, i -> docs.any { it.id == note.id && it.bodyMd == "v$i" } },
                )
                r.deleteDocument(note.id)
                seen.awaitMatching { docs -> docs.none { it.id == note.id } }
            }
        }

    @Test
    public fun searchTitles_and_searchBodies_never_return_a_deleted_document(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "zanzibar", bodyMd = "zanzibarx"))
            index().replaceChunks(note.id, listOf(NewChunk(ord = 0, text = "zanzibarx", tokenCount = 1)), "fake", 1)
            assertTrue("sanity: found by title", r.searchTitles("zanzibar").any { it.id == note.id })
            assertTrue("sanity: found by body", r.searchBodies("zanzibarx").any { it.document.id == note.id })

            r.deleteDocument(note.id)

            assertTrue(r.searchTitles("zanzibar").isEmpty())
            assertTrue(r.searchBodies("zanzibarx").isEmpty())
        }

    @Test
    public fun attachment_blob_is_deleted_only_after_commit(): Unit =
        runTest {
            val r = repo()
            val att = r.createAttachment("a.bin", "application/octet-stream") { it.write(byteArrayOf(7)) }

            r.transaction {
                r.deleteDocument(att.id)
                assertEquals(
                    "the blob outlives the uncommitted delete",
                    1,
                    r.openAttachment(att.id).use { it.readBytes() }.size,
                )
            }

            assertTrue(
                "the blob is gone once the delete committed",
                runCatching { r.openAttachment(att.id).close() }.isFailure,
            )
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §3.4 (LC-04): edges detach with a delete
    // ------------------------------------------------------------------

    @Test
    public fun deleteDocument_removes_out_edges_of_every_kind(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val a = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "a"))
            val b = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "b", bodyMd = "b"))
            val out =
                listOf(
                    Edge(srcId = a.id, dstId = b.id, kind = EdgeKind.WIKILINK, createdAt = 1L),
                    Edge(srcId = a.id, dstId = "tag:x", kind = EdgeKind.TAG, createdAt = 1L),
                    Edge(srcId = a.id, dstId = b.id, kind = EdgeKind.CITE, createdAt = 1L),
                    Edge(srcId = a.id, dstId = "entity:1", kind = EdgeKind.ENTITY, createdAt = 1L),
                )
            idx.replaceEdges(a.id, EdgeKind.entries.toSet(), out)

            r.deleteDocument(a.id)

            assertTrue(idx.edgesFrom(a.id).isEmpty())
            assertTrue("b keeps no edge from the deleted a", idx.edgesTo(b.id).isEmpty())
        }

    @Test
    public fun deleteDocument_rewrites_wikilink_in_edges_to_the_title_sentinel_at_weight_half(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val plan = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Plan", bodyMd = "p"))
            val c = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "c", bodyMd = "[[Plan]]"))
            idx.replaceEdges(
                c.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(srcId = c.id, dstId = plan.id, kind = EdgeKind.WIKILINK, createdAt = 42L)),
            )

            r.deleteDocument(plan.id)

            assertEquals(
                listOf(
                    Edge(srcId = c.id, dstId = "title:plan", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 42L),
                ),
                idx.edgesFrom(c.id),
            )
            assertTrue(idx.edgesTo(plan.id).isEmpty())
        }

    @Test
    public fun deleteDocument_sentinel_matches_EdgeUpserter_for_a_non_ascii_title(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val emile = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Émile", bodyMd = "e"))
            val c = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "c", bodyMd = "[[Émile]]"))
            idx.replaceEdges(
                c.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(srcId = c.id, dstId = emile.id, kind = EdgeKind.WIKILINK, createdAt = 1L)),
            )

            r.deleteDocument(emile.id)

            // `EdgeUpserter.unresolvedTarget("Émile")`: Kotlin lowercases the
            // É; SQLite's lower() would not.
            assertEquals(listOf("title:émile"), idx.edgesFrom(c.id).map { it.dstId })
        }

    @Test
    public fun deleteDocument_removes_cite_in_edges(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val pdf = r.createAttachment("report.pdf", "application/pdf") { it.write(byteArrayOf(1)) }
            val text = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "report", bodyMd = "t"))
            idx.replaceEdges(
                text.id,
                setOf(EdgeKind.CITE),
                listOf(Edge(srcId = text.id, dstId = pdf.id, kind = EdgeKind.CITE, createdAt = 1L)),
            )

            r.deleteDocument(pdf.id)

            assertTrue(idx.edgesTo(pdf.id).isEmpty())
            assertTrue(idx.edgesFrom(text.id).isEmpty())
        }

    @Test
    public fun deleteDocument_requeues_the_surviving_document_with_the_same_title(): Unit =
        runTest {
            val r = repo()
            val older = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Plan", bodyMd = "older"))
            Thread.sleep(2L)
            val newer = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "plan", bodyMd = "newer"))
            val queued = r.dequeueIngest(10).single { it.docId == older.id }
            r.completeIngest(older.id, queued.queuedAt)

            r.deleteDocument(newer.id)

            assertTrue(
                "the older 'Plan' now answers [[Plan]] and must be re-ingested to pick up its links",
                r.dequeueIngest(10).any { it.docId == older.id && it.reason == IngestReason.UPDATED },
            )
        }

    @Test
    public fun deleteDocument_keeps_other_chats_citation_excerpts(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = NOTE_BODY))
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            val record = citationRecordFor(note.id, requireNotNull(note.contentHash))
            r.appendMessage(chat.id, NewMessage(role = Role.ASSISTANT, contentMd = "see [1]", citations = record))

            r.deleteDocument(note.id)

            val kept = requireNotNull(r.listMessages(chat.id).single().citations).retrieved.single()
            assertEquals("the quote stays, disclosed by the delete dialog (spec §3.7)", CITED_EXCERPT, kept.excerpt)
            assertEquals(note.id, kept.documentId)
        }

    @Test
    public fun countChatsCiting_counts_distinct_chats_by_decoded_citation_records(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "note", bodyMd = NOTE_BODY))
            val other = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "other", bodyMd = "o"))
            val citesNote = citationRecordFor(note.id, requireNotNull(note.contentHash))
            val citesOther = citationRecordFor(other.id, requireNotNull(other.contentHash))

            suspend fun chatWith(vararg turns: NewMessage) {
                val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
                for (turn in turns) r.appendMessage(chat.id, turn)
            }
            // Two turns quoting the note in one chat count once.
            chatWith(
                NewMessage(role = Role.ASSISTANT, contentMd = "a", citations = citesNote),
                NewMessage(role = Role.ASSISTANT, contentMd = "b", citations = citesNote),
            )
            chatWith(NewMessage(role = Role.ASSISTANT, contentMd = "c", citations = citesNote))
            chatWith(NewMessage(role = Role.ASSISTANT, contentMd = "d", citations = citesOther))
            // A legacy chunk-id payload names no document.
            chatWith(NewMessage(role = Role.ASSISTANT, contentMd = "e", retrievedChunks = listOf(1L, 2L)))

            assertEquals(2, r.countChatsCiting(note.id))
            assertEquals(1, r.countChatsCiting(other.id))
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §4.3, §6.3, §11.2 (LC-05): rename, replaceBody,
    // kind guards
    // ------------------------------------------------------------------

    @Test
    public fun renameDocument_changes_only_the_title(): Unit =
        runTest {
            val r = repo()
            val fm = buildJsonObject { put("title", JsonPrimitive("Old")) }
            val before =
                r.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "Old", bodyMd = "body", frontmatter = fm),
                )
            Thread.sleep(2L)

            val renamed = r.renameDocument(before.id, "New")

            assertEquals(before.copy(title = "New"), renamed)
            assertEquals(
                "body, frontmatter (its stale title key included), hash and timestamps are untouched",
                before.copy(title = "New"),
                r.getDocument(before.id),
            )
        }

    @Test
    public fun renameDocument_keeps_content_hash_and_captures_no_revision(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Old", bodyMd = "body"))
            val revision = requireNotNull(r.currentRevision(note.id))

            r.renameDocument(note.id, "New")

            assertEquals(note.contentHash, r.getDocument(note.id)?.contentHash)
            assertEquals(revision, r.currentRevision(note.id))
            assertEquals("nothing to sweep: no revision was captured", 0, r.sweepUnreferencedRevisions())
        }

    @Test
    public fun renameDocument_does_not_change_updated_at(): Unit =
        runTest {
            val r = repo()
            val older = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "older", bodyMd = "o"))
            Thread.sleep(2L)
            val newer = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "newer", bodyMd = "n"))
            Thread.sleep(2L)

            r.renameDocument(older.id, "renamed")

            assertEquals(older.updatedAt, r.getDocument(older.id)?.updatedAt)
            assertEquals(
                "Recent order is unchanged",
                listOf(newer.id, older.id),
                r.observeTimeline(TimelineFilter()).first().map { it.id },
            )
        }

    @Test
    public fun renameDocument_requeues_ingest_with_a_fresh_queued_at(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Old", bodyMd = "body"))
            val inFlight = r.dequeueIngest(10).single { it.docId == note.id }
            Thread.sleep(2L)

            r.renameDocument(note.id, "New")
            r.completeIngest(note.id, inFlight.queuedAt)

            val requeued = r.dequeueIngest(10).single { it.docId == note.id }
            assertTrue("queued after the in-flight ingest (spec N9)", requeued.queuedAt > inFlight.queuedAt)
            assertEquals(IngestReason.UPDATED, requeued.reason)
        }

    @Test
    public fun renameDocument_detaches_wikilink_in_edges_to_the_old_title_sentinel(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val plan = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Plan", bodyMd = "p"))
            val c = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "c", bodyMd = "[[Plan]]"))
            idx.replaceEdges(
                c.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(srcId = c.id, dstId = plan.id, kind = EdgeKind.WIKILINK, createdAt = 7L)),
            )

            r.renameDocument(plan.id, "Roadmap")

            assertEquals(
                listOf(
                    Edge(srcId = c.id, dstId = "title:plan", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 7L),
                ),
                idx.edgesFrom(c.id),
            )
            assertTrue(idx.edgesTo(plan.id).isEmpty())
        }

    @Test
    public fun renameDocument_preserves_explicit_id_links_and_detaches_a_mixed_title_link(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val target = r.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "body"))
            val idOnly = r.createDocument(NewDocument(DocumentKind.NOTE, "ID source", "[[${target.id}#Section|Plan]]"))
            val mixed =
                r.createDocument(
                    NewDocument(DocumentKind.NOTE, "Mixed source", "[[${target.id}|Plan]] [[Plan]]"),
                )
            for (source in listOf(idOnly, mixed)) {
                idx.replaceEdges(
                    source.id,
                    setOf(EdgeKind.WIKILINK),
                    listOf(Edge(source.id, target.id, EdgeKind.WIKILINK, createdAt = 7L)),
                )
            }

            r.renameDocument(target.id, "Roadmap")

            assertEquals(
                listOf(Edge(idOnly.id, target.id, EdgeKind.WIKILINK, createdAt = 7L)),
                idx.edgesFrom(idOnly.id),
            )
            assertEquals(setOf(target.id, "title:plan"), idx.edgesFrom(mixed.id).map { it.dstId }.toSet())
            assertEquals(idOnly.bodyMd, r.getDocument(idOnly.id)?.bodyMd)
        }

    @Test
    public fun deleteDocument_explicit_id_link_is_not_a_title_link_to_a_replacement(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val target = r.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "body"))
            val source = r.createDocument(NewDocument(DocumentKind.NOTE, "Source", "[[${target.id}|Plan]]"))
            idx.replaceEdges(
                source.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(source.id, target.id, EdgeKind.WIKILINK, createdAt = 9L)),
            )

            r.deleteDocument(target.id)
            val replacement = r.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "replacement"))

            assertEquals(
                listOf(
                    Edge(
                        source.id,
                        "import:${source.id}:${target.id}",
                        EdgeKind.WIKILINK,
                        weight = 0.5,
                        createdAt = 9L,
                    ),
                ),
                idx.edgesFrom(source.id),
            )
            assertTrue(idx.edgesTo("title:plan").isEmpty())
            assertTrue(idx.edgesTo(replacement.id).isEmpty())
        }

    @Test
    public fun deleteDocument_id_spelling_equal_to_title_still_has_only_an_id_binding(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val target = r.createDocument(NewDocument(DocumentKind.NOTE, "Initial", "body"))
            r.renameDocument(target.id, target.id)
            val source = r.createDocument(NewDocument(DocumentKind.NOTE, "Source", "[[${target.id}]]"))
            idx.replaceEdges(
                source.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(source.id, target.id, EdgeKind.WIKILINK, createdAt = 1L)),
            )
            r.deleteDocument(target.id)
            assertEquals(listOf("import:${source.id}:${target.id}"), idx.edgesFrom(source.id).map { it.dstId })
        }

    @Test
    public fun renameDocument_does_not_treat_code_literal_id_as_a_link_binding(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val target = r.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "body"))
            val source =
                r.createDocument(
                    NewDocument(
                        DocumentKind.NOTE,
                        "Source",
                        "`[[${target.id}]]`\n```md\n[[${target.id}]]\n```\n[[Plan]]",
                    ),
                )
            idx.replaceEdges(
                source.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(source.id, target.id, EdgeKind.WIKILINK, createdAt = 7L)),
            )

            r.renameDocument(target.id, "Roadmap")

            assertEquals(
                listOf(Edge(source.id, "title:plan", EdgeKind.WIKILINK, weight = 0.5, createdAt = 7L)),
                idx.edgesFrom(source.id),
            )
        }

    @Test
    public fun renameDocument_keeps_cite_in_edges(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val pdf = r.createAttachment("report.pdf", "application/pdf") { it.write(byteArrayOf(1)) }
            val text = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "report", bodyMd = "t"))
            val cite = Edge(srcId = text.id, dstId = pdf.id, kind = EdgeKind.CITE, createdAt = 1L)
            idx.replaceEdges(text.id, setOf(EdgeKind.CITE), listOf(cite))

            r.renameDocument(pdf.id, "Q3 report.pdf")

            assertEquals(listOf(cite), idx.edgesTo(pdf.id))
        }

    @Test
    public fun renameDocument_trims_collapses_line_breaks_and_rejects_blank_or_over_200(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "b"))

            assertEquals("a b c", r.renameDocument(note.id, "  a\r\n\tb\nc \t")?.title)
            assertEquals("x".repeat(200), r.renameDocument(note.id, "x".repeat(200))?.title)
            for (bad in listOf("", " \n\t ", "y".repeat(201))) {
                assertTrue(runCatching { r.renameDocument(note.id, bad) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals("a rejected title writes nothing", "x".repeat(200), r.getDocument(note.id)?.title)
        }

    @Test
    public fun renameDocument_with_ifTitleIs_writes_nothing_when_the_title_changed(): Unit =
        runTest {
            val r = repo()
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = ""))
            r.renameDocument(chat.id, "Mine")

            assertNull(r.renameDocument(chat.id, "Generated", ifTitleIs = "Chat"))
            assertEquals("a user rename wins over a generated title", "Mine", r.getDocument(chat.id)?.title)
            assertEquals("Ours", r.renameDocument(chat.id, "Ours", ifTitleIs = "Mine")?.title)
        }

    @Test
    public fun renameDocument_on_an_attachment_keeps_its_byte_hash(): Unit =
        runTest {
            val r = repo()
            val pdf = r.createAttachment("report.pdf", "application/pdf") { it.write(byteArrayOf(1, 2, 3)) }

            r.renameDocument(pdf.id, "Q3 report.pdf")

            val renamed = requireNotNull(r.getDocument(pdf.id))
            assertEquals("Q3 report.pdf", renamed.title)
            assertEquals(pdf.contentHash, renamed.contentHash)
            assertNull("an attachment has no body", renamed.bodyMd)
        }

    @Test
    public fun updateBody_rejects_attachments_and_chats(): Unit =
        runTest {
            val r = repo()
            val pdf = r.createAttachment("report.pdf", "application/pdf") { it.write(byteArrayOf(1)) }
            val chat = r.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "chat", bodyMd = ""))
            r.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hi"))
            val transcript = r.getDocument(chat.id)?.bodyMd

            for (id in listOf(pdf.id, chat.id)) {
                assertTrue(runCatching { r.updateBody(id, "t", "text") }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { r.replaceBody(id, "text") }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(pdf.contentHash, r.getDocument(pdf.id)?.contentHash)
            assertEquals(transcript, r.getDocument(chat.id)?.bodyMd)
        }

    @Test
    public fun replaceBody_never_writes_the_title(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Old", bodyMd = "v1"))
            r.renameDocument(note.id, "New")

            val saved = r.replaceBody(note.id, "v2")

            assertEquals("New", saved.title)
            assertEquals("New" to "v2", r.getDocument(note.id)?.let { it.title to it.bodyMd })
            assertEquals(RevisionHashing.compute("v2", note.frontmatter), saved.contentHash)
            assertTrue(r.dequeueIngest(10).any { it.docId == note.id })
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §3.3 race rule, N4, §11.4 (LC-06): ingest
    // integrity under delete and edit
    // ------------------------------------------------------------------

    @Test
    public fun index_writes_for_a_missing_source_document_are_skipped(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val gone = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "gone", bodyMd = "b"))
            r.deleteDocument(gone.id)

            // What an ingest that read the document before the delete writes after it.
            val ids = idx.replaceChunks(gone.id, listOf(NewChunk(ord = 0, text = "late", tokenCount = 1)), "fake", 1)
            idx.replaceEdges(
                gone.id,
                setOf(EdgeKind.WIKILINK, EdgeKind.TAG),
                listOf(Edge(srcId = gone.id, dstId = "tag:late", kind = EdgeKind.TAG, createdAt = 1L)),
            )

            assertTrue(ids.isEmpty())
            assertTrue(idx.chunksForDocs(listOf(gone.id), limitPerDoc = 10).isEmpty())
            assertTrue(idx.edgesFrom(gone.id).isEmpty())
        }

    @Test
    public fun orphan_edge_sweep_deletes_edges_to_missing_documents_and_requeues_their_sources(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val (linker, gone) = plantOrphanInEdge(r, idx)

            assertEquals(1, r.sweepIndexOrphans())

            assertTrue(idx.edgesTo(gone).isEmpty())
            assertTrue(idx.edgesFrom(linker).isEmpty())
            assertTrue(
                "its ingest recomputes the link from the text",
                r.dequeueIngest(10).any { it.docId == linker && it.reason == IngestReason.UPDATED },
            )
        }

    @Test
    public fun orphan_edge_sweep_is_idempotent(): Unit =
        runTest {
            val r = repo()
            val idx = index()
            val a = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "a", bodyMd = "a"))
            val b = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "b", bodyMd = "b"))
            val healthy =
                listOf(
                    Edge(srcId = a.id, dstId = b.id, kind = EdgeKind.WIKILINK, createdAt = 1L),
                    Edge(srcId = a.id, dstId = "title:nowhere", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 1L),
                    Edge(srcId = a.id, dstId = "tag:x", kind = EdgeKind.TAG, createdAt = 1L),
                    Edge(srcId = a.id, dstId = "entity:1", kind = EdgeKind.ENTITY, createdAt = 1L),
                )
            idx.replaceEdges(a.id, EdgeKind.entries.toSet(), healthy)
            plantOrphanInEdge(r, idx)

            assertEquals(1, r.sweepIndexOrphans())
            assertEquals("a second sweep finds nothing", 0, r.sweepIndexOrphans())
            assertEquals(
                "live documents and non-document nodes are never swept",
                healthy.toSet(),
                idx.edgesFrom(a.id).toSet(),
            )
        }

    @Test
    public fun a_frontmatter_only_edit_requeues_ingest(): Unit =
        runTest {
            val r = repo()
            val note = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "body"))
            val created = r.dequeueIngest(10).single { it.docId == note.id }
            r.completeIngest(note.id, created.queuedAt)

            // Only `id`: canonicalization folds it away, so nothing moves.
            r.updateFrontmatter(note.id, buildJsonObject { put("id", JsonPrimitive(note.id)) })
            assertTrue(r.dequeueIngest(10).none { it.docId == note.id })

            r.updateFrontmatter(note.id, buildJsonObject { put("tags", JsonPrimitive("new")) })
            assertTrue("spec N4: tags re-link", r.dequeueIngest(10).any { it.docId == note.id })
        }

    /**
     * An edge from a live note into a deleted one — what
     * `DanglingResolver.resolveFor(deletedDocument)` writes when an ingest
     * races the delete (the source is alive, so the write lands) — with the
     * note's own ingest already completed. Returns `(linker, deleted)` ids.
     */
    private suspend fun plantOrphanInEdge(
        r: VaultRepository,
        idx: IndexStore,
    ): Pair<String, String> {
        val linker = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "linker", bodyMd = "[[Gone]]"))
        val gone = r.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Gone", bodyMd = "g"))
        r.deleteDocument(gone.id)
        idx.replaceEdges(
            linker.id,
            setOf(EdgeKind.WIKILINK),
            listOf(Edge(srcId = linker.id, dstId = gone.id, kind = EdgeKind.WIKILINK, createdAt = 1L)),
        )
        r.completeIngest(linker.id, r.dequeueIngest(10).single { it.docId == linker.id }.queuedAt)
        return linker.id to gone.id
    }

    /**
     * Collects [flow] live and runs [body] against its emissions, on a real
     * dispatcher with a real timeout: the SQL-backed impl queries on
     * `Dispatchers.IO`, which `runTest`'s virtual clock cannot wait for.
     */
    private suspend fun <T> live(
        flow: Flow<T>,
        body: suspend (ReceiveChannel<T>) -> Unit,
    ) {
        withContext(Dispatchers.Default) {
            val seen = Channel<T>(Channel.UNLIMITED)
            val job = launch { flow.collect { seen.send(it) } }
            try {
                withTimeout(LIVE_TIMEOUT_MS) { body(seen) }
            } finally {
                job.cancel()
            }
        }
    }

    /**
     * Takes the first (`onStart`) emission, then repeats [poke] until its
     * effect arrives live. Only a change tick can deliver it, so from then on
     * the collector is subscribed and cannot miss the next write's tick.
     */
    private suspend fun <T> ReceiveChannel<T>.primeWith(
        poke: suspend (Int) -> Unit,
        landed: (T, Int) -> Boolean,
    ) {
        receive()
        var i = 0
        while (true) {
            i++
            poke(i)
            val n = i
            if (withTimeoutOrNull(TICK_WAIT_MS) { awaitMatching { landed(it, n) } } != null) return
        }
    }

    private suspend fun <T> ReceiveChannel<T>.awaitMatching(predicate: (T) -> Boolean): T {
        while (true) {
            val value = receive()
            if (predicate(value)) return value
        }
    }

    private suspend fun assertThrowsNoSuchElement(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: NoSuchElementException) {
            return
        }
        fail("expected NoSuchElementException")
    }

    private fun citationRecordFor(
        docId: String,
        revisionHash: String,
    ): CitationRecord =
        CitationRecord(
            retrieved =
                listOf(
                    Citation(
                        marker = 1,
                        documentId = docId,
                        revisionHash = revisionHash,
                        locator = Locator(byteStart = 4, byteEnd = 4 + CITED_EXCERPT.length, chunkOrd = 0),
                        excerpt = CITED_EXCERPT,
                        sourceKind = CitationSourceKind.VECTOR,
                    ),
                ),
            cited = listOf(1),
        )

    private companion object {
        const val NOTE_BODY: String = "the quantum paragraph the assistant cited"
        const val CITED_EXCERPT: String = "quantum paragraph"

        /** Real-time bound for a live-flow test ([live]). */
        const val LIVE_TIMEOUT_MS: Long = 10_000L

        /** How long [primeWith] waits for one poke to arrive before poking again. */
        const val TICK_WAIT_MS: Long = 200L
    }
}
