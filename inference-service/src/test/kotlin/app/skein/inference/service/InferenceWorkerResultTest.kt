package app.skein.inference.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class InferenceWorkerResultTest {
    @Test
    fun `interruption cannot finish the caller while native work still owns resources`() {
        val outcome = interruptedCall { "completed" }

        assertThat(outcome.result).isEqualTo("completed")
        assertThat(outcome.failure).isNull()
        assertThat(outcome.interruptRestored).isTrue()
    }

    @Test
    fun `interrupted wait preserves the native exception itself and restores the flag`() {
        val expected = LlamaException(LlamaErrorCode.CANCELLED, "native operation cancelled")
        val outcome = interruptedCall<String> { throw expected }

        assertThat(outcome.failure).isSameInstanceAs(expected)
        assertThat(outcome.interruptRestored).isTrue()
    }

    @Test
    fun `an error is unwrapped without changing its identity`() {
        val expected = AssertionError("worker failure")
        val task = FutureTask(Callable<String> { throw expected })
        task.run()

        assertThat(runCatching { awaitWorkerResult(task) }.exceptionOrNull()).isSameInstanceAs(expected)
    }

    private fun <T> interruptedCall(block: () -> T): Outcome<T> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val restored = AtomicBoolean(false)
        val callerFinished = AtomicBoolean(false)
        val task =
            FutureTask(
                Callable {
                    entered.countDown()
                    check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    block()
                },
            )
        val worker = Thread(task).apply { isDaemon = true }
        val caller =
            Thread {
                try {
                    result.set(awaitWorkerResult(task))
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    restored.set(Thread.currentThread().isInterrupted)
                    callerFinished.set(true)
                }
            }.apply { isDaemon = true }
        worker.start()
        caller.start()
        try {
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            awaitWaiting(caller)
            caller.interrupt()
            // Future.get clears the flag when throwing InterruptedException.
            // Wait until the caller handled that interrupt and waits again;
            // an implementation that returns early terminates instead.
            awaitWaiting(caller, afterInterrupt = true)
            assertThat(callerFinished.get()).isFalse()
        } finally {
            release.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
        assertThat(worker.isAlive).isFalse()
        assertThat(caller.isAlive).isFalse()
        return Outcome(result.get(), failure.get(), restored.get())
    }

    private fun awaitWaiting(
        thread: Thread,
        afterInterrupt: Boolean = false,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (thread.isAlive && System.nanoTime() < deadline) {
            if (thread.state == Thread.State.WAITING && (!afterInterrupt || !thread.isInterrupted)) return
            Thread.yield()
        }
        throw AssertionError("caller did not remain waiting for worker completion: ${thread.state}")
    }

    private data class Outcome<T>(
        val result: T?,
        val failure: Throwable?,
        val interruptRestored: Boolean,
    )

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
