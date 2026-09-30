package app.skein.shell

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDeleteCoordinatorTest {
    private val index = InMemoryIndexStore()
    private val vault = InMemoryVaultRepository(index = index)
    private val pruned = mutableListOf<DocId>()
    private val events = mutableListOf<String>()
    private val notices = DocumentDeleteNotices()

    private fun TestScope.coordinator(
        repository: VaultRepository = vault,
        reservation: (Document) -> PendingDocumentDelete? = {
            events += "reserve"
            object : PendingDocumentDelete {
                override suspend fun awaitIdle() {
                    events += "drain"
                }

                override fun commit() {
                    events += "commit"
                }

                override suspend fun rollback() {
                    events += "rollback"
                }
            }
        },
    ) = DocumentDeleteCoordinator(repository, index, this, reservation, { pruned += it }, notices)

    private suspend fun document(
        kind: DocumentKind = DocumentKind.NOTE,
        title: String = "Disposable fixture",
    ) = vault.createDocument(NewDocument(kind = kind, title = title, bodyMd = "Fixture only"))

    @Test
    fun `opening then canceling never reserves writers or mutates content`() =
        runTest {
            val note = document()
            val coordinator = coordinator()
            coordinator.request(note.id)
            runCurrent()
            assertThat(coordinator.prompt.value?.heading).isEqualTo("Delete \"Disposable fixture\"?")
            coordinator.cancel()
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(note.id)).isEqualTo(note)
            assertThat(events).isEmpty()
            assertThat(pruned).isEmpty()
        }

    @Test
    fun `confirmation commits only its target then clears holders and prunes`() =
        runTest {
            val target = document(DocumentKind.CHAT)
            val other = document()
            val coordinator = coordinator()
            coordinator.request(target.id)
            runCurrent()
            coordinator.confirm()
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(target.id)).isNull()
            assertThat(vault.getDocument(other.id)).isEqualTo(other)
            assertThat(events).containsExactly("reserve", "drain", "commit").inOrder()
            assertThat(pruned).containsExactly(target.id)
            assertThat(coordinator.message.value?.text).isEqualTo("Chat deleted.")
        }

    @Test
    fun `failed transaction retains document and restores its writers`() =
        runTest {
            val target = document()
            val failing =
                object : VaultRepository by vault {
                    override suspend fun deleteDocument(id: DocId) {
                        vault.deleteDocument(id)
                        error("synthetic commit failure")
                    }
                }
            val coordinator = coordinator(failing)
            coordinator.request(target.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(target.id)).isEqualTo(target)
            assertThat(events).containsExactly("reserve", "drain", "rollback").inOrder()
            assertThat(pruned).isEmpty()
            assertThat(coordinator.message.value?.text).contains("Try again.")
        }

    @Test
    fun `cancellation on return after commit still discards and prunes`() =
        runTest {
            val target = document()
            val returning =
                object : VaultRepository by vault {
                    override suspend fun <T> transaction(block: suspend () -> T): T {
                        vault.transaction(block)
                        throw CancellationException("synthetic dispatcher return")
                    }
                }
            val coordinator = coordinator(returning)
            coordinator.request(target.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(target.id)).isNull()
            assertThat(events).containsExactly("reserve", "drain", "commit").inOrder()
            assertThat(pruned).containsExactly(target.id)
            assertThat(notices.consumeInterrupted()).isFalse()
        }

    @Test
    fun `cancellation before commit retains document and reports after next unlock`() =
        runTest {
            val target = document()
            val failing =
                object : VaultRepository by vault {
                    override suspend fun deleteDocument(id: DocId): Unit = throw CancellationException("synthetic lock")
                }
            val coordinator = coordinator(failing)
            coordinator.request(target.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(target.id)).isEqualTo(target)
            assertThat(events.last()).isEqualTo("rollback")
            assertThat(pruned).isEmpty()
            val nextSession = coordinator()
            assertThat(
                nextSession.message.value?.text,
            ).isEqualTo("Deletion was interrupted. Check the item and try again.")
            assertThat(notices.consumeInterrupted()).isFalse()
        }

    @Test
    fun `a send racing an open confirmation refuses deletion`() =
        runTest {
            val target = document(DocumentKind.CHAT)
            val coordinator = coordinator(reservation = { null })
            coordinator.request(target.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(target.id)).isEqualTo(target)
            assertThat(pruned).isEmpty()
            assertThat(coordinator.message.value?.text).isEqualTo("Stop the answer to delete this chat.")
        }

    @Test
    fun `writer drain precedes storage mutation and duplicate request cannot retarget`() =
        runTest {
            val target = document()
            val other = document()
            val drained = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(reservation = {
                    object : PendingDocumentDelete {
                        override suspend fun awaitIdle() {
                            drained.await()
                        }

                        override fun commit() {
                            events += "commit"
                        }

                        override suspend fun rollback() = Unit
                    }
                })
            coordinator.request(target.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            coordinator.request(other.id)
            assertThat(vault.getDocument(target.id)).isEqualTo(target)
            drained.complete(Unit)
            runCurrent()
            assertThat(vault.getDocument(target.id)).isNull()
            assertThat(vault.getDocument(other.id)).isEqualTo(other)
            assertThat(coordinator.prompt.value).isNull()
        }

    @Test
    fun `link and quote retention counts are disclosed and zero counts omitted`() =
        runTest {
            val target = document()
            val linking = document()
            index.replaceEdges(
                linking.id,
                setOf(EdgeKind.WIKILINK),
                listOf(Edge(linking.id, target.id, EdgeKind.WIKILINK, createdAt = 0)),
            )
            val counted =
                object : VaultRepository by vault {
                    override suspend fun countChatsCiting(id: DocId): Int = if (id == target.id) 3 else 0
                }
            val coordinator = coordinator(counted)
            coordinator.request(target.id)
            runCurrent()
            assertThat(
                coordinator.prompt.value?.consequence,
            ).contains("1 note links to it. That link will show as missing.")
            assertThat(coordinator.prompt.value?.consequence).contains("It was quoted in 3 chats. Those quotes stay.")
            coordinator.cancel()
            coordinator.request(linking.id)
            runCurrent()
            assertThat(coordinator.prompt.value?.consequence).isEqualTo("This permanently removes the note from Skein.")
        }

    @Test
    fun `extracted notes cannot enter the standalone note delete path`() =
        runTest {
            val extracted =
                vault
                    .createDocument(
                        NewDocument(
                            kind = DocumentKind.NOTE,
                            title = "Extracted",
                            bodyMd = "Text",
                            frontmatter =
                                buildJsonObject {
                                    put("source", "attachment")
                                },
                        ),
                    )
            val coordinator = coordinator()
            coordinator.request(extracted.id)
            runCurrent()
            assertThat(coordinator.prompt.value).isNull()
            coordinator.confirm()
            assertThat(vault.getDocument(extracted.id)).isEqualTo(extracted)
            assertThat(events).isEmpty()
        }

    @Test
    fun `existing confirmation rechecks file eligibility before reserving writers`() =
        runTest {
            val target = document()
            val coordinator = coordinator()
            coordinator.request(target.id)
            runCurrent()
            vault.updateFrontmatter(target.id, buildJsonObject { put("source", "attachment") })
            coordinator.confirm()
            runCurrent()
            assertThat(vault.getDocument(target.id)).isNotNull()
            assertThat(events).isEmpty()
            assertThat(coordinator.message.value?.text).isEqualTo("This item changed. Open it and try again.")
        }

    @Test
    fun `missing target is idempotent and a long title stays bounded`() =
        runTest {
            val coordinator = coordinator()
            coordinator.request("already-gone-imported-id")
            runCurrent()
            assertThat(pruned).containsExactly("already-gone-imported-id")
            assertThat(events).isEmpty()
            val title = DocumentDeletePrompt("id", "x".repeat(100), DocumentKind.NOTE, "").heading
            assertThat(title).isEqualTo("Delete \"${"x".repeat(59)}…\"?")
        }
}
