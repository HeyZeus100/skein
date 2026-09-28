// citation-record-v1 codec for `messages.retrieved_chunks` —
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3.
//
// §1.3 is explicit that "SQLite has no JSON schema enforcement; enforcement
// is in the Kotlin serializer" — so this object is the enforcement point for
// the `citation-record-v1.schema.json` constraints (marker range, list
// cardinality, excerpt length, hash shape), not a thin `@Serializable`
// mapping. Encode and decode share semantic validation. For compatibility
// with existing imported documents, document ids are nonblank opaque ids;
// canonical import id reminting is a separate migration boundary.
//
// Both concrete `VaultRepository` implementations share this codec so the
// JSON a message round-trips through is byte-identical whichever one wrote
// it (see `Revisions.kt`'s header for the same argument about hashing).

package app.skein.core.model

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

/** Excerpt integrity is independent of whether the source supports an answer's claims. */
public enum class ExcerptIntegrity { VERIFIED, HASH_MISSING, ALTERED }

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
        validateRecord(record)

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

    /** A missing optional legacy hash is unknown integrity, never a verified excerpt. */
    public fun excerptIntegrity(citation: Citation): ExcerptIntegrity {
        val stored = citation.excerptHash ?: return ExcerptIntegrity.HASH_MISSING
        return if (HASH_REGEX.matches(stored) && stored == RevisionHashing.excerptHash(citation.excerpt)) {
            ExcerptIntegrity.VERIFIED
        } else {
            ExcerptIntegrity.ALTERED
        }
    }

    private fun validateRecord(record: CitationRecord) {
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

    /**
     * Conservative retention references, not displayable or trusted citations.
     * Invalid excerpts must not unpin their source revisions. Null means an
     * ambiguous payload: callers must skip garbage collection rather than
     * interpret the unreadable record as having no references. Legacy arrays
     * carry no revision addresses and therefore contribute an empty set.
     */
    public fun revisionPins(text: String?): Set<Pair<DocId, RevisionHash>>? {
        if (text.isNullOrBlank()) return emptySet()
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        if (element is JsonArray) {
            return if (decodeLegacy(element) is RetrievedChunksPayload.Legacy || element.isEmpty()) emptySet() else null
        }
        return runCatching {
            val obj = element as JsonObject
            require(obj.getValue(KEY_RECORD_VERSION).integer() == RECORD_VERSION)
            (obj.getValue(KEY_RETRIEVED) as JsonArray)
                .map { item ->
                    val citation = item as JsonObject
                    val id = citation.getValue(KEY_DOCUMENT_ID).string()
                    val hash = citation.getValue(KEY_REVISION_HASH).string()
                    require(id.isNotBlank() && HASH_REGEX.matches(hash))
                    id to hash
                }.toSet()
        }.getOrNull()
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
            runCatching { obj.getValue(KEY_RECORD_VERSION).integer() }.getOrNull()
                ?: return RetrievedChunksPayload.Empty
        if (version != RECORD_VERSION) return RetrievedChunksPayload.Empty
        return runCatching {
            require(obj.keys == setOf(KEY_RECORD_VERSION, KEY_RETRIEVED, KEY_CITED))
            val retrieved =
                (obj.getValue(KEY_RETRIEVED) as JsonArray).map { decodeCitation(it as JsonObject) }
            val cited =
                (obj.getValue(KEY_CITED) as JsonArray).map { it.integer() }
            val record = CitationRecord(recordVersion = version, retrieved = retrieved, cited = cited)
            validateRecord(record)
            RetrievedChunksPayload.V1(record) as RetrievedChunksPayload
        }.getOrDefault(RetrievedChunksPayload.Empty)
    }

    private fun JsonElement.integer(): Int {
        val value = this as JsonPrimitive
        require(!value.isString)
        return value.int
    }

    private fun JsonElement.string(): String {
        val value = this as JsonPrimitive
        require(value.isString)
        return value.content
    }

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
        require(citation.locator.chunkOrd == null || citation.locator.chunkOrd >= 0) {
            "citation chunk ordinal must be non-negative"
        }
        require(citation.excerpt.length <= MAX_EXCERPT_CHARS) {
            "citation excerpt is ${citation.excerpt.length} chars, schema maximum is $MAX_EXCERPT_CHARS"
        }
        require(excerptIntegrity(citation) != ExcerptIntegrity.ALTERED) { "stored citation excerpt hash mismatch" }
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
        require(obj.keys.all { it in CITATION_KEYS })
        val locator = obj.getValue(KEY_LOCATOR) as JsonObject
        require(locator.keys.all { it in setOf(KEY_BYTE_START, KEY_BYTE_END, KEY_CHUNK_ORD) })
        return Citation(
            marker = obj.getValue(KEY_MARKER).integer(),
            documentId = obj.getValue(KEY_DOCUMENT_ID).string(),
            revisionHash = obj.getValue(KEY_REVISION_HASH).string(),
            locator =
                Locator(
                    byteStart = locator.getValue(KEY_BYTE_START).integer(),
                    byteEnd = locator.getValue(KEY_BYTE_END).integer(),
                    chunkOrd = locator[KEY_CHUNK_ORD]?.integer(),
                ),
            excerpt = obj.getValue(KEY_EXCERPT).string(),
            sourceKind = CitationSourceKind.fromDb(obj.getValue(KEY_SOURCE_KIND).string()),
            excerptHash = obj[KEY_EXCERPT_HASH]?.string(),
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
    private val CITATION_KEYS =
        setOf(
            KEY_MARKER,
            KEY_DOCUMENT_ID,
            KEY_REVISION_HASH,
            KEY_LOCATOR,
            KEY_EXCERPT,
            KEY_EXCERPT_HASH,
            KEY_SOURCE_KIND,
        )
}
