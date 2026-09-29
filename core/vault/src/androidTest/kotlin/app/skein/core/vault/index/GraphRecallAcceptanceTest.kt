package app.skein.core.vault.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewDocument
import app.skein.core.model.RecallSource
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.ingest.LinkStep
import app.skein.core.rag.recall.GraphRecall
import app.skein.core.rag.retrieval.RecallStages
import app.skein.core.rag.retrieval.RetrievalServiceImpl
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.core.vault.lifecycle.CreateResult
import app.skein.core.vault.lifecycle.VaultLifecycle
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.persona.PersonaServiceImpl
import app.skein.core.vault.repository.VaultRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Real title SQL, production ingest/link resolution, and graph-only retrieval; no injected seeds or edges. */
@RunWith(AndroidJUnit4::class)
public class GraphRecallAcceptanceTest {
    @Test
    public fun ordinaryPhraseDiscoversLongerTitlesAndLinkedAnswerWithinEvidenceScope(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = Files.createTempDirectory(context.cacheDir.toPath(), "graph-discovery-").toFile()
            val lifecycle =
                VaultLifecycle(
                    driverFactory = { key -> SkeinSQLiteDriver(key) },
                    migrator = { driver -> Migrator(driver) },
                    paths = VaultPaths(dir),
                    readerCount = 4,
                )
            try {
                assertThat(
                    lifecycle.create(ByteArray(32) { (it + 41).toByte() }),
                ).isInstanceOf(CreateResult.Success::class.java)
                val pool = lifecycle.connectionPool()
                val readers = pool.readers()
                val repo =
                    VaultRepositoryImpl(
                        writer = pool.writer(),
                        attachments = InMemoryAttachmentStore(),
                        readers = readers.subList(0, 2),
                    )
                val index = IndexStoreImpl(readers[2])
                val personas = PersonaServiceImpl(readers[3])
                val defaultSpace = personas.default().id
                val otherSpace = personas.create("Other Space", null, null).id
                val answer =
                    repo.createDocument(
                        NewDocument(
                            kind = DocumentKind.NOTE,
                            title = "Launch decision",
                            bodyMd = "Mira approved the Project Alder launch.",
                            personaId = defaultSpace,
                        ),
                    )
                val seed =
                    repo.createDocument(
                        NewDocument(
                            kind = DocumentKind.NOTE,
                            title = "Project Alder brief",
                            bodyMd = "The Project Alder approval is recorded in [[Launch decision]].",
                        ),
                    )
                val secondSeed =
                    repo.createDocument(
                        NewDocument(
                            kind = DocumentKind.NOTE,
                            title = "project alder minutes",
                            bodyMd = "Project Alder approval meeting minutes refer to [[Launch decision]].",
                            personaId = defaultSpace,
                        ),
                    )
                val excluded =
                    listOf(
                        NewDocument(
                            DocumentKind.NOTE,
                            "Project Alder private",
                            "Private approval.",
                            personaId = otherSpace,
                        ),
                        NewDocument(
                            DocumentKind.CHAT,
                            "Project Alder conversation",
                            "Prior generated approval.",
                            personaId = defaultSpace,
                        ),
                        NewDocument(
                            DocumentKind.AIOUT,
                            "Project Alder output",
                            "Generated approval.",
                            personaId = defaultSpace,
                        ),
                    ).map { repo.createDocument(it) }
                val partial =
                    repo.createDocument(
                        NewDocument(DocumentKind.NOTE, "Project Aldershot brief", "Unrelated launch approval."),
                    )
                val upserter = EdgeUpserter(repo, index)
                val resolver = DanglingResolver(repo, index)
                val ingested =
                    IngestPipeline(
                        repo,
                        Chunker(ApproximateTokenizer),
                        IngestSteps(index, embedder = null),
                        links =
                            LinkStep { doc ->
                                upserter.upsert(doc)
                                resolver.resolveFor(doc)
                            },
                    ).run()
                assertThat(ingested).isInstanceOf(IngestOutcome.Drained::class.java)
                assertThat(repo.dequeueIngest(1)).isEmpty()
                assertThat(index.edgesFrom(seed.id).filter { it.kind == EdgeKind.WIKILINK }.map { it.dstId })
                    .containsExactly(answer.id)

                val query = "Who approved Project Alder?"
                assertThat(repo.findByTitle("Project Alder")).isNull()
                val raw = GraphRecall(index, repo).recall(query)
                val rawDocs =
                    index
                        .getChunks(raw.map { it.chunkId })
                        .values
                        .map { it.docId }
                        .toSet()
                assertThat(rawDocs).containsAtLeast(seed.id, secondSeed.id, answer.id)
                assertThat(rawDocs).containsAtLeastElementsIn(excluded.map { it.id })
                assertThat(rawDocs).doesNotContain(partial.id)

                val service =
                    RetrievalServiceImpl(
                        index,
                        repo,
                        embedder = null,
                        legacyPersonaId = defaultSpace,
                        stages = RecallStages(lexical = false, vector = false, graph = true),
                    )
                val result = service.retrieveContext(query, k = 8, personaId = defaultSpace)
                assertThat(result.map { it.docId }).containsExactly(seed.id, secondSeed.id, answer.id)
                assertThat(result.single { it.docId == answer.id }.text).contains("Mira approved")
                assertThat(result.all { it.recalledBy == setOf(RecallSource.GRAPH) }).isTrue()
                assertThat(service.retrieveContext(query, k = 8, personaId = defaultSpace)).isEqualTo(result)
            } finally {
                lifecycle.close()
                dir.deleteRecursively()
            }
        }
}
