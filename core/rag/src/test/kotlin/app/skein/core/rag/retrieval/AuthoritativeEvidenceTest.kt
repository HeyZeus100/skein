package app.skein.core.rag.retrieval

import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.ScoredChunk
import app.skein.core.rag.rank.PprRanker
import app.skein.core.rag.rank.RankerConfig
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeEvidenceTest {
    @Test
    fun `chat candidates cannot displace supplied notes before fusion and candidate cap`() =
        runTest {
            val fixture = Fixture()
            val chat = fixture.source(DocumentKind.CHAT, "Prior invented answer")
            val generated = fixture.source(DocumentKind.AIOUT, "Imported generated output")
            val note = fixture.source(DocumentKind.NOTE, "Source note")
            val file = fixture.source(DocumentKind.ATTACHMENT, "Imported source")
            val recall =
                mapOf(
                    CitationSourceKind.LEXICAL to
                        listOf(
                            ScoredChunk(chat.second, 1.0),
                            ScoredChunk(generated.second, 0.9),
                            ScoredChunk(note.second, 0.5),
                            ScoredChunk(file.second, 0.4),
                        ),
                    CitationSourceKind.GRAPH to listOf(ScoredChunk(chat.second, 1.0)),
                    CitationSourceKind.VECTOR to listOf(ScoredChunk(chat.second, 1.0)),
                )
            fixture.index.replaceEdges(
                note.first,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(note.first, chat.first, EdgeKind.WIKILINK, createdAt = 0L)),
            )
            val ranked =
                PprRanker(fixture.index, fixture.repo, RankerConfig(maxCandidates = 2))
                    .rank(recall, k = 2)
            assertEquals(setOf(note.second, file.second), ranked.map { it.chunkId }.toSet())
        }

    @Test
    fun `generated artifacts are excluded even when conversation search is explicitly enabled`() =
        runTest {
            val fixture = Fixture()
            fixture.source(DocumentKind.AIOUT, "brindle generated output", "work")
            val default = RetrievalServiceImpl(fixture.index, fixture.repo, embedder = null)
            val conversationSearch =
                RetrievalServiceImpl(fixture.index, fixture.repo, embedder = null, includeChatHistory = true)
            assertTrue(default.retrieveContext("brindle", k = 8, personaId = "work").isEmpty())
            assertTrue(conversationSearch.retrieveContext("brindle", k = 8, personaId = "work").isEmpty())
        }

    @Test
    fun `automatic Knowledge retrieval returns no evidence from an all-chat match`() =
        runTest {
            val fixture = Fixture()
            fixture.source(DocumentKind.CHAT, "brindle")
            val default = RetrievalServiceImpl(fixture.index, fixture.repo, embedder = null)
            assertTrue(default.retrieveContext("brindle", k = 8, personaId = null).isEmpty())
        }

    @Test
    fun `explicit history retrieval preserves CHAT provenance and still respects Space`() =
        runTest {
            val fixture = Fixture()
            val current = fixture.source(DocumentKind.CHAT, "brindle current", "work")
            fixture.source(DocumentKind.CHAT, "brindle forbidden", "personal")
            val explicit =
                RetrievalServiceImpl(
                    fixture.index,
                    fixture.repo,
                    embedder = null,
                    legacyPersonaId = "default",
                    includeChatHistory = true,
                )
            val results = explicit.retrieveContext("brindle", k = 8, personaId = "work")
            assertEquals(listOf(current.first), results.map { it.docId })
            assertEquals(listOf(DocumentKind.CHAT), results.map { it.sourceKind })
        }

    @Test
    fun `ordinary notes remain eligible while matching chat history is excluded`() =
        runTest {
            val fixture = Fixture()
            fixture.source(DocumentKind.CHAT, "brindle conversation")
            val note = fixture.source(DocumentKind.NOTE, "brindle source")
            val default = RetrievalServiceImpl(fixture.index, fixture.repo, embedder = null)
            val results = default.retrieveContext("brindle", k = 1, personaId = null)
            assertEquals(listOf(note.first), results.map { it.docId })
            assertEquals(listOf(DocumentKind.NOTE), results.map { it.sourceKind })
        }

    private class Fixture {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()

        suspend fun source(
            kind: DocumentKind,
            text: String,
            personaId: String? = null,
        ): Pair<String, Long> {
            val document = repo.createDocument(NewDocument(kind, text, text, personaId = personaId))
            val chunk =
                index
                    .replaceChunks(
                        document.id,
                        listOf(NewChunk(0, text, text.length)),
                        embedderId = "pending",
                        embedderVersion = 0,
                    ).single()
            return document.id to chunk
        }
    }
}
