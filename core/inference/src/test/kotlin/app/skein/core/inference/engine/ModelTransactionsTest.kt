package app.skein.core.inference.engine

import app.skein.core.model.InferenceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelTransactionsTest {
    @Test
    fun revocationReleasesCallerButKeepsTransactionResourcesUntilBlockingCallReturns() =
        runTest {
            val calls = ModelTransactions(Dispatchers.IO)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val closeCount = AtomicInteger()
            try {
                val result =
                    async(Dispatchers.Default) {
                        runCatching {
                            calls.call(calls.current(), close = {
                                closeCount.incrementAndGet()
                                closed.countDown()
                            }) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                "late result"
                            }
                        }
                    }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                calls.revoke()
                assertThat(result.await().exceptionOrNull()).isInstanceOf(InferenceException.SessionLocked::class.java)
                assertThat(closeCount.get()).isEqualTo(0)
                release.countDown()
                assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(closeCount.get()).isEqualTo(1)
            } finally {
                release.countDown()
            }
        }

    @Test
    fun cancellationBeforeWorkerStartsClosesResourcesWithoutDispatch() =
        runTest {
            val calls = ModelTransactions(StandardTestDispatcher(testScheduler))
            var dispatched = false
            var closed = 0
            val caller =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    calls.call(calls.current(), close = { closed++ }) { dispatched = true }
                }
            caller.cancel()
            runCurrent()
            assertThat(dispatched).isFalse()
            assertThat(closed).isEqualTo(1)
        }
}
