// skein-3aw (E4.I1) — the one exception type the JNI layer throws.
//
// Vocabulary alignment: the codes below map 1:1 onto the failures
// `app.skein.core.model.InferenceException` / `StopReason` already
// name (E0.I10's locked contract), so `E4.I3`'s AIDL layer can translate
// without inventing a second taxonomy:
//
//   INVALID_MODEL           -> InferenceException.InvalidModel
//   OUT_OF_MEMORY           -> InferenceException.OutOfMemory
//   CANCELLED               -> StopReason.CANCELLED  (onDone, not onError)
//   CONTEXT_FULL            -> StopReason.LENGTH     (KV slot exhausted)
//   DECODE_FAILED / UNKNOWN -> a generic service error
//
// `:core:model` itself is NOT thrown from here: this module is the isolated
// process's native edge and must not couple its JNI error path to the app-side
// engine contract's exception hierarchy (which carries fields like the
// expected/actual sha256 that mean nothing to a native call). Translation is a
// three-line `when` in the service.

package app.skein.inference.service

/**
 * Why a native call failed. Ordinal-independent: [nativeCode] is the integer
 * `skein_jni.cpp` passes across the boundary and is the stable half of the
 * contract — reorder this enum freely, never renumber a code.
 */
enum class LlamaErrorCode(
    val nativeCode: Int,
) {
    /** Anything the native layer could not classify, including an unexpected C++ exception. */
    UNKNOWN(0),

    /** The file is not a loadable GGUF (wrong magic, truncated, unsupported arch). */
    INVALID_MODEL(1),

    /** A native allocation failed — model weights, KV cache, or a scratch buffer. */
    OUT_OF_MEMORY(2),

    /** `setCancelFlag(ctx, true)` aborted an in-flight decode. */
    CANCELLED(3),

    /** `llama_decode` could not find a KV slot: the context is full. */
    CONTEXT_FULL(4),

    /** `llama_decode` returned a fatal error code. */
    DECODE_FAILED(5),

    /** A caller-supplied argument was rejected before any llama.cpp call (empty batch, mismatched arrays). */
    INVALID_ARGUMENT(6),

    /** `llama_tokenize` failed, or the piece/detokenize buffer could not be sized. */
    TOKENIZE_FAILED(7),

    /** The GGUF embeds no chat template, or llama.cpp does not support the one it embeds. */
    TEMPLATE_UNSUPPORTED(8),

    /** The context was not created with `embeddings = true`, or the model has no pooling layer. */
    EMBEDDINGS_UNAVAILABLE(9),
    ;

    companion object {
        /** Maps a native code back to a constant; anything unrecognised becomes [UNKNOWN]. */
        @JvmStatic
        fun fromNative(nativeCode: Int): LlamaErrorCode = entries.firstOrNull { it.nativeCode == nativeCode } ?: UNKNOWN
    }
}

/**
 * Thrown by every `LlamaNative` entry point that fails inside llama.cpp.
 *
 * **The message never contains prompt, token, chunk or document text** (spec
 * §9). `skein_jni.cpp` builds messages from fixed strings plus numeric
 * quantities only — never from a caller-supplied `text`, from a token piece,
 * or from llama.cpp's own error strings, which can quote input. Callers may
 * log [message] at any level without passing it through a redactor.
 *
 * A `0` handle is *not* this exception: it is a caller bug rather than a
 * native failure and surfaces as [IllegalStateException], per the bead.
 */
class LlamaException : RuntimeException {
    val code: LlamaErrorCode

    constructor(code: LlamaErrorCode, message: String) : super(message) {
        this.code = code
    }

    /** Called from `skein_jni.cpp` (`JNI_OnLoad` caches this constructor's id). */
    constructor(nativeCode: Int, message: String) : super(message) {
        this.code = LlamaErrorCode.fromNative(nativeCode)
    }

    override fun toString(): String = "LlamaException($code): $message"
}
