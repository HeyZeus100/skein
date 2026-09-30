package app.skein.testing.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture integrity only. No retriever, candidate policy, model or measured output participates. */
public class FreshRejectionValidationFixtureTest {
    private val bytes =
        checkNotNull(javaClass.getResourceAsStream("/eval/rejection-validation-20260930.json")).use { it.readBytes() }
    private val fixture = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    private val documents = fixture.getValue("documents").jsonArray.map { it.jsonObject }
    private val queries = fixture.getValue("queries").jsonArray.map { it.jsonObject }
    private val docs = documents.associateBy { it.string("id") }

    @Test
    public fun frozen_bytes_counts_and_authorship_match_the_independent_split() {
        assertEquals(
            "653b79ce9ab1c36776b5876d1b5d687a1d335f811f388b832fd0d9d258952674",
            RetrievalEvaluationReport.sha256(bytes),
        )
        assertEquals(1, fixture.getValue("schema_version").jsonPrimitive.int)
        assertEquals(2, fixture.getValue("fixture_protocol_version").jsonPrimitive.int)
        assertEquals("independent_validation_20260930", fixture.string("split"))
        assertEquals(22, docs.size)
        assertEquals(22, documents.size)
        assertEquals(40, queries.size)
        assertEquals(40, queries.map { it.string("id") }.distinct().size)
        val authorship = fixture.getValue("authorship").jsonObject
        assertFalse(authorship.getValue("candidate_outputs_seen").jsonPrimitive.boolean)
        assertTrue(authorship.getValue("synthetic_only").jsonPrimitive.boolean)
        assertTrue(docs.keys.all { it.matches(Regex("019b0930-0000-7000-8000-[0-9]{12}")) })
    }

    @Test
    public fun every_gold_span_is_unique_current_source_text_in_the_requested_space() {
        var labelledSpans = 0
        for (query in queries) {
            val relevant = query.getValue("relevant").jsonArray
            assertEquals(query.getValue("answer") != JsonNull, relevant.isNotEmpty())
            assertTrue(query.getValue("all_gold_spans_required").jsonPrimitive.boolean)
            for (value in relevant) {
                val label = value.jsonObject
                val source = docs.getValue(label.string("doc_id"))
                val evidence = label.string("evidence")
                val body = source.string("body_md")
                assertEquals(3, label.getValue("grade").jsonPrimitive.int)
                assertEquals(query.string("persona_id"), source.string("persona_id"))
                assertTrue(
                    "Missing evidence in ${query.string("id")}",
                    evidence.isNotBlank() && body.contains(evidence),
                )
                assertEquals(body.indexOf(evidence), body.lastIndexOf(evidence))
                labelledSpans++
            }
            for (forbidden in query.getValue("forbidden_doc_ids").jsonArray) {
                val id = forbidden.jsonPrimitive.content
                assertTrue(docs.containsKey(id))
                assertTrue(docs.getValue(id).string("persona_id") != query.string("persona_id"))
                assertTrue(relevant.none { it.jsonObject.string("doc_id") == id })
            }
        }
        assertEquals(22, queries.count { it.getValue("answer") != JsonNull })
        assertEquals(18, queries.count { it.getValue("answer") == JsonNull })
        assertEquals(24, labelledSpans)
    }

    @Test
    public fun competing_sources_are_both_required_and_current_revisions_have_real_distinct_history() {
        val conflicts = queries.filter { it.string("category") == "contradiction_unresolved" }
        assertEquals(2, conflicts.size)
        for (query in conflicts) {
            assertEquals(2, query.getValue("relevant").jsonArray.size)
            assertEquals(
                2,
                query
                    .getValue("relevant")
                    .jsonArray
                    .map { it.jsonObject.string("doc_id") }
                    .distinct()
                    .size,
            )
        }
        val revised = documents.filter { "previous_revisions" in it }
        assertEquals(2, revised.size)
        for (source in revised) {
            val history = source.getValue("previous_revisions").jsonArray
            assertTrue(history.isNotEmpty())
            assertTrue(history.all { it.jsonObject.string("body_md") != source.string("body_md") })
        }
        val revisionCases = queries.filter { "revision_replay" in it.requirements() }
        assertEquals(4, revisionCases.size)
        assertTrue(revisionCases.all { it.string("category").startsWith("revision_") })
        assertTrue(
            fixture
                .getValue("execution_contract")
                .jsonObject
                .string("revision_replay")
                .contains("same doc id"),
        )
    }

    @Test
    public fun raw_followups_and_oracle_resolution_stay_separate_with_exact_source_pins() {
        val followups = queries.filter { "conversation_context" in it.requirements() }
        assertEquals(6, followups.size)
        for (query in followups) {
            val expected = query.getValue("expected_resolution").jsonObject
            assertEquals("oracle_review_only_not_retrieval_input", expected.string("use"))
            assertTrue(expected.string("query") != query.string("query"))
            val turns =
                query
                    .getValue("conversation")
                    .jsonObject
                    .getValue("turns")
                    .jsonArray
            assertTrue(turns.isNotEmpty())
            for (raw in turns) {
                val turn = raw.jsonObject
                assertTrue(turn.string("role") in setOf("user", "assistant"))
                for (value in turn["source_pins"]?.jsonArray.orEmpty()) {
                    val pin = value.jsonObject
                    val source = docs.getValue(pin.string("doc_id"))
                    assertEquals(source.string("persona_id"), pin.string("persona_id"))
                    assertEquals("current", pin.string("revision"))
                    assertEquals(
                        RetrievalEvaluationReport.sha256(source.string("body_md").toByteArray(Charsets.UTF_8)),
                        pin.string("body_sha256"),
                    )
                }
            }
        }
        val assistantOnly = followups.single { "assistant_claims_are_not_evidence" in it.requirements() }
        assertEquals(JsonNull, assistantOnly.getValue("answer"))
        assertTrue(assistantOnly.getValue("relevant").jsonArray.isEmpty())
        val switch = followups.single { "space_switch" in it.requirements() }
        assertEquals("work", switch.getValue("conversation").jsonObject.string("prior_persona_id"))
        assertEquals("default", switch.string("persona_id"))
        assertEquals(JsonNull, switch.getValue("answer"))
    }

    @Test
    public fun unsupported_runtime_contexts_remain_in_the_full_denominator() {
        val contract = fixture.getValue("execution_contract").jsonObject
        assertEquals(40, contract.getValue("required_query_count").jsonPrimitive.int)
        assertTrue(contract.string("unsupported_cases").contains("UNEXECUTED"))
        assertTrue(contract.string("unsupported_cases").contains("denominator"))
        assertTrue(contract.string("oracle_resolutions").contains("never feed"))
        assertEquals(10, queries.count { it.requirements().size > 1 })
        assertTrue(queries.all { "current_revision_anchored_notes" in it.requirements() })
        assertEquals(6, queries.count { it.string("category").startsWith("space_") })
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.requirements(): List<String> =
        getValue("execution_requirements").jsonArray.map { it.jsonPrimitive.content }
}
