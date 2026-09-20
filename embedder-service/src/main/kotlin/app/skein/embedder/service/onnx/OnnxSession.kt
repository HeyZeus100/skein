package app.skein.embedder.service.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Thin wrapper around an ONNX Runtime model + session (E1.I6, spec §4.1).
 *
 * Consumers in `:embedder-service` (the `EmbedPipeline` / `NerPipeline` /
 * `RerankPipeline` landing in E5.I1) go through this class rather than
 * touching `ai.onnxruntime.*` types directly, so the ONNX Runtime API surface
 * is isolated to one place in the module.
 *
 * Session options are fixed for deterministic, on-device CPU execution:
 *  - single-threaded (`intraOpNumThreads` / `interOpNumThreads` = 1): the
 *    embedder process serves synchronous binder calls one at a time
 *    (`Executors.newSingleThreadExecutor`, E5.I1) already, so extra ORT
 *    threads would only add scheduling overhead and battery cost on a phone.
 *  - sequential execution mode, memory-pattern optimization and the CPU
 *    memory arena disabled: avoids ORT's default over-allocation, keeping the
 *    isolated process's memory footprint small and predictable.
 *  - no execution providers beyond the default CPU EP — no NNAPI or other
 *    accelerator that could pull in a GMS/vendor dependency (spec §2.2); v1
 *    is CPU EP only for determinism (byte-identical embeddings across calls).
 */
class OnnxSession private constructor(
    private val session: OrtSession,
) : Closeable {
    val inputNames: Set<String> get() = session.inputNames
    val outputNames: Set<String> get() = session.outputNames

    /**
     * Runs inference for a single float32 input tensor and returns the first
     * output, flattened to a [FloatArray] regardless of its original shape.
     */
    fun runFloat(
        inputName: String,
        shape: LongArray,
        data: FloatArray,
    ): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return flattenToFloatArray(result[0].value)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Loads a model from its serialized bytes with the on-device session options above. */
        fun load(modelBytes: ByteArray): OnnxSession {
            val environment = OrtEnvironment.getEnvironment()
            val options =
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    setMemoryPatternOptimization(false)
                    setCPUArenaAllocator(false)
                }
            return OnnxSession(environment.createSession(modelBytes, options))
        }

        private fun flattenToFloatArray(value: Any?): FloatArray {
            val out = mutableListOf<Float>()
            flattenInto(value, out)
            return out.toFloatArray()
        }

        private fun flattenInto(
            value: Any?,
            out: MutableList<Float>,
        ) {
            when (value) {
                is FloatArray -> value.forEach { out.add(it) }
                is Array<*> -> value.forEach { flattenInto(it, out) }
                is Float -> out.add(value)
                else -> error("OnnxSession.runFloat: unsupported ONNX output element type ${value?.let { it::class }}")
            }
        }
    }
}
