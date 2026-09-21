/*
 * jni_stub.cpp — placeholder for the llama.cpp JNI bindings (E4.I1, bd
 * skein-3aw).
 *
 * E1.I4's job is the *build*: pin llama.cpp, compile it and ggml into
 * libskein_llama.so reproducibly, and prove the llama_* API is exported. The
 * bindings themselves are a separate bead, so this translation unit
 * intentionally declares no `Java_…` native methods and no JNI_OnLoad yet:
 * an empty stub cannot accidentally freeze a JNI contract that E4.I1 has not
 * designed.
 *
 * It is not empty of purpose, though. It is the C++ anchor of the shared
 * library, which:
 *   1. forces the C++ toolchain (and therefore the static libc++ runtime that
 *      llama.cpp and ggml need) into the link, and
 *   2. gives `add_library(skein_llama SHARED …)` a real source file, since the
 *      llama.cpp/ggml archives reach the link through
 *      `-Wl,--whole-archive` rather than as sources.
 *
 * Log redaction (E1.I11 / bd skein-4je): `LlamaLogRedactor.kt` already exists
 * on the Kotlin side. Wiring `llama_log_set` to route ggml/llama log callbacks
 * through it belongs to E4.I1 together with the rest of the JNI, so it is
 * deliberately NOT done here — a half-wired log sink that drops straight to
 * __android_log_write would ship unredacted prompt text in the meantime.
 *
 * When E4.I1 lands, its `Java_…` entry points and `JNI_OnLoad` need no linker
 * changes: `skein_llama.exports.ld` already whitelists `JNI_OnLoad` /
 * `JNI_OnUnload`, and `-Wl,--undefined-version` tolerates their absence until
 * then.
 */

#include <jni.h>

namespace {

/*
 * Touching `jint` keeps <jni.h> genuinely included (rather than an unused
 * header a future tidy-up deletes) and keeps this TU non-empty for linkers
 * that warn on empty objects. Internal linkage plus the version script's
 * `local: *;` means it is not exported.
 */
[[maybe_unused]] constexpr jint kSkeinLlamaJniPlaceholder = 0;

}  // namespace
