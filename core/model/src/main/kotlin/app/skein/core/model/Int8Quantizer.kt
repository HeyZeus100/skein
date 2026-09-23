// M0.5 contract file (`E0.I17`): the int8 quantization rule fixed by plan
// § 4.6 (`docs/superpowers/plans/2026-09-19-skein-v1-plan.md`, verbatim):
//
//   "Int8 quantization is fixed as: `q = round(clamp(x * 127, -127, 127))`
//   on the L2-normalized 256-d vector. Since inputs are unit vectors,
//   sqlite-vec cosine over int8 ranks identically to float cosine within
//   quantization noise."
//
// `Int8Quantizer` performs only the clamp+round+narrow step; L2
// normalization and the 256-d Matryoshka truncation happen in the embed
// pipeline (`EmbedderService.embedDocuments`/`embedQuery`, `EmbedPipeline`
// in `E5.I3`) before the vector reaches here.

package app.skein.core.model

import kotlin.math.roundToInt

public object Int8Quantizer {
    private const val SCALE: Float = 127f
    private const val CLAMP: Float = 127f

    /**
     * `q = round(clamp(x * 127, -127, 127))`, applied component-wise.
     *
     * Callers pass an already L2-normalized vector (e.g. the 256-d
     * Matryoshka-truncated embedding); this function does not normalize.
     */
    public fun quantize(vector: FloatArray): ByteArray =
        ByteArray(vector.size) { i ->
            (vector[i] * SCALE).coerceIn(-CLAMP, CLAMP).roundToInt().toByte()
        }
}
