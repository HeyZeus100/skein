package app.skein.embedder.service

import app.skein.core.model.Int8Quantizer
import kotlin.math.sqrt

/**
 * Numeric preparation for E5.I1; this does not choose or load an embedding backend.
 * ONNX token outputs need [meanPool]; an already pooled backend output enters
 * [matryoshkaInt8] directly. Both paths use the contract's same 256-dimensional result.
 */
internal object Pooling {
    private const val DIMENSIONS = 256

    /** Pool one sequence, excluding padding. Invalid model output must never become an index vector. */
    fun meanPool(
        hiddenStates: Array<FloatArray>,
        attentionMask: LongArray,
    ): FloatArray {
        require(hiddenStates.isNotEmpty()) { "Embedding sequence is empty" }
        require(hiddenStates.size == attentionMask.size) { "Embedding sequence and mask lengths differ" }
        val dimensions = hiddenStates.first().size
        require(dimensions > 0 && hiddenStates.all { it.size == dimensions }) { "Invalid embedding tensor shape" }
        require(attentionMask.all { it == 0L || it == 1L }) { "Embedding attention mask must be binary" }
        val attended = attentionMask.count { it == 1L }
        require(attended > 0) { "Embedding sequence has no attended tokens" }
        val sums = DoubleArray(dimensions)
        for (token in hiddenStates.indices) {
            if (attentionMask[token] == 0L) continue
            for (dimension in 0 until dimensions) {
                val value = hiddenStates[token][dimension]
                require(value.isFinite()) { "Embedding output is not finite" }
                sums[dimension] += value.toDouble()
            }
        }
        return FloatArray(dimensions) { (sums[it] / attended).toFloat() }
    }

    /** Slice before L2 normalization, then apply the shared fixed clamp/round int8 rule. */
    fun matryoshkaInt8(pooled: FloatArray): ByteArray {
        require(pooled.size >= DIMENSIONS) { "Embedding output has too few dimensions" }
        require(pooled.all { it.isFinite() }) { "Embedding output is not finite" }
        // Double accumulation avoids overflow/underflow for finite float outputs.
        var squaredNorm = 0.0
        for (i in 0 until DIMENSIONS) squaredNorm += pooled[i].toDouble() * pooled[i].toDouble()
        require(squaredNorm > 0.0) { "Embedding output has zero magnitude after truncation" }
        val norm = sqrt(squaredNorm)
        return Int8Quantizer.quantize(FloatArray(DIMENSIONS) { (pooled[it] / norm).toFloat() })
    }
}
