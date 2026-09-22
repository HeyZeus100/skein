// SQL-backed `VaultRepository` implementation (E2.I4, plan §4.2).
//
// This is the on-device implementation that backs the JVM
// `us.aherrera.skein.testing.InMemoryVaultRepository` fake — the
// "specification by fake" both this class and `VaultRepositoryContractTest`
// (shared, in `:testing`) are written against.
//
// Design summary (bd `skein-2my` DESCRIPTION):
//   • One writer `SQLiteConnection`, guarded by a single coroutine `Mutex`
//     ([writerMutex]), plus a small pool of reader connections handed in by
//     the caller (`readers`, typically 2-3) round-robined via
//     [nextReaderIndex] and each guarded by its own per-connection `Mutex`
//     (a `SQLiteConnection` is not safe for concurrent statement execution
//     from two coroutines at once, even for reads). When no readers are
//     supplied, every read also serializes through the writer connection —
//     correct, just not concurrent; production callers should hand in 2-3
//     reader connections opened against the same vault file for real
//     concurrency.
//   • Every operation dispatches onto `io` (`Dispatchers.IO` by default).
//   • [transaction] is coroutine-reentrant: nested repository calls made
//     from inside a `transaction { }` block detect the ambient
//     [TxContext] coroutine-context element and reuse its already-open
//     `BEGIN IMMEDIATE` and already-held [writerMutex] instead of trying
//     (and deadlocking) to acquire a second lock or open a second
//     transaction. This matches the plan's explicit ask: "keep transaction
//     re-entrant via a ThreadLocal-free coroutine context element
//     TxContext."
//   • `ChangeBus.emit` only actually happens once the relevant write's
//     outermost transaction commits — a [TxContext] queues [TableChange]s
//     in [TxContext.pending] and flushes them after `COMMIT` succeeds, so
//     an `observe*` collector that wakes up and re-queries always sees the
//     committed row, never a change notification for data that then rolls
//     back or hasn't landed yet on a nested call's connection.
//   • A read made while an ambient [TxContext] is active goes to the
//     *writer* connection (never a reader-pool connection) — reading a
//     document that was just inserted earlier in the same transaction (via
//     `transaction { createDocument(...); getDocument(...) }`) must see
//     that uncommitted row, which a separate reader connection would not.
//   • Every SQL string is centralised in `VaultSql` (E2.I4 review pattern,
//     matching `IndexStoreImpl`/`IndexSql`, `E2.I15`).
//   • `appendMessage` re-materializes the chat's `body_md` as
//     `**user:** …\n\n**assistant:** …` (spec §5 / plan `E2.I4`) so chats
//     index like notes; the `documents_au_ingest` trigger
//     (`001_initial.sql`) then enqueues the re-index automatically.
//   • `frontmatter.id` is always overwritten with the row's own id on
//     `createDocument`/`updateFrontmatter` — the caller's `NewDocument.id`
//     (or a frontmatter `id` key, as a fallback) picks *which* id the
//     document gets, but once chosen, the persisted frontmatter always
//     agrees with `documents.id`.
//   • `searchBodies` has no document-level FTS table to query — only
//     `chunks_fts` (chunk-level, populated by the ingest pipeline via
//     `IndexStore.replaceChunks`, `E2.I15`/`E5.I8`) exists in the v1
//     schema. `VaultSql.SEARCH_BODIES_BM25` joins through it and keeps
//     each document's single best-ranked chunk (see that constant's KDoc).
//   • Attachments delegate to the injected `AttachmentStore` (`E2.I5`,
//     skein-1nr, not yet landed — see `app.skein.core.vault.blob` for the
//     interface and the in-memory stand-in used until then).
//
// Not implemented here (deliberately, matching `IndexStoreImpl`'s own
// "not implemented yet" list):
//   • A dedicated `ConnectionPool` type — the plan's `ConnectionPool` does
//     not exist in-tree yet (see `IndexStoreImpl`'s header for the same
//     note); this class takes a writer connection plus a raw
//     `List<SQLiteConnection>` of readers instead.

package app.skein.core.vault.repository

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.skein.core.vault.blob.AttachmentStore
import app.skein.core.vault.export.stage.ExportStageRepository
import app.skein.core.vault.export.stage.ExportStageRow
import app.skein.core.vault.id.Uuid7
import app.skein.core.vault.index.FtsQuerySanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import us.aherrera.skein.core.model.Citation
import us.aherrera.skein.core.model.CitationRecordJson
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentHit
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.DocumentRevision
import us.aherrera.skein.core.model.IngestItem
import us.aherrera.skein.core.model.IngestReason
import us.aherrera.skein.core.model.Message
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.NewMessage
import us.aherrera.skein.core.model.RetrievedChunksPayload
import us.aherrera.skein.core.model.RevisionHash
import us.aherrera.skein.core.model.RevisionHashing
import us.aherrera.skein.core.model.RevisionReason
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

public class VaultRepositoryImpl(
    private val writer: SQLiteConnection,
    private val attachments: AttachmentStore,
    readers: List<SQLiteConnection> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VaultRepository,
    // skein-0m1z: `export_stages` (migration 005) is vault-internal
    // bookkeeping, so it is a `:core:vault` interface rather than an
    // addition to the `:core:model` `VaultRepository` contract (which the
    // `InMemoryVaultRepository` fake mirrors). See its KDoc.
    ExportStageRepository,
    AutoCloseable {
    private val writerMutex: Mutex = Mutex()
    private val readerSlots: List<ReaderSlot> = readers.map { ReaderSlot(it) }
    private val nextReaderIndex: AtomicInteger = AtomicInteger(0)
    private val changeBus: ChangeBus = ChangeBus()
    private val json: Json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Documents
    // ------------------------------------------------------------------

    override suspend fun createDocument(new: NewDocument): Document =
        writeTx {
            val now = clock()
            val chosenId = resolveDocumentId(new)
            val frontmatterWithId = mergeFrontmatterWithId(new.frontmatter, chosenId)
            // Migration 003 / POST_REVIEW_RESOLUTIONS.md §1.3: for every
            // citable (non-attachment) kind, `content_hash` IS the
            // RevisionHash. Attachments keep SHA-256-over-bytes, written by
            // `createAttachment` instead.
            val revisionHash =
                if (new.kind ==
                    DocumentKind.ATTACHMENT
                ) {
                    null
                } else {
                    RevisionHashing.compute(new.bodyMd, frontmatterWithId)
                }
            val contentHash = revisionHash ?: new.bodyMd?.let(::sha256Hex)
            writer.prepare(VaultSql.INSERT_DOCUMENT).use { stmt ->
                stmt.bindText(1, chosenId)
                stmt.bindText(2, new.kind.db)
                stmt.bindText(3, new.title)
                bindNullableText(stmt, 4, new.bodyMd)
                stmt.bindLong(5, now)
                stmt.bindLong(6, now)
                bindNullableText(stmt, 7, new.personaId)
                stmt.bindText(8, encodeFrontmatter(frontmatterWithId))
                bindNullableText(stmt, 9, contentHash)
                stmt.step()
            }
            if (revisionHash != null) {
                captureRevision(
                    chosenId,
                    new.kind,
                    new.bodyMd,
                    frontmatterWithId,
                    revisionHash,
                    now,
                    RevisionReason.INGEST,
                )
            }
            publish(TableChange.Documents(chosenId))
            Document(
                id = chosenId,
                kind = new.kind,
                title = new.title,
                bodyMd = new.bodyMd,
                createdAt = now,
                updatedAt = now,
                personaId = new.personaId,
                frontmatter = frontmatterWithId,
                contentHash = contentHash,
            )
        }

    override suspend fun getDocument(id: DocId): Document? =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_DOCUMENT_BY_ID).use { stmt ->
                stmt.bindText(1, id)
                if (stmt.step()) readDocument(stmt) else null
            }
        }

    override suspend fun updateBody(
        id: DocId,
        title: String,
        bodyMd: String,
    ): Document =
        writeTx {
            val existing = requireDocument(id)
            val now = clock()
            // §1.3: the new content address of the document. Note it covers
            // `body_md` + frontmatter only — retitling alone leaves the
            // revision, and therefore every citation into this document,
            // untouched. An attachment (which has no citable body) keeps the
            // pre-003 SHA-256 shape; see `Document.contentHash`.
            val citable = existing.kind != DocumentKind.ATTACHMENT
            val hash = if (citable) RevisionHashing.compute(bodyMd, existing.frontmatter) else sha256Hex(bodyMd)
            writer.prepare(VaultSql.UPDATE_DOCUMENT_BODY).use { stmt ->
                stmt.bindText(1, title)
                stmt.bindText(2, bodyMd)
                stmt.bindLong(3, now)
                stmt.bindText(4, hash)
                stmt.bindText(5, id)
                stmt.step()
            }
            // Nothing to capture when the content address did not move:
            // §1.4's "newRevision is idempotent when content is unchanged".
            if (citable && hash != existing.contentHash) {
                captureRevision(id, existing.kind, bodyMd, existing.frontmatter, hash, now, RevisionReason.INGEST)
            }
            // documents_au_ingest (001_initial.sql) enqueues ingest_queue
            // automatically when body_md/title actually change — no manual
            // enqueue here.
            publish(TableChange.Documents(id))
            existing.copy(title = title, bodyMd = bodyMd, updatedAt = now, contentHash = hash)
        }

    override suspend fun updateFrontmatter(
        id: DocId,
        frontmatter: JsonObject,
    ): Document =
        writeTx {
            val existing = requireDocument(id)
            val now = clock()
            val withId = mergeFrontmatterWithId(frontmatter, id)
            val citable = existing.kind != DocumentKind.ATTACHMENT
            // §1.3's hash covers the normalized frontmatter, so a
            // frontmatter rewrite re-addresses the document — unless the
            // rewrite is cosmetic (key reordering, or touching only `id`),
            // which canonicalization folds away and `captureRevision`
            // therefore treats as a no-op.
            val hash = if (citable) RevisionHashing.compute(existing.bodyMd, withId) else existing.contentHash
            writer.prepare(VaultSql.UPDATE_DOCUMENT_FRONTMATTER).use { stmt ->
                stmt.bindText(1, encodeFrontmatter(withId))
                stmt.bindLong(2, now)
                bindNullableText(stmt, 3, hash)
                stmt.bindText(4, id)
                stmt.step()
            }
            if (citable && hash != null && hash != existing.contentHash) {
                captureRevision(id, existing.kind, existing.bodyMd, withId, hash, now, RevisionReason.INGEST)
            }
            publish(TableChange.Documents(id))
            existing.copy(frontmatter = withId, updatedAt = now, contentHash = hash)
        }

    override suspend fun deleteDocument(id: DocId) {
        writeTx {
            writer.prepare(VaultSql.DELETE_DOCUMENT).use { stmt ->
                stmt.bindText(1, id)
                stmt.step()
            }
            // `chunks`, `messages`, `ingest_queue` all cascade off
            // documents.id (001_initial.sql FK `ON DELETE CASCADE`); the
            // blob store does not, so it is cleaned up explicitly. A
            // missing/never-written blob is a no-op for both stand-in and
            // real (E2.I5) stores.
            attachments.delete(id)
            publish(TableChange.Documents(id))
        }
    }

    override fun observeDocument(id: DocId): Flow<Document?> =
        changeTicks { it is TableChange.Documents && it.docId == id }
            .map { getDocument(id) }
            .distinctUntilChanged()

    override fun observeTimeline(
        filter: TimelineFilter,
        limit: Int,
        before: Long?,
    ): Flow<List<Document>> =
        changeTicks { it is TableChange.Documents }
            .map { queryTimeline(filter, limit, before) }
            .distinctUntilChanged()

    override suspend fun findByTitle(title: String): Document? =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_DOCUMENT_BY_TITLE_EXACT).use { stmt ->
                stmt.bindText(1, title)
                if (stmt.step()) readDocument(stmt) else null
            }
        }

    override suspend fun searchTitles(
        prefix: String,
        limit: Int,
    ): List<Document> =
        withReader { conn ->
            conn.prepare(VaultSql.SEARCH_TITLES_PREFIX).use { stmt ->
                stmt.bindText(1, escapeLikePattern(prefix) + "%")
                stmt.bindLong(2, limit.toLong())
                val out = ArrayList<Document>()
                while (stmt.step()) out += readDocument(stmt)
                out
            }
        }

    override suspend fun searchBodies(
        query: String,
        limit: Int,
    ): List<DocumentHit> {
        val fts = FtsQuerySanitizer.sanitize(query)
        if (fts.isEmpty() || limit <= 0) return emptyList()
        return withReader { conn ->
            conn.prepare(VaultSql.SEARCH_BODIES_BM25).use { stmt ->
                stmt.bindText(1, fts)
                stmt.bindLong(2, limit.toLong())
                val out = ArrayList<DocumentHit>()
                while (stmt.step()) {
                    val document = readDocument(stmt)
                    val bm25Rank = stmt.getDouble(DOCUMENT_COLUMN_COUNT)
                    val snippet = stmt.getText(DOCUMENT_COLUMN_COUNT + 1)
                    // bm25() is negative-good (FTS5 convention); negate so
                    // higher-is-better, matching IndexStoreImpl.bm25().
                    out += DocumentHit(document = document, snippet = snippet, rank = -bm25Rank)
                }
                out
            }
        }
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    override suspend fun appendMessage(
        chatDocId: DocId,
        message: NewMessage,
    ): Message =
        writeTx {
            val chat = requireDocument(chatDocId)
            require(chat.kind == DocumentKind.CHAT) {
                "not a chat document: $chatDocId (kind=${chat.kind})"
            }
            val now = clock()
            val id = Uuid7.generate()
            writer.prepare(VaultSql.INSERT_MESSAGE).use { stmt ->
                stmt.bindText(1, id)
                stmt.bindText(2, chatDocId)
                stmt.bindText(3, message.role.wire)
                stmt.bindText(4, message.contentMd)
                bindNullableText(stmt, 5, message.modelId)
                stmt.bindText(6, encodeRetrievedChunks(message))
                stmt.bindLong(7, now)
                stmt.step()
            }
            publish(TableChange.Messages(chatDocId))

            // Re-materialize body_md as a Markdown transcript so chats
            // index like notes (spec §5 / plan E2.I4). `listMessages` below
            // resolves to the writer connection (ambient TxContext is
            // active), so it sees the row just inserted above.
            val transcript = renderTranscript(listMessages(chatDocId))
            val hash = RevisionHashing.compute(transcript, chat.frontmatter)
            writer.prepare(VaultSql.UPDATE_CHAT_BODY).use { stmt ->
                stmt.bindText(1, transcript)
                stmt.bindLong(2, now)
                stmt.bindText(3, hash)
                stmt.bindText(4, chatDocId)
                stmt.step()
            }
            // A chat is retrievable (it is chunked and indexed like a note),
            // so its transcript needs a revision address too — otherwise a
            // citation into a chat would resolve against a `content_hash`
            // with no `document_revisions` row behind it (§1.2 step 3).
            if (hash != chat.contentHash) {
                captureRevision(chatDocId, chat.kind, transcript, chat.frontmatter, hash, now, RevisionReason.INGEST)
            }
            // documents_au_ingest fires on this body_md change, enqueuing
            // the chat for re-index automatically.
            publish(TableChange.Documents(chatDocId))

            Message(
                id = id,
                chatDocId = chatDocId,
                role = message.role,
                contentMd = message.contentMd,
                modelId = message.modelId,
                retrievedChunks = if (message.citations == null) message.retrievedChunks else emptyList(),
                createdAt = now,
                citations = message.citations,
            )
        }

    override suspend fun listMessages(chatDocId: DocId): List<Message> =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_MESSAGES_FOR_CHAT).use { stmt ->
                stmt.bindText(1, chatDocId)
                val out = ArrayList<Message>()
                while (stmt.step()) out += readMessage(stmt)
                out
            }
        }

    override fun observeMessages(chatDocId: DocId): Flow<List<Message>> =
        changeTicks { it is TableChange.Messages && it.chatDocId == chatDocId }
            .map { listMessages(chatDocId) }
            .distinctUntilChanged()

    // ------------------------------------------------------------------
    // Document revisions (migration 003, POST_REVIEW_RESOLUTIONS.md §1)
    // ------------------------------------------------------------------

    override suspend fun currentRevision(id: DocId): DocumentRevision? =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_CURRENT_REVISION).use { stmt ->
                stmt.bindText(1, id)
                if (stmt.step()) readRevision(stmt) else null
            }
        }

    override suspend fun getRevision(
        id: DocId,
        revisionHash: RevisionHash,
    ): DocumentRevision? =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_REVISION_BY_HASH).use { stmt ->
                stmt.bindText(1, id)
                stmt.bindText(2, revisionHash)
                if (stmt.step()) readRevision(stmt) else null
            }
        }

    override suspend fun revisionMatches(citation: Citation): Boolean =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_REVISION_MATCHES).use { stmt ->
                stmt.bindText(1, citation.documentId)
                stmt.bindText(2, citation.revisionHash)
                stmt.step()
            }
        }

    override suspend fun sweepUnreferencedRevisions(): Int =
        writeTx {
            val referenced = collectReferencedRevisions()
            val candidates = ArrayList<Pair<DocId, RevisionHash>>()
            writer.prepare(VaultSql.SELECT_SWEEPABLE_REVISIONS).use { stmt ->
                while (stmt.step()) candidates += stmt.getText(0) to stmt.getText(1)
            }
            var deleted = 0
            for ((docId, hash) in candidates) {
                if (docId to hash in referenced) continue
                writer.prepare(VaultSql.DELETE_DOCUMENT_REVISION).use { stmt ->
                    stmt.bindText(1, docId)
                    stmt.bindText(2, hash)
                    stmt.step()
                }
                deleted++
            }
            deleted
        }

    /**
     * Every `(documentId, revisionHash)` pair named by any message's
     * citation-record-v1 payload, decoded via [CitationRecordJson.decode]
     * (never string-matched — §1.3 is explicit that SQLite enforces no JSON
     * schema and this codec is the only enforcement point). Scanning
     * `retrieved` alone is sufficient: every `cited` marker names an entry
     * in `retrieved` by construction ([CitationRecordJson.encode] requires
     * it). A legacy or unreadable payload decodes to something other than
     * [RetrievedChunksPayload.V1] and contributes nothing.
     */
    private fun collectReferencedRevisions(): Set<Pair<DocId, RevisionHash>> {
        val referenced = HashSet<Pair<DocId, RevisionHash>>()
        writer.prepare(VaultSql.SELECT_ALL_RETRIEVED_CHUNKS).use { stmt ->
            while (stmt.step()) {
                if (stmt.isNull(0)) continue
                val payload = CitationRecordJson.decode(stmt.getText(0))
                if (payload is RetrievedChunksPayload.V1) {
                    for (citation in payload.record.retrieved) {
                        referenced += citation.documentId to citation.revisionHash
                    }
                }
            }
        }
        return referenced
    }

    /**
     * Records [revisionHash] as a `document_revisions` row for [docId], per
     * POST_REVIEW_RESOLUTIONS.md §1.3. Idempotent by construction: the hash
     * is the content address, so re-capturing unchanged content updates the
     * existing row's position in history rather than appending a duplicate
     * (see `VaultSql.UPSERT_DOCUMENT_REVISION`).
     *
     * Always called from inside the caller's `writeTx`, so the revision and
     * the `documents` row it addresses commit together or not at all.
     *
     * **Chat bound (skein-a2yr).** For [DocumentKind.CHAT] the stored
     * `body_md_snapshot` is the empty string rather than
     * `RevisionHashing.canonicalBody(bodyMd)`: `appendMessage` passes the
     * whole re-materialized transcript as [bodyMd] on every turn, so
     * archiving it in full every turn would make storage quadratic in turn
     * count (`003_document_revisions.sql`'s former "Known cost, tracked
     * separately" note; see `VaultRepository.currentRevision`'s KDoc for the
     * full rationale). [revisionHash] itself is unaffected — it is computed
     * by the caller from the real transcript before this method runs — so
     * citation replay for a chat turn is unaffected; only the archived-copy
     * bytes are skipped.
     */
    private fun captureRevision(
        docId: DocId,
        kind: DocumentKind,
        bodyMd: String?,
        frontmatter: JsonObject,
        revisionHash: RevisionHash,
        capturedAt: Long,
        reason: RevisionReason,
    ) {
        val nextOrd =
            writer.prepare(VaultSql.SELECT_MAX_REVISION_ORD).use { stmt ->
                stmt.bindText(1, docId)
                if (stmt.step()) stmt.getLong(0) + 1 else 0L
            }
        val bodySnapshot = if (kind == DocumentKind.CHAT) "" else RevisionHashing.canonicalBody(bodyMd)
        writer.prepare(VaultSql.UPSERT_DOCUMENT_REVISION).use { stmt ->
            stmt.bindText(1, docId)
            stmt.bindText(2, revisionHash)
            stmt.bindLong(3, nextOrd)
            // The snapshot stores the CANONICAL bytes that were hashed, so
            // `RevisionHashing.compute(bodyMdSnapshot, frontmatterSnapshot)`
            // reproduces `revision_hash` from the row alone — EXCEPT for a
            // chat document, where the empty string above intentionally
            // breaks that self-verifying property in exchange for bounded
            // storage (see this method's KDoc).
            stmt.bindText(4, bodySnapshot)
            stmt.bindText(5, RevisionHashing.canonicalFrontmatter(frontmatter))
            stmt.bindLong(6, capturedAt)
            stmt.bindText(7, reason.db)
            stmt.step()
        }
    }

    // ------------------------------------------------------------------
    // Attachments (E2.I5 / skein-1nr owns the real blob store)
    // ------------------------------------------------------------------

    override suspend fun createAttachment(
        title: String,
        mimeType: String,
        write: suspend (OutputStream) -> Unit,
    ): Document {
        // Bytes are materialized (and hashed) outside the writer
        // transaction — blob I/O may be slow and must not hold the DB
        // writer lock; the DB insert below is the only part guarded by it.
        val id = Uuid7.generate()
        val size = attachments.write(id, write)
        val digest = hashAttachment(id)
        return writeTx {
            val now = clock()
            val frontmatter =
                buildJsonObject {
                    put(FRONTMATTER_ID_KEY, JsonPrimitive(id))
                    put("mime", JsonPrimitive(mimeType))
                    put("size", JsonPrimitive(size))
                }
            writer.prepare(VaultSql.INSERT_ATTACHMENT_DOCUMENT).use { stmt ->
                stmt.bindText(1, id)
                stmt.bindText(2, title)
                stmt.bindLong(3, now)
                stmt.bindLong(4, now)
                stmt.bindText(5, encodeFrontmatter(frontmatter))
                bindNullableText(stmt, 6, digest)
                stmt.bindText(7, mimeType)
                stmt.bindLong(8, size)
                stmt.step()
            }
            publish(TableChange.Documents(id))
            Document(
                id = id,
                kind = DocumentKind.ATTACHMENT,
                title = title,
                bodyMd = null,
                createdAt = now,
                updatedAt = now,
                personaId = null,
                frontmatter = frontmatter,
                contentHash = digest,
            )
        }
    }

    override suspend fun openAttachment(id: DocId): InputStream = attachments.open(id)

    override suspend fun attachmentMimeType(id: DocId): String =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_ATTACHMENT_MIME_TYPE).use { stmt ->
                stmt.bindText(1, id)
                if (!stmt.step() || stmt.isNull(0)) {
                    throw NoSuchElementException("no attachment mime type for id=$id")
                }
                stmt.getText(0)
            }
        }

    // ------------------------------------------------------------------
    // Ingest queue
    // ------------------------------------------------------------------

    override suspend fun dequeueIngest(limit: Int): List<IngestItem> =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_INGEST_QUEUE).use { stmt ->
                stmt.bindLong(1, limit.toLong())
                val out = ArrayList<IngestItem>()
                while (stmt.step()) {
                    out +=
                        IngestItem(
                            docId = stmt.getText(0),
                            reason = IngestReason.fromDb(stmt.getText(1)),
                            queuedAt = stmt.getLong(2),
                            attempts = stmt.getLong(3).toInt(),
                        )
                }
                out
            }
        }

    override suspend fun completeIngest(
        docId: DocId,
        queuedAt: Long,
    ) {
        writeTx {
            writer.prepare(VaultSql.DELETE_INGEST_ITEM_IF_UNCHANGED).use { stmt ->
                stmt.bindText(1, docId)
                stmt.bindLong(2, queuedAt)
                stmt.step()
            }
            publish(TableChange.IngestQueue)
        }
    }

    override suspend fun recordIngestFailure(docId: DocId): Int =
        writeTx {
            val next =
                writer.prepare(VaultSql.INCREMENT_INGEST_ATTEMPTS).use { stmt ->
                    stmt.bindText(1, docId)
                    if (stmt.step()) stmt.getLong(0).toInt() else 0
                }
            if (next > 0) publish(TableChange.IngestQueue)
            next
        }

    override suspend fun enqueueReembedAll() {
        writeTx {
            writer.prepare(VaultSql.ENQUEUE_REEMBED_ALL).use { stmt ->
                stmt.bindLong(1, clock())
                stmt.step()
            }
            publish(TableChange.IngestQueue)
        }
    }

    // ------------------------------------------------------------------
    // Export stages (migration 005, skein-0m1z)
    // ------------------------------------------------------------------
    //
    // Implemented here rather than in a class of its own so a stage row
    // shares this repository's single writer connection, its [writerMutex]
    // and its reentrant transaction plumbing: a second writing connection
    // to the same SQLCipher file would serialize on SQLite's own lock with
    // no coordination with the writes already in flight here.

    override suspend fun insertStage(row: ExportStageRow) {
        writeTx {
            writer.prepare(VaultSql.INSERT_EXPORT_STAGE).use { stmt ->
                stmt.bindText(1, row.stageId)
                stmt.bindText(2, row.path)
                stmt.bindText(3, row.origin)
                if (row.documentId == null) stmt.bindNull(4) else stmt.bindText(4, row.documentId)
                if (row.revisionHash == null) stmt.bindNull(5) else stmt.bindText(5, row.revisionHash)
                stmt.bindLong(6, row.createdAt)
                stmt.bindLong(7, row.expiresAt)
                stmt.bindLong(8, if (row.swept) 1L else 0L)
                stmt.step()
            }
        }
    }

    override suspend fun getStage(stageId: String): ExportStageRow? =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_EXPORT_STAGE).use { stmt ->
                stmt.bindText(1, stageId)
                if (stmt.step()) readExportStage(stmt) else null
            }
        }

    override suspend fun listUnsweptStages(): List<ExportStageRow> =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_UNSWEPT_EXPORT_STAGES).use { stmt ->
                buildList { while (stmt.step()) add(readExportStage(stmt)) }
            }
        }

    override suspend fun markStageSwept(stageId: String): Boolean =
        writeTx {
            writer.prepare(VaultSql.MARK_EXPORT_STAGE_SWEPT).use { stmt ->
                stmt.bindText(1, stageId)
                stmt.step()
            }
            changedRows() > 0
        }

    override suspend fun markAllStagesSwept(): Int =
        writeTx {
            writer.prepare(VaultSql.MARK_ALL_EXPORT_STAGES_SWEPT).use { it.step() }
            changedRows()
        }

    /** Rows touched by the statement just run on [writer]; must be read before any other statement. */
    private fun changedRows(): Int =
        writer.prepare(VaultSql.SELECT_CHANGES).use { stmt ->
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }

    private fun readExportStage(stmt: SQLiteStatement): ExportStageRow =
        ExportStageRow(
            stageId = stmt.getText(0),
            path = stmt.getText(1),
            origin = stmt.getText(2),
            documentId = if (stmt.isNull(3)) null else stmt.getText(3),
            revisionHash = if (stmt.isNull(4)) null else stmt.getText(4),
            createdAt = stmt.getLong(5),
            expiresAt = stmt.getLong(6),
            swept = stmt.getLong(7) != 0L,
        )

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    override suspend fun <T> transaction(block: suspend () -> T): T = writeTx(block)

    override fun close() {
        writer.close()
        for (slot in readerSlots) slot.connection.close()
    }

    // ------------------------------------------------------------------
    // Internals — connection routing
    // ------------------------------------------------------------------

    /**
     * Runs [block] against the writer connection. If an ambient
     * [TxContext] is already active (this call is nested inside a
     * `transaction { }` / another writing method), [block] runs directly —
     * the outer call already holds [writerMutex] and already opened
     * `BEGIN IMMEDIATE`. Otherwise this call is itself the outermost write:
     * it acquires [writerMutex], opens its own `BEGIN IMMEDIATE`, and on
     * success commits and flushes every [TableChange] queued via [publish]
     * during [block] — on failure it rolls back and nothing is ever
     * published.
     */
    private suspend fun <T> writeTx(block: suspend () -> T): T {
        val ambient = coroutineContext[TxContext.Key]
        if (ambient != null) return block()
        val tx = TxContext()
        return withContext(io + tx) {
            writerMutex.withLock {
                writer.prepare(VaultSql.BEGIN_IMMEDIATE).use { it.step() }
                try {
                    val result = block()
                    writer.prepare(VaultSql.COMMIT).use { it.step() }
                    for (change in tx.pending) changeBus.emit(change)
                    result
                } catch (t: Throwable) {
                    runCatching { writer.prepare(VaultSql.ROLLBACK).use { it.step() } }
                    throw t
                }
            }
        }
    }

    /**
     * Runs [block] against a connection suitable for reading: the writer
     * connection (without acquiring [writerMutex] again) when an ambient
     * [TxContext] is active — so a read-after-write within the same
     * `transaction { }` sees its own uncommitted writes — otherwise a
     * round-robined reader-pool connection, or the writer connection under
     * [writerMutex] when no reader connections were supplied.
     */
    private suspend fun <T> withReader(block: (SQLiteConnection) -> T): T {
        if (coroutineContext[TxContext.Key] != null) return block(writer)
        if (readerSlots.isEmpty()) {
            return withContext(io) { writerMutex.withLock { block(writer) } }
        }
        return withContext(io) {
            val index = Math.floorMod(nextReaderIndex.getAndIncrement(), readerSlots.size)
            val slot = readerSlots[index]
            slot.mutex.withLock { block(slot.connection) }
        }
    }

    /** Queues [change] on the active [TxContext] (flushed after its commit), or emits it immediately when there is none. */
    private suspend fun publish(change: TableChange) {
        val tx = coroutineContext[TxContext.Key]
        if (tx != null) tx.pending += change else changeBus.emit(change)
    }

    private fun changeTicks(matches: suspend (TableChange) -> Boolean): Flow<Unit> =
        changeBus.events
            .filter(matches)
            .map { }
            .onStart { emit(Unit) }

    private suspend fun requireDocument(id: DocId): Document =
        getDocument(id) ?: throw NoSuchElementException("no document with id=$id")

    private suspend fun queryTimeline(
        filter: TimelineFilter,
        limit: Int,
        before: Long?,
    ): List<Document> =
        withReader { conn ->
            conn.prepare(VaultSql.SELECT_TIMELINE).use { stmt ->
                bindNullableText(stmt, 1, filter.personaId)
                bindNullableText(stmt, 2, filter.personaId)
                bindNullableLong(stmt, 3, before)
                bindNullableLong(stmt, 4, before)
                bindNullableText(stmt, 5, filter.tag)
                bindNullableText(stmt, 6, filter.tag)
                val padded = padKinds(filter.kinds)
                stmt.bindText(7, padded[0])
                stmt.bindText(8, padded[1])
                stmt.bindText(9, padded[2])
                stmt.bindText(10, padded[3])
                stmt.bindLong(11, limit.toLong())
                val out = ArrayList<Document>()
                while (stmt.step()) out += readDocument(stmt)
                out
            }
        }

    private suspend fun hashAttachment(id: DocId): String {
        val bytes = attachments.open(id).use { it.readBytes() }
        return sha256Hex(bytes)
    }

    // ------------------------------------------------------------------
    // Internals — row mapping
    // ------------------------------------------------------------------

    private fun readDocument(stmt: SQLiteStatement): Document =
        Document(
            id = stmt.getText(0),
            kind = DocumentKind.fromDb(stmt.getText(1)),
            title = stmt.getText(2),
            bodyMd = if (stmt.isNull(3)) null else stmt.getText(3),
            createdAt = stmt.getLong(4),
            updatedAt = stmt.getLong(5),
            personaId = if (stmt.isNull(6)) null else stmt.getText(6),
            frontmatter = decodeFrontmatter(if (stmt.isNull(7)) null else stmt.getText(7)),
            contentHash = if (stmt.isNull(8)) null else stmt.getText(8),
        )

    private fun readMessage(stmt: SQLiteStatement): Message {
        val payload = CitationRecordJson.decode(if (stmt.isNull(5)) null else stmt.getText(5))
        return Message(
            id = stmt.getText(0),
            chatDocId = stmt.getText(1),
            role = roleFromWire(stmt.getText(2)),
            contentMd = stmt.getText(3),
            modelId = if (stmt.isNull(4)) null else stmt.getText(4),
            retrievedChunks = (payload as? RetrievedChunksPayload.Legacy)?.chunkIds ?: emptyList(),
            createdAt = stmt.getLong(6),
            citations = (payload as? RetrievedChunksPayload.V1)?.record,
        )
    }

    private fun readRevision(stmt: SQLiteStatement): DocumentRevision =
        DocumentRevision(
            documentId = stmt.getText(0),
            revisionHash = stmt.getText(1),
            revisionOrd = stmt.getLong(2).toInt(),
            bodyMdSnapshot = stmt.getText(3),
            frontmatterSnapshot = decodeFrontmatter(if (stmt.isNull(4)) null else stmt.getText(4)),
            capturedAt = stmt.getLong(5),
            reason = RevisionReason.fromDb(stmt.getText(6)),
        )

    private fun roleFromWire(wire: String): Role = Role.entries.first { it.wire == wire }

    private fun renderTranscript(messages: List<Message>): String =
        messages.joinToString(separator = "\n\n") { m -> "**${m.role.wire}:** ${m.contentMd}" }

    // ------------------------------------------------------------------
    // Internals — id / frontmatter / JSON helpers
    // ------------------------------------------------------------------

    /** Caller's `NewDocument.id` wins; a frontmatter `id` key is the fallback; a fresh UUIDv7 otherwise. */
    private fun resolveDocumentId(new: NewDocument): DocId =
        new.id
            ?: (new.frontmatter[FRONTMATTER_ID_KEY] as? JsonPrimitive)?.content
            ?: Uuid7.generate()

    /** Overwrites (or inserts) the frontmatter `id` key with [id] — the persisted frontmatter always agrees with the row id. */
    private fun mergeFrontmatterWithId(
        frontmatter: JsonObject,
        id: DocId,
    ): JsonObject =
        buildJsonObject {
            frontmatter.forEach { (k, v) -> if (k != FRONTMATTER_ID_KEY) put(k, v) }
            put(FRONTMATTER_ID_KEY, JsonPrimitive(id))
        }

    private fun decodeFrontmatter(text: String?): JsonObject =
        if (text.isNullOrEmpty()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.decodeFromString(JsonObject.serializer(), text) }
                .getOrDefault(JsonObject(emptyMap()))
        }

    private fun encodeFrontmatter(frontmatter: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), frontmatter)

    /**
     * The `messages.retrieved_chunks` payload for [message]: citation-record-v1
     * when the caller supplied a `CitationRecord`, otherwise the legacy
     * (`record_version: 0`) bare chunk-id array so callers not yet ported to
     * POST_REVIEW_RESOLUTIONS.md §1 keep working unchanged (§1.5).
     */
    private fun encodeRetrievedChunks(message: NewMessage): String =
        message.citations
            ?.let(CitationRecordJson::encode)
            ?: CitationRecordJson.encodeLegacyChunkIds(message.retrievedChunks)

    private fun padKinds(kinds: Set<DocumentKind>): List<String> {
        val dbs = kinds.map { it.db }.ifEmpty { listOf(UNMATCHABLE_KIND) }
        return List(DocumentKind.entries.size) { i -> dbs[i % dbs.size] }
    }

    private fun escapeLikePattern(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun bindNullableText(
        stmt: SQLiteStatement,
        index: Int,
        value: String?,
    ) {
        if (value == null) stmt.bindNull(index) else stmt.bindText(index, value)
    }

    private fun bindNullableLong(
        stmt: SQLiteStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) stmt.bindNull(index) else stmt.bindLong(index, value)
    }

    private fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    // ------------------------------------------------------------------
    // Internals — connection pool / reentrant transaction plumbing
    // ------------------------------------------------------------------

    private class ReaderSlot(
        val connection: SQLiteConnection,
    ) {
        val mutex: Mutex = Mutex()
    }

    /** Coroutine-context marker for an in-flight [writeTx]/[transaction]: makes nested writes and reads reentrant. */
    private class TxContext : AbstractCoroutineContextElement(Key) {
        val pending: MutableList<TableChange> = mutableListOf()

        companion object Key : CoroutineContext.Key<TxContext>
    }

    private companion object {
        const val FRONTMATTER_ID_KEY: String = "id"
        const val UNMATCHABLE_KIND: String = ""
        const val DOCUMENT_COLUMN_COUNT: Int = 9
    }
}
