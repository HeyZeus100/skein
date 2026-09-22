// skein-nxk (E4.I3) step 1: `Utf8Buffer`.
//
// `LlamaNative.tokenToPieceBytes` hands over RAW BYTES, not a String, because a
// single token's piece is frequently a FRAGMENT of a UTF-8 sequence — every CJK
// character and every emoji is split across two or three tokens. Decoding each
// piece on its own replaces those fragments with U+FFFD, so the stream would
// show a replacement character where the user typed a name.
//
// This buffer holds an incomplete trailing sequence until the next piece
// completes it. Its contract is exactly: concatenating everything it ever
// emits equals decoding the concatenation of every piece it was fed.

package app.skein.inference.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8BufferTest {
    @Test
    fun `ascii passes straight through`() {
        val buffer = Utf8Buffer()

        assertEquals("hello", buffer.append("hello".toByteArray()))
    }

    @Test
    fun `an empty piece emits nothing`() {
        val buffer = Utf8Buffer()

        assertEquals("", buffer.append(ByteArray(0)))
    }

    @Test
    fun `a three-byte character split across two pieces emits nothing on the first`() {
        val bytes = "日".toByteArray(Charsets.UTF_8)
        val buffer = Utf8Buffer()

        assertEquals("", buffer.append(bytes.copyOfRange(0, 2)))
    }

    @Test
    fun `a three-byte character split across two pieces completes on the second`() {
        val bytes = "日".toByteArray(Charsets.UTF_8)
        val buffer = Utf8Buffer()
        buffer.append(bytes.copyOfRange(0, 2))

        assertEquals("日", buffer.append(bytes.copyOfRange(2, 3)))
    }

    @Test
    fun `a three-byte character split one byte at a time survives`() {
        val bytes = "日".toByteArray(Charsets.UTF_8)
        val buffer = Utf8Buffer()
        val out = StringBuilder()

        for (b in bytes) out.append(buffer.append(byteArrayOf(b)))

        assertEquals("日", out.toString())
    }

    @Test
    fun `a four-byte emoji split across three pieces survives`() {
        val bytes = "😀".toByteArray(Charsets.UTF_8)
        val buffer = Utf8Buffer()
        val out = StringBuilder()

        out.append(buffer.append(bytes.copyOfRange(0, 1)))
        out.append(buffer.append(bytes.copyOfRange(1, 3)))
        out.append(buffer.append(bytes.copyOfRange(3, 4)))

        assertEquals("😀", out.toString())
    }

    @Test
    fun `a complete character followed by a fragment emits only the complete one`() {
        val complete = "a".toByteArray()
        val fragment = "日".toByteArray(Charsets.UTF_8).copyOfRange(0, 2)
        val buffer = Utf8Buffer()

        assertEquals("a", buffer.append(complete + fragment))
    }

    @Test
    fun `mixed scripts across arbitrary splits reassemble exactly`() {
        val text = "héllo 日本語 😀 mixed ünicode ☃"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = Utf8Buffer()
        val out = StringBuilder()

        var i = 0
        var step = 1
        while (i < bytes.size) {
            val take = minOf(step, bytes.size - i)
            out.append(buffer.append(bytes.copyOfRange(i, i + take)))
            i += take
            step = if (step >= 5) 1 else step + 1
        }

        assertEquals(text, out.toString())
    }

    @Test
    fun `flush emits nothing when the buffer is empty`() {
        val buffer = Utf8Buffer()
        buffer.append("done".toByteArray())

        assertEquals("", buffer.flush())
    }

    @Test
    fun `flush emits a replacement for a truncated tail at end of stream`() {
        val buffer = Utf8Buffer()
        buffer.append("日".toByteArray(Charsets.UTF_8).copyOfRange(0, 2))

        // The generation ended mid-sequence (cancel, or a length stop). The
        // bytes will never be completed, so the only honest rendering is the
        // replacement character — dropping them silently would let a truncated
        // stream look complete.
        assertTrue(buffer.flush().isNotEmpty())
    }

    @Test
    fun `flush is idempotent`() {
        val buffer = Utf8Buffer()
        buffer.append("日".toByteArray(Charsets.UTF_8).copyOfRange(0, 2))
        buffer.flush()

        assertEquals("", buffer.flush())
    }

    @Test
    fun `an invalid lead byte does not wedge the buffer`() {
        val buffer = Utf8Buffer()
        buffer.append(byteArrayOf(0xFF.toByte()))

        assertEquals("ok", buffer.append("ok".toByteArray()))
    }

    @Test
    fun `a pending fragment never exceeds three bytes`() {
        val buffer = Utf8Buffer()
        buffer.append("😀".toByteArray(Charsets.UTF_8).copyOfRange(0, 3))

        assertEquals(3, buffer.pendingBytes)
    }
}
