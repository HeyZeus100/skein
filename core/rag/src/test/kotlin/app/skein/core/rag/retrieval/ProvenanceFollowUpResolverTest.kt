package app.skein.core.rag.retrieval

import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.NewDocument
import app.skein.core.model.RetrievalFollowUpContext
import app.skein.core.model.RetrievalSourcePin
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProvenanceFollowUpResolverTest {
    @Test
    fun `direct requests remain exactly unchanged`() =
        runTest {
            val result =
                ProvenanceFollowUpResolver(InMemoryVaultRepository())
                    .resolve(ContextualRetrievalRequest("Raw question?"))
            assertThat(result.originalQuery).isEqualTo("Raw question?")
            assertThat(result.recallQuery).isEqualTo(result.originalQuery)
            assertThat(result.resolution).isEqualTo(FollowUpResolution.DIRECT)
            assertThat(result.anchorIds).isEmpty()
        }

    @Test
    fun `one current source adds title and prior user question only to recall`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Copper packing", "Seven hooks. Violet box."))
            val result = ProvenanceFollowUpResolver(repo).resolve(request(doc))
            assertThat(result.originalQuery).isEqualTo("What color is its box?")
            assertThat(result.recallQuery).isEqualTo("What color is its box?\nCopper packing\nHow many hooks?")
            assertThat(result.resolution).isEqualTo(FollowUpResolution.RESOLVED)
            assertThat(result.anchorIds).containsExactly(doc.id)
        }

    @Test
    fun `missing context never guesses source from assistant prose`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val resolver = ProvenanceFollowUpResolver(repo)
            val result =
                resolver.resolve(
                    ContextualRetrievalRequest(
                        "Its color?",
                        followUp = RetrievalFollowUpContext("Hooks?", emptyList()),
                    ),
                )
            assertThat(result.resolution).isEqualTo(FollowUpResolution.MISSING_CONTEXT)
            assertThat(result.recallQuery).isEqualTo("Its color?")
        }

    @Test
    fun `two distinct cited sources refuse ambiguous expansion`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val first = repo.createDocument(NewDocument(DocumentKind.NOTE, "First", "Hooks."))
            val second = repo.createDocument(NewDocument(DocumentKind.NOTE, "Second", "Hooks."))
            val request =
                request(first).copy(
                    followUp = RetrievalFollowUpContext("Hooks?", listOf(pin(first), pin(second))),
                )
            assertThat(ProvenanceFollowUpResolver(repo).resolve(request).resolution)
                .isEqualTo(FollowUpResolution.AMBIGUOUS_CONTEXT)
        }

    @Test
    fun `duplicate identical pins are not ambiguous`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "First", "Hooks."))
            val request = request(doc).copy(followUp = RetrievalFollowUpContext("Hooks?", listOf(pin(doc), pin(doc))))
            assertThat(
                ProvenanceFollowUpResolver(repo).resolve(request).resolution,
            ).isEqualTo(FollowUpResolution.RESOLVED)
        }

    @Test
    fun `edited or deleted source cannot be silently repinned`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "Count", "Seven hooks."))
            val request = request(doc)
            repo.updateBody(doc.id, "Count", "Eight hooks.")
            val resolver = ProvenanceFollowUpResolver(repo)
            assertThat(resolver.resolve(request).resolution).isEqualTo(FollowUpResolution.SOURCE_CHANGED)
            repo.deleteDocument(doc.id)
            assertThat(resolver.resolve(request).resolution).isEqualTo(FollowUpResolution.SOURCE_UNAVAILABLE)
        }

    @Test
    fun `previous turn Space and current source Space both constrain anchors`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val shared = repo.createDocument(NewDocument(DocumentKind.NOTE, "Shared", "Hooks."))
            val private = repo.createDocument(NewDocument(DocumentKind.NOTE, "Private", "Hooks.", personaId = "B"))
            val resolver = ProvenanceFollowUpResolver(repo)
            assertThat(resolver.resolve(request(shared).copy(personaId = "A")).resolution)
                .isEqualTo(FollowUpResolution.OUT_OF_SCOPE)
            val forged =
                request(private).copy(
                    personaId = "A",
                    followUp = RetrievalFollowUpContext("Hooks?", listOf(pin(private).copy(personaId = "A"))),
                )
            assertThat(resolver.resolve(forged).resolution).isEqualTo(FollowUpResolution.OUT_OF_SCOPE)
        }

    @Test
    fun `generated source and excessive private context are refused`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val generated = repo.createDocument(NewDocument(DocumentKind.AIOUT, "Generated", "Hooks."))
            val resolver = ProvenanceFollowUpResolver(repo)
            assertThat(resolver.resolve(request(generated)).resolution).isEqualTo(FollowUpResolution.UNSUPPORTED_SOURCE)
            val large =
                request(generated).copy(
                    followUp = RetrievalFollowUpContext("x".repeat(4097), listOf(pin(generated))),
                )
            assertThat(resolver.resolve(large).resolution).isEqualTo(FollowUpResolution.CONTEXT_TOO_LARGE)
        }

    private fun pin(doc: Document) = RetrievalSourcePin(doc.id, checkNotNull(doc.contentHash), null)

    private fun request(doc: Document) =
        ContextualRetrievalRequest(
            query = "What color is its box?",
            followUp = RetrievalFollowUpContext("How many hooks?", listOf(pin(doc))),
        )
}
