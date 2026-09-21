// citation-record-v1 codec for `messages.retrieved_chunks` —
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3.
//
// §1.3 is explicit that "SQLite has no JSON schema enforcement; enforcement
// is in the Kotlin serializer" — so this object is the enforcement point for
// the `citation-record-v1.schema.json` constraints (marker range, list
// cardinality, excerpt length, hash shape), not a thin `@Serializable`
// mapping. Every constraint the schema states that can be checked on the
// Kotlin side is checked in [CitationRecordJson.encode] and re-checked on
// [CitationRecordJson.decode].
//
// Both concrete `VaultRepository` implementations share this codec so the
// JSON a message round-trips through is byte-identical whichever one wrote
// it (see `Revisions.kt`'s header for the same argument about hashing).

package us.aherrera.skein.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

/**
 * What `messages.retrieved_chunks` held, once decoded.
 *
 * The three cases are exactly §1.5's migration story: a v1 record, a legacy
 * (pre-003) bare chunk-id array that §1.3 says to treat as `record_version:
 * 0`, and an absent/unparseable payload.
 */
public sealed interface RetrievedChunksPayload {
    /** No payload (NULL column, empty string, or JSON this codec cannot make sense of). */
    public data object Empty : RetrievedChunksPayload

    /**
     * Pre-migration-003 shape: a bare JSON array of `chunks.id` values, with
     * no revision hash and no excerpt. `chunks.id` is reassigned on
     * re-ingestion, so these references are *not* stable — the replay surface
     * renders them as "source unknown" (§1.4 `RetrievedChunkMigrationTest`)
     * rather than resolving them.
     */
    public data class Legacy(
        val chunkIds: List<ChunkId>,
    ) : RetrievedChunksPayload

    /** citation-record-v1 (`record_version: 1`). */
    public data class V1(
        val record: CitationRecord,
    ) : RetrievedChunksPayload
}

/** Encodes/decodes the `messages.retrieved_chunks` payload per §1.3. */
public object CitationRecordJson {
    /** The `record_version` this codec writes. */
    public const val RECORD_VERSION: Int = 1

    /** `citation.excerpt` `maxLength` from the schema. */
    public const val MAX_EXCERPT_CHARS: Int = 1024

    /** `retrieved`/`cited` `maxItems`, and the inclusive upper bound on `marker`. */
    public const val MAX_CITATIONS: Int = 30

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val HASH_REGEX: Regex = Regex("^[a-f0-9]{64}$")

    /**
     * Serializes [record] as citation-record-v1, filling in any missing
     * [Citation.excerptHash] via [RevisionHashing.excerptHash].
     *
     * @throws IllegalArgumentException if [record] violates a
     *   `citation-record-v1.schema.json` constraint. Persisting an invalid
     *   record would defeat the whole point of §1 — a citation that cannot be
     *   validated at replay is exactly the silent corruption the design
     *   removes — so this fails loudly rather than writing a degraded row.
     */
    public fun encode(record: CitationRecord): String {
        require(record.recordVersion == RECORD_VERSION) {
            "unsupported citation record_version=${record.recordVersion} (this codec writes $RECORD_VERSION)"
        }
        require(record.retrieved.size <= MAX_CITATIONS) {
            "citation record carries ${record.retrieved.size} retrieved entries, schema maximum is $MAX_CITATIONS"
        }
        require(record.cited.size <= MAX_CITATIONS) {
            "citation record carries ${record.cited.size} cited markers, schema maximum is $MAX_CITATIONS"
        }
        val markers = record.retrieved.map { it.marker }
        require(markers.toSet().size == markers.size) { "citation markers must be unique within a record" }
        require(record.cited.all { it in markers }) { "every `cited` marker must name an entry in `retrieved`" }
        for (citation in record.retrieved) validate(citation)

        val obj =
            buildJsonObject {
                put(KEY_RECORD_VERSION, JsonPrimitive(RECORD_VERSION))
                put(
                    KEY_RETRIEVED,
                    buildJsonArray { record.retrieved.forEach { add(encodeCitation(it)) } },
                )
                put(KEY_CITED, buildJsonArray { record.cited.forEach { add(JsonPrimitive(it)) } })
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * The legacy (`record_version: 0`) shape: a bare array of `chunks.id`.
     * Written only when a `NewMessage` carries no [CitationRecord], so a
     * caller that has not yet been ported to §1 keeps working unchanged.
     */
    public fun encodeLegacyChunkIds(ids: List<ChunkId>): String =
        json.encodeToString(JsonArray.serializer(), JsonArray(ids.map { JsonPrimitive(it) }))

    /**
     * Decodes whatever `messages.retrieved_chunks` holds. Never throws: an
     * unreadable payload decodes to [RetrievedChunksPayload.Empty] so a
     * single corrupt row cannot make a whole chat unopenable.
     */
    public fun decode(text: String?): RetrievedChunksPayload {
        if (text.isNullOrBlank()) return RetrievedChunksPayload.Empty
        val element =
            runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: return RetrievedChunksPayload.Empty
        return when (element) {
            is JsonArray -> decodeLegacy(element)
            is JsonObject -> decodeV1(element)
            else -> RetrievedChunksPayload.Empty
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun decodeLegacy(array: JsonArray): RetrievedChunksPayload {
        val ids =
            runCatching { array.map { (it as JsonPrimitive).long } }
                .getOrNull() ?: return RetrievedChunksPayload.Empty
        return if (ids.isEmpty()) RetrievedChunksPayload.Empty else RetrievedChunksPayload.Legacy(ids)
    }

    private fun decodeV1(obj: JsonObject): RetrievedChunksPayload {
        val version =
            runCatching { (obj[KEY_RECORD_VERSION] as? JsonPrimitive)?.int }.getOrNull()
                ?: return RetrievedChunksPayload.Empty
        if (version != RECORD_VERSION) return RetrievedChunksPayload.Empty
        return runCatching {
            val retrieved =
                (obj[KEY_RETRIEVED] as? JsonArray).orEmpty().map { decodeCitation(it as JsonObject) }
            val cited =
                (obj[KEY_CITED] as? JsonArray).orEmpty().map { (it as JsonPrimitive).int }
            RetrievedChunksPayload.V1(
                CitationRecord(recordVersion = version, retrieved = retrieved, cited = cited),
            ) as RetrievedChunksPayload
        }.getOrDefault(RetrievedChunksPayload.Empty)
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun validate(citation: Citation) {
        require(citation.marker in 1..MAX_CITATIONS) {
            "citation marker ${citation.marker} out of the schema range 1..$MAX_CITATIONS"
        }
        require(citation.documentId.isNotBlank()) { "citation document_id must not be blank" }
        require(HASH_REGEX.matches(citation.revisionHash)) {
            "citation revision_hash must be 64 lowercase hex characters"
        }
        require(citation.locator.byteStart >= 0 && citation.locator.byteEnd >= citation.locator.byteStart) {
            "citation locator byte range must be non-negative and non-inverted"
        }
        require(citation.excerpt.length <= MAX_EXCERPT_CHARS) {
            "citation excerpt is ${citation.excerpt.length} chars, schema maximum is $MAX_EXCERPT_CHARS"
        }
    }

    private fun encodeCitation(citation: Citation): JsonObject =
        buildJsonObject {
            put(KEY_MARKER, JsonPrimitive(citation.marker))
            put(KEY_DOCUMENT_ID, JsonPrimitive(citation.documentId))
            put(KEY_REVISION_HASH, JsonPrimitive(citation.revisionHash))
            put(
                KEY_LOCATOR,
                buildJsonObject {
                    put(KEY_BYTE_START, JsonPrimitive(citation.locator.byteStart))
                    put(KEY_BYTE_END, JsonPrimitive(citation.locator.byteEnd))
                    citation.locator.chunkOrd?.let { put(KEY_CHUNK_ORD, JsonPrimitive(it)) }
                },
            )
            put(KEY_EXCERPT, JsonPrimitive(citation.excerpt))
            put(
                KEY_EXCERPT_HASH,
                JsonPrimitive(citation.excerptHash ?: RevisionHashing.excerptHash(citation.excerpt)),
            )
            put(KEY_SOURCE_KIND, JsonPrimitive(citation.sourceKind.db))
        }

    private fun decodeCitation(obj: JsonObject): Citation {
        val locator = obj.getValue(KEY_LOCATOR) as JsonObject
        return Citation(
            marker = (obj.getValue(KEY_MARKER) as JsonPrimitive).int,
            documentId = (obj.getValue(KEY_DOCUMENT_ID) as JsonPrimitive).content,
            revisionHash = (obj.getValue(KEY_REVISION_HASH) as JsonPrimitive).content,
            locator =
                Locator(
                    byteStart = (locator.getValue(KEY_BYTE_START) as JsonPrimitive).int,
                    byteEnd = (locator.getValue(KEY_BYTE_END) as JsonPrimitive).int,
                    chunkOrd = (locator[KEY_CHUNK_ORD] as? JsonPrimitive)?.int,
                ),
            excerpt = (obj.getValue(KEY_EXCERPT) as JsonPrimitive).content,
            sourceKind = CitationSourceKind.fromDb((obj.getValue(KEY_SOURCE_KIND) as JsonPrimitive).content),
            excerptHash = (obj[KEY_EXCERPT_HASH] as? JsonPrimitive)?.content,
        )
    }

    private const val KEY_RECORD_VERSION: String = "record_version"
    private const val KEY_RETRIEVED: String = "retrieved"
    private const val KEY_CITED: String = "cited"
    private const val KEY_MARKER: String = "marker"
    private const val KEY_DOCUMENT_ID: String = "document_id"
    private const val KEY_REVISION_HASH: String = "revision_hash"
    private const val KEY_LOCATOR: String = "locator"
    private const val KEY_BYTE_START: String = "byte_start"
    private const val KEY_BYTE_END: String = "byte_end"
    private const val KEY_CHUNK_ORD: String = "chunk_ord"
    private const val KEY_EXCERPT: String = "excerpt"
    private const val KEY_EXCERPT_HASH: String = "excerpt_hash"
    private const val KEY_SOURCE_KIND: String = "source_kind"
}
