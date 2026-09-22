// `IngestPipeline` (bd skein-7v3, E5.I10) tests over the JVM fakes
// (`InMemoryVaultRepository`, `InMemoryIndexStore`, `FakeEmbedderService`,
// `SyntheticVault.Preset.SMALL`) with the real `Chunker` (over
// `ApproximateTokenizer`) and the real `EdgeUpserter`/`DanglingResolver`
// (`:core:vault`, test-only dependency) wired through `LinkStep`.

package app.skein.core.rag.ingest

import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.testing.SkeinLogCaptureRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.IngestItem
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.ModelFormat
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.VaultRepository
import us.aherrera.skein.testing.FakeEmbedderService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository
import us.aherrera.skein.testing.fixtures.SyntheticVault

class IngestPipelineTest {
    /** Also fails any test in which a captured `SkeinLog` entry carries content (spec §9). */
    @get:Rule
    val logCapture = SkeinLogCaptureRule()

    // ---- happy path over the synthetic vault ----------------------------------

    @Test
    fun `a run over the SMALL synthetic vault drains the queue`() =
        runTest {
            // Arrange
            val h = Harness().seeded()
            // Act
            val outcome = h.pipeline().run()
            // Assert
            assertThat(outcome).isInstanceOf(IngestOutcome.Drained::class.java)
            assertThat(h.repository.peekIngestQueue()).isEmpty()
        }

    @Test
    fun `after a run every queued note has FTS-searchable chunks`() =
        runTest {
            val h = Harness().seeded()
            val queued =
                h.repository
                    .peekIngestQueue()
                    .map { it.docId }
                    .toSet()

            h.pipeline().run()

            // Every note body in the fixture contains this phrase.
            val hits = h.index.bm25("Key points", k = 1_000)
            val docsWithHits =
                h.index
                    .getChunks(hits.map { it.chunkId })
                    .values
                    .map { it.docId }
                    .toSet()
            val notes = queued.filter { h.repository.getDocument(it)?.kind == DocumentKind.NOTE }
            assertThat(docsWithHits).containsAtLeastElementsIn(notes)
        }

    @Test
    fun `after a run every fixture wikilink is an edge — resolved to its note, or a sentinel when dangling`() =
        runTest {
            val h = Harness().seeded()
            val notes =
                h.repository
                    .peekIngestQueue()
                    .mapNotNull { h.repository.getDocument(it.docId) }
                    .filter { it.kind == DocumentKind.NOTE }

            h.pipeline().run()

            var checked = 0
            for (note in notes) {
                val targets = wikilinkTarget.findAll(note.bodyMd!!).map { it.groupValues[1] }.toSet()
                if (targets.isEmpty()) continue
                val dstIds =
                    h.index
                        .edgesFrom(note.id)
                        .filter { it.kind == EdgeKind.WIKILINK }
                        .map { it.dstId }
                        .toSet()
                for (target in targets) {
                    val expected = h.repository.findByTitle(target)?.id ?: EdgeUpserter.unresolvedTarget(target)
                    assertThat(dstIds).contains(expected)
                    checked++
                }
            }
            assertThat(checked).isGreaterThan(0)
        }

    @Test
    fun `completeIngest is called exactly once per drained entry`() =
        runTest {
            val h = Harness().seeded()
            val queued = h.repository.peekIngestQueue().map { it.docId }

            h.pipeline().run()

            assertThat(h.repository.completeCalls.map { it.first }).containsExactlyElementsIn(queued)
        }

    // ---- dangling links resolve on the created note's ingest -----------------

    @Test
    fun `a dangling wikilink resolves when the target note is created and ingested`() =
        runTest {
            // Arrange — A links to a not-yet-existing B.
            val h = Harness()
            val a = h.note("A", "See [[B]] for details.")
            h.pipeline().run()
            val sentinel = EdgeUpserter.unresolvedTarget("B")
            assertThat(h.index.edgesTo(sentinel, EdgeKind.WIKILINK).map { it.srcId }).containsExactly(a.id)
            // Act — B is created; its `created` ingest runs the resolver.
            val b = h.note("B", "I am B.")
            h.pipeline().run()
            // Assert
            assertThat(h.index.edgesTo(sentinel, EdgeKind.WIKILINK)).isEmpty()
            val resolved = h.index.edgesTo(b.id, EdgeKind.WIKILINK)
            assertThat(resolved.map { it.srcId }).containsExactly(a.id)
            assertThat(resolved.single().weight).isEqualTo(EdgeKind.WIKILINK.weight)
        }

    // ---- vectors: skipped without an embedder, written with one -----------------

    @Test
    fun `without an embedder chunks are written but no vectors, and the outcome counts them as pending`() =
        runTest {
            val h = Harness()
            h.note("Vectorless", "Some body text about nothing in particular.")

            val outcome = h.pipeline(embedder = null).run()

            assertThat(outcome.processed).isEqualTo(1)
            assertThat(outcome.vectorsPending).isEqualTo(1)
            val chunk = h.index.chunksForDocs(setOf(h.lastDocId), limitPerDoc = 10).single()
            assertThat(chunk.embedderId).isEqualTo(IngestSteps.PENDING_EMBEDDER_ID)
            assertThat(chunk.embedderVersion).isEqualTo(IngestSteps.PENDING_EMBEDDER_VERSION)
            assertThat(h.index.knn(ByteArray(256), k = 10)).isEmpty()
        }

    @Test
    fun `with a loaded FakeEmbedderService vectors are written and nothing is pending`() =
        runTest {
            val h = Harness()
            h.note("Vectored", "Some body text about nothing in particular.")
            val embedder = loadedEmbedder()

            val outcome = h.pipeline(embedder = embedder).run()

            assertThat(outcome.vectorsPending).isEqualTo(0)
            val chunk = h.index.chunksForDocs(setOf(h.lastDocId), limitPerDoc = 10).single()
            assertThat(chunk.embedderId).isEqualTo(embedder.embedderId)
            val hits = h.index.knn(embedder.embedQuery("body text"), k = 10)
            assertThat(hits.map { it.chunkId }).containsExactly(chunk.id)
        }

    // ---- cancellation checkpoints ------------------------------------------------

    @Test
    fun `cancellation mid-document leaves the entry queued and never completes it`() =
        runTest {
            // Arrange — the link step parks until cancelled.
            val h = Harness()
            h.note("Interrupted", "Body with [[Somewhere]].")
            val entered = CompletableDeferred<Unit>()
            val pipeline =
                h.pipeline(
                    links = {
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                )
            // Act
            val job = launch { pipeline.run() }
            entered.await()
            job.cancel()
            job.join()
            // Assert
            assertThat(h.repository.completeCalls).isEmpty()
            assertThat(h.repository.peekIngestQueue().map { it.docId }).containsExactly(h.lastDocId)
        }

    @Test
    fun `cancellation propagates out of the pipeline rather than being swallowed as a step failure`() =
        runTest {
            val h = Harness()
            h.note("Interrupted", "Body.")
            val warnings = mutableListOf<String>()
            val pipeline = h.pipeline(links = { throw CancellationException("lock") }, warn = { warnings += it })

            val thrown = runCatching { pipeline.ingest(h.repository.peekIngestQueue().single()) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            assertThat(warnings).isEmpty()
        }

    @Test
    fun `a rerun after a cancelled run converges to the same index state as a clean run`() =
        runTest {
            // Arrange — two identical vaults; one is interrupted after the lexical step.
            val clean = Harness()
            clean.note("Doc", "Body with [[Target]] and #tag.")
            clean.pipeline().run()
            val interrupted = Harness()
            interrupted.note("Doc", "Body with [[Target]] and #tag.")
            val entered = CompletableDeferred<Unit>()
            var park = true
            val pipeline =
                interrupted.pipeline(
                    links = { document ->
                        if (park) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                        interrupted.realLinks.link(document)
                    },
                )
            val job = launch { pipeline.run() }
            entered.await()
            job.cancel()
            job.join()
            park = false
            // Act
            pipeline.run()
            // Assert
            assertThat(interrupted.repository.peekIngestQueue()).isEmpty()
            assertThat(edgesOf(interrupted, interrupted.lastDocId)).isEqualTo(edgesOf(clean, clean.lastDocId))
            assertThat(chunkTextsOf(interrupted, interrupted.lastDocId)).isEqualTo(chunkTextsOf(clean, clean.lastDocId))
        }

    // ---- pacing ---------------------------------------------------------------

    @Test
    fun `PAUSED before the first dequeue stops with Paused and touches nothing`() =
        runTest {
            val h = Harness().seeded()
            val before = h.repository.peekIngestQueue().size

            val outcome = h.pipeline(pace = { IngestPace.PAUSED }).run()

            assertThat(outcome).isEqualTo(IngestOutcome.Paused(0, 0))
            assertThat(h.repository.peekIngestQueue()).hasSize(before)
        }

    @Test
    fun `PAUSED between documents stops early and leaves the rest queued`() =
        runTest {
            val h = Harness()
            repeat(5) { h.note("N$it", "Body $it.") }
            var calls = 0
            // FULL for the dequeue and the first two documents, then PAUSED.
            val pipeline = h.pipeline(pace = { if (calls++ < 3) IngestPace.FULL else IngestPace.PAUSED })

            val outcome = pipeline.run()

            assertThat(outcome).isInstanceOf(IngestOutcome.Paused::class.java)
            assertThat(outcome.processed).isEqualTo(2)
            assertThat(h.repository.peekIngestQueue()).hasSize(3)
        }

    @Test
    fun `LOCKED between documents stops with Locked`() =
        runTest {
            val h = Harness()
            repeat(3) { h.note("N$it", "Body $it.") }
            var calls = 0
            val pipeline = h.pipeline(pace = { if (calls++ < 2) IngestPace.FULL else IngestPace.LOCKED })

            val outcome = pipeline.run()

            assertThat(outcome).isEqualTo(IngestOutcome.Locked(1, 1))
            assertThat(h.repository.peekIngestQueue()).hasSize(2)
        }

    @Test
    fun `REDUCED dequeues 16 entries at a time and FULL dequeues 32`() =
        runTest {
            for ((pace, expected) in listOf(IngestPace.REDUCED to 16, IngestPace.FULL to 32)) {
                val h = Harness()
                repeat(40) { h.note("N$it", "Body $it.") }

                h.pipeline(pace = { pace }).run()

                assertThat(h.repository.dequeueLimits.first()).isEqualTo(expected)
            }
        }

    // ---- failure isolation -------------------------------------------------------

    @Test
    fun `a failing entity step leaves the document chunked, linked and completed`() =
        runTest {
            val h = Harness()
            h.note("Entities", "Body with [[Elsewhere]].")
            val warnings = mutableListOf<String>()

            val outcome =
                h
                    .pipeline(entities = { throw IllegalStateException("gliner exploded") }, warn = { warnings += it })
                    .run()

            assertThat(outcome.processed).isEqualTo(1)
            assertThat(h.repository.peekIngestQueue()).isEmpty()
            assertThat(h.index.chunksForDocs(setOf(h.lastDocId), limitPerDoc = 10)).isNotEmpty()
            assertThat(h.index.edgesFrom(h.lastDocId).map { it.kind }).contains(EdgeKind.WIKILINK)
            assertThat(warnings.single()).contains("entity step failed")
            assertThat(warnings.single()).doesNotContain("Elsewhere")
        }

    @Test
    fun `a failing link step leaves the entry queued but does not block the rest of the run`() =
        runTest {
            val h = Harness()
            val poisoned = h.note("Poisoned", "Body.")
            h.note("Healthy", "Body.")
            val warnings = mutableListOf<String>()
            val pipeline =
                h.pipeline(
                    links = { document ->
                        if (document.id == poisoned.id) throw IllegalStateException("boom")
                        h.realLinks.link(document)
                    },
                    warn = { warnings += it },
                )

            val outcome = pipeline.run()

            assertThat(outcome).isEqualTo(IngestOutcome.Drained(1, 1))
            assertThat(h.repository.peekIngestQueue().map { it.docId }).containsExactly(poisoned.id)
            assertThat(warnings.single()).contains("link step failed")
        }

    // ---- bounded retries (persisted ingest_queue.attempts, migration 008, skein-zx15) ----

    @Test
    fun `a document failing a mandatory step stays queued for two runs and is dropped on the third, without content`() =
        runTest {
            // Arrange — every pipeline() call shares the same repository, so
            // the persisted attempts counter (not an in-memory object this
            // test has to thread through) is what carries the count across
            // the three separate `run()` calls below.
            val h = Harness()
            val poisoned = h.note("Poisoned", "Body with secret details.")
            val warnings = mutableListOf<String>()

            fun pipeline() = h.pipeline(links = { throw IllegalStateException("boom") }, warn = { warnings += it })
            // Act
            pipeline().run()
            pipeline().run()
            val queuedAfterTwo = h.repository.peekIngestQueue().map { it.docId }
            val completedAfterTwo = h.repository.completeCalls.toList()
            val third = pipeline().run()
            // Assert
            assertThat(queuedAfterTwo).containsExactly(poisoned.id)
            assertThat(completedAfterTwo).isEmpty()
            assertThat(third).isEqualTo(IngestOutcome.Drained(0, 0))
            assertThat(h.repository.peekIngestQueue()).isEmpty()
            assertThat(h.repository.completeCalls.map { it.first }).containsExactly(poisoned.id)
            assertThat(warnings).hasSize(3)
            assertThat(warnings.last()).contains("dropping")
            warnings.forEach {
                assertThat(it).doesNotContain("secret")
                assertThat(it).doesNotContain("Poisoned")
            }
        }

    @Test
    fun `a successful ingest resets the document's persisted failure count`() =
        runTest {
            // Arrange — fail twice, succeed once, then fail twice more after an edit re-queues the note.
            val h = Harness()
            val doc = h.note("Flaky", "Body.")
            var fail = true

            fun pipeline() =
                h.pipeline(
                    links = { document ->
                        if (fail) throw IllegalStateException("boom")
                        h.realLinks.link(document)
                    },
                )
            pipeline().run()
            pipeline().run()
            fail = false
            pipeline().run()
            h.repository.updateBody(doc.id, doc.title, "Edited body.")
            fail = true
            // Act — without the reset these would be the fourth and fifth failures and the entry dropped.
            pipeline().run()
            pipeline().run()
            // Assert
            val queued = h.repository.peekIngestQueue()
            assertThat(queued.map { it.docId }).containsExactly(doc.id)
            assertThat(queued.single().attempts).isEqualTo(2)
        }

    @Test
    fun `the persisted failure count survives a fresh IngestPipeline instance sharing the same repository`() =
        runTest {
            // Proves the counter is no longer the pipeline-local IngestAttempts
            // object skein-7v3 shipped as a stand-in: a *different*
            // IngestPipeline built straight from IngestPipeline's own
            // constructor (bypassing the Harness factory that used to thread
            // a shared IngestAttempts through) still sees the prior failures.
            val h = Harness()
            h.note("Poisoned", "Body.")
            val failingLinks = LinkStep { throw IllegalStateException("boom") }

            fun freshPipeline() =
                IngestPipeline(
                    repository = h.repository,
                    chunker = Chunker(ApproximateTokenizer),
                    steps = IngestSteps(h.index),
                    links = failingLinks,
                )
            freshPipeline().run()
            freshPipeline().run()

            val third = freshPipeline().run()

            assertThat(third).isEqualTo(IngestOutcome.Drained(0, 0))
            assertThat(h.repository.peekIngestQueue()).isEmpty()
        }

    // ---- revision hash + byte-offset stamping (migration 003 + 008, skein-zx15 / skein-s9hm) ----

    @Test
    fun `ingest stamps chunks_revision_hash equal to the repository's current revision for the document`() =
        runTest {
            val h = Harness()
            val doc = h.note("Revisioned", "Some body text for revision stamping.")

            h.pipeline().run()

            val expected = h.repository.currentRevision(doc.id)?.revisionHash
            assertThat(expected).isNotNull()
            val chunkIds = h.index.chunksForDocs(setOf(doc.id), limitPerDoc = 10).map { it.id }
            assertThat(chunkIds).isNotEmpty()
            for (id in chunkIds) assertThat(h.index.revisionHashOf(id)).isEqualTo(expected)
        }

    @Test
    fun `re-ingesting an unchanged document is idempotent — same revision hash, single revision row`() =
        runTest {
            val h = Harness()
            val doc = h.note("Stable", "Unchanging body text.")

            h.pipeline().run()
            val firstHash = h.repository.currentRevision(doc.id)?.revisionHash
            val firstChunkIds =
                h.index
                    .chunksForDocs(setOf(doc.id), limitPerDoc = 10)
                    .map { it.id }
                    .toSet()

            // Re-queue the same, unchanged content and ingest again.
            h.repository.forceQueue(IngestItem(doc.id, us.aherrera.skein.core.model.IngestReason.UPDATED, 999_999L))
            h.pipeline().run()

            val secondHash = h.repository.currentRevision(doc.id)?.revisionHash
            val secondChunkIds = h.index.chunksForDocs(setOf(doc.id), limitPerDoc = 10).map { it.id }
            assertThat(secondHash).isEqualTo(firstHash)
            for (id in secondChunkIds) assertThat(h.index.revisionHashOf(id)).isEqualTo(firstHash)
            // replaceChunks always deletes+reinserts, so ids may differ, but
            // there is still exactly one row per original chunk.
            assertThat(secondChunkIds).hasSize(firstChunkIds.size)
        }

    @Test
    fun `chunk byte offsets are UTF-8, not UTF-16 char offsets, for a multi-byte body`() =
        runTest {
            val h = Harness()
            // "café — 日本" — é is 2 UTF-8 bytes vs 1 char; the em dash is 3
            // bytes vs 1 char; each CJK character is 3 bytes vs 1 char. The
            // whole line's UTF-8 byte length exceeds its UTF-16 char length,
            // so a correct byte_end must differ from the char end skein-s9hm
            // flagged core/rag's `Chunk.end` as being.
            val body = "café — 日本"
            val doc = h.note("Multibyte", body)

            h.pipeline().run()

            val chunk = h.index.chunksForDocs(setOf(doc.id), limitPerDoc = 10).single()
            val range = h.index.byteRangeOf(chunk.id)
            assertThat(range).isNotNull()
            val (byteStart, byteEnd) = requireNotNull(range)
            assertThat(byteStart).isEqualTo(0)
            assertThat(byteEnd).isEqualTo(body.toByteArray(Charsets.UTF_8).size)
            assertThat(byteEnd).isNotEqualTo(body.length)
        }

    @Test
    fun `the default warn sink is SkeinLog under the IngestPipeline tag`() =
        runTest {
            val h = Harness()
            h.note("Entities", "Body.")
            val pipeline =
                IngestPipeline(
                    repository = h.repository,
                    chunker = Chunker(ApproximateTokenizer),
                    steps = IngestSteps(h.index),
                    links = h.realLinks,
                    entities = { throw IllegalStateException("gliner exploded") },
                )

            pipeline.run()

            val entry = logCapture.captured().single { it.tag == IngestPipeline.TAG }
            assertThat(entry.message).contains("entity step failed")
            assertThat(entry.isSensitive).isFalse()
        }

    @Test
    fun `a document that changes mid-run stays queued for the next dequeue`() =
        runTest {
            val h = Harness()
            val doc = h.note("Moving", "First body.")
            var edited = false
            val pipeline =
                h.pipeline(
                    links = { document ->
                        if (!edited) {
                            edited = true
                            h.repository.updateBody(document.id, document.title, "Second body.")
                        }
                        h.realLinks.link(document)
                    },
                )

            val outcome = pipeline.run()

            // The first pass completed a stale entry (no-op), the second
            // pass indexed the new body and cleared the bumped entry.
            assertThat(outcome.processed).isEqualTo(2)
            assertThat(h.repository.peekIngestQueue()).isEmpty()
            assertThat(chunkTextsOf(h, doc.id).single()).contains("Second body")
        }

    @Test
    fun `a deleted document's entry is completed without indexing`() =
        runTest {
            val h = Harness()
            val doc = h.note("Gone", "Body.")
            h.repository.deleteDocument(doc.id)
            h.repository.forceQueue(IngestItem(doc.id, us.aherrera.skein.core.model.IngestReason.CREATED, 1L))

            val outcome = h.pipeline().run()

            assertThat(outcome).isEqualTo(IngestOutcome.Drained(0, 0))
            assertThat(h.repository.peekIngestQueue()).isEmpty()
            assertThat(h.index.chunksForDocs(setOf(doc.id), limitPerDoc = 10)).isEmpty()
        }

    @Test
    fun `run reports progress after every completed entry`() =
        runTest {
            val h = Harness()
            repeat(3) { h.note("N$it", "Body $it.") }
            val seen = mutableListOf<Int>()

            h.pipeline().run(onProgress = { seen += it })

            assertThat(seen).containsExactly(1, 2, 3).inOrder()
        }

    // ------------------------------------------------------------------

    private val wikilinkTarget = Regex("""\[\[([^\]|#]+)""")

    private suspend fun edgesOf(
        h: Harness,
        docId: DocId,
    ) = h.index
        .edgesFrom(docId)
        .map { Triple(it.dstId, it.kind, it.weight) }
        .toSet()

    private suspend fun chunkTextsOf(
        h: Harness,
        docId: DocId,
    ) = h.index.chunksForDocs(setOf(docId), limitPerDoc = 100).map { it.text }

    private suspend fun loadedEmbedder(): EmbedderService {
        val embedder = FakeEmbedderService()
        embedder
            .load(
                embed =
                    Model(
                        id = "fake-embed-model",
                        name = "Fake Embed Model",
                        path = "/dev/null/fake-embed-model.onnx",
                        sha256 = "b".repeat(64),
                        format = ModelFormat.ONNX,
                        capabilities = setOf(Capability.EMBEDDING),
                        sizeBytes = 1_000L,
                    ),
                ner = null,
                rerank = null,
            ).getOrThrow()
        return embedder
    }

    /**
     * `InMemoryVaultRepository` that records `completeIngest`/`dequeueIngest`
     * calls and lets a test plant a queue row for a document that no longer
     * exists. The clock is a strict counter so two writes never share a
     * `queued_at` (the real DB's millisecond clock has the same edge, just
     * far harder to hit than in a test).
     */
    private class RecordingVault(
        private val delegate: InMemoryVaultRepository,
    ) : VaultRepository by delegate {
        val completeCalls = mutableListOf<Pair<DocId, Long>>()
        val dequeueLimits = mutableListOf<Int>()
        private val planted = linkedMapOf<DocId, IngestItem>()

        override suspend fun dequeueIngest(limit: Int): List<IngestItem> {
            dequeueLimits += limit
            return (planted.values + delegate.dequeueIngest(limit)).sortedBy { it.queuedAt }.take(limit)
        }

        override suspend fun completeIngest(
            docId: DocId,
            queuedAt: Long,
        ) {
            completeCalls += docId to queuedAt
            if (planted[docId]?.queuedAt == queuedAt) planted.remove(docId)
            delegate.completeIngest(docId, queuedAt)
        }

        fun peekIngestQueue(): List<IngestItem> = planted.values + delegate.peekIngestQueue()

        fun forceQueue(item: IngestItem) {
            planted[item.docId] = item
        }
    }

    private class Harness {
        private var now = 1_700_000_000_000L
        val repository = RecordingVault(InMemoryVaultRepository(clock = { ++now }))
        val index = InMemoryIndexStore()
        val realLinks: LinkStep =
            object : LinkStep {
                private val upserter = EdgeUpserter(repository, index)
                private val resolver = DanglingResolver(repository, index)

                override suspend fun link(document: Document) {
                    upserter.upsert(document)
                    resolver.resolveFor(document)
                }
            }

        var lastDocId: DocId = ""
            private set

        fun seeded(): Harness {
            SyntheticVault.seed(repository, size = SyntheticVault.Preset.SMALL)
            return this
        }

        suspend fun note(
            title: String,
            body: String,
        ): Document =
            repository
                .createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))
                .also { lastDocId = it.id }

        fun pipeline(
            embedder: EmbedderService? = null,
            links: LinkStep = realLinks,
            entities: EntityStep? = null,
            pace: () -> IngestPace = { IngestPace.FULL },
            warn: (String) -> Unit = {},
        ): IngestPipeline =
            IngestPipeline(
                repository = repository,
                chunker = Chunker(ApproximateTokenizer),
                steps = IngestSteps(index, embedder, warn),
                links = links,
                entities = entities,
                pace = pace,
                warn = warn,
            )
    }
}
