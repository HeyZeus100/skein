// skein-st1r: §2.4's `Blake3VectorTest`. The hand-rolled BLAKE3 is only
// trustworthy if it is pinned to the reference implementation's own vectors,
// so all 35 official cases are replayed here — including the extended (XOF)
// output, which exercises the root-output counter that a 32-byte-only test
// would never reach.

package app.skein.core.inference.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Blake3Test {
    @Test
    fun `official vectors are covered`() {
        assertThat(officialVectors()).hasSize(35)
    }

    @Test
    fun `digest matches every official vector`() {
        for (vector in officialVectors()) {
            val expected = vector.extendedOutputHex.take(Blake3.OUT_LEN * 2)
            assertThat("len=${vector.inputLen} ${Blake3.hexDigest(vector.input())}")
                .isEqualTo("len=${vector.inputLen} $expected")
        }
    }

    @Test
    fun `extended output matches every official vector`() {
        for (vector in officialVectors()) {
            val out = ByteArray(vector.extendedOutputHex.length / 2)
            Blake3.Hasher().apply { update(vector.input()) }.digestInto(out)
            assertThat("len=${vector.inputLen} ${Hex.encode(out)}")
                .isEqualTo("len=${vector.inputLen} ${vector.extendedOutputHex}")
        }
    }

    @Test
    fun `digest of empty input matches the published constant`() {
        // Spot-check against the value quoted in the BLAKE3 paper, so the test
        // does not depend solely on the vector file being the right file.
        assertThat(Blake3.hexDigest(ByteArray(0)))
            .isEqualTo("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262")
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
        assertThat(Hex.encode(hasher.digest())).isEqualTo(Blake3.hexDigest(input))
    }

    @Test
    fun `byte buffer overload digests the remaining bytes and restores position`() {
        val input = repeatingInput(5_000)
        val buffer = java.nio.ByteBuffer.wrap(input)
        buffer.position(0)
        assertThat(Blake3.hexDigest(buffer)).isEqualTo(Blake3.hexDigest(input))
        assertThat(buffer.position()).isEqualTo(0)
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
