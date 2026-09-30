package app.skein.testing.eval

import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.RetrievalFollowUpContext
import app.skein.core.model.RetrievalSourcePin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class FreshValidationDocument(
    val id: String,
    val title: String,
    val body: String,
    val spaceAlias: String,
    val previousBodies: List<String>,
)

public data class FreshValidationPin(
    val documentId: String,
    val bodySha256: String,
    val spaceAlias: String,
)

/** Only the prior USER text and independently pinned sources can become request context. */
public data class FreshValidationHistory(
    val priorUserQuery: String,
    val pins: List<FreshValidationPin>,
)

public data class FreshValidationQuery(
    val gold: RetrievalGoldQuery,
    val requirements: Set<String>,
    val history: FreshValidationHistory?,
    /** Review-only. This is deliberately absent from [FreshValidationFixture.request]. */
    val oracleResolution: String?,
) {
    public val requiresApplicationContext: Boolean
        get() = "conversation_context" in requirements
}

public data class FreshValidationFixture(
    val documents: List<FreshValidationDocument>,
    val queries: List<FreshValidationQuery>,
) {
    /** Gold, assistant prose and oracle resolution never reach the production request. */
    public fun request(
        query: FreshValidationQuery,
        spaces: Map<String, String>,
        snapshots: Map<String, ExpandedValidationDocument>,
    ): ContextualRetrievalRequest {
        val followUp =
            query.history?.let { history ->
                RetrievalFollowUpContext(
                    priorUserQuery = history.priorUserQuery,
                    citedSources =
                        history.pins.map { pin ->
                            val source = snapshots.getValue(pin.documentId)
                            check(source.personaId == spaces.getValue(pin.spaceAlias))
                            check(
                                RetrievalEvaluationReport.sha256(source.bodySnapshot.toByteArray(Charsets.UTF_8)) ==
                                    pin.bodySha256,
                            )
                            RetrievalSourcePin(pin.documentId, source.revisionHash, source.personaId)
                        },
                )
            }
        return ContextualRetrievalRequest(
            query.gold.query,
            ExpandedRetrievalMetrics.K,
            spaces.getValue(query.gold.personaAlias!!),
            followUp,
        )
    }
}

/** Frozen protocol-2 parser; no candidate or measured outputs are used to define expectations. */
public object FreshContextualValidationFixture {
    public const val SHA256: String = "653b79ce9ab1c36776b5876d1b5d687a1d335f811f388b832fd0d9d258952674"
    public const val RESOURCE: String = "/eval/rejection-validation-20260930.json"
    public val QUERY_IDS: List<String> = (1..40).map { "independent-20260930-${it.toString().padStart(2, '0')}" }

    public fun load(): FreshValidationFixture =
        parse(checkNotNull(javaClass.getResourceAsStream(RESOURCE)).use { it.readBytes() })

    public fun parse(bytes: ByteArray): FreshValidationFixture {
        require(RetrievalEvaluationReport.sha256(bytes) == SHA256) { "Fresh fixture bytes changed" }
        return decode(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
    }

    internal fun decode(root: JsonObject): FreshValidationFixture {
        require(root.getValue("schema_version").jsonPrimitive.int == 1)
        require(root.getValue("fixture_protocol_version").jsonPrimitive.int == 2)
        require(root.string("split") == "independent_validation_20260930")
        val documents =
            root.getValue("documents").jsonArray.map { value ->
                val source = value.jsonObject
                FreshValidationDocument(
                    source.string("id"),
                    source.string("title"),
                    source.string("body_md"),
                    source.string("persona_id"),
                    source["previous_revisions"]?.jsonArray.orEmpty().map { it.jsonObject.string("body_md") },
                )
            }
        require(documents.size == 22 && documents.map { it.id }.distinct().size == 22)
        val byId = documents.associateBy { it.id }
        val queries =
            root.getValue("queries").jsonArray.map { value ->
                val source = value.jsonObject
                require(source.getValue("all_gold_spans_required").jsonPrimitive.boolean)
                val spans =
                    source.getValue("relevant").jsonArray.map { raw ->
                        val span = raw.jsonObject
                        val label =
                            GoldEvidence(
                                span.string("doc_id"),
                                span.getValue("grade").jsonPrimitive.int,
                                span.string("evidence"),
                            )
                        val document = byId.getValue(label.docId)
                        require(label.grade == 3 && label.evidence.isNotBlank())
                        require(document.spaceAlias == source.string("persona_id"))
                        require(
                            document.body.indexOf(label.evidence) >= 0 &&
                                document.body.indexOf(label.evidence) == document.body.lastIndexOf(label.evidence),
                        )
                        label
                    }
                require((source.getValue("answer") != JsonNull) == spans.isNotEmpty())
                val forbidden =
                    source
                        .getValue("forbidden_doc_ids")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet()
                require(forbidden.all { byId.getValue(it).spaceAlias != source.string("persona_id") })
                val requirements =
                    source
                        .getValue(
                            "execution_requirements",
                        ).jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet()
                val history =
                    source["conversation"]?.jsonObject?.let { conversation ->
                        val turns = conversation.getValue("turns").jsonArray.map { it.jsonObject }
                        require(turns.all { it.string("role") in setOf("user", "assistant") })
                        val priorUser = turns.last { it.string("role") == "user" }.string("content")
                        val pins =
                            turns.flatMap { it["source_pins"]?.jsonArray.orEmpty() }.map { raw ->
                                val pin = raw.jsonObject
                                require(pin.string("revision") == "current")
                                val document = byId.getValue(pin.string("doc_id"))
                                require(document.spaceAlias == pin.string("persona_id"))
                                require(
                                    RetrievalEvaluationReport.sha256(document.body.toByteArray(Charsets.UTF_8)) ==
                                        pin.string("body_sha256"),
                                )
                                FreshValidationPin(document.id, pin.string("body_sha256"), document.spaceAlias)
                            }
                        FreshValidationHistory(priorUser, pins)
                    }
                require((history != null) == ("conversation_context" in requirements))
                val oracle =
                    source["expected_resolution"]?.jsonObject?.let {
                        require(it.string("use") == "oracle_review_only_not_retrieval_input")
                        require(it.string("query") != source.string("query"))
                        it.string("query")
                    }
                require((oracle != null) == (history != null))
                FreshValidationQuery(
                    RetrievalGoldQuery(
                        source.string("id"),
                        source.string("category"),
                        source.string("query"),
                        source.string("persona_id"),
                        spans,
                        forbidden,
                    ),
                    requirements,
                    history,
                    oracle,
                )
            }
        require(queries.map { it.gold.id } == QUERY_IDS)
        require(queries.count { it.gold.answerable } == 22 && queries.sumOf { it.gold.relevant.size } == 24)
        require(queries.count { it.requiresApplicationContext } == 6)
        require(queries.count { "revision_replay" in it.requirements } == 4)
        require(queries.count { it.history != null } == 6)
        require(
            queries.filter { it.gold.category == "contradiction_unresolved" }.let {
                it.size == 2 &&
                    it.all { q -> q.gold.relevant.size == 2 }
            },
        )
        return FreshValidationFixture(documents, queries)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
