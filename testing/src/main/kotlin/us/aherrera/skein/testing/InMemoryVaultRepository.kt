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

package us.aherrera.skein.testing

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentHit
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.IngestItem
import us.aherrera.skein.core.model.IngestReason
import us.aherrera.skein.core.model.Message
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.NewMessage
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository
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
                    contentHash = new.bodyMd?.let(::sha256Hex),
                )
            documents[chosenId] = doc
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
            val updated =
                existing.copy(
                    title = title,
                    bodyMd = bodyMd,
                    updatedAt = now,
                    contentHash = sha256Hex(bodyMd),
                )
            documents[id] = updated
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
            val updated =
                existing.copy(
                    frontmatter = withId,
                    updatedAt = clock(),
                )
            documents[id] = updated
            emitChange()
            updated
        }

    override suspend fun deleteDocument(id: DocId) {
        writeLock.withLock {
            documents.remove(id)
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
                    retrievedChunks = message.retrievedChunks,
                    createdAt = now,
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
            val rewritten =
                chat.copy(
                    bodyMd = transcript,
                    updatedAt = now,
                    contentHash = sha256Hex(transcript),
                )
            documents[chatDocId] = rewritten
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
