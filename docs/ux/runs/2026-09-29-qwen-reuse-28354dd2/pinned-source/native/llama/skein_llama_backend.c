/*
 * skein_llama_backend.c — the only user-facing symbols libskein_llama.so
 * exports of its own (E1.I4 / bd skein-ca2).
 *
 * Deliberately separate from jni_stub.cpp: that file stays an empty JNI
 * placeholder until E4.I1 (bd skein-3aw) fills it in, while these three
 * accessors are build facts that the isolated inference process needs before
 * any JNI exists — chiefly "which backend was this .so built to prefer".
 *
 * Plain C, no allocation, no state: every return value is a pointer into
 * .rodata that stays valid for the lifetime of the process.
 */
#include "skein_llama_config.h"

/*
 * Backend the service should request by default: "vulkan" or "cpu".
 *
 * This is the *effective* value for the ABI this .so was built for (x86_64
 * always reports "cpu"; it has no Vulkan backend compiled in). Kotlin also
 * sees the *requested* value as `BuildConfig.SKEIN_LLAMA_DEFAULT_BACKEND`;
 * when the two disagree, this one is the truth about the loaded library.
 */
const char *skein_llama_default_backend(void) {
    return SKEIN_LLAMA_DEFAULT_BACKEND;
}

/*
 * 1 when the ggml Vulkan backend is compiled in at all. Says nothing about
 * whether the *device* has a usable Vulkan driver — that is a runtime
 * question for ggml's backend registry, and answering it here would require
 * loading the loader, which this function deliberately does not do.
 */
int skein_llama_vulkan_available(void) {
    return SKEIN_LLAMA_HAS_VULKAN;
}

/* Pinned llama.cpp version, e.g. "0.4.1" — see native/llama/PINNED_COMMIT. */
const char *skein_llama_version(void) {
    return SKEIN_LLAMA_UPSTREAM_VERSION;
}
