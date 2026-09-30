package app.skein.testing.eval

import app.skein.core.model.DocumentKind
import app.skein.core.model.RevisionHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser integrity only; never invokes retrieval or measures candidate quality. */
public class FreshContextualValidationFixtureTest {
    private val fixture = FreshContextualValidationFixture.load()
    private val spaces =
        fixture.documents
            .map { it.spaceAlias }
            .distinct()
            .associateWith { "actual-space-$it" }
    private val snapshots =
        fixture.documents.associate { doc ->
            val frontmatter = JsonObject(emptyMap())
            doc.id to
                ExpandedValidationDocument(
                    doc.id,
                    spaces.getValue(doc.spaceAlias),
                    RevisionHashing.compute(doc.body, frontmatter),
                    RevisionHashing.canonicalBody(doc.body),
                    frontmatter,
                    DocumentKind.NOTE,
                    doc.title,
                    doc.body,
                )
        }

    @Test
    public fun frozen_schema_keeps_all_spans_absences_and_distinct_runtime_requirements() {
        assertEquals(40, fixture.queries.size)
        assertEquals(22, fixture.queries.count { it.gold.answerable })
        assertEquals(24, fixture.queries.sumOf { it.gold.relevant.size })
        assertEquals(6, fixture.queries.count { it.requiresApplicationContext })
        assertEquals(4, fixture.queries.count { "revision_replay" in it.requirements })
        assertEquals(2, fixture.documents.count { it.previousBodies.isNotEmpty() })
    }

    @Test
    public fun raw_query_and_only_prior_user_and_source_pins_reach_request() {
        for (query in fixture.queries) {
            val request = fixture.request(query, spaces, snapshots)
            assertEquals(query.gold.query, request.query)
            assertEquals(spaces.getValue(query.gold.personaAlias!!), request.personaId)
            assertEquals(query.history?.priorUserQuery, request.followUp?.priorUserQuery)
            if (query.oracleResolution != null) assertNotEquals(query.oracleResolution, request.query)
            for (pin in request.followUp?.citedSources.orEmpty()) {
                assertEquals(snapshots.getValue(pin.documentId).revisionHash, pin.revisionHash)
            }
        }
    }

    @Test
    public fun assistant_only_claim_does_not_become_a_source_and_cross_space_pin_is_not_reassigned() {
        val unsupported = fixture.queries.single { "assistant_claims_are_not_evidence" in it.requirements }
        assertTrue(
            fixture
                .request(unsupported, spaces, snapshots)
                .followUp!!
                .citedSources
                .isEmpty(),
        )
        val switched = fixture.queries.single { "space_switch" in it.requirements }
        val request = fixture.request(switched, spaces, snapshots)
        assertTrue(request.followUp!!.citedSources.all { it.personaId != request.personaId })
        assertFalse(switched.gold.answerable)
    }

    @Test
    public fun changed_source_body_cannot_be_relabelled_with_a_frozen_pin() {
        val query = fixture.queries.first { it.history?.pins?.isNotEmpty() == true }
        val id =
            query.history!!
                .pins
                .first()
                .documentId
        val altered = snapshots + (id to snapshots.getValue(id).copy(bodySnapshot = "Unrelated synthetic replacement."))
        assertThrows(IllegalStateException::class.java) { fixture.request(query, spaces, altered) }
    }

    @Test
    public fun changed_bytes_missing_rows_and_changed_protocol_are_refused() {
        val bytes = javaClass.getResourceAsStream(FreshContextualValidationFixture.RESOURCE)!!.use { it.readBytes() }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { FreshContextualValidationFixture.parse(bytes + 32.toByte()) }
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            FreshContextualValidationFixture.decode(
                JsonObject(
                    root + ("queries" to JsonArray(root.getValue("queries").jsonArray.dropLast(1))),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FreshContextualValidationFixture.decode(JsonObject(root + ("fixture_protocol_version" to JsonPrimitive(1))))
        }
    }
}
