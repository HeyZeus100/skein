// skein-nxk (E4.I3) step 1: `TokenBatcher`.
//
// Two jobs, both from POST_REVIEW_RESOLUTIONS.md §3.2:
//
//   * batching — `onTokens` fires at most every `TOKEN_BATCH_INTERVAL_MS` or
//     every `TOKEN_BATCH_MAX_TOKENS`, whichever comes first, so a 40 tok/s
//     stream costs ~2 Binder transactions a second rather than 40;
//   * backpressure (rule 4) — at most `MAX_INFLIGHT_TOKEN_BATCHES` batches may
//     be outstanding toward a client, and the OLDEST is shed first. A wedged
//     client must cost the service bounded memory, and the client must be told
//     exactly how many pieces it missed through `onTokens(…, dropped)`.
//
// The clock is injected so the tests assert on the rule rather than on wall
// time; the real worker passes `System::nanoTime`.

package app.skein.inference.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.ipc.TransportRules

class TokenBatcherTest {
    private val sent = mutableListOf<TokenBatch>()
    private var now = 0L
    private var inFlight = 0

    private fun batcher() =
        TokenBatcher(
            nowNanos = { now },
            inFlightBatches = { inFlight },
            emit = { batch -> sent += batch },
        )

    private fun advanceMillis(millis: Long) {
        now += millis * 1_000_000L
    }

    // ------------------------------------------------------------- batching

    @Test
    fun `a single token does not flush immediately`() {
        batcher().offer("a", 1)

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `sixteen tokens flush`() {
        val batcher = batcher()
        repeat(16) { batcher.offer("a", it) }

        assertEquals(1, sent.size)
    }

    @Test
    fun `the flushed batch carries every piece in order`() {
        val batcher = batcher()
        repeat(16) { batcher.offer("p$it", it) }

        assertEquals((0 until 16).map { "p$it" }, sent.single().pieces.toList())
    }

    @Test
    fun `the flushed batch carries every id in order`() {
        val batcher = batcher()
        repeat(16) { batcher.offer("p", it) }

        assertEquals((0 until 16).toList(), sent.single().ids.toList())
    }

    @Test
    fun `fifteen tokens do not flush on count`() {
        val batcher = batcher()
        repeat(15) { batcher.offer("a", it) }

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `twenty milliseconds flush a partial batch`() {
        val batcher = batcher()
        batcher.offer("a", 1)
        advanceMillis(20)
        batcher.offer("b", 2)

        assertEquals(1, sent.size)
    }

    @Test
    fun `nineteen milliseconds do not flush`() {
        val batcher = batcher()
        batcher.offer("a", 1)
        advanceMillis(19)
        batcher.offer("b", 2)

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `flush sends whatever is buffered`() {
        val batcher = batcher()
        batcher.offer("a", 1)
        batcher.flush()

        assertEquals(listOf("a"), sent.single().pieces.toList())
    }

    @Test
    fun `flush on an empty buffer sends nothing`() {
        batcher().flush()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `flush is idempotent`() {
        val batcher = batcher()
        batcher.offer("a", 1)
        batcher.flush()
        batcher.flush()

        assertEquals(1, sent.size)
    }

    @Test
    fun `a batch over the byte ceiling flushes early`() {
        val batcher = batcher()
        val big = "x".repeat(TransportRules.TOKEN_BATCH_MAX_BYTES)

        batcher.offer(big, 1)

        assertEquals(1, sent.size)
    }

    // -------------------------------------------------------- backpressure

    @Test
    fun `a batch is dropped when the client is at the in-flight ceiling`() {
        inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES
        val batcher = batcher()

        repeat(16) { batcher.offer("a", it) }

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `dropped pieces are counted`() {
        inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES
        val batcher = batcher()
        repeat(16) { batcher.offer("a", it) }

        assertEquals(16, batcher.droppedPieces)
    }

    @Test
    fun `the next delivered batch reports everything dropped before it`() {
        val batcher = batcher()
        inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES
        repeat(16) { batcher.offer("a", it) }
        inFlight = 0
        repeat(16) { batcher.offer("b", it) }

        assertEquals(16, sent.single().dropped)
    }

    @Test
    fun `the dropped counter resets once reported`() {
        val batcher = batcher()
        inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES
        repeat(16) { batcher.offer("a", it) }
        inFlight = 0
        repeat(16) { batcher.offer("b", it) }
        repeat(16) { batcher.offer("c", it) }

        assertEquals(0, sent.last().dropped)
    }

    @Test
    fun `a delivered batch reports zero dropped when nothing was shed`() {
        val batcher = batcher()
        repeat(16) { batcher.offer("a", it) }

        assertEquals(0, sent.single().dropped)
    }

    @Test
    fun `one under the ceiling still delivers`() {
        inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES - 1
        val batcher = batcher()

        repeat(16) { batcher.offer("a", it) }

        assertEquals(1, sent.size)
    }
}
