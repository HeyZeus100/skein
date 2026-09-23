// skein-st1r's §2.4 `Blake3VectorTest`, moved here by skein-nxk (E4.I3,
// coordinator decision skein-hiwb) when the duplicate BLAKE3 in
// `:core:inference` was deleted in favour of this one.
//
// `Blake3Test` beside this file pins a dozen hand-quoted vectors; this replays
// all 35 official cases from the reference implementation's own vector file
// (`src/test/resources/blake3/official_test_vectors.txt`), which is the
// stricter of the two sets the coordinator decision said to keep. A
// hand-rolled hash that is only ever tested against itself is worthless.
//
// DEVIATION, recorded on skein-nxk: the `:core:inference` copy also asserted
// the EXTENDED (XOF) output of every vector, because that implementation
// exposed `Hasher.digestInto(out)` and a root-output counter. This `Blake3`
// deliberately exposes only the 32-byte digest — `digest()`/`hex()` — so the
// XOF assertion has no surface to run against and is not ported. The first 32
// bytes of every official vector are still pinned below, which is the whole of
// what any Skein caller consumes (revision hashes, the post-mmap gate).
// Follow-up: skein-fjyw.

package app.skein.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Blake3VectorTest {
    @Test
    fun `the official vector file is complete`() {
        assertEquals(35, officialVectors().size)
    }

    @Test
    fun `digest matches every official vector`() {
        for (vector in officialVectors()) {
            val expected = vector.extendedOutputHex.take(64)
            assertEquals("len=${vector.inputLen}", expected, Blake3.hex(vector.input()))
        }
    }

    @Test
    fun `incremental updates equal a single update`() {
        val input = repeatingInput(9_001)
        val hasher = Blake3.Hasher()
        var offset = 0
        var step = 1
        while (offset < input.size) {
            val take = minOf(step, input.size - offset)
            hasher.update(input, offset, take)
            offset += take
            step = step * 3 + 1
        }

        assertEquals(Blake3.hex(input), hasher.hex())
    }

    @Test
    fun `the byte buffer overload digests the remaining bytes`() {
        val input = repeatingInput(5_000)

        assertEquals(Blake3.hex(input), Blake3.hexDigest(ByteBuffer.wrap(input)))
    }

    @Test
    fun `the byte buffer overload leaves the caller's position alone`() {
        val buffer = ByteBuffer.wrap(repeatingInput(5_000))
        Blake3.hexDigest(buffer)

        assertEquals(0, buffer.position())
    }

    @Test
    fun `the byte buffer overload digests only what remains`() {
        val input = repeatingInput(5_000)
        val buffer = ByteBuffer.wrap(input)
        buffer.position(1_000)

        assertEquals(Blake3.hex(input.copyOfRange(1_000, input.size)), Blake3.hexDigest(buffer))
    }

    @Test
    fun `an empty buffer digests to the published empty-input vector`() {
        assertEquals(
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            Blake3.hexDigest(ByteBuffer.allocate(0)),
        )
    }

    // ------------------------------------------------------------- fixtures

    private data class Vector(
        val inputLen: Int,
        val extendedOutputHex: String,
    ) {
        fun input(): ByteArray = repeatingInput(inputLen)
    }

    private fun officialVectors(): List<Vector> {
        val text =
            checkNotNull(javaClass.getResourceAsStream(VECTOR_RESOURCE)) {
                "missing $VECTOR_RESOURCE on the test classpath"
            }.use { it.readBytes().decodeToString() }
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (len, hex) = line.split(' ', limit = 2)
                Vector(len.toInt(), hex)
            }.toList()
    }

    private companion object {
        const val VECTOR_RESOURCE = "/blake3/official_test_vectors.txt"

        /** The reference input: the repeating byte sequence 0, 1, ..., 250, 0, 1, ... */
        fun repeatingInput(length: Int): ByteArray = ByteArray(length) { (it % 251).toByte() }
    }
}
