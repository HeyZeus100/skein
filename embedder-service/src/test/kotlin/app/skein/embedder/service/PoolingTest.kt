package app.skein.embedder.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PoolingTest {
    @Test
    fun `padding does not affect mean pooling including nonfinite padding values`() {
        val pooled =
            Pooling.meanPool(
                arrayOf(floatArrayOf(1f, 4f), floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), floatArrayOf(3f, 8f)),
                longArrayOf(1, 0, 1),
            )
        assertArrayEquals(floatArrayOf(2f, 6f), pooled, 0f)
    }

    @Test
    fun `mean pooling accumulates finite values without float overflow`() {
        val pooled =
            Pooling.meanPool(arrayOf(floatArrayOf(Float.MAX_VALUE), floatArrayOf(Float.MAX_VALUE)), longArrayOf(1, 1))
        assertEquals(Float.MAX_VALUE, pooled.single(), 0f)
    }

    @Test
    fun `malformed tensor or mask is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Pooling.meanPool(emptyArray(), longArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            Pooling.meanPool(arrayOf(floatArrayOf(1f)), longArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pooling.meanPool(arrayOf(floatArrayOf(1f), floatArrayOf()), longArrayOf(1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pooling.meanPool(arrayOf(floatArrayOf()), longArrayOf(1))
        }
        for (mask in listOf(longArrayOf(0), longArrayOf(-1), longArrayOf(2))) {
            assertThrows(IllegalArgumentException::class.java) {
                Pooling.meanPool(arrayOf(floatArrayOf(1f)), mask)
            }
        }
    }

    @Test
    fun `nonfinite attended values are rejected`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                Pooling.meanPool(arrayOf(floatArrayOf(value)), longArrayOf(1))
            }
        }
    }

    @Test
    fun `truncation precedes normalization and uses fixed int8 rounding`() {
        val pooled =
            FloatArray(768).apply {
                this[0] = 3f
                this[1] = -4f
                this[256] = 10_000f
            }
        val original = pooled.copyOf()
        val expected =
            ByteArray(256).apply {
                this[0] = 76
                this[1] = -102
            }
        assertArrayEquals(expected, Pooling.matryoshkaInt8(pooled))
        assertArrayEquals(original, pooled, 0f)
        assertArrayEquals(expected, Pooling.matryoshkaInt8(pooled))
    }

    @Test
    fun `normalization is stable for tiny and large finite vectors`() {
        val expected = ByteArray(256).apply { this[0] = 127 }
        for (value in listOf(Float.MIN_VALUE, Float.MAX_VALUE)) {
            assertArrayEquals(expected, Pooling.matryoshkaInt8(FloatArray(256).apply { this[0] = value }))
        }
    }

    @Test
    fun `zero retained dimensions are rejected even with a nonzero discarded tail`() {
        assertThrows(IllegalArgumentException::class.java) { Pooling.matryoshkaInt8(FloatArray(256)) }
        assertThrows(IllegalArgumentException::class.java) {
            Pooling.matryoshkaInt8(FloatArray(768).apply { this[256] = 1f })
        }
    }

    @Test
    fun `short and nonfinite vectors are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Pooling.matryoshkaInt8(FloatArray(255) { 1f }) }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                Pooling.matryoshkaInt8(FloatArray(256).apply { this[0] = value })
            }
        }
    }
}
