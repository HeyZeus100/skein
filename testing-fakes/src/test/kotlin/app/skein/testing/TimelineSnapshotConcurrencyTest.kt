package app.skein.testing

import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.TimelineFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotConcurrencyTest {
    @Test
    fun `foreign transaction reader waits for writer rollback and receives committed snapshot`(): Unit =
        runBlocking {
            withTimeout(5_000) {
                val repository = InMemoryVaultRepository()
                val foreignRepository = InMemoryVaultRepository()
                val committed = repository.createDocument(NewDocument(DocumentKind.NOTE, "Committed", "body"))
                val writerEntered = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val writer =
                    async(Dispatchers.Default) {
                        runCatching {
                            repository.transaction {
                                repository.createDocument(NewDocument(DocumentKind.NOTE, "Uncommitted", "body"))
                                writerEntered.complete(Unit)
                                releaseWriter.await()
                                error("controlled rollback")
                            }
                        }.exceptionOrNull()
                    }
                writerEntered.await()
                val reader =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        // Another repository's marker cannot bypass this repository's lock.
                        foreignRepository.transaction { repository.observeTimeline(TimelineFilter()).first() }
                    }
                try {
                    // UNDISPATCHED reaches the contested read before async returns;
                    // no sleeps or assumptions about thread scheduling are needed.
                    assertFalse("timeline exposed an in-progress write", reader.isCompleted)
                    releaseWriter.complete(Unit)
                    assertTrue(writer.await() is IllegalStateException)
                    assertEquals(listOf(committed.id), reader.await().map { it.id })
                } finally {
                    releaseWriter.complete(Unit)
                    writer.cancelAndJoin()
                    reader.cancelAndJoin()
                }
            }
        }

    @Test
    fun `owned transaction timeline is reentrant and read remains available after quiesce`(): Unit =
        runBlocking {
            withTimeout(5_000) {
                val repository = InMemoryVaultRepository()
                val committed = repository.createDocument(NewDocument(DocumentKind.NOTE, "Committed", "body"))
                val failure =
                    runCatching {
                        repository.transaction {
                            val transient =
                                repository.createDocument(
                                    NewDocument(DocumentKind.NOTE, "Transient", "body"),
                                )
                            repository.transaction {
                                assertEquals(
                                    setOf(committed.id, transient.id),
                                    repository
                                        .observeTimeline(TimelineFilter())
                                        .first()
                                        .map { it.id }
                                        .toSet(),
                                )
                            }
                            error("controlled rollback")
                        }
                    }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                repository.quiesce(0)
                assertEquals(listOf(committed.id), repository.observeTimeline(TimelineFilter()).first().map { it.id })
            }
        }
}
