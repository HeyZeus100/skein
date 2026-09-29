package app.skein.core.vault.eval

import androidx.sqlite.SQLiteConnection
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.DocumentKind
import app.skein.core.model.IndexStore
import app.skein.core.model.NewDocument
import app.skein.core.model.PersonaId
import app.skein.core.model.RetrievalService
import app.skein.core.model.ScoredChunk
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.ingest.LinkStep
import app.skein.core.rag.rank.RetrievedAssembler
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.core.vault.index.IndexStoreImpl
import app.skein.core.vault.lifecycle.CreateResult
import app.skein.core.vault.lifecycle.IntegrityResult
import app.skein.core.vault.lifecycle.VaultLifecycle
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.persona.PersonaServiceImpl
import app.skein.core.vault.repository.VaultRepositoryImpl
import app.skein.testing.eval.EvaluatedQuery
import app.skein.testing.eval.EvaluationDocument
import app.skein.testing.eval.GoldEvidence
import app.skein.testing.eval.RetrievalEvaluationReport
import app.skein.testing.eval.RetrievalGoldQuery
import app.skein.testing.eval.RetrievalMetrics
import app.skein.testing.eval.RetrievalSample
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.Collections

/**
 * Opt-in execution helper for the frozen, independently authored public reserved
 * validation fixture. No @Test entrypoint: invoke only after policy calibration
 * is frozen, from the dedicated retrieval lane. The factory constructs the real
 * production service over this helper's stores; it does not inject candidates.
 * This vault is separate from the original development corpus and gold labels.
 */
public object RejectionValidationEvaluation {
    public const val FIXTURE_SHA256: String = "bf162254094103310102e28ad90b4c945bf24dd7f8f9a706039b2fe3a826b57c"

    /** Quality failures remain measured FAIL rows; only invalid harness/index state throws. */
    public suspend fun evaluate(
        factory: (IndexStore, VaultRepository, PersonaId) -> RetrievalService,
        repetitions: Int = 3,
        configuration: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        require(repetitions in 2..10)
        val fixtureBytes =
            checkNotNull(javaClass.getResourceAsStream("/eval/rejection-validation.json")) {
                "Missing reserved validation fixture"
            }.use { it.readBytes() }
        check(RetrievalEvaluationReport.sha256(fixtureBytes) == FIXTURE_SHA256) { "Reserved fixture changed" }
        val fixture = Json.parseToJsonElement(fixtureBytes.toString(Charsets.UTF_8)).jsonObject
        check(fixture.getValue("schema_version").jsonPrimitive.int == 1)
        check(fixture.getValue("split").jsonPrimitive.content == "public_reserved_validation")
        val queries = parseQueries(fixture)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "rejection-validation-").toFile()
        val lifecycle =
            VaultLifecycle(
                driverFactory = { key -> SkeinSQLiteDriver(key) },
                migrator = { driver -> Migrator(driver) },
                paths = VaultPaths(dir),
                readerCount = 4,
            )
        try {
            return withTimeout(120_000L) {
                check(lifecycle.create(ByteArray(32) { (it + 73).toByte() }) is CreateResult.Success)
                val pool = lifecycle.connectionPool()
                val readers = pool.readers()
                val repository =
                    VaultRepositoryImpl(
                        writer = pool.writer(),
                        attachments = InMemoryAttachmentStore(),
                        readers = readers.subList(0, 2),
                    )
                val index = IndexStoreImpl(readers[2])
                val space = PersonaServiceImpl(readers[3]).default().id
                val documents =
                    fixture.getValue("documents").jsonArray.map { raw ->
                        val source = raw.jsonObject
                        check(source.getValue("persona_id").jsonPrimitive.content == "default")
                        repository.createDocument(
                            NewDocument(
                                id = source.getValue("id").jsonPrimitive.content,
                                kind = DocumentKind.NOTE,
                                title = source.getValue("title").jsonPrimitive.content,
                                bodyMd = source.getValue("body_md").jsonPrimitive.content,
                                personaId = space,
                            ),
                        )
                    }
                check(documents.size == 6 && documents.map { it.id }.distinct().size == 6)
                check(count(pool.writer(), "SELECT count(*) FROM documents") == 6L)
                val warnings = Collections.synchronizedList(mutableListOf<String>())
                val chunker = Chunker(ApproximateTokenizer)
                val upserter = EdgeUpserter(repository, index)
                val resolver = DanglingResolver(repository, index)
                val ingestStarted = System.nanoTime()
                val outcome =
                    IngestPipeline(
                        repository,
                        chunker,
                        IngestSteps(index, embedder = null, warn = warnings::add),
                        links =
                            LinkStep { doc ->
                                upserter.upsert(doc)
                                resolver.resolveFor(doc)
                            },
                        warn = warnings::add,
                    ).run()
                val ingestMillis = (System.nanoTime() - ingestStarted) / 1_000_000.0
                check(outcome is IngestOutcome.Drained && repository.dequeueIngest(1).isEmpty())
                check(lifecycle.integrityCheck() == IntegrityResult.Ok)
                check(count(pool.writer(), "SELECT count(*) FROM chunks_vec") == 0L)
                val chunks = index.chunksForDocs(documents.map { it.id }, limitPerDoc = 100)
                check(count(pool.writer(), "SELECT count(*) FROM chunks") == chunks.size.toLong())
                val stored = linkedMapOf<String, EvaluationDocument>()
                for (doc in documents) {
                    val revision = checkNotNull(repository.currentRevision(doc.id))
                    stored[doc.id] =
                        EvaluationDocument(doc.id, space, revision.revisionHash, revision.bodyMdSnapshot, doc.kind)
                    val expected = chunker.chunk(checkNotNull(doc.bodyMd))
                    val actual = chunks.filter { it.docId == doc.id }.sortedBy { it.ord }
                    check(actual.size == expected.size)
                    for ((source, row) in expected.zip(actual)) {
                        check(row.ord == source.ord && row.text == source.embeddingText)
                        check(row.revisionHash == revision.revisionHash)
                        check(
                            row.byteStart ==
                                doc.bodyMd!!
                                    .substring(0, source.start)
                                    .toByteArray(Charsets.UTF_8)
                                    .size,
                        )
                        check(
                            row.byteEnd ==
                                doc.bodyMd!!
                                    .substring(0, source.end)
                                    .toByteArray(Charsets.UTF_8)
                                    .size,
                        )
                    }
                }
                val indexed =
                    RetrievedAssembler(index, repository).assemble(chunks.map { ScoredChunk(it.id, 1.0) }, emptyMap())
                check(indexed.size == chunks.size && indexed.all { RetrievalMetrics.validAnchor(it, stored) })
                for (query in queries.filter { it.answerable }) {
                    val coverage =
                        RetrievalMetrics.score(
                            query,
                            space,
                            stored,
                            indexed,
                            indexed.filter { row -> query.relevant.any { it.docId == row.docId } },
                        )
                    check(coverage.totalSpans == 1 && coverage.coveredSpans == 1) { "Reserved source span missing" }
                }

                val service = factory(index, repository, space)
                val rows =
                    queries.map { query ->
                        service.retrieveContext(query.query, k = RetrievalMetrics.K, personaId = space)
                        val samples =
                            List(repetitions) {
                                val started = System.nanoTime()
                                val results =
                                    service.retrieveContext(
                                        query.query,
                                        k = RetrievalMetrics.K,
                                        personaId = space,
                                    )
                                RetrievalSample((System.nanoTime() - started) / 1_000_000.0, results)
                            }
                        EvaluatedQuery(
                            query,
                            RetrievalMetrics.score(query, space, stored, indexed, samples.first().results),
                            samples,
                        )
                    }
                buildJsonObject {
                    put("schema_version", 1)
                    put("status", "MEASURED_DIAGNOSTIC")
                    put("split", "public_reserved_validation")
                    put("blind_benchmark", false)
                    put("fixture_sha256", FIXTURE_SHA256)
                    put("corpus", "separate encrypted six-document vault; no development background documents")
                    put("document_count", documents.size)
                    put("chunk_count", chunks.size)
                    put("query_count", queries.size)
                    put("validated_answer_spans", queries.count { it.answerable })
                    put("embedder", JsonNull)
                    put("entity_extractor", JsonNull)
                    put("vector_count", 0)
                    put("full_hybrid_gate", "INELIGIBLE")
                    put("warmups_per_query", 1)
                    put("repetitions", repetitions)
                    put("ingest_ms", ingestMillis)
                    put("ingest_warnings", JsonArray(warnings.toList().map(::JsonPrimitive)))
                    put("validation_status", if (rows.all(::passed)) "PASS" else "FAIL")
                    put("mode", RetrievalEvaluationReport.mode("production_policy", configuration, rows))
                    // Includes actual returned source kinds as well as the common
                    // report's text, anchors, raw source signals and fingerprints.
                    put(
                        "returned_source_kinds",
                        buildJsonObject {
                            for (row in rows) {
                                put(
                                    row.query.id,
                                    JsonArray(
                                        row.samples.map { sample ->
                                            JsonArray(sample.results.map { JsonPrimitive(it.sourceKind.name) })
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        } finally {
            lifecycle.close()
            dir.deleteRecursively()
        }
    }

    private fun parseQueries(fixture: JsonObject): List<RetrievalGoldQuery> {
        val queries =
            fixture.getValue("queries").jsonArray.map { raw ->
                val query = raw.jsonObject
                val labels =
                    query.getValue("relevant").jsonArray.map { value ->
                        val label = value.jsonObject
                        GoldEvidence(
                            label.getValue("doc_id").jsonPrimitive.content,
                            label.getValue("grade").jsonPrimitive.int,
                            label.getValue("evidence").jsonPrimitive.content,
                        )
                    }
                check(query.getValue("persona_id").jsonPrimitive.content == "default")
                check((query.getValue("answer") != JsonNull) == labels.isNotEmpty())
                RetrievalGoldQuery(
                    id = query.getValue("id").jsonPrimitive.content,
                    category = query.getValue("category").jsonPrimitive.content,
                    query = query.getValue("query").jsonPrimitive.content,
                    personaAlias = "default",
                    relevant = labels,
                    forbiddenDocIds = emptySet(),
                )
            }
        check(queries.size == 12 && queries.map { it.id }.distinct().size == 12)
        check(queries.count { it.answerable } == 6)
        check(queries.count { it.category == "related_only" } == 3)
        check(queries.count { it.category == "no_match" } == 3)
        return queries
    }

    private fun passed(row: EvaluatedQuery): Boolean =
        row.deterministic &&
            row.score.invalidAnchorChunkIds.isEmpty() &&
            row.score.scopeViolationChunkIds.isEmpty() &&
            row.score.provenanceViolationChunkIds.isEmpty() &&
            row.score.duplicateCount == 0 &&
            if (row.query.answerable) row.score.coveredSpans == row.score.totalSpans else row.score.rejected

    private fun count(
        connection: SQLiteConnection,
        sql: String,
    ): Long =
        connection.prepare(sql).use {
            check(it.step())
            it.getLong(0)
        }
}
