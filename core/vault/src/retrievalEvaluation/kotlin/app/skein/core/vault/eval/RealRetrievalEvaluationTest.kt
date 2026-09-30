package app.skein.core.vault.eval

import android.os.Build
import android.os.Bundle
import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind
import app.skein.core.model.ScoredChunk
import app.skein.core.model.TimelineFilter
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.ingest.LinkStep
import app.skein.core.rag.rank.RankerConfig
import app.skein.core.rag.rank.RetrievedAssembler
import app.skein.core.rag.retrieval.LexicalEvidenceGate
import app.skein.core.rag.retrieval.RecallStages
import app.skein.core.rag.retrieval.RetrievalServiceImpl
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.blob.FileAttachmentStore
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
import app.skein.testing.eval.RetrievalEvaluationReport
import app.skein.testing.eval.RetrievalEvaluationVault
import app.skein.testing.eval.RetrievalMetrics
import app.skein.testing.eval.RetrievalSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.Collections

/**
 * Opt-in real SQLite retrieval development evaluation (skein-9744).
 * No fake index, injected recall, gold edges, embeddings or source selection.
 * Build with `-Pskein.retrievalEvaluation=true` and run with
 * `skein.retrieval.eval=true`; see docs/RETRIEVAL_EVAL.md.
 * Diagnostic success is NOT the full hybrid gate: that remains INELIGIBLE
 * until the app has a production embedder and an actual measured baseline.
 */
@RunWith(AndroidJUnit4::class)
public class RealRetrievalEvaluationTest {
    @Test
    public fun evaluate_real_ingest_and_retrieval(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assertEquals(
                "explicit retrieval evaluation argument required",
                "true",
                args.getString("skein.retrieval.eval"),
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val artifact = File(context.filesDir, "artifacts/eval/retrieval.json")
            artifact.parentFile!!.mkdirs()
            val dir = Files.createTempDirectory(context.cacheDir.toPath(), "real-retrieval-eval-").toFile()
            val lifecycle =
                VaultLifecycle(
                    driverFactory = { key -> SkeinSQLiteDriver(key) },
                    migrator = { driver -> Migrator(driver) },
                    paths = VaultPaths(dir),
                    readerCount = 4,
                )
            var phase = "create"
            var reportWritten = false
            try {
                withTimeout(20 * 60 * 1_000L) {
                    val created = lifecycle.create(fixtureKey())
                    check(created is CreateResult.Success) { "Evaluation vault creation failed" }
                    val pool = lifecycle.connectionPool()
                    val readers = pool.readers()
                    val repository =
                        VaultRepositoryImpl(
                            writer = pool.writer(),
                            attachments = FileAttachmentStore(File(dir, "attachments"), masterKey = ::fixtureKey),
                            readers = readers.subList(0, 2),
                        )
                    val index = IndexStoreImpl(readers[2])
                    val personas = PersonaServiceImpl(readers[3])
                    val personaIds = linkedMapOf("default" to personas.default().id)
                    for (alias in RetrievalEvaluationVault.personaAliases - "default") {
                        personaIds[alias] = personas.create(alias, null, null).id
                    }
                    phase = "seed"
                    RetrievalEvaluationVault.seed(repository, personaIds)
                    val documents =
                        repository
                            .observeTimeline(
                                TimelineFilter(kinds = DocumentKind.entries.toSet()),
                                limit = 1_001,
                            ).first()
                    assertEquals(1_000, documents.size)
                    assertEquals(800, documents.count { it.kind == DocumentKind.NOTE })
                    assertEquals(50, documents.count { it.kind == DocumentKind.CHAT })
                    assertEquals(150, documents.count { it.kind == DocumentKind.ATTACHMENT })

                    // Same components and defaults as app/IngestPipelines.forSession.
                    // These are real writes from document bodies, never gold-derived rows.
                    phase = "ingest"
                    val warnings = Collections.synchronizedList(mutableListOf<String>())
                    val chunker = Chunker(ApproximateTokenizer)
                    val upserter = EdgeUpserter(repository, index)
                    val resolver = DanglingResolver(repository, index)
                    val startedIngest = System.nanoTime()
                    val lexicalProbeAudit = LexicalProbeDiagnostics(index)
                    val outcome =
                        IngestPipeline(
                            repository,
                            chunker,
                            IngestSteps(lexicalProbeAudit, embedder = null, warn = warnings::add),
                            links =
                                LinkStep { doc ->
                                    upserter.upsert(doc)
                                    resolver.resolveFor(doc)
                                },
                            entities = null,
                            warn = warnings::add,
                        ).run()
                    val ingestMillis = (System.nanoTime() - startedIngest) / 1_000_000.0
                    check(outcome is IngestOutcome.Drained) { "Evaluation ingestion did not drain" }
                    check(repository.dequeueIngest(1).isEmpty()) { "Evaluation ingestion left failed documents queued" }
                    assertEquals(IntegrityResult.Ok, lifecycle.integrityCheck())

                    phase = "validate_index"
                    val chunks =
                        documents.chunked(100).flatMap { batch ->
                            index.chunksForDocs(batch.map { it.id }, limitPerDoc = 10_000)
                        }
                    assertEquals(count(pool.writer(), "SELECT count(*) FROM chunks"), chunks.size.toLong())
                    val byDoc = chunks.groupBy { it.docId }
                    val stored = linkedMapOf<String, EvaluationDocument>()
                    for (doc in documents) {
                        val revision = repository.currentRevision(doc.id)
                        stored[doc.id] =
                            EvaluationDocument(
                                doc.id,
                                doc.personaId ?: personaIds.getValue("default"),
                                revision?.revisionHash.orEmpty(),
                                revision?.bodyMdSnapshot.orEmpty(),
                                doc.kind,
                            )
                        val expected = doc.bodyMd?.let(chunker::chunk).orEmpty()
                        val actual = byDoc[doc.id].orEmpty().sortedBy { it.ord }
                        assertEquals("Indexed chunk count differs from production chunker", expected.size, actual.size)
                        for ((source, row) in expected.zip(actual)) {
                            assertEquals(source.ord, row.ord)
                            assertEquals(source.embeddingText, row.text)
                            assertEquals(revision?.revisionHash, row.revisionHash)
                            assertEquals(
                                doc.bodyMd!!
                                    .substring(0, source.start)
                                    .toByteArray(Charsets.UTF_8)
                                    .size,
                                row.byteStart,
                            )
                            assertEquals(
                                doc.bodyMd!!
                                    .substring(0, source.end)
                                    .toByteArray(Charsets.UTF_8)
                                    .size,
                                row.byteEnd,
                            )
                        }
                    }
                    // CHAT snapshots are intentionally empty/bounded. Every indexed
                    // row is checked above; only eligible NOTE/ATTACHMENT rows enter
                    // the citation oracle. This list never enters the retriever.
                    val eligibleChunks = chunks.filter { RetrievalMetrics.eligible(stored.getValue(it.docId).kind) }
                    val indexed =
                        RetrievedAssembler(
                            index,
                            repository,
                        ).assemble(eligibleChunks.map { ScoredChunk(it.id, 1.0) }, emptyMap())
                    check(
                        indexed.size == eligibleChunks.size &&
                            indexed.all {
                                RetrievalMetrics.validAnchor(it, stored)
                            },
                    ) { "Indexed revision anchors failed validation" }
                    val queries = RetrievalMetrics.goldQueries()
                    assertEquals(76, queries.size)
                    var materializedGoldLinks = 0
                    for (raw in RetrievalEvaluationVault.queries()) {
                        val row = raw.jsonObject
                        val seed = row["seed_doc_id"]?.jsonPrimitive?.content ?: continue
                        val answer =
                            row
                                .getValue(
                                    "relevant",
                                ).jsonArray
                                .first()
                                .jsonObject
                                .getValue("doc_id")
                                .jsonPrimitive.content
                        check(
                            index.edgesFrom(seed).any {
                                it.dstId == answer && it.kind == EdgeKind.WIKILINK
                            },
                        ) { "Gold graph path was not materialized by production ingest" }
                        materializedGoldLinks++
                    }
                    assertEquals(12, materializedGoldLinks)
                    for (query in queries.filter { it.answerable }) {
                        val owner = personaIds.getValue(query.personaAlias ?: "default")
                        check(
                            query.relevant.all { stored.getValue(it.docId).personaId == owner },
                        ) { "Gold label is outside its query Space" }
                        val coverage =
                            RetrievalMetrics.score(
                                query,
                                owner,
                                stored,
                                indexed,
                                indexed.filter {
                                    it.docId in
                                        query.relevant.map { label -> label.docId }
                                },
                            )
                        check(
                            coverage.coveredSpans == coverage.totalSpans,
                        ) { "Real index cannot cover a labelled evidence span" }
                    }

                    phase = "retrieve"
                    val repetitions = args.getString("skein.retrieval.repetitions")?.toInt() ?: 3
                    require(repetitions in 2..10) { "Evaluation repetitions must be between 2 and 10" }
                    val allRows = mutableListOf<EvaluatedQuery>()
                    val evidencePolicy =
                        buildJsonObject {
                            put("version", LexicalEvidenceGate.VERSION)
                            put("minimum_query_coverage", LexicalEvidenceGate.DEFAULT_MINIMUM_COVERAGE)
                            put("semantic_vector_policy", "uncalibrated_bypass")
                        }
                    val experimentalPolicy =
                        buildJsonObject {
                            put("version", LexicalEvidenceGate.CANDIDATE_VERSION)
                            put("minimum_query_coverage", LexicalEvidenceGate.DEFAULT_MINIMUM_COVERAGE)
                            put("minimum_value_coverage", LexicalEvidenceGate.DEFAULT_MINIMUM_VALUE_COVERAGE)
                            put("semantic_vector_policy", "uncalibrated_bypass")
                        }
                    val modes =
                        listOf(
                            Mode(
                                "lexical_only",
                                RecallStages(vector = false, graph = false),
                                RankerConfig(recallWeight = 1.0, pprWeight = 0.0, neighborHops = 0),
                            ),
                            Mode("graph_only", RecallStages(lexical = false, vector = false), RankerConfig.DEFAULT),
                            Mode("lexical_graph_default", RecallStages(), RankerConfig.DEFAULT),
                        )
                    val reports =
                        modes.map { mode ->
                            val stageWarnings = Collections.synchronizedList(mutableListOf<String>())
                            val service =
                                RetrievalServiceImpl(
                                    index,
                                    repository,
                                    embedder = null,
                                    config = mode.ranker,
                                    legacyPersonaId = personaIds.getValue("default"),
                                    stages = mode.stages,
                                    warn = stageWarnings::add,
                                )
                            val rows =
                                queries.map { query ->
                                    val owner = personaIds.getValue(query.personaAlias ?: "default")
                                    // One untimed warm-up per query/mode.
                                    service.retrieveContext(query.query, personaId = owner)
                                    val samples =
                                        List(repetitions) {
                                            val started = System.nanoTime()
                                            val results = service.retrieveContext(query.query, personaId = owner)
                                            RetrievalSample((System.nanoTime() - started) / 1_000_000.0, results)
                                        }
                                    EvaluatedQuery(
                                        query,
                                        RetrievalMetrics.score(query, owner, stored, indexed, samples.first().results),
                                        samples,
                                    )
                                }
                            allRows += rows
                            RetrievalEvaluationReport.mode(
                                mode.name,
                                buildJsonObject {
                                    put("lexical", mode.stages.lexical)
                                    put("vector_requested", mode.stages.vector)
                                    put("vector_available", false)
                                    put("graph_recall", mode.stages.graph)
                                    put("ranker", mode.ranker.toString())
                                    put("include_chat_history", false)
                                    put("recall_timeout_ms", RetrievalServiceImpl.DEFAULT_RECALL_TIMEOUT_MILLIS)
                                    put("stage_warnings", JsonArray(stageWarnings.toList().map(::JsonPrimitive)))
                                    put("evidence_policy", evidencePolicy)
                                },
                                rows,
                            )
                        }
                    // The separate fixture is frozen before development calibration.
                    // Both factories construct the real production service; neither
                    // receives query labels or injects candidates. Quality FAIL is
                    // reported unchanged, never converted into successful validation.
                    val policyFreeze = checkNotNull(args.getString("skein.retrieval.validationFreeze"))
                    require(policyFreeze.matches(Regex("[a-f0-9]{40}"))) { "A frozen policy source SHA is required" }
                    phase = "reserved_validation"

                    suspend fun validation(definition: RejectionValidationEvaluation.Fixture): JsonObject =
                        buildJsonObject {
                            for (name in listOf("production_policy", "ungated_control", "experimental_candidate")) {
                                val policy =
                                    when (name) {
                                        "production_policy" -> evidencePolicy
                                        "experimental_candidate" -> experimentalPolicy
                                        else -> buildJsonObject { put("version", "disabled_control") }
                                    }
                                put(
                                    name,
                                    RejectionValidationEvaluation.evaluate(
                                        factory = { validationIndex, validationRepository, space ->
                                            RetrievalServiceImpl(
                                                validationIndex,
                                                validationRepository,
                                                embedder = null,
                                                legacyPersonaId = space,
                                                evidenceGate =
                                                    when (name) {
                                                        "production_policy" -> LexicalEvidenceGate()
                                                        "experimental_candidate" ->
                                                            LexicalEvidenceGate(
                                                                requestedValueChecks = true,
                                                            )
                                                        else -> null
                                                    },
                                            )
                                        },
                                        repetitions = repetitions,
                                        configuration = buildJsonObject { put("evidence_policy", policy) },
                                        modeName = name,
                                        definition = definition,
                                    ),
                                )
                            }
                        }
                    val reservedValidation = validation(RejectionValidationEvaluation.Fixture.PUBLIC_RESERVED)
                    phase = "independent_validation"
                    val independentValidation = validation(RejectionValidationEvaluation.Fixture.FROZEN_20260928)
                    val freshContextualValidation =
                        if (args.getString("skein.retrieval.fresh40") == "true") {
                            phase = "fresh_contextual_validation"
                            FreshContextualValidationEvaluation.evaluate(
                                repetitions = repetitions,
                                buildRevision = checkNotNull(args.getString("skein.retrieval.revision")),
                                candidateFreeze =
                                    checkNotNull(
                                        args.getString("skein.retrieval.fresh40CandidateFreeze"),
                                    ),
                                evaluatorFreeze =
                                    checkNotNull(
                                        args.getString("skein.retrieval.fresh40EvaluatorFreeze"),
                                    ),
                            )
                        } else {
                            null
                        }
                    phase = "report"
                    val report =
                        buildJsonObject {
                            put("schema_version", 1)
                            put("status", "MEASURED_DIAGNOSTIC")
                            put("full_hybrid_gate", "INELIGIBLE")
                            put(
                                "full_hybrid_reason",
                                "Production embedder unavailable; no vector stage or full-hybrid baseline measured",
                            )
                            put(
                                "evidence_selection",
                                "production ranked top 8 with lexical query-coverage gate; no reranking by the harness",
                            )
                            put("evidence_policy", evidencePolicy)
                            put("experimental_policy", experimentalPolicy)
                            put("rejection_validation", reservedValidation)
                            put("independent_validation", independentValidation)
                            freshContextualValidation?.let { put("fresh_contextual_validation", it) }
                            put("validation_policy_freeze", policyFreeze)
                            put(
                                "scope_mapping",
                                "null gold persona and unassigned documents resolve to the real default Space",
                            )
                            put("split", "development")
                            put("build_revision", args.getString("skein.retrieval.revision") ?: "UNRECORDED")
                            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                            put("android_api", Build.VERSION.SDK_INT)
                            put("abi", Build.SUPPORTED_ABIS.first())
                            put("corpus_sha256", resourceHash("corpus.json"))
                            put("gold_sha256", resourceHash("gold.json"))
                            put("seed", 42)
                            put("document_count", documents.size)
                            put("overlay_document_count", 72)
                            put("query_count", queries.size)
                            put("chunk_count", chunks.size)
                            put("eligible_evidence_chunks", eligibleChunks.size)
                            put("excluded_generated_chunks", chunks.size - eligibleChunks.size)
                            put(
                                "excluded_document_kinds",
                                buildJsonObject {
                                    put("CHAT", documents.count { it.kind == DocumentKind.CHAT })
                                    put("AIOUT", documents.count { it.kind == DocumentKind.AIOUT })
                                },
                            )
                            put("edge_count", count(pool.writer(), "SELECT count(*) FROM edges"))
                            put("materialized_gold_links", materializedGoldLinks)
                            put("vector_count", count(pool.writer(), "SELECT count(*) FROM chunks_vec"))
                            put("vectors_pending_documents", outcome.vectorsPending)
                            put(
                                "pending_embedding_chunks",
                                chunks.count {
                                    it.embedderId ==
                                        IngestSteps.PENDING_EMBEDDER_ID
                                },
                            )
                            put("chunker", "Chunker(target=512,overlap=64); same build revision")
                            put("tokenizer", "ApproximateTokenizer; same build revision")
                            put("embedder", JsonNull)
                            put("entity_extractor", JsonNull)
                            put("reranker", JsonNull)
                            put("sqlite_version", scalar(pool.writer(), "SELECT sqlite_version()"))
                            put("schema_version_applied", created.migration.toVersion)
                            put("ingest_ms", ingestMillis)
                            put("lexical_probe_audit", lexicalProbeAudit.report())
                            put("ingest_warnings", JsonArray(warnings.toList().map(::JsonPrimitive)))
                            put("repetitions", repetitions)
                            put("warmups_per_query_mode", 1)
                            put("modes", JsonArray(reports))
                        }
                    artifact.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
                    reportWritten = true
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("retrieval_report_package", context.packageName)
                            putString("retrieval_report_path", artifact.relativeTo(context.filesDir).path)
                            putString("full_hybrid_gate", "INELIGIBLE")
                        },
                    )
                    // Hard integrity/security gates also cover every repeated sample,
                    // not only the first sample used for aggregate quality metrics.
                    assertTrue(
                        "Retrieval changed across identical repetitions; see artifact",
                        allRows.all { it.deterministic },
                    )
                    assertTrue(
                        "Retrieval returned forbidden Space, generated content, or invalid anchors; see artifact",
                        allRows.all {
                            it.score.scopeViolationChunkIds.isEmpty() &&
                                it.score.invalidAnchorChunkIds.isEmpty() &&
                                it.score.provenanceViolationChunkIds.isEmpty()
                        },
                    )
                    assertTrue(
                        "Production without embedder unexpectedly wrote vectors",
                        count(pool.writer(), "SELECT count(*) FROM chunks_vec") == 0L,
                    )
                    assertTrue(
                        "Full hybrid gate is ineligible without a production embedder; see artifact",
                        args.getString("skein.retrieval.requireHybrid") != "true",
                    )
                }
            } catch (failure: Throwable) {
                if (!reportWritten) {
                    artifact.writeText(
                        buildJsonObject {
                            put("schema_version", 1)
                            put("status", "HARNESS_FAILED")
                            put("phase", phase)
                            put("failure_type", failure.javaClass.simpleName)
                            put("full_hybrid_gate", "INELIGIBLE")
                        }.toString(),
                    )
                }
                throw failure
            } finally {
                lifecycle.close()
                dir.deleteRecursively()
            }
        }

    private data class Mode(
        val name: String,
        val stages: RecallStages,
        val ranker: RankerConfig,
    )

    private fun fixtureKey(): ByteArray = ByteArray(32) { (it + 17).toByte() }

    private fun count(
        connection: SQLiteConnection,
        sql: String,
    ): Long =
        connection.prepare(sql).use {
            check(it.step())
            it.getLong(0)
        }

    private fun scalar(
        connection: SQLiteConnection,
        sql: String,
    ): String =
        connection.prepare(sql).use {
            check(it.step())
            it.getText(0)
        }

    private fun resourceHash(name: String): String =
        RetrievalEvaluationReport.sha256(
            checkNotNull(
                RetrievalEvaluationVault.javaClass.getResourceAsStream("/eval/$name"),
            ).use {
                it.readBytes()
            },
        )
}
