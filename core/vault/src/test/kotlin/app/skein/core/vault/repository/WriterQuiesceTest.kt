package app.skein.core.vault.repository

import app.skein.core.model.VaultQuiesceTimeoutException
import app.skein.core.model.VaultQuiescedException
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WriterQuiesceTest {
    @Test
    fun `queued writes cannot enter after drain closes admission`() =
        runTest {
            val native = FakeSkeinSQLiteNative()
            val repository =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(native, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            val finish = CompletableDeferred<Unit>()
            val first =
                async {
                    repository.transaction {
                        finish.await()
                        repository.transaction { }
                    }
                }
            runCurrent()
            var queuedRan = false
            val queued = async { runCatching { repository.transaction { queuedRan = true } }.exceptionOrNull() }
            runCurrent()
            val drain = async { repository.quiesce() }
            runCurrent()
            assertFalse(drain.isCompleted)
            finish.complete(Unit)
            first.await()
            assertTrue(queued.await() is VaultQuiescedException)
            drain.await()
            assertFalse(queuedRan)
            assertEquals(listOf(VaultSql.BEGIN_IMMEDIATE, VaultSql.COMMIT), native.prepareCalls)
            repository.close()
            assertEquals(listOf(1L), native.closedHandles)
        }

    @Test
    fun `drain deadline cancels admitted writer and rolls back without a later commit`() =
        runTest {
            val native = FakeSkeinSQLiteNative()
            val repository =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(native, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            val first =
                async {
                    repository.transaction {
                        // Even a caller swallowing cancellation cannot sneak a COMMIT through.
                        runCatching { awaitCancellation() }
                    }
                }
            runCurrent()
            val failure = runCatching { repository.quiesce(25) }.exceptionOrNull()
            first.join()
            assertTrue(failure is VaultQuiesceTimeoutException)
            assertTrue(first.isCancelled)
            assertEquals(listOf(VaultSql.BEGIN_IMMEDIATE, VaultSql.ROLLBACK), native.prepareCalls)
            assertTrue(runCatching { repository.transaction { } }.exceptionOrNull() is VaultQuiescedException)
            assertEquals(25L, testScheduler.currentTime)
        }

    @Test
    fun `caller's tighter deadline is retained and gate stays closed`() =
        runTest {
            val native = FakeSkeinSQLiteNative()
            val repository =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(native, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            val first = async { repository.transaction { awaitCancellation() } }
            runCurrent()
            assertEquals(
                null,
                withTimeoutOrNull(10) {
                    repository.quiesce(10_000)
                    true
                },
            )
            first.join()
            assertEquals(10L, testScheduler.currentTime)
            assertTrue(runCatching { repository.transaction { } }.exceptionOrNull() is VaultQuiescedException)
            assertTrue(native.prepareCalls.contains(VaultSql.ROLLBACK))
        }

    @Test
    fun `zero wait drains an idle writer and repeated calls stay safe`() =
        runTest {
            val native = FakeSkeinSQLiteNative()
            val repository =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(native, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            repository.quiesce(0)
            repository.quiesce(0)
            assertTrue(runCatching { repository.transaction { } }.exceptionOrNull() is VaultQuiescedException)
            assertTrue(native.prepareCalls.isEmpty())
        }

    @Test
    fun `drain caps an excessive requested timeout and forbids self deadlock`() =
        runTest {
            val native = FakeSkeinSQLiteNative()
            val repository =
                VaultRepositoryImpl(
                    SkeinSQLiteConnection(native, 1),
                    InMemoryAttachmentStore(),
                    io = StandardTestDispatcher(testScheduler),
                )
            repository.transaction {
                assertTrue(runCatching { repository.quiesce() }.exceptionOrNull() is IllegalStateException)
            }
            val first = async { repository.transaction { awaitCancellation() } }
            runCurrent()
            assertTrue(runCatching { repository.quiesce(10_000) }.exceptionOrNull() is VaultQuiesceTimeoutException)
            first.join()
            assertEquals(500L, testScheduler.currentTime)
        }
}
