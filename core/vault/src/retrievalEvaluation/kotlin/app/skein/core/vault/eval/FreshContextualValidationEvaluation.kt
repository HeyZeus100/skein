package app.skein.core.vault.eval

import androidx.sqlite.SQLiteConnection
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.NewDocument
import app.skein.core.model.RevisionHashing
import app.skein.core.model.ScoredChunk
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.ingest.LinkStep
import app.skein.core.rag.rank.RetrievedAssembler
import app.skein.core.rag.retrieval.RetrievalServiceImpl
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
import app.skein.testing.eval.ExpandedIndexedChunk
import app.skein.testing.eval.ExpandedRetrievalMetrics
import app.skein.testing.eval.ExpandedValidationDocument
import app.skein.testing.eval.FreshContextualValidationFixture
import app.skein.testing.eval.FreshContextualValidationReport
import app.skein.testing.eval.FreshExecutionStatus
import app.skein.testing.eval.FreshValidationExecution
import app.skein.testing.eval.FreshValidationFixture
import app.skein.testing.eval.FreshValidationQuery
import app.skein.testing.eval.FreshValidationRow
import app.skein.testing.eval.FreshValidationSample
import app.skein.testing.eval.RetrievalEvaluationReport
import app.skein.testing.eval.ValidationRepresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.Collections

/**
 * Only the existing explicit retrieval lane calls this helper. No ordinary test or app wiring.
 * Fixture labels and oracle resolutions are available solely to the scorer after real retrieval.
 */
public object FreshContextualValidationEvaluation {
    public suspend fun evaluate(
        repetitions: Int,
        buildRevision: String,
        candidateFreeze: String,
        evaluatorFreeze: String,
    ): JsonObject {
        require(repetitions in 2..10)
        require(listOf(buildRevision, candidateFreeze, evaluatorFreeze).all { it.matches(Regex("[a-f0-9]{40}")) })
        val fixture = FreshContextualValidationFixture.load()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "fresh-contextual-validation-").toFile()
        val lifecycle =
            VaultLifecycle(
                driverFactory = { key -> SkeinSQLiteDriver(key) },
                migrator = { driver -> Migrator(driver) },
                paths = VaultPaths(dir),
                readerCount = 4,
            )
        try {
            check(lifecycle.create(ByteArray(32) { (it + 91).toByte() }) is CreateResult.Success)
            val pool = lifecycle.connectionPool()
            val readers = pool.readers()
            val repository =
                VaultRepositoryImpl(pool.writer(), InMemoryAttachmentStore(), readers = readers.subList(0, 2))
            val index = IndexStoreImpl(readers[2])
            val personas = PersonaServiceImpl(readers[3])
            val spaces = linkedMapOf("default" to personas.default().id)
            for (alias in fixture.documents.map { it.spaceAlias }.toSet() - "default") {
                spaces[alias] =
                    personas.create(alias, null, null).id
            }
            val warnings = Collections.synchronizedList(mutableListOf<String>())
            val chunker = Chunker(ApproximateTokenizer)
            val upserter = EdgeUpserter(repository, index)
            val resolver = DanglingResolver(repository, index)
            val pipeline =
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
                )

            suspend fun ingest() {
                check(pipeline.run() is IngestOutcome.Drained && repository.dequeueIngest(1).isEmpty())
            }
            for (source in fixture.documents) {
                repository.createDocument(
                    NewDocument(
                        id = source.id,
                        kind = DocumentKind.NOTE,
                        title = source.title,
                        bodyMd = source.previousBodies.firstOrNull() ?: source.body,
                        personaId = spaces.getValue(source.spaceAlias),
                    ),
                )
            }
            ingest()
            val revisionReplay = mutableListOf<JsonObject>()
            for (source in fixture.documents.filter { it.previousBodies.isNotEmpty() }) {
                val history = mutableListOf<JsonObject>()
                for ((position, body) in source.previousBodies.withIndex()) {
                    if (position > 0) {
                        repository.updateBody(source.id, source.title, body)
                        ingest()
                    }
                    val revision = checkNotNull(repository.currentRevision(source.id))
                    check(revision.bodyMdSnapshot == RevisionHashing.canonicalBody(body))
                    val oldChunks = index.chunksForDocs(listOf(source.id), limitPerDoc = 1_000)
                    check(oldChunks.isNotEmpty() && oldChunks.all { it.revisionHash == revision.revisionHash })
                    history +=
                        buildJsonObject {
                            put("revision_hash", revision.revisionHash)
                            put("revision_ord", revision.revisionOrd)
                            put("body_snapshot", revision.bodyMdSnapshot)
                            put("body_sha256", hash(body))
                            put("chunk_ids", JsonArray(oldChunks.map { JsonPrimitive(it.id) }))
                        }
                }
                repository.updateBody(source.id, source.title, source.body)
                ingest()
                val current = checkNotNull(repository.currentRevision(source.id))
                check(current.bodyMdSnapshot == RevisionHashing.canonicalBody(source.body))
                for (body in source.previousBodies) {
                    val oldHash = RevisionHashing.compute(body, current.frontmatterSnapshot)
                    check(oldHash != current.revisionHash)
                    val old = checkNotNull(repository.getRevision(source.id, oldHash))
                    check(old.bodyMdSnapshot == RevisionHashing.canonicalBody(body))
                    check(old.revisionOrd < current.revisionOrd)
                }
                val currentChunks = index.chunksForDocs(listOf(source.id), limitPerDoc = 1_000)
                check(currentChunks.isNotEmpty() && currentChunks.all { it.revisionHash == current.revisionHash })
                revisionReplay +=
                    buildJsonObject {
                        put("doc_id", source.id)
                        put("status", "EXECUTED_REPOSITORY_UPDATE_AND_INGEST")
                        put("history", JsonArray(history))
                        put("current_revision_hash", current.revisionHash)
                        put("current_revision_ord", current.revisionOrd)
                        put("current_chunk_ids", JsonArray(currentChunks.map { JsonPrimitive(it.id) }))
                    }
            }
            check(revisionReplay.size == 2)
            check(warnings.isEmpty()) { "Fresh synthetic ingest failed or degraded" }
            val documents = fixture.documents.map { checkNotNull(repository.getDocument(it.id)) }
            check(count(pool.writer(), "SELECT count(*) FROM documents") == 22L)
            check(lifecycle.integrityCheck() == IntegrityResult.Ok)
            check(count(pool.writer(), "SELECT count(*) FROM chunks_vec") == 0L)
            val snapshots =
                documents.associate { doc ->
                    val source = fixture.documents.single { it.id == doc.id }
                    check(
                        doc.bodyMd == source.body &&
                            doc.title == source.title &&
                            doc.personaId == spaces.getValue(source.spaceAlias),
                    )
                    check(RevisionHashing.compute(doc.bodyMd, doc.frontmatter) == doc.contentHash)
                    val revision = checkNotNull(repository.currentRevision(doc.id))
                    check(doc.contentHash == revision.revisionHash)
                    check(RevisionHashing.canonicalBody(doc.bodyMd) == revision.bodyMdSnapshot)
                    check(
                        RevisionHashing.canonicalFrontmatter(doc.frontmatter) ==
                            RevisionHashing.canonicalFrontmatter(revision.frontmatterSnapshot),
                    )
                    check(
                        RevisionHashing.compute(revision.bodyMdSnapshot, revision.frontmatterSnapshot) ==
                            revision.revisionHash,
                    )
                    doc.id to
                        ExpandedValidationDocument(
                            doc.id,
                            checkNotNull(doc.personaId),
                            revision.revisionHash,
                            revision.bodyMdSnapshot,
                            revision.frontmatterSnapshot,
                            doc.kind,
                            doc.title,
                            checkNotNull(doc.bodyMd),
                        )
                }
            val rows = index.chunksForDocs(documents.map { it.id }, limitPerDoc = 1_000)
            check(rows.size.toLong() == count(pool.writer(), "SELECT count(*) FROM chunks"))
            check(rows.map { it.id }.distinct().size == rows.size)
            for (doc in documents) {
                val expected = chunker.chunk(checkNotNull(doc.bodyMd))
                val actual = rows.filter { it.docId == doc.id }.sortedBy { it.ord }
                check(actual.size == expected.size)
                for ((source, row) in expected.zip(actual)) {
                    check(
                        row.ord == source.ord &&
                            row.text == source.embeddingText &&
                            row.tokenCount == source.tokenCount,
                    )
                    check(row.revisionHash == doc.contentHash)
                    check(
                        row.embedderId == IngestSteps.PENDING_EMBEDDER_ID &&
                            row.embedderVersion == IngestSteps.PENDING_EMBEDDER_VERSION,
                    )
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
            val assembled =
                RetrievedAssembler(
                    index,
                    repository,
                ).assemble(rows.map { ScoredChunk(it.id, 1.0) }, emptyMap())
            check(assembled.map { it.chunkId }.toSet() == rows.map { it.id }.toSet())
            val indexed =
                rows.map { row ->
                    val item = assembled.single { it.chunkId == row.id }
                    val doc = documents.single { it.id == row.docId }
                    val expected = chunker.chunk(checkNotNull(doc.bodyMd)).single { it.ord == row.ord }
                    val canonicalStart =
                        RevisionHashing
                            .canonicalBody(
                                doc.bodyMd!!.substring(0, expected.start),
                            ).toByteArray(Charsets.UTF_8)
                            .size
                    val canonicalEnd =
                        RevisionHashing
                            .canonicalBody(
                                doc.bodyMd!!.substring(0, expected.end),
                            ).toByteArray(Charsets.UTF_8)
                            .size
                    check(
                        item.locator?.byteStart == canonicalStart &&
                            item.locator?.byteEnd == canonicalEnd &&
                            item.locator?.chunkOrd == row.ord,
                    )
                    ExpandedIndexedChunk(row, item).also {
                        check(ExpandedRetrievalMetrics.validIndexedIdentity(it, snapshots.getValue(row.docId)))
                    }
                }
            val modes = linkedMapOf<String, JsonObject>()
            for ((name, representation) in listOf(
                "raw_string_baseline" to ValidationRepresentation.INDEXED_CHUNKS,
                "contextual_candidate" to ValidationRepresentation.EXPANDED_UNITS,
            )) {
                val queryRows =
                    fixture.queries.map { query ->
                        val request = fixture.request(query, spaces, snapshots)
                        val execution =
                            execute(
                                query,
                                request,
                                representation,
                                repository,
                                index,
                                spaces.getValue("default"),
                                snapshots,
                                indexed,
                                repetitions,
                            )
                        if (query.requiresApplicationContext) {
                            FreshValidationRow(
                                query,
                                FreshValidationExecution(FreshExecutionStatus.UNEXECUTED),
                                execution,
                            )
                        } else {
                            FreshValidationRow(query, execution)
                        }
                    }
                modes[name] =
                    FreshContextualValidationReport.mode(fixture, snapshots, queryRows, representation, repetitions)
            }
            check(count(pool.writer(), "SELECT count(*) FROM chunks_vec") == 0L)
            check(lifecycle.integrityCheck() == IntegrityResult.Ok)
            return buildJsonObject {
                put("schema_version", 1)
                put("fixture_protocol_version", 2)
                put("status", "MEASURED_DIAGNOSTIC")
                put("fixture_sha256", FreshContextualValidationFixture.SHA256)
                put("candidate_freeze", candidateFreeze)
                put("evaluator_freeze", evaluatorFreeze)
                put("build_revision", buildRevision)
                put("expanded_ranking_gate", "INELIGIBLE")
                put("full_hybrid_gate", "INELIGIBLE")
                put("repetitions", repetitions)
                put("warmups_per_query_mode", 1)
                put(
                    "sample_credit",
                    "minimum valid span count across repetitions; any nonempty repetition admits; strict success also requires identical results",
                )
                put(
                    "configuration",
                    buildJsonObject {
                        put("chunker", "Chunker(target=512,overlap=64)")
                        put("tokenizer", "ApproximateTokenizer")
                        put("embedder", JsonNull)
                        put("vector_count", 0)
                        put("evidence_policy", "production LexicalEvidenceGate defaults unchanged")
                        put("recall_timeout_ms", RetrievalServiceImpl.DEFAULT_RECALL_TIMEOUT_MILLIS)
                        put("call_timeout_ms", 10_000)
                    },
                )
                put(
                    "application_context",
                    buildJsonObject {
                        put("status", "UNEXECUTED")
                        put("executed", 0)
                        put("total", 6)
                        put(
                            "required_query_ids",
                            JsonArray(
                                fixture.queries
                                    .filter {
                                        it.requiresApplicationContext
                                    }.map { JsonPrimitive(it.gold.id) },
                            ),
                        )
                        put(
                            "reason",
                            "Fixture-fed component requests do not exercise the app SendPipeline context path",
                        )
                    },
                )
                put(
                    "revision_replay",
                    buildJsonObject {
                        put("status", "EXECUTED_REPOSITORY_UPDATE_AND_INGEST")
                        put(
                            "required_query_ids",
                            JsonArray(
                                fixture.queries
                                    .filter {
                                        "revision_replay" in it.requirements
                                    }.map { JsonPrimitive(it.gold.id) },
                            ),
                        )
                        put("documents", JsonArray(revisionReplay))
                    },
                )
                put("snapshot", snapshot(documents, snapshots, indexed, fixture, spaces, revisionReplay))
                put("ingest_warnings", JsonArray(warnings.toList().map(::JsonPrimitive)))
                put("modes", JsonObject(modes))
            }
        } finally {
            lifecycle.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun execute(
        query: FreshValidationQuery,
        request: ContextualRetrievalRequest,
        representation: ValidationRepresentation,
        repository: VaultRepositoryImpl,
        index: IndexStoreImpl,
        defaultSpace: String,
        documents: Map<String, ExpandedValidationDocument>,
        indexed: List<ExpandedIndexedChunk>,
        repetitions: Int,
    ): FreshValidationExecution {
        val warnings = Collections.synchronizedList(mutableListOf<String>())
        val service =
            RetrievalServiceImpl(
                index,
                repository,
                embedder = null,
                legacyPersonaId = defaultSpace,
                warn = warnings::add,
            )

        suspend fun sample(): FreshValidationSample {
            warnings.clear()
            val started = System.nanoTime()
            var result: ContextualRetrievalResult? = null
            var failure: String? = null
            val status =
                try {
                    result =
                        withTimeoutOrNull(10_000L) {
                            if (representation == ValidationRepresentation.EXPANDED_UNITS) {
                                service.retrieveContext(request)
                            } else {
                                ContextualRetrievalResult(
                                    request.query,
                                    request.query,
                                    FollowUpResolution.DIRECT,
                                    emptySet(),
                                    service.retrieveContext(request.query, request.k, request.personaId),
                                    emptyList(),
                                )
                            }
                        }
                    if (result == null) {
                        FreshExecutionStatus.TIMEOUT
                    } else {
                        val unexpected =
                            warnings.filterNot {
                                it ==
                                    "recall stage=vector skipped: no embedder loaded; degrading to lexical + graph"
                            }
                        when {
                            unexpected.any { " timed out " in it } -> FreshExecutionStatus.TIMEOUT
                            unexpected.isNotEmpty() -> FreshExecutionStatus.ERROR
                            else -> FreshExecutionStatus.EXECUTED
                        }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    failure = error.javaClass.simpleName
                    FreshExecutionStatus.ERROR
                }
            return FreshValidationSample(
                status,
                (System.nanoTime() - started) / 1_000_000.0,
                result,
                warnings.toList(),
                failure,
            )
        }
        val warmup = sample()
        val samples = List(repetitions) { sample() }
        val failed = (listOf(warmup) + samples).firstOrNull { it.status != FreshExecutionStatus.EXECUTED }
        if (failed != null) return FreshValidationExecution(failed.status, warmup, samples)
        val score =
            ExpandedRetrievalMetrics.acrossSamples(
                samples.map {
                    ExpandedRetrievalMetrics.score(
                        query.gold,
                        checkNotNull(request.personaId),
                        documents,
                        indexed,
                        checkNotNull(it.result),
                        representation,
                    )
                },
            )
        return FreshValidationExecution(FreshExecutionStatus.EXECUTED, warmup, samples, score)
    }

    private fun snapshot(
        documents: List<Document>,
        snapshots: Map<String, ExpandedValidationDocument>,
        indexed: List<ExpandedIndexedChunk>,
        fixture: FreshValidationFixture,
        spaces: Map<String, String>,
        revisionReplay: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put(
                "documents",
                JsonArray(
                    documents.map { doc ->
                        val snapshot = snapshots.getValue(doc.id)
                        buildJsonObject {
                            put("doc_id", doc.id)
                            put("title", doc.title)
                            put("kind", doc.kind.name)
                            put("persona_id", snapshot.personaId)
                            put("content_hash", doc.contentHash)
                            put("revision_hash", snapshot.revisionHash)
                            put("raw_body", snapshot.rawBody)
                            put("raw_body_sha256", hash(snapshot.rawBody))
                            put("body_snapshot", snapshot.bodySnapshot)
                            put("body_sha256", hash(snapshot.bodySnapshot))
                            put("frontmatter_snapshot", snapshot.frontmatterSnapshot)
                        }
                    },
                ),
            )
            put(
                "indexed_chunks",
                JsonArray(
                    indexed.map { item ->
                        buildJsonObject {
                            val row = item.stored
                            put("chunk_id", row.id)
                            put("doc_id", row.docId)
                            put("ord", row.ord)
                            put("stored_text", row.text)
                            put("stored_revision_hash", row.revisionHash)
                            put("current_revision_hash", snapshots.getValue(row.docId).revisionHash)
                            put("raw_byte_start", row.byteStart)
                            put("raw_byte_end", row.byteEnd)
                            put("token_count", row.tokenCount)
                            put("embedder_id", row.embedderId)
                            put("embedder_version", row.embedderVersion)
                            put("canonical_identity", FreshContextualValidationReport.retrieved(item.assembled))
                            put("enumeration_scores_are_query_signals", false)
                        }
                    },
                ),
            )
            put("revision_replay", JsonArray(revisionReplay))
            put("space_mapping", buildJsonObject { for ((alias, id) in spaces) put(alias, id) })
            put(
                "requests",
                JsonArray(
                    fixture.queries.map { query ->
                        val request = fixture.request(query, spaces, snapshots)
                        buildJsonObject {
                            put("id", query.gold.id)
                            put("query", request.query)
                            put("persona_id", request.personaId)
                            put("prior_user_query", request.followUp?.priorUserQuery)
                            put(
                                "source_pins",
                                JsonArray(
                                    request.followUp?.citedSources.orEmpty().map { pin ->
                                        buildJsonObject {
                                            put("doc_id", pin.documentId)
                                            put("revision_hash", pin.revisionHash)
                                            put("persona_id", pin.personaId)
                                        }
                                    },
                                ),
                            )
                            put("oracle_or_assistant_text_supplied", false)
                        }
                    },
                ),
            )
        }

    private fun hash(text: String): String = RetrievalEvaluationReport.sha256(text.toByteArray(Charsets.UTF_8))

    private fun count(
        connection: SQLiteConnection,
        sql: String,
    ): Long =
        connection.prepare(sql).use {
            check(it.step())
            it.getLong(0)
        }
}
