// `RetrievalServiceImplContractTest` (skein-do6, E5.I13): runs the locked
// `RetrievalServiceContractTest` suite (`:testing`, `E0.I12`) against the
// real `RetrievalServiceImpl` over the JVM `InMemoryIndexStore`/
// `InMemoryVaultRepository` fakes.
//
// The base suite only fixes the contract's *shape* — `k` ceiling,
// descending-score ordering, "empty vault → empty list, never throws", and
// determinism (see that class's own file header) — never the specific
// `chunkId`/`docId`/`text` values a fixture carries. So [retrievalService]
// seeds exactly `results.size` documents (one chunk each) whose body
// literally contains the word "anything" — every contract test query is
// either `"anything"`, `""` or `"   "` — which makes `LexicalRecall`'s BM25
// fake surface all of them for the real queries and nothing (without
// throwing) for the blank ones, with no wikilinks or entities in play so
// `GraphRecall` contributes nothing and never has to be stubbed. Every
// document is persona-less, so a scoped `personaId` never removes a
// candidate, honouring `PprRanker`'s persona rule while asserting nothing
// about it — that is `RetrievalServiceImplTest`'s job.
package app.skein.core.rag.retrieval

import kotlinx.coroutines.runBlocking
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.RetrievalService
import us.aherrera.skein.core.model.Retrieved
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository
import us.aherrera.skein.testing.RetrievalServiceContractTest

class RetrievalServiceImplContractTest : RetrievalServiceContractTest() {
    override fun retrievalService(results: List<Retrieved>): RetrievalService {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        runBlocking {
            for (i in results.indices) {
                val document =
                    repo.createDocument(
                        NewDocument(
                            kind = DocumentKind.NOTE,
                            title = "Fixture doc $i",
                            bodyMd = "anything chunk body $i",
                        ),
                    )
                index.replaceChunks(
                    docId = document.id,
                    chunks = listOf(NewChunk(ord = 0, text = "anything chunk body $i", tokenCount = 4)),
                    embedderId = "fake-embedder",
                    embedderVersion = 1,
                )
            }
        }
        // No embedder: the contract suite only needs lexical recall to
        // surface the fixture, matching the "empty vault or embedder
        // unavailable → degrade to lexical + graph only" plan rule.
        return RetrievalServiceImpl(index = index, repository = repo, embedder = null)
    }
}
