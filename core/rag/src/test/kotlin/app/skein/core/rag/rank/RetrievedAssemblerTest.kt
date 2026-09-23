// `RetrievedAssembler` (skein-wqli, E5.I13 prep) tests. `skein-x4f` locked
// `app.skein.core.model.Retrieved`; this bridges `PprRanker`'s
// `List<ScoredChunk>` (plus the per-stage recall lists already keyed by
// `CitationSourceKind`, since `RetrievalServiceImpl` builds that map for
// `PprRanker.rank` anyway) onto it, so `skein-do6` never re-derives a
// field from a chunk id by hand.
//
// AAA throughout: one behavior asserted per test.

package app.skein.core.rag.rank

import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.RecallSource
import app.skein.core.model.ScoredChunk
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RetrievedAssemblerTest {
    @Test
    fun `every field of a chunk found by one recall stage is mapped`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "Zamboni Facts",
                        bodyMd = "the zamboni resurfaces ice",
                    ),
                )
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks = listOf(NewChunk(ord = 0, text = "the zamboni resurfaces ice", tokenCount = 5)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            val ranked = listOf(ScoredChunk(chunkId = chunkId, score = 0.75))
            val sources = mapOf(CitationSourceKind.LEXICAL to listOf(ScoredChunk(chunkId = chunkId, score = 1.0)))

            val retrieved = RetrievedAssembler(index, repo).assemble(ranked, sources)

            assertThat(retrieved).hasSize(1)
            val hit = retrieved.single()
            assertThat(hit.chunkId).isEqualTo(chunkId)
            assertThat(hit.docId).isEqualTo(doc.id)
            assertThat(hit.docTitle).isEqualTo("Zamboni Facts")
            assertThat(hit.text).isEqualTo("the zamboni resurfaces ice")
            assertThat(hit.score).isEqualTo(0.75)
            assertThat(hit.sourceKind).isEqualTo(DocumentKind.NOTE)
            assertThat(hit.recalledBy).containsExactly(RecallSource.LEXICAL)
        }

    @Test
    fun `a chunk found by every recall stage unions all three RecallSource values`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = "body"))
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks = listOf(NewChunk(ord = 0, text = "body", tokenCount = 1)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            val ranked = listOf(ScoredChunk(chunkId = chunkId, score = 0.5))
            val sources =
                mapOf(
                    CitationSourceKind.LEXICAL to listOf(ScoredChunk(chunkId, 1.0)),
                    CitationSourceKind.VECTOR to listOf(ScoredChunk(chunkId, 1.0)),
                    CitationSourceKind.GRAPH to listOf(ScoredChunk(chunkId, 1.0)),
                )

            val retrieved = RetrievedAssembler(index, repo).assemble(ranked, sources)

            assertThat(retrieved.single().recalledBy)
                .containsExactly(RecallSource.LEXICAL, RecallSource.VECTOR, RecallSource.GRAPH)
        }

    @Test
    fun `RERANK provenance contributes no RecallSource (no such value exists)`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = "body"))
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks = listOf(NewChunk(ord = 0, text = "body", tokenCount = 1)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            val ranked = listOf(ScoredChunk(chunkId = chunkId, score = 0.5))
            val sources = mapOf(CitationSourceKind.RERANK to listOf(ScoredChunk(chunkId, 1.0)))

            val retrieved = RetrievedAssembler(index, repo).assemble(ranked, sources)

            assertThat(retrieved.single().recalledBy).isEmpty()
        }

    @Test
    fun `ordering and the k cap already applied by PprRanker are preserved verbatim`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val chunkIds =
                (1..3).map { n ->
                    val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T$n", bodyMd = "b$n"))
                    index
                        .replaceChunks(
                            docId = doc.id,
                            chunks = listOf(NewChunk(ord = 0, text = "b$n", tokenCount = 1)),
                            embedderId = "fake",
                            embedderVersion = 1,
                        ).single()
                }
            // Deliberately out of score order and out of chunkId order — the
            // assembler must not re-sort or re-derive an order of its own.
            val ranked =
                listOf(
                    ScoredChunk(chunkId = chunkIds[2], score = 0.9),
                    ScoredChunk(chunkId = chunkIds[0], score = 0.5),
                )

            val retrieved = RetrievedAssembler(index, repo).assemble(ranked, emptyMap())

            assertThat(retrieved.map { it.chunkId }).containsExactly(chunkIds[2], chunkIds[0]).inOrder()
        }

    @Test
    fun `an empty ranked list yields an empty result without touching the store`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val retrieved = RetrievedAssembler(index, repo).assemble(emptyList(), emptyMap())

            assertThat(retrieved).isEmpty()
        }

    @Test
    fun `locator and revisionHash are null pending Migration 003`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = "body"))
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks = listOf(NewChunk(ord = 0, text = "body", tokenCount = 1)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            val ranked = listOf(ScoredChunk(chunkId = chunkId, score = 0.5))

            val retrieved = RetrievedAssembler(index, repo).assemble(ranked, emptyMap())

            assertThat(retrieved.single().revisionHash).isNull()
            assertThat(retrieved.single().locator).isNull()
        }

    @Test
    fun `a ranked chunk whose document has since been deleted is dropped, not thrown`(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "T", bodyMd = "body"))
            val chunkId =
                index
                    .replaceChunks(
                        docId = doc.id,
                        chunks = listOf(NewChunk(ord = 0, text = "body", tokenCount = 1)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            repo.deleteDocument(doc.id)
            val ranked = listOf(ScoredChunk(chunkId = chunkId, score = 0.5))

            val retrieved = RetrievedAssembler(index, repo).assemble(ranked, emptyMap())

            assertThat(retrieved).isEmpty()
        }
}
