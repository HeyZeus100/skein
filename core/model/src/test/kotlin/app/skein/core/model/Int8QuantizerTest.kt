// `E0.I17` acceptance criterion: `Int8Quantizer.quantize(FloatArray(256))`
// (plan § 4.6's `q = round(clamp(x * 127, -127, 127))` rule) — a unit
// vector quantizes to values in [-127, 127], is deterministic, and
// preserves the vector's L2 norm within 2 %.

package app.skein.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

public class Int8QuantizerTest {
    /** A deterministic, seeded 256-d unit vector — not a real embedding, just a fixture. */
    private fun unitVector(
        seed: Int = 42,
        dim: Int = 256,
    ): FloatArray {
        val random = Random(seed)
        val raw = FloatArray(dim) { random.nextFloat() * 2f - 1f }
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { raw[it] / norm }
    }

    @Test
    public fun quantize_unit_vector_values_in_range() {
        val quantized = Int8Quantizer.quantize(unitVector())

        for (byte in quantized) {
            assertTrue(
                "expected every quantized value in [-127, 127], found $byte",
                byte in -127..127,
            )
        }
    }

    @Test
    public fun quantize_is_deterministic() {
        val vector = unitVector()

        val first = Int8Quantizer.quantize(vector)
        val second = Int8Quantizer.quantize(vector)

        assertArrayEquals("quantize(v) must equal quantize(v)", first, second)
    }

    @Test
    public fun quantize_preserves_norm_within_2_percent() {
        val vector = unitVector()

        val quantized = Int8Quantizer.quantize(vector)
        val dequantized = FloatArray(quantized.size) { quantized[it] / 127f }
        val dequantizedNorm = sqrt(dequantized.sumOf { (it * it).toDouble() }).toFloat()

        val relativeError = kotlin.math.abs(dequantizedNorm - 1f)
        assertTrue(
            "expected dequantized norm within 2% of 1.0, was $dequantizedNorm (error $relativeError)",
            relativeError <= 0.02f,
        )
    }
}
