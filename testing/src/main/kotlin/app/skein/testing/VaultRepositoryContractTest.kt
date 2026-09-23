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

import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.PersonaId
import app.skein.core.model.RevisionHashing
import app.skein.core.model.Role
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

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
    }
}
