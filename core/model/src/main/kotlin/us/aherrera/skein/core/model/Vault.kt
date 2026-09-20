// M0.5 contract file (`E0.I11`): lands the design plan §4.2 interfaces —
// `VaultRepository` and `IndexStore` — plus the value types they speak in.
//
// `:core:model` is pure Kotlin/JVM (no Android imports) so every process
// (`:app`, `:inference-service`, `:embedder-service`, `:testing`) can consume
// the same domain types. The only runtime deps this file needs are
// `kotlinx.coroutines.flow.Flow` and `kotlinx.serialization.json.JsonObject`
// (both wired in the module's `build.gradle.kts`).
//
// The file follows the plan §4.2 signatures verbatim. No measurement-derived
// constants are baked in: the vec0 dimension (256) and the int8 quantization
// convention live in the DDL (`001_initial.sql`) and the `EmbedderService`
// contract (§4.6, `E0.I17`) respectively — implementations may branch on
// `Chunk.embedderVersion` when the eventual `MEASUREMENTS.md` (`skein-5hr`)
// picks a different Matryoshka slice.
//
// Additive types from POST_REVIEW_RESOLUTIONS.md §1 (citation stability) live
// alongside the plan-verbatim contract because they influence how a
// `VaultRepository` records the `retrieved_chunks` JSON payload on
// `Message`. The full `document_revisions` table itself is Migration 003 in
// POST_REVIEW_RESOLUTIONS.md §1.3 and is intentionally NOT in the v1
// `001_initial.sql` — the Kotlin types are locked here so downstream RAG
// code can compile against a stable payload shape before Migration 003
// lands (see `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3).

package us.aherrera.skein.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream

// -----------------------------------------------------------------------------
// Identifier typealiases (plan §4.2)
// -----------------------------------------------------------------------------

/** UUIDv7, lowercase 36-char RFC 9562. Stored in `documents.id` and mirrored in the note's frontmatter `id:` key. */
public typealias DocId = String

/** UUIDv7. */
public typealias PersonaId = String

/** `chunks.id` (also the shared rowid for `chunks_fts` and `chunks_vec`). */
public typealias ChunkId = Long

/**
 * Hex-encoded BLAKE3-256 over the canonicalized `body_md` + normalized
 * frontmatter of a document at a given revision — see
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1 (citation stability).
 *
 * The revision hash is content-addressable: two documents whose canonical
 * bodies match hash to the same value, so a citation can be resolved by
 * hash lookup even if the row's `updated_at` has since advanced.
 */
public typealias RevisionHash = String

// -----------------------------------------------------------------------------
// Documents (plan §4.2)
// -----------------------------------------------------------------------------

public enum class DocumentKind(
    public val db: String,
) {
    NOTE("note"),
    CHAT("chat"),
    ATTACHMENT("attachment"),
    AIOUT("aiout"),
    ;

    public companion object {
        public fun fromDb(s: String): DocumentKind = entries.first { it.db == s }
    }
}

/** One row of the `documents` table. `bodyMd` is null exactly for `ATTACHMENT`. */
public data class Document(
    val id: DocId,
    val kind: DocumentKind,
    val title: String,
    val bodyMd: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val personaId: PersonaId?,
    val frontmatter: JsonObject,
    val contentHash: String?,
)

/**
 * Creation payload. `id` is set only when importing a file that already
 * carries a Skein/Obsidian `id:`; otherwise a fresh UUIDv7 is minted by the
 * repository implementation. `frontmatter` may include an `id` key that
 * the repository preserves (see `VaultRepositoryContractTest`).
 */
public data class NewDocument(
    val kind: DocumentKind,
    val title: String,
    val bodyMd: String?,
    val personaId: PersonaId? = null,
    val frontmatter: JsonObject = JsonObject(emptyMap()),
    val id: DocId? = null,
)

public data class DocumentHit(
    val document: Document,
    val snippet: String,
    val rank: Double,
)

public data class TimelineFilter(
    val personaId: PersonaId? = null,
    val tag: String? = null,
    val kinds: Set<DocumentKind> = DocumentKind.entries.toSet(),
)

// -----------------------------------------------------------------------------
// Messages (plan §4.2)
// -----------------------------------------------------------------------------

public data class Message(
    val id: String,
    val chatDocId: DocId,
    val role: Role,
    val contentMd: String,
    val modelId: ModelId?,
    val retrievedChunks: List<ChunkId>,
    val createdAt: Long,
)

public data class NewMessage(
    val role: Role,
    val contentMd: String,
    val modelId: ModelId? = null,
    val retrievedChunks: List<ChunkId> = emptyList(),
)

// -----------------------------------------------------------------------------
// Personas (plan §4.2)
// -----------------------------------------------------------------------------

public data class Persona(
    val id: PersonaId,
    val name: String,
    val systemPrompt: String?,
    val defaultModel: ModelId?,
    val createdAt: Long,
)

// -----------------------------------------------------------------------------
// Ingest queue (plan §4.2)
// -----------------------------------------------------------------------------

public enum class IngestReason(
    public val db: String,
) {
    CREATED("created"),
    UPDATED("updated"),
    REEMBED("reembed"),
    ;

    public companion object {
        public fun fromDb(s: String): IngestReason = entries.first { it.db == s }
    }
}

public data class IngestItem(
    val docId: DocId,
    val reason: IngestReason,
    val queuedAt: Long,
)

// -----------------------------------------------------------------------------
// Citation record — POST_REVIEW_RESOLUTIONS.md §1
// -----------------------------------------------------------------------------

public enum class CitationSourceKind(
    public val db: String,
) {
    VECTOR("vector"),
    LEXICAL("lexical"),
    GRAPH("graph"),
    RERANK("rerank"),
    ;

    public companion object {
        public fun fromDb(s: String): CitationSourceKind = entries.first { it.db == s }
    }
}

/**
 * Byte-anchored locator into a document revision's `body_md`. The v1 payload
 * (see `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 citation-record-v1
 * schema) uses `byte_start`/`byte_end` for stability across re-chunking;
 * `chunkOrd` is a soft hint back to the chunk index at citation time.
 */
public data class Locator(
    val byteStart: Int,
    val byteEnd: Int,
    val chunkOrd: Int? = null,
)

public data class Citation(
    val marker: Int,
    val documentId: DocId,
    val revisionHash: RevisionHash,
    val locator: Locator,
    val excerpt: String,
    val sourceKind: CitationSourceKind,
    /** BLAKE3-256(excerpt) hex, populated at persistence for tamper detection at replay. */
    val excerptHash: String? = null,
)

/**
 * Persisted payload of `messages.retrieved_chunks`. `record_version = 1`
 * matches `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3.
 */
public data class CitationRecord(
    val recordVersion: Int = 1,
    val retrieved: List<Citation>,
    val cited: List<Int>,
)

// -----------------------------------------------------------------------------
// VaultRepository (plan §4.2)
// -----------------------------------------------------------------------------

/**
 * Aggregate contract over the vault DB. Concrete implementations:
 *   • `us.aherrera.skein.testing.InMemoryVaultRepository` (this milestone) —
 *     JVM fake for feature and RAG unit tests.
 *   • `VaultRepositoryImpl` (`E2.I4`) — SQLCipher-backed, single-writer +
 *     reader-pool, on `Dispatchers.IO`.
 *
 * All methods are model-agnostic — no embedder / inference / thermal
 * coupling. `MEASUREMENTS.md`-derived numerics (chunk size, embedder dim,
 * batch limits) belong in the RAG or embedder services, not here.
 */
public interface VaultRepository {
    // ---- documents ----
    public suspend fun createDocument(new: NewDocument): Document

    public suspend fun getDocument(id: DocId): Document?

    /** Rewrites title/body, bumps `updated_at`, recomputes `content_hash`. DB trigger enqueues ingest. */
    public suspend fun updateBody(
        id: DocId,
        title: String,
        bodyMd: String,
    ): Document

    public suspend fun updateFrontmatter(
        id: DocId,
        frontmatter: JsonObject,
    ): Document

    public suspend fun deleteDocument(id: DocId)

    public fun observeDocument(id: DocId): Flow<Document?>

    /** Newest first. [before] pages by `updated_at`. */
    public fun observeTimeline(
        filter: TimelineFilter,
        limit: Int = 50,
        before: Long? = null,
    ): Flow<List<Document>>

    /** Case-insensitive exact match. */
    public suspend fun findByTitle(title: String): Document?

    public suspend fun searchTitles(
        prefix: String,
        limit: Int = 20,
    ): List<Document>

    public suspend fun searchBodies(
        query: String,
        limit: Int = 20,
    ): List<DocumentHit>

    // ---- messages (chats) ----

    /**
     * Appends [message] to the chat and re-materializes the chat document's
     * `body_md` as a Markdown transcript so chats are indexed like notes.
     */
    public suspend fun appendMessage(
        chatDocId: DocId,
        message: NewMessage,
    ): Message

    public suspend fun listMessages(chatDocId: DocId): List<Message>

    public fun observeMessages(chatDocId: DocId): Flow<List<Message>>

    // ---- attachments (blob store, encrypted at rest) ----

    /**
     * Creates a `kind = ATTACHMENT` document and streams its bytes through
     * the injected blob store — three-layer key hierarchy per
     * `docs/design/ATTACHMENT_ENCRYPTION.md` §1.3.
     */
    public suspend fun createAttachment(
        title: String,
        mimeType: String,
        write: suspend (OutputStream) -> Unit,
    ): Document

    public suspend fun openAttachment(id: DocId): InputStream

    public suspend fun attachmentMimeType(id: DocId): String

    // ---- ingest queue ----

    public suspend fun dequeueIngest(limit: Int): List<IngestItem>

    /** Removes the queue row only if `queued_at` has not advanced since [queuedAt]. */
    public suspend fun completeIngest(
        docId: DocId,
        queuedAt: Long,
    )

    public suspend fun enqueueReembedAll()

    public suspend fun <T> transaction(block: suspend () -> T): T
}

// -----------------------------------------------------------------------------
// RAG storage: chunks, vectors, lexical, graph (plan §4.2)
// -----------------------------------------------------------------------------

public enum class EdgeKind(
    public val db: String,
    public val weight: Double,
) {
    WIKILINK("wikilink", 1.0),
    ENTITY("entity", 0.6),
    TAG("tag", 0.4),
    CITE("cite", 0.8),
    ;

    public companion object {
        public fun fromDb(s: String): EdgeKind = entries.first { it.db == s }
    }
}

/**
 * Node identifiers in `edges`:
 *   • documents → bare UUIDv7
 *   • entities  → `"entity:<entities.id>"`
 *   • tags      → `"tag:<lowercased-name>"`
 *
 * Backlinks are `IndexStore.edgesTo(docId)` — never stored separately.
 */
public data class Edge(
    val srcId: String,
    val dstId: String,
    val kind: EdgeKind,
    val weight: Double = kind.weight,
    val createdAt: Long,
)

public data class Chunk(
    val id: ChunkId,
    val docId: DocId,
    val ord: Int,
    val text: String,
    val tokenCount: Int,
    val embedderId: String?,
    val embedderVersion: Int?,
)

public data class NewChunk(
    val ord: Int,
    val text: String,
    val tokenCount: Int,
)

public data class ScoredChunk(
    val chunkId: ChunkId,
    val score: Double,
)

public data class Entity(
    val id: Long,
    val canonicalName: String,
    val entityType: String,
    val firstSeen: Long,
)

// -----------------------------------------------------------------------------
// IndexStore (plan §4.2)
// -----------------------------------------------------------------------------

public interface IndexStore {
    /**
     * Deletes the document's old chunks (FTS/vec rows follow via triggers)
     * and inserts the new ones. Returns new ids in `ord` order.
     */
    public suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
    ): List<ChunkId>

    /** Each `ByteArray` is exactly 256 int8 values. */
    public suspend fun putEmbeddings(embeddings: List<Pair<ChunkId, ByteArray>>)

    /** Cosine similarity in [-1, 1]. Higher is better. */
    public suspend fun knn(
        queryInt8: ByteArray,
        k: Int,
    ): List<ScoredChunk>

    /** Score is `-bm25()` (higher is better). */
    public suspend fun bm25(
        query: String,
        k: Int,
    ): List<ScoredChunk>

    public suspend fun getChunks(ids: Collection<ChunkId>): Map<ChunkId, Chunk>

    public suspend fun chunksForDocs(
        docIds: Collection<DocId>,
        limitPerDoc: Int,
    ): List<Chunk>

    public suspend fun replaceEdges(
        srcId: String,
        kinds: Set<EdgeKind>,
        edges: List<Edge>,
    )

    public suspend fun edgesFrom(srcId: String): List<Edge>

    public suspend fun edgesTo(
        dstId: String,
        kind: EdgeKind? = null,
    ): List<Edge>

    /** Undirected expansion from [seeds] up to [hops]; stops when [maxNodes] reached. */
    public suspend fun neighborhood(
        seeds: Set<String>,
        hops: Int,
        maxNodes: Int,
    ): List<Edge>

    public suspend fun upsertEntity(
        canonicalName: String,
        entityType: String,
        firstSeen: Long,
    ): Entity

    public suspend fun findEntitiesByName(names: Collection<String>): List<Entity>
}
