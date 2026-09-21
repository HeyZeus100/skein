package us.aherrera.skein.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.4 `CitationRecordJsonTest` /
 * `RetrievedChunkMigrationTest`: round-trips `record_version: 1`, rejects
 * schema violations, and reads the legacy (`record_version: 0`) chunk-id
 * payload without crashing.
 */
class CitationRecordJsonTest {
    private val hash = "a".repeat(64)

    private fun citation(
        marker: Int = 1,
        excerpt: String = "the cited text",
        revisionHash: String = hash,
    ): Citation =
        Citation(
            marker = marker,
            documentId = "01924a4b-4d29-7000-8000-000000000001",
            revisionHash = revisionHash,
            locator = Locator(byteStart = 12, byteEnd = 40, chunkOrd = 3),
            excerpt = excerpt,
            sourceKind = CitationSourceKind.VECTOR,
        )

    @Test
    fun `round-trips a v1 record`() {
        val record = CitationRecord(retrieved = listOf(citation(1), citation(2)), cited = listOf(2))
        val decoded = CitationRecordJson.decode(CitationRecordJson.encode(record))

        assertTrue(decoded is RetrievedChunksPayload.V1)
        val back = (decoded as RetrievedChunksPayload.V1).record
        assertEquals(1, back.recordVersion)
        assertEquals(listOf(1, 2), back.retrieved.map { it.marker })
        assertEquals(listOf(2), back.cited)
        assertEquals(
            12,
            back.retrieved
                .first()
                .locator.byteStart,
        )
        assertEquals(
            3,
            back.retrieved
                .first()
                .locator.chunkOrd,
        )
        assertEquals(CitationSourceKind.VECTOR, back.retrieved.first().sourceKind)
    }

    @Test
    fun `encode fills in the excerpt hash for tamper detection`() {
        val json = CitationRecordJson.encode(CitationRecord(retrieved = listOf(citation()), cited = listOf(1)))
        assertTrue(json, json.contains(RevisionHashing.excerptHash("the cited text")))
    }

    @Test
    fun `encoded json uses the snake_case wire keys from the schema`() {
        val json = CitationRecordJson.encode(CitationRecord(retrieved = listOf(citation()), cited = listOf(1)))
        for (key in listOf(
            "record_version",
            "retrieved",
            "cited",
            "document_id",
            "revision_hash",
            "byte_start",
            "source_kind",
        )) {
            assertTrue("expected key `$key` in $json", json.contains("\"$key\""))
        }
    }

    @Test
    fun `chunk_ord is omitted when absent`() {
        val c = citation().copy(locator = Locator(byteStart = 0, byteEnd = 1))
        val json = CitationRecordJson.encode(CitationRecord(retrieved = listOf(c), cited = emptyList()))
        assertTrue(json, !json.contains("chunk_ord"))
        val back = (CitationRecordJson.decode(json) as RetrievedChunksPayload.V1).record
        assertEquals(
            null,
            back.retrieved
                .single()
                .locator.chunkOrd,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an oversized excerpt`() {
        CitationRecordJson.encode(
            CitationRecord(retrieved = listOf(citation(excerpt = "x".repeat(1025))), cited = emptyList()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a marker above the schema maximum`() {
        CitationRecordJson.encode(CitationRecord(retrieved = listOf(citation(marker = 31)), cited = emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a malformed revision hash`() {
        CitationRecordJson.encode(
            CitationRecord(retrieved = listOf(citation(revisionHash = "NOTHEX")), cited = emptyList()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a cited marker that names no retrieved entry`() {
        CitationRecordJson.encode(CitationRecord(retrieved = listOf(citation(1)), cited = listOf(7)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate markers`() {
        CitationRecordJson.encode(CitationRecord(retrieved = listOf(citation(1), citation(1)), cited = emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects more retrieved entries than the schema allows`() {
        CitationRecordJson.encode(
            CitationRecord(retrieved = (1..31).map { citation(marker = it) }, cited = emptyList()),
        )
    }

    @Test
    fun `reads the legacy record_version 0 chunk-id array`() {
        val decoded = CitationRecordJson.decode("[147,812]")
        assertTrue(decoded is RetrievedChunksPayload.Legacy)
        assertEquals(listOf(147L, 812L), (decoded as RetrievedChunksPayload.Legacy).chunkIds)
    }

    @Test
    fun `legacy encoder still writes the bare array shape`() {
        assertEquals("[147,812]", CitationRecordJson.encodeLegacyChunkIds(listOf(147L, 812L)))
    }

    @Test
    fun `an unknown record_version decodes as empty rather than throwing`() {
        assertEquals(RetrievedChunksPayload.Empty, CitationRecordJson.decode("""{"record_version":2,"retrieved":[]}"""))
    }

    @Test
    fun `null, blank and unparseable payloads decode as empty`() {
        assertEquals(RetrievedChunksPayload.Empty, CitationRecordJson.decode(null))
        assertEquals(RetrievedChunksPayload.Empty, CitationRecordJson.decode(""))
        assertEquals(RetrievedChunksPayload.Empty, CitationRecordJson.decode("not json at all"))
        assertEquals(RetrievedChunksPayload.Empty, CitationRecordJson.decode("[]"))
    }

    @Test
    fun `a v1 payload missing a required field decodes as empty rather than throwing`() {
        assertNotNull(CitationRecordJson.decode("""{"record_version":1,"retrieved":[{"marker":1}],"cited":[]}"""))
        assertEquals(
            RetrievedChunksPayload.Empty,
            CitationRecordJson.decode("""{"record_version":1,"retrieved":[{"marker":1}],"cited":[]}"""),
        )
    }
}
