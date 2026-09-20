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

package us.aherrera.skein.testing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.NewMessage
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository
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
}
