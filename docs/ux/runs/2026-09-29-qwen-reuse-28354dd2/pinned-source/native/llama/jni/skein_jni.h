/*
 * skein_jni.h — shared declarations for the llama.cpp JNI bindings
 * (E4.I1, bd skein-3aw).
 *
 * The `Java_…` entry points themselves are deliberately NOT declared here.
 * They are `extern "C"` definitions in `skein_jni.cpp`, and the single place
 * their names are written down is the Kotlin `object LlamaNative` —
 * `tools/ci/jni-symbols.sh` asserts that the `.so`'s `Java_` symbols and that
 * object's `external fun`s are the same set, which a hand-maintained header of
 * prototypes would only let drift.
 *
 * What is here: the error-code vocabulary shared with `LlamaErrorCode.kt`, and
 * the one non-JNI native symbol this module exports.
 */

#ifndef SKEIN_LLAMA_JNI_SKEIN_JNI_H
#define SKEIN_LLAMA_JNI_SKEIN_JNI_H

#include "llama.h"

namespace skein {

/*
 * Must stay in lockstep with `LlamaErrorCode.nativeCode`
 * (inference-service/src/main/kotlin/app/skein/inference/service/
 * LlamaException.kt). Codes are append-only: never renumber one.
 */
enum ErrorCode : int {
    kUnknown = 0,
    kInvalidModel = 1,
    kOutOfMemory = 2,
    kCancelled = 3,
    kContextFull = 4,
    kDecodeFailed = 5,
    kInvalidArgument = 6,
    kTokenizeFailed = 7,
    kTemplateUnsupported = 8,
    kEmbeddingsUnavailable = 9,
};

}  // namespace skein

#ifdef __cplusplus
extern "C" {
#endif

/*
 * `docs/design/LOCK_POLICY_INDEXING.md` §4.5 / §7.7, consumed by E4.I3's
 * `onLocked` path: zero the context's KV cache buffers and only then free it.
 *
 * `llama_free` alone returns the KV pages to the allocator with the session's
 * decrypted prompt and retrieved-context token state still in them, which is
 * the residual-plaintext risk §4.5 closes. The zeroing is
 * `llama_memory_clear(mem, data = true)`, i.e. `ggml_backend_buffer_clear(buf, 0)`
 * over every KV buffer, which works for host and device buffers alike.
 *
 * Exported (rather than static) so the design document's name resolves to a
 * real symbol in `nm -D`, and so a future non-JNI caller in this process can
 * use it. Safe to call with a null `ctx` (no-op).
 *
 * Thread: the caller must guarantee no decode is in flight on `ctx`.
 */
void skein_ctx_free_secure(struct llama_context *ctx);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // SKEIN_LLAMA_JNI_SKEIN_JNI_H
