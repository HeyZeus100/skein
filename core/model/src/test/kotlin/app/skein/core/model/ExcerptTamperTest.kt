package app.skein.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptTamperTest {
    private val citation =
        Citation(
            marker = 1,
            documentId = "01924a4b-4d29-7000-8000-000000000001",
            revisionHash = "a".repeat(64),
            locator = Locator(0, 25, 0),
            excerpt = "Café 日本語 🧶 costs 42.",
            sourceKind = CitationSourceKind.LEXICAL,
        )
    private val encoded =
        Json
            .parseToJsonElement(
                CitationRecordJson.encode(CitationRecord(retrieved = listOf(citation), cited = listOf(1))),
            ).jsonObject

    @Test
    fun `mutating persisted excerpt is detected and cannot become a trusted citation`() {
        val original =
            (
                CitationRecordJson.decode(
                    encoded.toString(),
                ) as RetrievedChunksPayload.V1
            ).record.retrieved.single()
        assertEquals(ExcerptIntegrity.VERIFIED, CitationRecordJson.excerptIntegrity(original))
        assertEquals(ExcerptIntegrity.ALTERED, CitationRecordJson.excerptIntegrity(original.copy(excerpt = "costs 99")))
        assertRejected(changeCitation("excerpt", JsonPrimitive("costs 99")))
    }

    @Test
    fun `missing optional legacy hash remains explicitly unverified`() {
        val oldCitation =
            encoded
                .getValue("retrieved")
                .jsonArray
                .single()
                .jsonObject - "excerpt_hash"
        val legacy = JsonObject(encoded + ("retrieved" to JsonArray(listOf(JsonObject(oldCitation)))))
        val payload = CitationRecordJson.decode(legacy.toString()) as RetrievedChunksPayload.V1
        assertEquals(
            ExcerptIntegrity.HASH_MISSING,
            CitationRecordJson.excerptIntegrity(payload.record.retrieved.single()),
        )
    }

    @Test
    fun `encode refuses an already mismatched supplied hash`() {
        val failure =
            runCatching {
                CitationRecordJson.encode(
                    CitationRecord(retrieved = listOf(citation.copy(excerptHash = "b".repeat(64))), cited = listOf(1)),
                )
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("stored citation excerpt hash mismatch", failure?.message)
    }

    @Test
    fun `decode validates all markers hashes locators and excerpt bounds without throwing`() {
        for (invalid in listOf(
            changeCitation("marker", JsonPrimitive(0)),
            changeCitation("marker", JsonPrimitive(31)),
            changeCitation("marker", JsonPrimitive("1")),
            changeCitation("document_id", JsonPrimitive(" ")),
            changeCitation("revision_hash", JsonPrimitive("not-a-revision")),
            changeCitation("excerpt", JsonPrimitive("x".repeat(1025))),
            changeCitation("excerpt_hash", JsonPrimitive("not-a-hash")),
            changeCitation("excerpt_hash", JsonPrimitive("b".repeat(64))),
            changeCitation("source_kind", JsonPrimitive("note")),
            changeCitation("unknown", JsonPrimitive("ignored")),
            changeLocator("byte_start", JsonPrimitive(-1)),
            changeLocator("byte_end", JsonPrimitive(-1)),
            changeLocator("byte_start", JsonPrimitive(26)),
            changeLocator("chunk_ord", JsonPrimitive(-1)),
            changeLocator("byte_end", JsonPrimitive("25")),
            changeLocator("unknown", JsonPrimitive(1)),
        )) {
            assertRejected(invalid)
        }
    }

    @Test
    fun `decode rejects invalid record sets and missing arrays`() {
        val retrieved = encoded.getValue("retrieved").jsonArray.single()
        for (invalid in listOf(
            JsonObject(encoded + ("record_version" to JsonPrimitive("1"))),
            JsonObject(encoded + ("cited" to JsonArray(listOf(JsonPrimitive(7))))),
            JsonObject(encoded + ("retrieved" to JsonArray(listOf(retrieved, retrieved)))),
            JsonObject(encoded + ("retrieved" to JsonArray(List(31) { retrieved }))),
            JsonObject(encoded + ("cited" to JsonArray(List(31) { JsonPrimitive(1) }))),
            JsonObject(encoded - "retrieved"),
            JsonObject(encoded - "cited"),
            JsonObject(encoded + ("cited" to JsonPrimitive("[]"))),
            JsonObject(encoded + ("unknown" to JsonPrimitive(true))),
        )) {
            assertRejected(invalid)
        }
    }

    @Test
    fun `invalid display excerpts do not discard readable revision pins`() {
        val changed = changeCitation("excerpt", JsonPrimitive("altered"))
        assertRejected(changed)
        assertEquals(
            setOf(citation.documentId to citation.revisionHash),
            CitationRecordJson.revisionPins(changed.toString()),
        )
        assertNull(CitationRecordJson.revisionPins(changeCitation("revision_hash", JsonPrimitive("bad")).toString()))
        assertNull(CitationRecordJson.revisionPins("{truncated"))
        assertEquals(emptySet<Pair<DocId, RevisionHash>>(), CitationRecordJson.revisionPins("[1,2]"))
    }

    @Test
    fun `legacy noncanonical document ids remain resolvable until import reminting`() {
        val payload =
            CitationRecordJson.decode(
                changeCitation("document_id", JsonPrimitive("old-import-id")).toString(),
            )
        assertTrue(payload is RetrievedChunksPayload.V1)
    }

    private fun changeCitation(
        key: String,
        value: JsonElement,
    ): JsonObject {
        val original =
            encoded
                .getValue("retrieved")
                .jsonArray
                .single()
                .jsonObject
        return JsonObject(encoded + ("retrieved" to JsonArray(listOf(JsonObject(original + (key to value))))))
    }

    private fun changeLocator(
        key: String,
        value: JsonElement,
    ): JsonObject {
        val locator =
            encoded
                .getValue("retrieved")
                .jsonArray
                .single()
                .jsonObject
                .getValue("locator")
                .jsonObject
        return changeCitation("locator", JsonObject(locator + (key to value)))
    }

    private fun assertRejected(record: JsonObject) {
        assertEquals(RetrievedChunksPayload.Empty, CitationRecordJson.decode(record.toString()))
    }
}
