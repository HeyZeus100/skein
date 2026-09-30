package app.skein.core.rag.retrieval

import app.skein.core.model.ContextualEvidenceMode
import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.RetrievalFollowUpContext
import app.skein.core.model.RetrievalSourcePin
import app.skein.core.model.ScoredChunk
import app.skein.core.rag.rank.RankerConfig
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextualRetrievalServiceTest {
    @Test
    fun `legacy overload remains unchanged when opt-in selector refuses missing provenance`() =
        runTest {
            val fixture = fixture(anchored = false)
            assertThat(
                fixture.service
                    .retrieveContext("Hooks")
                    .single()
                    .text,
            ).isEqualTo("Seven hooks")
            val candidate = fixture.service.retrieveContext(ContextualRetrievalRequest("Hooks"))
            assertThat(candidate.evidence).isEmpty()
            assertThat(candidate.originalCandidates).hasSize(1)
            assertThat(candidate.exclusionCounts).containsEntry("MISSING_REVISION", 1)
        }

    @Test
    fun `opt-in expands verified structure while old overload still returns indexed chunk`() =
        runTest {
            val fixture = fixture()
            val original = fixture.service.retrieveContext("Hooks").single()
            val candidate = fixture.service.retrieveContext(ContextualRetrievalRequest("Hooks"))
            assertThat(original.text).isEqualTo("Seven hooks")
            assertThat(candidate.evidence.single().text).isEqualTo("Seven hooks. The box is violet.")
            assertThat(candidate.originalCandidates).containsExactly(original)
            assertThat(candidate.evidenceMembers).containsExactly(original.chunkId, listOf(original))
            assertThat(candidate.resolution).isEqualTo(FollowUpResolution.DIRECT)
            assertThat(candidate.originalQuery).isEqualTo("Hooks")
        }

    @Test
    fun `recall expansion cannot answer a raw unsupported follow-up by substituting prior query`() =
        runTest {
            val fixture = fixture()
            val request =
                ContextualRetrievalRequest(
                    query = "Who manufactured it?",
                    followUp = context(fixture.doc),
                )
            val candidate = fixture.service.retrieveContext(request)
            assertThat(candidate.resolution).isEqualTo(FollowUpResolution.RESOLVED)
            assertThat(candidate.recallQuery).contains("Packing")
            assertThat(candidate.originalQuery).isEqualTo("Who manufactured it?")
            assertThat(candidate.originalCandidates).isNotEmpty()
            assertThat(candidate.evidence).isEmpty()
            assertThat(fixture.service.acceptsEvidence(candidate.originalQuery, candidate.originalCandidates)).isFalse()
            assertThat(fixture.service.acceptsEvidence("hooks", candidate.originalCandidates)).isTrue()
        }

    @Test
    fun `a resolved follow-up cannot promote another source returned by recall`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val store = InMemoryIndexStore()
            val anchor = seed(repo, store, "Packing", "Seven hooks. The box is violet.", "Seven hooks")
            val other =
                seed(repo, store, "Another box", "Color of another box is white.", "Color of another box is white.")
            val index =
                object : IndexStore by store {
                    override suspend fun bm25(
                        query: String,
                        k: Int,
                    ) = listOf(ScoredChunk(other.second, 2.0), ScoredChunk(anchor.second, 1.0))
                }
            val result =
                service(repo, index).retrieveContext(
                    ContextualRetrievalRequest("hooks", followUp = context(anchor.first)),
                )
            assertThat(result.originalCandidates).hasSize(2)
            assertThat(result.evidence.map { it.docId }).containsExactly(anchor.first.id)
            assertThat(result.exclusionCounts).containsEntry("OUTSIDE_FOLLOW_UP_ANCHOR", 1)
        }

    @Test
    fun `edit during recall refuses old context even if new matching chunks appear`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val store = InMemoryIndexStore()
            val seeded = seed(repo, store, "Packing", "Seven hooks. The box is violet.", "Seven hooks")
            val index =
                object : IndexStore by store {
                    override suspend fun bm25(
                        query: String,
                        k: Int,
                    ): List<ScoredChunk> {
                        repo.updateBody(seeded.first.id, "Packing", "Eight hooks. The box is white.")
                        return listOf(ScoredChunk(seeded.second, 1.0))
                    }
                }
            val result =
                service(repo, index).retrieveContext(
                    ContextualRetrievalRequest("hooks", followUp = context(seeded.first)),
                )
            assertThat(result.resolution).isEqualTo(FollowUpResolution.SOURCE_CHANGED)
            assertThat(result.evidence).isEmpty()
        }

    @Test
    fun `unresolved context does not recall or manufacture a direct query fallback`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val store = InMemoryIndexStore()
            val index =
                object : IndexStore by store {
                    override suspend fun bm25(
                        query: String,
                        k: Int,
                    ): List<ScoredChunk> = error("recall must not run")
                }
            val result =
                service(repo, index).retrieveContext(
                    ContextualRetrievalRequest(
                        "Its color?",
                        followUp = RetrievalFollowUpContext("Hooks?", emptyList()),
                    ),
                )
            assertThat(result.resolution).isEqualTo(FollowUpResolution.MISSING_CONTEXT)
            assertThat(result.originalCandidates).isEmpty()
            assertThat(result.evidence).isEmpty()
        }

    @Test
    fun `indexed followup validates without expanding selected source bytes or changing scores`() =
        runTest {
            val fixture = fixture()
            val request =
                ContextualRetrievalRequest(
                    "hooks",
                    followUp = context(fixture.doc),
                    evidenceMode = ContextualEvidenceMode.INDEXED,
                )
            val result = fixture.service.retrieveContext(request)
            assertThat(result.evidence.single().text).isEqualTo("Seven hooks")
            assertThat(result.evidence).isEqualTo(result.originalCandidates)
            assertThat(fixture.service.isContextCurrent(request, result.evidence)).isTrue()
            val forged = result.evidence.single().copy(text = "Invented hooks")
            assertThat(fixture.service.isContextCurrent(request, listOf(forged))).isFalse()
        }

    @Test
    fun `freshness recheck rejects evidence from a document outside the resolved anchor`() =
        runTest {
            val fixture = fixture()
            val request =
                ContextualRetrievalRequest(
                    "hooks",
                    followUp = context(fixture.doc),
                    evidenceMode = ContextualEvidenceMode.INDEXED,
                )
            val evidence =
                fixture.service
                    .retrieveContext(request)
                    .evidence
                    .single()
            assertThat(fixture.service.isContextCurrent(request, listOf(evidence.copy(docId = "other")))).isFalse()
        }

    @Test
    fun `freshness recheck does not accept an unpinned direct request as contextual proof`() =
        runTest {
            val fixture = fixture()
            val request = ContextualRetrievalRequest("hooks", evidenceMode = ContextualEvidenceMode.INDEXED)
            assertThat(fixture.service.isContextCurrent(request, fixture.service.retrieveContext(request).evidence))
                .isFalse()
        }

    private data class Fixture(
        val doc: Document,
        val service: RetrievalServiceImpl,
    )

    private suspend fun fixture(anchored: Boolean = true): Fixture {
        val repo = InMemoryVaultRepository()
        val store = InMemoryIndexStore()
        val (doc, chunk) = seed(repo, store, "Packing", "Seven hooks. The box is violet.", "Seven hooks", anchored)
        val index =
            object : IndexStore by store {
                override suspend fun bm25(
                    query: String,
                    k: Int,
                ) = listOf(ScoredChunk(chunk, 0.0004))
            }
        return Fixture(doc, service(repo, index))
    }

    private fun service(
        repo: InMemoryVaultRepository,
        index: IndexStore,
    ) = RetrievalServiceImpl(
        index,
        repo,
        null,
        config = RankerConfig(recallWeight = 1.0, pprWeight = 0.0, neighborHops = 0),
        warn = {},
        stages = RecallStages(vector = false, graph = false),
    )

    private fun context(doc: Document) =
        RetrievalFollowUpContext(
            "How many hooks?",
            listOf(RetrievalSourcePin(doc.id, checkNotNull(doc.contentHash), null)),
        )

    private suspend fun seed(
        repo: InMemoryVaultRepository,
        store: InMemoryIndexStore,
        title: String,
        body: String,
        excerpt: String,
        anchored: Boolean = true,
    ): Pair<Document, Long> {
        val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, title, body))
        val start = body.substring(0, body.indexOf(excerpt)).toByteArray().size
        val id =
            store
                .replaceChunks(
                    doc.id,
                    listOf(NewChunk(0, excerpt, 4, byteStart = start, byteEnd = start + excerpt.toByteArray().size)),
                    "development-test",
                    1,
                    revisionHash = doc.contentHash.takeIf { anchored },
                ).single()
        return doc to id
    }
}
