// The `E0.I11` in-memory `VaultRepository` fake: a JVM-only stand-in for the
// aggregate contract locked in `core/model/.../Vault.kt`. It powers
// `VaultRepositoryContractTest` on the JVM and every feature/RAG unit test
// that needs a working vault without SQLCipher / native libraries.
//
// Not modelled here (deliberately):
//   • transactional isolation between concurrent writers — the fake uses one
//     `Mutex` around every write so behavior is serialized rather than
//     transactional in the DB sense; the SQL-backed `VaultRepositoryImpl`
//     (`E2.I4`) provides real transactions.
//   • the DB-side ingest trigger — the fake enqueues on `createDocument`
//     and `updateBody` explicitly, matching the observable effect of the
//     `documents_ai_ingest` / `documents_au_ingest` triggers in
//     `001_initial.sql`.
//   • measurement-derived numerics — no chunk size, no embedder dim, no
//     thermal thresholds. `MEASUREMENTS.md` (`skein-5hr`) affects the real
//     implementation, not this fake.
//
// UUIDv7 minting: `java.util.UUID.randomUUID()` (a UUIDv4) is used because
// (a) `:testing` is pre-`E2.I3` (the real UUIDv7 codec) and (b) uniqueness
// is all any contract test cares about. Real code paths that need
// time-ordered UUIDs go through `Uuid7` when it lands.

package app.skein.testing

import app.skein.core.model.Citation
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentHit
import app.skein.core.model.DocumentKind
import app.skein.core.model.DocumentRevision
import app.skein.core.model.IngestItem
import app.skein.core.model.IngestReason
import app.skein.core.model.Message
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.RevisionHash
import app.skein.core.model.RevisionHashing
import app.skein.core.model.RevisionReason
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Coroutine-safe in-memory `VaultRepository`. See file header for what is
 * intentionally simplified vs. `VaultRepositoryImpl` (`E2.I4`).
 *
 * @param clock monotonic-enough `now()` for `created_at` / `updated_at`.
 *   Tests inject a `FakeClock` to make timestamps deterministic.
 * @param attachments backing blob store — `InMemoryAttachmentStore()` is the
 *   default; a real vault would inject a SQLCipher-backed one.
 */
public class InMemoryVaultRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val attachments: AttachmentStore = InMemoryAttachmentStore(),
) : VaultRepository {
    private val writeLock: Mutex = Mutex()

    // Order preservation via LinkedHashMap; iteration order is insertion
    // order, which we don't rely on (queries sort by updated_at).
    private val documents: MutableMap<DocId, Document> = linkedMapOf()
    private val messagesByChat: MutableMap<DocId, MutableList<Message>> = linkedMapOf()
    private val ingestQueue: MutableMap<DocId, IngestItem> = linkedMapOf()
    private val mimeTypes: MutableMap<DocId, String> = linkedMapOf()

    // `document_revisions` (migration 003 / POST_REVIEW_RESOLUTIONS.md §1.3),
    // keyed by (documentId, revisionHash) exactly like the table's primary
    // key so a re-captured content address updates its row rather than
    // appending a duplicate.
    private val revisions: MutableMap<Pair<DocId, RevisionHash>, DocumentRevision> = linkedMapOf()

    // Emits after every committed write. Observers re-query on tick.
    private val changeBus: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)
    private val ready: MutableStateFlow<Unit> = MutableStateFlow(Unit)

    // ------------------------------------------------------------------
    // Documents
    // ------------------------------------------------------------------

    override suspend fun createDocument(new: NewDocument): Document =
        writeLock.withLock {
            val now = clock()
            val chosenId =
                new.id
                    ?: (new.frontmatter[FRONTMATTER_ID_KEY] as? JsonPrimitive)?.content
                    ?: UUID.randomUUID().toString()
            require(chosenId !in documents) { "duplicate document id: $chosenId" }
            // Preserve the frontmatter but ensure `id` is present and matches
            // the row's id (see acceptance criterion: "create→get round-trip
            // preserves frontmatter id").
            val frontmatterWithId: JsonObject =
                buildJsonObject {
                    new.frontmatter.forEach { (k, v) -> if (k != FRONTMATTER_ID_KEY) put(k, v) }
                    put(FRONTMATTER_ID_KEY, JsonPrimitive(chosenId))
                }
            // §1.3: for every citable (non-attachment) kind `content_hash`
            // IS the document's RevisionHash. Attachments keep the
            // SHA-256-over-bytes shape (`createAttachment`).
            val revisionHash =
                if (new.kind == DocumentKind.ATTACHMENT) {
                    null
                } else {
                    RevisionHashing.compute(new.bodyMd, frontmatterWithId)
                }
            val doc =
                Document(
                    id = chosenId,
                    kind = new.kind,
                    title = new.title,
                    bodyMd = new.bodyMd,
                    createdAt = now,
                    updatedAt = now,
                    personaId = new.personaId,
                    frontmatter = frontmatterWithId,
                    contentHash = revisionHash ?: new.bodyMd?.let(::sha256Hex),
                )
            documents[chosenId] = doc
            if (revisionHash != null) {
                captureRevision(chosenId, new.kind, new.bodyMd, frontmatterWithId, revisionHash, now)
            }
            // DB trigger simulation: enqueue ingest for non-attachment docs.
            if (new.kind != DocumentKind.ATTACHMENT) {
                ingestQueue[chosenId] =
                    IngestItem(
                        docId = chosenId,
                        reason = IngestReason.CREATED,
                        queuedAt = now,
                    )
            }
            emitChange()
            doc
        }

    override suspend fun getDocument(id: DocId): Document? = documents[id]

    override suspend fun updateBody(
        id: DocId,
        title: String,
        bodyMd: String,
    ): Document =
        writeLock.withLock {
            val existing = requireNotNull(documents[id]) { "no document with id=$id" }
            val now = clock()
            val citable = existing.kind != DocumentKind.ATTACHMENT
            val hash =
                if (citable) RevisionHashing.compute(bodyMd, existing.frontmatter) else sha256Hex(bodyMd)
            val updated =
                existing.copy(
                    title = title,
                    bodyMd = bodyMd,
                    updatedAt = now,
                    contentHash = hash,
                )
            documents[id] = updated
            // Nothing to capture when the content address did not move:
            // §1.4's "newRevision is idempotent when content is unchanged".
            if (citable && hash != existing.contentHash) {
                captureRevision(id, existing.kind, bodyMd, existing.frontmatter, hash, now)
            }
            if (existing.kind != DocumentKind.ATTACHMENT) {
                ingestQueue[id] =
                    IngestItem(
                        docId = id,
                        reason = IngestReason.UPDATED,
                        queuedAt = now,
                    )
            }
            emitChange()
            updated
        }

    override suspend fun updateFrontmatter(
        id: DocId,
        frontmatter: JsonObject,
    ): Document =
        writeLock.withLock {
            val existing = requireNotNull(documents[id]) { "no document with id=$id" }
            // Preserve the id key across a frontmatter rewrite (§4.2 note).
            val withId =
                buildJsonObject {
                    frontmatter.forEach { (k, v) -> if (k != FRONTMATTER_ID_KEY) put(k, v) }
                    put(FRONTMATTER_ID_KEY, JsonPrimitive(id))
                }
            val now = clock()
            val citable = existing.kind != DocumentKind.ATTACHMENT
            // §1.3's hash covers the normalized frontmatter too, so a
            // frontmatter rewrite re-addresses the document — unless the
            // rewrite is cosmetic (key reordering, or touching only `id`),
            // which canonicalization folds away.
            val hash =
                if (citable) RevisionHashing.compute(existing.bodyMd, withId) else existing.contentHash
            val updated =
                existing.copy(
                    frontmatter = withId,
                    updatedAt = now,
                    contentHash = hash,
                )
            documents[id] = updated
            if (citable && hash != null && hash != existing.contentHash) {
                captureRevision(id, existing.kind, existing.bodyMd, withId, hash, now)
            }
            emitChange()
            updated
        }

    override suspend fun deleteDocument(id: DocId) {
        writeLock.withLock {
            documents.remove(id)
            // `document_revisions.document_id` is ON DELETE CASCADE (003).
            revisions.keys.removeAll { it.first == id }
            messagesByChat.remove(id)
            ingestQueue.remove(id)
            mimeTypes.remove(id)
            attachments.delete(id)
            emitChange()
        }
    }

    override fun observeDocument(id: DocId): Flow<Document?> =
        changeTicks().map { documents[id] }.distinctUntilChanged()

    override fun observeTimeline(
        filter: TimelineFilter,
        limit: Int,
        before: Long?,
    ): Flow<List<Document>> = changeTicks().map { queryTimeline(filter, limit, before) }.distinctUntilChanged()

    private fun queryTimeline(
        filter: TimelineFilter,
        limit: Int,
        before: Long?,
    ): List<Document> =
        documents.values
            .asSequence()
            .filter { it.kind in filter.kinds }
            .filter { filter.personaId == null || it.personaId == filter.personaId }
            .filter { before == null || it.updatedAt < before }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .toList()

    override suspend fun findByTitle(title: String): Document? =
        documents.values.firstOrNull { it.title.equals(title, ignoreCase = true) }

    override suspend fun searchTitles(
        prefix: String,
        limit: Int,
    ): List<Document> =
        documents.values
            .filter { it.title.startsWith(prefix, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override suspend fun searchBodies(
        query: String,
        limit: Int,
    ): List<DocumentHit> =
        documents.values
            .filter { it.bodyMd?.contains(query, ignoreCase = true) == true }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { DocumentHit(document = it, snippet = snippetFor(it.bodyMd ?: "", query), rank = 1.0) }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    override suspend fun appendMessage(
        chatDocId: DocId,
        message: NewMessage,
    ): Message =
        writeLock.withLock {
            val chat = requireNotNull(documents[chatDocId]) { "no chat document with id=$chatDocId" }
            require(chat.kind == DocumentKind.CHAT) { "not a chat document: $chatDocId (kind=${chat.kind})" }
            val now = clock()
            val row =
                Message(
                    id = UUID.randomUUID().toString(),
                    chatDocId = chatDocId,
                    role = message.role,
                    contentMd = message.contentMd,
                    modelId = message.modelId,
                    retrievedChunks = if (message.citations == null) message.retrievedChunks else emptyList(),
                    createdAt = now,
                    citations = message.citations,
                )
            messagesByChat.getOrPut(chatDocId) { mutableListOf() } += row
            // Re-materialize the chat's body_md as a Markdown transcript so
            // chats index like notes (spec §5 / plan `E2.I4`). Format:
            //     **<role>:** <content>
            //     (blank line)
            val transcript =
                messagesByChat.getValue(chatDocId).joinToString(separator = "\n\n") { m ->
                    val rolePrefix = m.role.name.lowercase()
                    "**$rolePrefix:** ${m.contentMd}"
                }
            // A chat is chunked and indexed like a note, so its transcript
            // needs a revision address too (§1.2 step 3) — see the matching
            // comment in `VaultRepositoryImpl.appendMessage`.
            val hash = RevisionHashing.compute(transcript, chat.frontmatter)
            val rewritten =
                chat.copy(
                    bodyMd = transcript,
                    updatedAt = now,
                    contentHash = hash,
                )
            documents[chatDocId] = rewritten
            if (hash != chat.contentHash) captureRevision(chatDocId, chat.kind, transcript, chat.frontmatter, hash, now)
            ingestQueue[chatDocId] =
                IngestItem(
                    docId = chatDocId,
                    reason = IngestReason.UPDATED,
                    queuedAt = now,
                )
            emitChange()
            row
        }

    override suspend fun listMessages(chatDocId: DocId): List<Message> =
        messagesByChat[chatDocId]?.toList() ?: emptyList()

    override fun observeMessages(chatDocId: DocId): Flow<List<Message>> =
        changeTicks().map { listMessages(chatDocId) }.distinctUntilChanged()

    // ------------------------------------------------------------------
    // Document revisions (migration 003, POST_REVIEW_RESOLUTIONS.md §1)
    // ------------------------------------------------------------------

    override suspend fun currentRevision(id: DocId): DocumentRevision? {
        val hash = documents[id]?.contentHash ?: return null
        return revisions[id to hash]
    }

    override suspend fun getRevision(
        id: DocId,
        revisionHash: RevisionHash,
    ): DocumentRevision? = revisions[id to revisionHash]

    override suspend fun revisionMatches(citation: Citation): Boolean =
        documents[citation.documentId]?.contentHash == citation.revisionHash

    /**
     * Mirrors `VaultRepositoryImpl.sweepUnreferencedRevisions` (skein-a2yr,
     * POST_REVIEW_RESOLUTIONS.md §1.2 step 4): removes every revision that is
     * neither its document's current one nor named by any message's
     * citation-record-v1 `retrieved` list (which already covers every
     * `cited` marker — see `CitationRecordJson.encode`'s own invariant).
     */
    override suspend fun sweepUnreferencedRevisions(): Int =
        writeLock.withLock {
            val referenced: Set<Pair<DocId, RevisionHash>> =
                messagesByChat.values
                    .asSequence()
                    .flatten()
                    .mapNotNull { it.citations }
                    .flatMap { record -> record.retrieved.asSequence() }
                    .map { it.documentId to it.revisionHash }
                    .toSet()
            val toRemove =
                revisions.keys.filter { key ->
                    val (docId, hash) = key
                    hash != documents[docId]?.contentHash && key !in referenced
                }
            toRemove.forEach { revisions.remove(it) }
            toRemove.size
        }

    /**
     * Mirrors `VaultSql.UPSERT_DOCUMENT_REVISION`: the content address is the
     * key, so re-capturing unchanged content reuses the row and moves it back
     * to the head of the document's history (which is what makes a
     * `A -> B -> A` edit sequence resolve to A again).
     *
     * **Chat bound (skein-a2yr).** For [DocumentKind.CHAT] the stored
     * `bodyMdSnapshot` is the empty string rather than the canonicalized
     * transcript — see `VaultRepository.currentRevision`'s KDoc and
     * `VaultRepositoryImpl.captureRevision`'s matching note for the full
     * rationale (unbounded per-turn archiving would make storage quadratic
     * in turn count). [revisionHash] is unaffected, so citation replay for a
     * chat turn still works.
     */
    private fun captureRevision(
        docId: DocId,
        kind: DocumentKind,
        bodyMd: String?,
        frontmatter: JsonObject,
        revisionHash: RevisionHash,
        capturedAt: Long,
    ) {
        val nextOrd =
            (revisions.keys.filter { it.first == docId }.maxOfOrNull { revisions.getValue(it).revisionOrd } ?: -1) + 1
        val bodySnapshot = if (kind == DocumentKind.CHAT) "" else RevisionHashing.canonicalBody(bodyMd)
        revisions[docId to revisionHash] =
            DocumentRevision(
                documentId = docId,
                revisionHash = revisionHash,
                revisionOrd = nextOrd,
                // The snapshot holds the CANONICAL bytes that were hashed,
                // so the row alone reproduces `revisionHash` — except for a
                // chat document, where the empty string above intentionally
                // trades that self-verifying property for bounded storage.
                bodyMdSnapshot = bodySnapshot,
                frontmatterSnapshot =
                    Json.parseToJsonElement(RevisionHashing.canonicalFrontmatter(frontmatter)) as JsonObject,
                capturedAt = capturedAt,
                reason = RevisionReason.INGEST,
            )
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    override suspend fun createAttachment(
        title: String,
        mimeType: String,
        write: suspend (OutputStream) -> Unit,
    ): Document {
        // First materialize bytes so we can hash + size them before the row
        // insert. `write` runs outside the write lock (it may suspend on
        // I/O); the DB write is then guarded by `writeLock`.
        val id = UUID.randomUUID().toString()
        val bytes = attachments.write(id, write)
        val digest = openAttachmentDigest(id)
        return writeLock.withLock {
            val now = clock()
            val frontmatter =
                buildJsonObject {
                    put(FRONTMATTER_ID_KEY, JsonPrimitive(id))
                    put("mime", JsonPrimitive(mimeType))
                    put("size", JsonPrimitive(bytes))
                }
            val doc =
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
            documents[id] = doc
            mimeTypes[id] = mimeType
            emitChange()
            doc
        }
    }

    override suspend fun openAttachment(id: DocId): InputStream = attachments.open(id)

    override suspend fun attachmentMimeType(id: DocId): String =
        mimeTypes[id] ?: throw NoSuchElementException("no attachment mime type for id=$id")

    // ------------------------------------------------------------------
    // Ingest queue
    // ------------------------------------------------------------------

    override suspend fun dequeueIngest(limit: Int): List<IngestItem> =
        ingestQueue.values
            .sortedBy { it.queuedAt }
            .take(limit)

    override suspend fun completeIngest(
        docId: DocId,
        queuedAt: Long,
    ) {
        writeLock.withLock {
            val current = ingestQueue[docId] ?: return@withLock
            // Semantic (`E0.I11` AC): only remove the row when queued_at has
            // NOT advanced since [queuedAt].
            if (current.queuedAt == queuedAt) {
                ingestQueue.remove(docId)
                emitChange()
            }
        }
    }

    override suspend fun recordIngestFailure(docId: DocId): Int =
        writeLock.withLock {
            val current = ingestQueue[docId] ?: return@withLock 0
            val next = current.attempts + 1
            ingestQueue[docId] = current.copy(attempts = next)
            next
        }

    override suspend fun enqueueReembedAll() {
        writeLock.withLock {
            val now = clock()
            for (doc in documents.values) {
                if (doc.kind != DocumentKind.ATTACHMENT) {
                    ingestQueue[doc.id] =
                        IngestItem(docId = doc.id, reason = IngestReason.REEMBED, queuedAt = now)
                }
            }
            emitChange()
        }
    }

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    override suspend fun <T> transaction(block: suspend () -> T): T = writeLock.withLock { block() }

    // ------------------------------------------------------------------
    // Test-only accessors (used by contract tests that need to peek at
    // internal state without going through the public interface).
    // ------------------------------------------------------------------

    /** Read-only view of ingest_queue for assertion in tests. */
    public fun peekIngestQueue(): List<IngestItem> = ingestQueue.values.toList()

    /** Exposes the current version of `changeBus` for backpressure-free polling. */
    public val changes: StateFlow<Unit> get() = ready

    private fun changeTicks(): Flow<Unit> =
        merge(
            changeBus.asSharedFlow(),
            ready,
        ).onStart { emit(Unit) }

    private fun emitChange() {
        changeBus.tryEmit(Unit)
        ready.value = Unit
    }

    private suspend fun openAttachmentDigest(id: DocId): String {
        // Small enough for tests; a real impl would hash on the fly.
        val bytes =
            attachments.open(id).use { it.readBytes() }
        return sha256Hex(bytes)
    }

    private companion object {
        const val FRONTMATTER_ID_KEY: String = "id"

        fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
                sb.append(HEX[b.toInt() and 0x0F])
            }
            return sb.toString()
        }

        val HEX = "0123456789abcdef".toCharArray()

        fun snippetFor(
            body: String,
            query: String,
        ): String {
            val i = body.indexOf(query, ignoreCase = true)
            if (i < 0) return body.take(80)
            val start = maxOf(0, i - 30)
            val end = minOf(body.length, i + query.length + 30)
            return body.substring(start, end)
        }
    }
}
