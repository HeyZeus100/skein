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
    /**
     * Content address of this document as it stands.
     *
     * For every non-`ATTACHMENT` kind this **is** the document's current
     * [RevisionHash] — `RevisionHashing.compute(bodyMd, frontmatter)`, i.e.
     * BLAKE3-256 over the canonicalized body plus normalized frontmatter
     * (migration 003 / `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3). §1.2
     * step 3's replay check ("compare the cited `revision_hash` against the
     * document's current `content_hash`") is therefore a literal comparison
     * of this field against [Citation.revisionHash]; see
     * [VaultRepository.revisionMatches].
     *
     * For `ATTACHMENT` it stays what it has always been — SHA-256 over the
     * blob's plaintext bytes. Attachments have no `body_md`, are never
     * chunked, and so are never the source of a citation; they get no
     * `document_revisions` rows.
     *
     * Null only for rows this repository did not write (a hand-seeded row,
     * or one written before migration 003 landed); every document created or
     * updated through [VaultRepository] carries a hash.
     */
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
    /**
     * Legacy (`record_version: 0`) contents of `messages.retrieved_chunks`:
     * raw `chunks.id` values. Non-empty only for messages written before
     * migration 003, because `chunks.id` is reassigned on re-ingestion and
     * so is not a stable citation — that is the defect
     * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.1 describes. When this is
     * non-empty and [citations] is null the replay surface renders
     * "source unknown" rather than resolving the ids (§1.4).
     */
    val retrievedChunks: List<ChunkId>,
    val createdAt: Long,
    /**
     * citation-record-v1 payload of `messages.retrieved_chunks`
     * (POST_REVIEW_RESOLUTIONS.md §1.3), or null when the row carries the
     * legacy shape (see [retrievedChunks]) or nothing at all. A message
     * never carries both.
     */
    val citations: CitationRecord? = null,
)

public data class NewMessage(
    val role: Role,
    val contentMd: String,
    val modelId: ModelId? = null,
    /** Legacy chunk-id list; ignored when [citations] is set. See [Message.retrievedChunks]. */
    val retrievedChunks: List<ChunkId> = emptyList(),
    /**
     * The citation record to persist for this turn. When set, the repository
     * writes citation-record-v1 JSON into `messages.retrieved_chunks` and
     * [Message.retrievedChunks] reads back empty (§1.3).
     */
    val citations: CitationRecord? = null,
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
    /**
     * `ingest_queue.attempts` as of the dequeue (migration 008,
     * skein-zx15) — the number of consecutive mandatory-step failures
     * already recorded for this entry. Defaults to 0 so every pre-008
     * caller (fakes, other constructors) keeps compiling unchanged.
     */
    val attempts: Int = 0,
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

    // ---- document revisions (POST_REVIEW_RESOLUTIONS.md §1, migration 003) ----

    /**
     * The `document_revisions` row addressing [id]'s content as it stands —
     * the row whose `revision_hash` equals the document's current
     * [Document.contentHash] (POST_REVIEW_RESOLUTIONS.md §1.2 step 3).
     *
     * Null for an unknown document, for an `ATTACHMENT` (no body, never
     * cited), and for a row written before migration 003 whose content has
     * not been rewritten since.
     *
     * Implementations capture a revision on [createDocument], [updateBody],
     * [updateFrontmatter] and on the transcript rewrite inside
     * [appendMessage]; capture is idempotent, so re-writing identical content
     * reuses the existing row rather than growing history (§1.4
     * `DocumentRevisionsRepositoryTest`).
     */
    public suspend fun currentRevision(id: DocId): DocumentRevision?

    /**
     * The archived revision a citation points at — the left-hand side of
     * §1.2 step 3's diff view. Null once the revision has been swept by the
     * `documentRevisions_gc` job (§1.2 step 4) or its document deleted.
     */
    public suspend fun getRevision(
        id: DocId,
        revisionHash: RevisionHash,
    ): DocumentRevision?

    /**
     * True when [citation] still addresses its document's current content —
     * i.e. the citation marker renders "live"; false means the replay surface
     * shows the "source changed" badge (POST_REVIEW_RESOLUTIONS.md §1.2
     * step 3). Two hex strings compared, no re-hashing: "no cross-turn
     * re-verification cost" (§1.2 rationale).
     *
     * A citation whose document no longer exists is not a match.
     */
    public suspend fun revisionMatches(citation: Citation): Boolean

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

    /**
     * Increments `ingest_queue.attempts` for [docId] (migration 008,
     * skein-zx15 — the persisted counterpart of the plan's "re-queued at
     * most 3 times" rule, E5.I10) and returns the new value. Replaces the
     * `IngestPipeline`-local `IngestAttempts` in-memory counter: because
     * the count now lives in the row itself, it survives a lock/unlock
     * cycle or a process restart rather than resetting with it, and it is
     * implicitly cleared whenever the row is deleted ([completeIngest]) or
     * replaced by a fresh `INSERT OR REPLACE` (a document edit re-queues
     * with `attempts` back at its column default, 0).
     *
     * Returns 0, and writes nothing, if [docId] is no longer queued (the
     * entry completed or the document was deleted concurrently) — the
     * caller has nothing left to bound retries on.
     */
    public suspend fun recordIngestFailure(docId: DocId): Int

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
    /**
     * `chunks.revision_hash` (migration 003, populated as of migration 008 —
     * skein-zx15) — the [RevisionHash] this row was stamped with at ingest
     * time. Additive and nullable: a row written before 008, or by a fake
     * that has no revision to report, still compiles and reads back `null`.
     * See `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3.
     */
    val revisionHash: RevisionHash? = null,
    /**
     * `[byteStart, byteEnd)` — `chunks.byte_start`/`chunks.byte_end`
     * (migration 008, skein-zx15/skein-s9hm): UTF-8 byte offsets into the
     * RAW `documents.body_md` this chunk was cut from at ingest time — NOT
     * the canonicalized `document_revisions.body_md_snapshot` §1.3's
     * locator anchors to (see `RevisionHashing.canonicalBody`; the two
     * disagree for a CRLF body). A citation producer (`RetrievedAssembler`,
     * skein-g32i) remaps these onto the canonical snapshot before building
     * a `Locator`. Additive and nullable, same rule as [revisionHash]: null
     * exactly when the row predates 008 or the caller had no offsets.
     */
    val byteStart: Int? = null,
    val byteEnd: Int? = null,
)

public data class NewChunk(
    val ord: Int,
    val text: String,
    val tokenCount: Int,
    /**
     * `[byteStart, byteEnd)` UTF-8 byte offsets into the document's
     * `body_md` this chunk was cut from — migration 008 (skein-zx15,
     * folding in skein-s9hm), `chunks.byte_start`/`chunks.byte_end`.
     * Additive and nullable: a caller that has no offsets to report (or
     * predates 008) still compiles and still gets a row, just with no
     * byte-anchored locator. Null exactly when the other is null. See
     * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 — these are the
     * "byte offsets into body_md" locators anchor to, in contrast to
     * `core/rag`'s `Chunk.start`/`Chunk.end`, which are UTF-16 char
     * offsets (the Kotlin `String` native unit) and disagree with the
     * byte offsets for any body containing non-ASCII text.
     */
    val byteStart: Int? = null,
    val byteEnd: Int? = null,
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

/**
 * One committed mutation of the index, published by
 * [IndexStore.observeChanges].
 *
 * This is the `core/model` twin of `core/vault`'s internal
 * `TableChange`/`ChangeBus` pair (which deliberately covers only the
 * tables `VaultRepositoryImpl` writes — see that file's header). Feature
 * and RAG modules may only depend on the contracts in this module, so the
 * index's own invalidation vocabulary has to live here rather than in
 * `core/vault`.
 *
 * ## Why a notification stream and not a query-shaped flow
 *
 * An `observeEdgesTo(docId): Flow<List<Edge>>` would have been the other
 * candidate (bd `skein-rkxi`). It was rejected because every real consumer
 * runs a *composite* query, not a single `edgesTo`: the backlinks drawer
 * reads `edgesTo(docId)` **and** `edgesTo("title:<lowercased title>")`
 * (the unresolved-wikilink sentinel) **and** `chunksForDocs` for the
 * excerpt. A per-query flow would force each implementor to re-implement
 * that composition — or force the consumer to subscribe to several flows
 * and re-join them. A narrow "something changed, re-query" tick keeps
 * every implementor's obligation to a single `tryEmit` and lets each
 * consumer own its own query shape, exactly as `ChangeBus` already does
 * for documents.
 *
 * ## Coverage
 *
 * Every write that can change what a *read* on this contract returns has a
 * case here. [IndexStore.upsertEntity] deliberately does not: entity rows
 * are write-once, name-addressed, and only ever read back by the same
 * ingest pass that wrote them (`findEntitiesByName`), so there is no
 * observer to notify. Adding a case for it later is a purely additive
 * change.
 */
public sealed interface IndexChange {
    /** [IndexStore.replaceChunks] rewrote every chunk (and its FTS/vec rows) of [docId]. */
    public data class ChunksReplaced(
        public val docId: DocId,
    ) : IndexChange

    /** [IndexStore.putEmbeddings] wrote vectors for [chunkIds] — `knn` results may have moved. */
    public data class EmbeddingsUpdated(
        public val chunkIds: List<ChunkId>,
    ) : IndexChange

    /**
     * [IndexStore.replaceEdges] rewrote the [kinds] edges whose `src_id` is
     * [srcId]. [kinds] is the caller's nominated filter, so a consumer that
     * only cares about (say) backlinks can drop every event whose [kinds]
     * does not contain [EdgeKind.WIKILINK] without re-querying.
     *
     * Note this names the *source* of the rewritten edges. A backlinks
     * consumer watching a destination cannot tell from the event alone
     * whether its own document was affected — it re-queries and diffs.
     */
    public data class EdgesReplaced(
        public val srcId: String,
        public val kinds: Set<EdgeKind>,
    ) : IndexChange
}

public interface IndexStore {
    /**
     * Hot stream of committed index mutations, for consumers that need to
     * invalidate a cached read (backlinks drawers, graph views, retrieval
     * caches) without polling.
     *
     * ## Emission guarantee
     *
     * An [IndexChange] is **emitted after the outermost commit that changed
     * the index, and never for rolled-back work**. Concretely:
     *   • The mutation is durable and visible to any subsequent read on
     *     this store *before* the event is published.
     *   • The event is published before the mutating call returns, so a
     *     collector that subscribed beforehand is guaranteed to be offered
     *     it.
     *   • A mutating call that throws — at any point, including from
     *     inside a nested call sharing the same transaction — publishes
     *     nothing. Changes queued by an inner call are discarded with the
     *     rollback.
     *   • A no-op call publishes nothing: `replaceEdges(src, kinds =
     *     emptySet(), …)` and `putEmbeddings(emptyList())` are silent.
     *     `replaceChunks(doc, chunks = emptyList(), …)` is *not* silent —
     *     it still deletes the document's existing chunks.
     *
     * ## Ordering and coalescing
     *
     * Events reach a collector in the order they were published — one
     * event per successful mutating call, never one per row. There is no
     * replay: a subscriber sees only changes made after it subscribes, so
     * a consumer that also needs "state as of subscription time" must seed
     * its own first query (the `onStart { emit(Unit) }` pattern).
     *
     * Implementations are permitted to **drop the oldest undelivered
     * events** for a collector that falls far enough behind to exhaust the
     * buffer; the newest event is never dropped in favour of an older one,
     * and a mutating call never blocks waiting for a slow collector.
     * Consumers must therefore treat each event as "re-query now" rather
     * than as an entry in a delta log that must be replayed in full.
     */
    public fun observeChanges(): Flow<IndexChange>

    /**
     * Deletes the document's old chunks (FTS/vec rows follow via triggers)
     * and inserts the new ones. Returns new ids in `ord` order.
     *
     * @param revisionHash stamped into every inserted row's
     *   `chunks.revision_hash` (migration 003's reverse pointer, populated
     *   as of migration 008 / skein-zx15) — the caller's
     *   `VaultRepository.currentRevision(docId)?.revisionHash` at ingest
     *   time. Additive and defaulted (coordinator decision, skein-zx15,
     *   2026-09-21): a caller with no revision to report (or predating
     *   008) still compiles and gets rows with a null `revision_hash`,
     *   exactly as migration 003 shipped.
     */
    public suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
        revisionHash: RevisionHash? = null,
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

// -----------------------------------------------------------------------------
// AuthorizationToken (skein-3el)
// -----------------------------------------------------------------------------

/**
 * Opaque handle proving that the caller passed through a successful
 * `VaultKeyProvider.unlock()` at a given generation of the unlocked session.
 *
 * The [epoch] is a monotonically non-decreasing counter bumped on every
 * `unlock()` (and again on every `rewrapAfterInvalidation()` — see
 * `docs/design/ATTACHMENT_ENCRYPTION.md` §3.5). Write-path callers
 * (`VaultRepositoryImpl`, `WriteOnceAttachmentStore`) capture the epoch at
 * the start of an operation and re-check it before committing; a mismatch
 * (from a lock, a rewrap, or a subsequent unlock) MUST cause the write to
 * abort rather than proceed with what is now stale authorization state.
 *
 * The token itself carries no key material and is safe to log its type
 * name but not its epoch value in security-relevant traces.
 */
public class AuthorizationToken(
    public val epoch: Long,
) {
    override fun equals(other: Any?): Boolean = other is AuthorizationToken && other.epoch == epoch

    override fun hashCode(): Int = epoch.hashCode()

    override fun toString(): String = "AuthorizationToken(epoch=…)"
}
