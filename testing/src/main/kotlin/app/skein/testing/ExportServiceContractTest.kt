// The `E0.I14` contract suite: an abstract JUnit 4 test class any
// `ExportService` implementation — the `FakeExportService` here, and the
// eventual `VaultRepository`-backed implementation (`E2.I10`, `E2.I11`) —
// must satisfy.
//
// JUnit 4 (not 5) matches the surrounding codebase (see
// `InferenceEngineContractTest`, `VaultRepositoryContractTest`) and keeps
// this suite consumable by downstream modules via
// `testImplementation(project(":testing"))` without pulling the Vintage
// engine. Kept in `src/main` for the same reason those two are.

package app.skein.testing

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.ExportService
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Contract suite for `ExportService` (plan §4.5). Concrete subclasses
 * provide an implementation pre-seeded with the given documents; each test
 * then exercises one semantic of the locked contract.
 */
public abstract class ExportServiceContractTest {
    /** A fresh implementation, pre-seeded with [seed] as the exportable vault contents. */
    protected abstract fun service(seed: List<Document> = emptyList()): ExportService

    protected fun sampleDocument(
        id: String,
        title: String = "Doc $id",
        body: String = "body of $id",
    ): Document =
        Document(
            id = id,
            kind = DocumentKind.NOTE,
            title = title,
            bodyMd = body,
            createdAt = 0L,
            updatedAt = 0L,
            personaId = null,
            frontmatter = JsonObject(emptyMap()),
            contentHash = null,
        )

    // ------------------------------------------------------------------
    // `exportMarkdown` writes frontmatter ([FrontmatterKeys]) + body.
    // ------------------------------------------------------------------

    @Test
    public fun exportMarkdown_writes_frontmatter_id_and_body(): Unit =
        runTest {
            val doc = sampleDocument(id = "doc-1", body = "hello world")
            val service = service(seed = listOf(doc))

            val out = ByteArrayOutputStream()
            service.exportMarkdown(doc.id, out)
            val text = out.toString(Charsets.UTF_8)

            assertTrue("expected the output to open with a YAML frontmatter fence, was: $text", text.startsWith("---"))
            assertTrue("expected the frontmatter to carry the document's id", text.contains("id: ${doc.id}"))
            assertTrue("expected the rendered body to be present", text.contains("hello world"))
        }

    // ------------------------------------------------------------------
    // `exportMarkdown` of an unknown document fails rather than silently
    // writing nothing.
    // ------------------------------------------------------------------

    @Test
    public fun exportMarkdown_unknown_document_throws(): Unit =
        runTest {
            val service = service()
            val out = ByteArrayOutputStream()

            try {
                service.exportMarkdown("does-not-exist", out)
                fail("expected exportMarkdown to throw for an unknown document id")
            } catch (_: Exception) {
                // Any exception counts as "rejected" — the contract does not
                // pin a specific exception type, only that it must not
                // silently succeed.
            }
        }

    // ------------------------------------------------------------------
    // `exportVaultZip`: one entry per document, ascending progress that
    // ends at (total, total).
    // ------------------------------------------------------------------

    @Test
    public fun exportVaultZip_writes_one_entry_per_document_and_reports_ascending_progress(): Unit =
        runTest {
            val docs = listOf(sampleDocument("doc-1"), sampleDocument("doc-2"), sampleDocument("doc-3"))
            val service = service(seed = docs)
            val progressCalls = mutableListOf<Pair<Int, Int>>()

            val out = ByteArrayOutputStream()
            service.exportVaultZip(out) { done, total -> progressCalls.add(done to total) }

            assertEquals(
                "expected one progress callback per document",
                listOf(1 to 3, 2 to 3, 3 to 3),
                progressCalls,
            )

            var entryCount = 0
            ZipInputStream(out.toByteArray().inputStream()).use { zip ->
                while (zip.nextEntry != null) entryCount++
            }
            assertEquals("expected one zip entry per document", docs.size, entryCount)
        }

    // ------------------------------------------------------------------
    // Cancelling the collecting coroutine stops `exportVaultZip` promptly
    // — a real implementation streaming a large vault must remain
    // cancellable rather than running to completion regardless.
    // ------------------------------------------------------------------

    @Test
    public fun exportVaultZip_cancel_stops_within_100ms(): Unit =
        runTest {
            val manyDocs = (1..1_000).map { sampleDocument("doc-$it") }
            val service = service(seed = manyDocs)
            val out = ByteArrayOutputStream()

            withTimeout(1_000L) {
                val job: Job =
                    launch {
                        try {
                            service.exportVaultZip(out) { _, _ -> }
                        } catch (_: Throwable) {
                            // Any exception (including CancellationException) counts as "stopped".
                        }
                    }

                delay(1L)
                val startNs = System.nanoTime()
                job.cancelAndJoin()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                assertTrue("expected cancellation within 100 ms, took $elapsedMs ms", elapsedMs < 100L)
            }
        }

    // ------------------------------------------------------------------
    // `exportDocx` produces non-empty output, with or without a template.
    // ------------------------------------------------------------------

    @Test
    public fun exportDocx_without_template_produces_output(): Unit =
        runTest {
            val doc = sampleDocument("doc-1")
            val service = service(seed = listOf(doc))
            val out = ByteArrayOutputStream()

            service.exportDocx(doc.id, out, template = null)

            assertTrue("expected exportDocx to write bytes", out.size() > 0)
        }
}
