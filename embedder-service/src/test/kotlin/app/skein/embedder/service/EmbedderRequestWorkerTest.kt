package app.skein.embedder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EmbedderRequestWorkerTest {
    @Test
    fun coldServiceClosesRefusedInputWithoutTouchingAction() {
        EmbedderRequestWorker(EmbedderSessionGate()).use { worker ->
            val closes = AtomicInteger()
            val error =
                assertThrows(EmbedderRequestException::class.java) {
                    worker.run(7, 1, closeInput = { closes.incrementAndGet() }) { error("must not read") }
                }
            assertEquals(EmbedderRequestFailure.SESSION_LOCKED, error.failure)
            assertEquals(1, closes.get())
        }
    }

    @Test
    fun positiveIdsCannotBeReusedButCanRestartInNewEpoch() {
        val gate = EmbedderSessionGate().apply { authorize(7) }
        EmbedderRequestWorker(gate).use { worker ->
            assertEquals(3, worker.run(7, 4) { 3 })
            listOf(0, 3, 4).forEach { id ->
                assertEquals(
                    EmbedderRequestFailure.INVALID_REQUEST,
                    assertThrows(
                        EmbedderRequestException::class.java,
                    ) {
                        worker.run(7, id) { 0 }
                    }.failure,
                )
            }
            gate.revoke(7)
            gate.authorize(8)
            assertEquals(2, worker.run(8, 1) { 2 })
        }
    }

    @Test
    fun cancellationRevokesReplyButRetainsBusySlotAndInputUntilActionExits() {
        val gate = EmbedderSessionGate().apply { authorize(7) }
        EmbedderRequestWorker(gate).use { worker ->
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val discarded = CountDownLatch(1)
            val closes = AtomicInteger()
            val caller =
                CompletableFuture.supplyAsync {
                    runCatching {
                        worker.run(
                            7,
                            1,
                            closeInput = { closes.incrementAndGet() },
                            discardResult = { discarded.countDown() },
                        ) {
                            started.countDown()
                            awaitUninterruptibly(finish)
                            42
                        }
                    }.exceptionOrNull() as? EmbedderRequestException
                }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                worker.cancel(6, 1)
                worker.cancel(7, 2)
                assertFalse(caller.isDone)
                worker.cancel(7, 1)
                assertEquals(EmbedderRequestFailure.CANCELLED, caller.get(2, TimeUnit.SECONDS)?.failure)
                assertTrue(worker.isBusy(7))
                assertEquals(0, closes.get())
                assertEquals(
                    EmbedderRequestFailure.BUSY,
                    assertThrows(EmbedderRequestException::class.java) {
                        worker.run(7, 2) { 0 }
                    }.failure,
                )
            } finally {
                finish.countDown()
            }
            assertTrue(discarded.await(2, TimeUnit.SECONDS))
            assertEquals(1, closes.get())
            assertFalse(worker.isBusy())
        }
    }

    @Test
    fun timeoutDoesNotFreeInputOrPublishLateResult() {
        val gate = EmbedderSessionGate().apply { authorize(7) }
        EmbedderRequestWorker(gate, timeoutMillis = 100).use { worker ->
            val finish = CountDownLatch(1)
            val discarded = CountDownLatch(1)
            val closed = AtomicInteger()
            try {
                val error =
                    assertThrows(EmbedderRequestException::class.java) {
                        worker.run(
                            7,
                            1,
                            closeInput = { closed.incrementAndGet() },
                            discardResult = { discarded.countDown() },
                        ) {
                            awaitUninterruptibly(finish)
                            42
                        }
                    }
                assertEquals(EmbedderRequestFailure.TIMED_OUT, error.failure)
                assertEquals(0, closed.get())
                assertTrue(worker.isBusy())
            } finally {
                finish.countDown()
            }
            assertTrue(discarded.await(2, TimeUnit.SECONDS))
            assertEquals(1, closed.get())
        }
    }

    @Test
    fun revocationRejectsResultEvenWhenBackendDidNotObserveCancellation() {
        val gate = EmbedderSessionGate().apply { authorize(7) }
        EmbedderRequestWorker(gate).use { worker ->
            val discarded = AtomicInteger()
            val published = AtomicInteger()
            val error =
                assertThrows(EmbedderRequestException::class.java) {
                    worker.run(
                        7,
                        1,
                        discardResult = { discarded.incrementAndGet() },
                        publishResult = { published.incrementAndGet() },
                    ) {
                        gate.revoke(7)
                        42
                    }
                }
            assertEquals(EmbedderRequestFailure.SESSION_LOCKED, error.failure)
            assertEquals(1, discarded.get())
            assertEquals(0, published.get())
        }
    }

    @Test
    fun successfulReplyFollowsOwnedInputCleanup() {
        val gate = EmbedderSessionGate().apply { authorize(7) }
        EmbedderRequestWorker(gate).use { worker ->
            val closed = AtomicInteger()
            worker.run(7, 1, closeInput = { closed.incrementAndGet() }) { 42 }
            assertEquals(1, closed.get())
            assertFalse(worker.isBusy())
            assertEquals(43, worker.run(7, 2) { 43 })
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                check(latch.await(5, TimeUnit.SECONDS))
                return
            } catch (_: InterruptedException) {
                // Deliberately model an uncooperative native call, released by the test.
            }
        }
    }
}
