package app.skein.testing.fakes

import app.skein.testing.FakeClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A document as stored by [FakeVaultRepository] — a simplification of the real `Document` (plan §4.2). */
data class FakeDocument(
    val id: String,
    val title: String,
    val bodyMd: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * In-memory stand-in for the (not-yet-landed) `VaultRepository` contract
 * (plan §4.2, `E0.I11`). There is no FTS/vector index, no ingest queue, and
 * no transactional isolation — [searchBodies] is a naive substring match,
 * not the real BM25 ranking `IndexStore.bm25` provides. Use this only to
 * exercise call sequencing and simple CRUD flows.
 *
 * `E10.I2` (skein-0j1) re-targets this against the real `VaultRepository`
 * interface once `E0.I11` lands.
 */
class FakeVaultRepository(
    private val clock: FakeClock = FakeClock(),
) {
    private val documents = ConcurrentHashMap<String, FakeDocument>()
    private val nextId = AtomicLong(1)

    fun createDocument(
        title: String,
        bodyMd: String?,
    ): FakeDocument {
        val now = clock.now()
        val doc =
            FakeDocument(
                id = "doc-${nextId.getAndIncrement()}",
                title = title,
                bodyMd = bodyMd,
                createdAt = now,
                updatedAt = now,
            )
        documents[doc.id] = doc
        return doc
    }

    fun getDocument(id: String): FakeDocument? = documents[id]

    fun updateBody(
        id: String,
        title: String,
        bodyMd: String,
    ): FakeDocument {
        val existing = requireNotNull(documents[id]) { "no document with id $id" }
        val updated = existing.copy(title = title, bodyMd = bodyMd, updatedAt = clock.now())
        documents[id] = updated
        return updated
    }

    fun deleteDocument(id: String) {
        documents.remove(id)
    }

    fun listAll(): List<FakeDocument> = documents.values.sortedByDescending { it.updatedAt }

    /** Naive case-insensitive substring match — NOT the real BM25 ranking `IndexStore.bm25` provides. */
    fun searchBodies(query: String): List<FakeDocument> =
        documents.values.filter {
            it.bodyMd?.contains(query, ignoreCase = true) ==
                true
        }
}

/** Builder mirroring the plan's `fakeVault { note("Title", "body") }` shape (`E10.I2`). */
class FakeVaultBuilder(
    private val repo: FakeVaultRepository = FakeVaultRepository(),
) {
    fun note(
        title: String,
        body: String,
    ): FakeDocument = repo.createDocument(title, body)

    internal fun build(): FakeVaultRepository = repo
}

fun fakeVault(block: FakeVaultBuilder.() -> Unit): FakeVaultRepository = FakeVaultBuilder().apply(block).build()
