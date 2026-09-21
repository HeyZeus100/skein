// Shared fixture plumbing for the `E5.I12` end-to-end and throughput
// suites (skein-dxj): ingest a `SyntheticVault` into the JVM
// `InMemoryIndexStore` the way the real pipeline would — chunk
// (`Chunker`, E5.I5), index (`IndexStore.replaceChunks`), embed
// (`IngestSteps.indexVectors` + `FakeEmbedderService`), and materialize
// the wikilink `edges` rows.
//
// `SyntheticVault` generates documents only — "wikilinks below are plain
// `[[Note N]]` text inside `bodyMd`, not materialized `Edge` rows" (its
// file header). Building those rows is normally `E2.I2`'s ingest trigger
// work in `:core:vault`, which this module may not depend on, so
// [ingest] does the same job here with the node-id conventions
// `EdgeUpserter` uses: a resolved target is the target's document id, an
// unresolved one is `"title:<lowercased title>"` at weight 0.5.

package app.skein.core.rag.rank

import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.tokenizers.TokenizerFixtures
import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.ModelFormat
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.testing.FakeEmbedderService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

internal object RankFixtures {
    private val WIKILINK = Regex("\\[\\[([^\\]]+)]]")

    /** A loaded [FakeEmbedderService] — `embedDocuments`/`embedQuery` throw until `load` succeeds. */
    suspend fun loadedEmbedder(): EmbedderService {
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
     * Chunks, indexes, embeds and links [documents] into [index]. Returns
     * the number of chunks written.
     */
    suspend fun ingest(
        index: InMemoryIndexStore,
        repo: InMemoryVaultRepository,
        embedder: EmbedderService,
        documents: List<Document>,
    ): Int {
        val chunker = Chunker(TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC))
        val steps = IngestSteps(index, embedder)
        var total = 0
        for (document in documents) {
            val body = document.bodyMd ?: continue
            val chunks = chunker.chunk(body)
            if (chunks.isEmpty()) continue
            val ids =
                index.replaceChunks(
                    docId = document.id,
                    chunks =
                        chunks.map {
                            NewChunk(ord = it.ord, text = it.embeddingText, tokenCount = it.tokenCount)
                        },
                    embedderId = embedder.embedderId,
                    embedderVersion = embedder.embedderVersion,
                )
            steps.indexVectors(ids, chunks.map { it.embeddingText })
            total += ids.size
        }
        linkWikilinks(index, repo, documents)
        return total
    }

    /** Materializes `[[Target]]` body text as `WIKILINK` edge rows (see the file header). */
    private suspend fun linkWikilinks(
        index: InMemoryIndexStore,
        repo: InMemoryVaultRepository,
        documents: List<Document>,
    ) {
        for (document in documents) {
            val body = document.bodyMd ?: continue
            val targets =
                WIKILINK
                    .findAll(body)
                    .map { it.groupValues[1].trim() }
                    .distinct()
                    .toList()
            if (targets.isEmpty()) continue
            val edges =
                targets.map { title ->
                    val resolved = repo.findByTitle(title)
                    if (resolved != null) {
                        Edge(
                            srcId = document.id,
                            dstId = resolved.id,
                            kind = EdgeKind.WIKILINK,
                            createdAt = 0L,
                        )
                    } else {
                        Edge(
                            srcId = document.id,
                            dstId = "title:${title.lowercase()}",
                            kind = EdgeKind.WIKILINK,
                            weight = 0.5,
                            createdAt = 0L,
                        )
                    }
                }
            index.replaceEdges(document.id, setOf(EdgeKind.WIKILINK), edges)
        }
    }
}
