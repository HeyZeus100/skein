/*
 * skein_jni.cpp — the llama.cpp JNI bindings (E4.I1, bd skein-3aw).
 *
 * Replaces E1.I4's `jni_stub.cpp`. One translation unit, one `Java_…` entry
 * point per `external fun` in
 * `inference-service/src/main/kotlin/app/skein/inference/service/LlamaNative.kt`,
 * and nothing else: no batching policy, no stop strings, no UTF-8 stream
 * buffering, no sampling defaults. Those are `E4.I3`/`E4.I6` and live in
 * Kotlin, where they are unit-testable without a device.
 *
 * INVARIANTS every entry point holds:
 *
 *   1. Handles are validated through `skein::Handles()` (handles.h). A `0`,
 *      stale, forged or wrong-kind handle throws `IllegalStateException` and
 *      nothing is dereferenced.
 *   2. No C++ exception crosses the JNI frame. Every body is wrapped in
 *      SKEIN_JNI_TRY / SKEIN_JNI_CATCH, which rethrows as `LlamaException`.
 *   3. No message contains caller content. Messages are built from fixed
 *      strings and numbers only — in particular `e.what()` and llama.cpp's own
 *      error strings are NEVER forwarded, because they can quote the input
 *      that failed (spec §9: prompt/chunk text must not be logged at any
 *      level, and an exception message is a log line waiting to happen).
 *   4. Every function's comment names the thread(s) it may be called from.
 *      The Kotlin KDoc says the same thing; this is the copy a C++ reviewer
 *      reads.
 *
 * ABSENT ON PURPOSE: `std::cout`, `printf`, `fprintf`, `__android_log_*`. The
 * only path from this file to logcat is `LlamaNative.onNativeLog`, which goes
 * through `LlamaLogRedactor` (E1.I11) and then `SkeinLog`.
 * `tools/ci/jni-symbols.sh` greps for the first three.
 */

#include <jni.h>

#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <new>
#include <string>
#include <vector>

#include "ggml.h"
#include "handles.h"
#include "llama.h"
#include "skein_jni.h"
#include "utf8.h"

namespace {

using skein::Entry;
using skein::ErrorCode;
using skein::HandleKind;
using skein::Handles;

/* ------------------------------------------------------------------------ */
/* Cached ids (JNI_OnLoad).                                                  */
/* ------------------------------------------------------------------------ */
/* Looking a class up by name from a native thread is not merely slow, it is
 * wrong: FindClass uses the class loader of the *calling* frame, and a thread
 * attached from ggml has no Java frame, so it would resolve against the system
 * loader and miss app classes. Caching global refs in JNI_OnLoad — which runs
 * on the thread that called System.loadLibrary, with the app loader in scope —
 * is the standard fix and is what the acceptance criteria ask for. */

JavaVM *g_vm = nullptr;
jclass g_exception_class = nullptr;   /* LlamaException */
jmethodID g_exception_ctor = nullptr; /* (ILjava/lang/String;)V */
jclass g_illegal_state_class = nullptr;
jclass g_native_class = nullptr;   /* LlamaNative */
jmethodID g_on_log_method = nullptr; /* static void onNativeLog(int, String) */

std::atomic<int> g_secure_free_count{0};

/* ------------------------------------------------------------------------ */
/* Throw helpers.                                                            */
/* ------------------------------------------------------------------------ */

void ThrowLlama(JNIEnv *env, ErrorCode code, const char *message) {
    if (env->ExceptionCheck()) {
        return; /* an earlier JNI call already has one pending */
    }
    jstring jmsg = skein::Utf8ToJString(env, message, std::strlen(message));
    if (jmsg == nullptr || g_exception_class == nullptr || g_exception_ctor == nullptr) {
        return; /* OOM: the JVM has its own pending error */
    }
    auto throwable = static_cast<jthrowable>(
        env->NewObject(g_exception_class, g_exception_ctor, static_cast<jint>(code), jmsg));
    env->DeleteLocalRef(jmsg);
    if (throwable != nullptr) {
        env->Throw(throwable);
        env->DeleteLocalRef(throwable);
    }
}

void ThrowIllegalState(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck() || g_illegal_state_class == nullptr) {
        return;
    }
    env->ThrowNew(g_illegal_state_class, message);
}

/*
 * The uniform "bad handle" failure. The message names the *kind* only —
 * never the numeric handle, which is not sensitive but is also not useful,
 * and never anything the caller passed.
 */
void ThrowBadHandle(JNIEnv *env, const char *kind) {
    std::string msg = "invalid ";
    msg += kind;
    msg += " handle (0, already freed, or not a ";
    msg += kind;
    msg += ")";
    ThrowIllegalState(env, msg.c_str());
}

#define SKEIN_JNI_TRY try {
#define SKEIN_JNI_CATCH(ret)                                                       \
    }                                                                              \
    catch (const std::bad_alloc &) {                                               \
        ThrowLlama(env, ErrorCode::kOutOfMemory, "native allocation failed");       \
        return ret;                                                                \
    }                                                                              \
    catch (const std::exception &) {                                               \
        /* Deliberately not e.what(): llama.cpp error strings quote input. */       \
        ThrowLlama(env, ErrorCode::kUnknown, "native exception in llama.cpp");      \
        return ret;                                                                \
    }                                                                              \
    catch (...) {                                                                  \
        ThrowLlama(env, ErrorCode::kUnknown, "unknown native exception");           \
        return ret;                                                                \
    }

/* ------------------------------------------------------------------------ */
/* Handle resolution.                                                        */
/* ------------------------------------------------------------------------ */

llama_model *ModelOf(JNIEnv *env, jlong handle) {
    Entry *entry = Handles().Get(static_cast<std::int64_t>(handle), HandleKind::kModel);
    if (entry == nullptr) {
        ThrowBadHandle(env, "model");
        return nullptr;
    }
    return static_cast<llama_model *>(entry->ptr);
}

Entry *ContextEntryOf(JNIEnv *env, jlong handle) {
    Entry *entry = Handles().Get(static_cast<std::int64_t>(handle), HandleKind::kContext);
    if (entry == nullptr) {
        ThrowBadHandle(env, "context");
        return nullptr;
    }
    return entry;
}

llama_sampler *SamplerOf(JNIEnv *env, jlong handle) {
    Entry *entry = Handles().Get(static_cast<std::int64_t>(handle), HandleKind::kSampler);
    if (entry == nullptr) {
        ThrowBadHandle(env, "sampler");
        return nullptr;
    }
    return static_cast<llama_sampler *>(entry->ptr);
}

/* ------------------------------------------------------------------------ */
/* Cancellation.                                                             */
/* ------------------------------------------------------------------------ */

/*
 * ggml calls this between graph nodes; returning true aborts the graph and
 * makes llama_decode return 2. `data` is the context's registry Entry, whose
 * address is stable for the life of the handle (handles.h).
 *
 * Thread: a ggml compute thread, inside llama_decode.
 */
bool AbortCallback(void *data) {
    auto *entry = static_cast<Entry *>(data);
    return entry != nullptr && entry->cancel_flag.load(std::memory_order_relaxed);
}

/* ------------------------------------------------------------------------ */
/* Log sink.                                                                 */
/* ------------------------------------------------------------------------ */

/*
 * Thread: whichever ggml/llama thread emitted the line — including threads the
 * JVM has never seen, hence the attach dance. Attaching as a *daemon* matters:
 * a ggml worker that outlives the request must not keep the VM alive.
 *
 * Deliberately no detach: ggml reuses its worker threads across decodes, and
 * detaching on every line would make logging cost a thread attach each time.
 * The threads are pool threads that die with the process.
 */
void LogCallback(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (g_vm == nullptr || g_native_class == nullptr || g_on_log_method == nullptr || text == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    const jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        JavaVMAttachArgs args{JNI_VERSION_1_6, "ggml-log", nullptr};
        if (g_vm->AttachCurrentThreadAsDaemon(&env, &args) != JNI_OK) {
            return;
        }
    } else if (status != JNI_OK) {
        return;
    }
    /* A log callback firing while an exception is pending would clobber it;
     * ggml does log from error paths, so this is not hypothetical. */
    if (env->ExceptionCheck()) {
        return;
    }
    jstring jtext = skein::Utf8ToJString(env, text, std::strlen(text));
    if (jtext == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(g_native_class, g_on_log_method, static_cast<jint>(level), jtext);
    env->DeleteLocalRef(jtext);
    if (env->ExceptionCheck()) {
        /* Never let a logging failure become the caller's exception. */
        env->ExceptionClear();
    }
}

/* ------------------------------------------------------------------------ */
/* Small helpers.                                                            */
/* ------------------------------------------------------------------------ */

/* Reads a jstring metadata value of unknown length. Returns false if absent. */
bool ReadMeta(const llama_model *model, const char *key, std::string *out) {
    const int32_t needed = llama_model_meta_val_str(model, key, nullptr, 0);
    if (needed < 0) {
        return false;
    }
    out->assign(static_cast<std::size_t>(needed) + 1, '\0');
    const int32_t written = llama_model_meta_val_str(model, key, out->data(), out->size());
    if (written < 0) {
        return false;
    }
    out->resize(static_cast<std::size_t>(written));
    return true;
}

}  // namespace

/* ------------------------------------------------------------------------ */
/* skein_ctx_free_secure — LOCK_POLICY_INDEXING.md §4.5.                     */
/* ------------------------------------------------------------------------ */

extern "C" void skein_ctx_free_secure(llama_context *ctx) {
    if (ctx == nullptr) {
        return;
    }
    llama_memory_t memory = llama_get_memory(ctx);
    if (memory != nullptr) {
        /* data = true: clears the metadata AND ggml_backend_buffer_clear(buf, 0)
         * every KV buffer. That memset is the property §4.5 requires; llama_free
         * alone hands those pages back still holding the session's tokens. */
        llama_memory_clear(memory, true);
    }
    llama_free(ctx);
    g_secure_free_count.fetch_add(1, std::memory_order_relaxed);
}

/* ------------------------------------------------------------------------ */
/* JNI_OnLoad / JNI_OnUnload.                                                */
/* ------------------------------------------------------------------------ */

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    g_vm = vm;

    jclass exception_local = env->FindClass("app/skein/inference/service/LlamaException");
    jclass illegal_local = env->FindClass("java/lang/IllegalStateException");
    jclass native_local = env->FindClass("app/skein/inference/service/LlamaNative");
    if (exception_local == nullptr || illegal_local == nullptr || native_local == nullptr) {
        /* FindClass leaves a pending NoClassDefFoundError; returning JNI_ERR
         * with one pending is a checked-JNI abort. */
        env->ExceptionClear();
        return JNI_ERR;
    }

    g_exception_class = static_cast<jclass>(env->NewGlobalRef(exception_local));
    g_illegal_state_class = static_cast<jclass>(env->NewGlobalRef(illegal_local));
    g_native_class = static_cast<jclass>(env->NewGlobalRef(native_local));
    env->DeleteLocalRef(exception_local);
    env->DeleteLocalRef(illegal_local);
    env->DeleteLocalRef(native_local);

    g_exception_ctor = env->GetMethodID(g_exception_class, "<init>", "(ILjava/lang/String;)V");
    g_on_log_method = env->GetStaticMethodID(g_native_class, "onNativeLog", "(ILjava/lang/String;)V");
    if (g_exception_ctor == nullptr || g_on_log_method == nullptr) {
        env->ExceptionClear();
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void * /*reserved*/) {
    /* Unhook the log sink before the class refs go away: a ggml thread logging
     * after this point would otherwise call into a stale global ref. */
    llama_log_set(nullptr, nullptr);
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;
    }
    if (g_exception_class != nullptr) env->DeleteGlobalRef(g_exception_class);
    if (g_illegal_state_class != nullptr) env->DeleteGlobalRef(g_illegal_state_class);
    if (g_native_class != nullptr) env->DeleteGlobalRef(g_native_class);
    g_exception_class = nullptr;
    g_illegal_state_class = nullptr;
    g_native_class = nullptr;
    g_exception_ctor = nullptr;
    g_on_log_method = nullptr;
    g_vm = nullptr;
}

/* ------------------------------------------------------------------------ */
/* Group 1: backend, log sink, model load/free.                              */
/* ------------------------------------------------------------------------ */

/* Thread: any, once, before any worker starts (process-global ggml state). */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_backendInit(JNIEnv *env, jobject /*thiz*/) {
    SKEIN_JNI_TRY
    llama_backend_init();
    SKEIN_JNI_CATCH()
}

/* Thread: any, once, alongside backendInit. llama.cpp's logger state is global
 * and not thread-safe, so this must not race a decode. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_setLogCallback(JNIEnv *env, jobject /*thiz*/) {
    SKEIN_JNI_TRY
    llama_log_set(LogCallback, nullptr);
    SKEIN_JNI_CATCH()
}

/* Thread: the inference worker thread. Blocks for seconds on a large model. */
extern "C" JNIEXPORT jlong JNICALL
Java_app_skein_inference_service_LlamaNative_loadModel(
    JNIEnv *env, jobject /*thiz*/, jstring path, jint n_gpu_layers, jboolean use_mmap) {
    SKEIN_JNI_TRY
    if (path == nullptr) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "model path is null");
        return 0;
    }
    const std::string native_path = skein::JStringToUtf8(env, path);
    if (native_path.empty()) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "model path is empty");
        return 0;
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = n_gpu_layers;
    /* v0.4.1 replaced the `use_mmap` bool with the `load_mode` enum. MMAP is
     * what spec §6 requires ("model file mmap'd read-only"); NONE is the
     * fallback for filesystems where mmap is refused. */
    params.load_mode = (use_mmap == JNI_TRUE) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file(native_path.c_str(), params);
    if (model == nullptr) {
        /* llama.cpp collapses "not a GGUF", "truncated", "unknown arch" and
         * "could not allocate" into one null return. INVALID_MODEL is the
         * honest classification of the common case and is what the acceptance
         * criteria pin; a genuine OOM surfaces as std::bad_alloc above. */
        ThrowLlama(env, ErrorCode::kInvalidModel, "model load failed (not a loadable GGUF)");
        return 0;
    }
    return static_cast<jlong>(Handles().Add(HandleKind::kModel, model));
    SKEIN_JNI_CATCH(0)
}

/*
 * Loads a GGUF from an ALREADY-OPEN descriptor (E4.I3, bd skein-nxk, closing
 * the defect bd skein-lnp2 found).
 *
 * WHY A DESCRIPTOR AND NOT A PATH. The isolated `:inference` process cannot
 * open the model by path — not the real one (app-private, 0400, owned by
 * another uid) and not `/proc/self/fd/<n>` either, because that is a fresh
 * open(2) whose DAC and SELinux checks run against the isolated uid, and
 * AOSP's isolated_app policy denies app_data_file opens outright. The
 * descriptor handed over Binder is the only way in.
 *
 * `llama_model_load_from_file_ptr` takes the stream and llama.cpp maps it via
 * fileno() — no path is resolved anywhere in that path.
 *
 * OWNERSHIP. We dup() first: the caller's fd belongs to its PinnedModelFile,
 * which keeps it open for the model's lifetime and closes it on unload, and
 * fclose() on a stream we did not own would close it underneath the pin.
 * llama.cpp does NOT take ownership of the FILE* (llama_file's impl(FILE*)
 * sets owns_fp = false), so the stream is parked in the handle entry's `aux`
 * and closed by freeModel after llama_model_free.
 *
 * Thread: the inference worker thread. Blocks for seconds on a large model.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_app_skein_inference_service_LlamaNative_loadModelFromFd(
    JNIEnv *env, jobject /*thiz*/, jint fd, jint n_gpu_layers, jboolean use_mmap) {
    SKEIN_JNI_TRY
    if (fd < 0) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "model descriptor is negative");
        return 0;
    }
    const int owned_fd = ::dup(static_cast<int>(fd));
    if (owned_fd < 0) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "could not duplicate the model descriptor");
        return 0;
    }
    std::FILE *file = ::fdopen(owned_fd, "rb");
    if (file == nullptr) {
        ::close(owned_fd);
        ThrowLlama(env, ErrorCode::kInvalidArgument, "could not open a stream over the model descriptor");
        return 0;
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = n_gpu_layers;
    params.load_mode = (use_mmap == JNI_TRUE) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file_ptr(file, params);
    if (model == nullptr) {
        std::fclose(file);
        ThrowLlama(env, ErrorCode::kInvalidModel, "model load failed (not a loadable GGUF)");
        return 0;
    }
    return static_cast<jlong>(Handles().Add(HandleKind::kModel, model, file));
    SKEIN_JNI_CATCH(0)
}

/* Thread: the inference worker thread, with every context of this model freed. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_freeModel(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    SKEIN_JNI_TRY
    void *stream = nullptr;
    void *ptr = Handles().Remove(static_cast<std::int64_t>(handle), HandleKind::kModel, &stream);
    if (ptr == nullptr) {
        ThrowBadHandle(env, "model");
        return;
    }
    llama_model_free(static_cast<llama_model *>(ptr));
    /* After llama_model_free, never before: the mapping llama.cpp holds was
     * made from this stream's descriptor. nullptr for a path-loaded model. */
    if (stream != nullptr) {
        std::fclose(static_cast<std::FILE *>(stream));
    }
    SKEIN_JNI_CATCH()
}

/* ------------------------------------------------------------------------ */
/* Group 2: context lifecycle (incl. the §4.5 secure free).                  */
/* ------------------------------------------------------------------------ */

/* Thread: the inference worker thread. */
extern "C" JNIEXPORT jlong JNICALL
Java_app_skein_inference_service_LlamaNative_newContext(
    JNIEnv *env, jobject /*thiz*/, jlong model_handle, jint n_ctx, jint n_threads, jint n_batch, jboolean embeddings) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return 0;
    }
    if (n_ctx < 0 || n_threads <= 0 || n_batch <= 0) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "n_ctx/n_threads/n_batch out of range");
        return 0;
    }

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(n_ctx);
    params.n_batch = static_cast<uint32_t>(n_batch);
    /* n_ubatch <= n_batch is a llama.cpp precondition; the default (512) can
     * exceed a small n_batch and fails context creation outright. */
    params.n_ubatch = std::min<uint32_t>(params.n_ubatch, static_cast<uint32_t>(n_batch));
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.embeddings = (embeddings == JNI_TRUE);
    /* Pooling is left UNSPECIFIED so the model's own pooling type applies; a
     * causal model then reports NONE and `embed` mean-pools by hand. */

    llama_context *ctx = llama_init_from_model(model, params);
    if (ctx == nullptr) {
        ThrowLlama(env, ErrorCode::kOutOfMemory, "context creation failed (KV cache allocation)");
        return 0;
    }

    const std::int64_t id = Handles().Add(HandleKind::kContext, ctx);
    Entry *entry = Handles().Get(id, HandleKind::kContext);
    /* The abort callback is installed after registration because its user data
     * IS the registry entry — that is what makes setCancelFlag from another
     * thread safe (handles.h). */
    llama_set_abort_callback(ctx, AbortCallback, entry);
    return static_cast<jlong>(id);
    SKEIN_JNI_CATCH(0)
}

namespace {

/* Shared body of freeContext / freeContextSecure. */
void FreeContextSecure(JNIEnv *env, jlong handle) {
    void *ptr = Handles().Remove(static_cast<std::int64_t>(handle), HandleKind::kContext);
    if (ptr == nullptr) {
        ThrowBadHandle(env, "context");
        return;
    }
    skein_ctx_free_secure(static_cast<llama_context *>(ptr));
}

}  // namespace

/* Thread: the inference worker thread, no decode in flight. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_freeContext(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    SKEIN_JNI_TRY
    FreeContextSecure(env, handle);
    SKEIN_JNI_CATCH()
}

/* Thread: the binder thread handling onLocked, after the worker has stopped. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_freeContextSecure(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    SKEIN_JNI_TRY
    FreeContextSecure(env, handle);
    SKEIN_JNI_CATCH()
}

/* ------------------------------------------------------------------------ */
/* Group 3: vocabulary.                                                      */
/* ------------------------------------------------------------------------ */

/* Thread: any (llama.cpp documents the tokenizer API as thread-safe). */
extern "C" JNIEXPORT jintArray JNICALL
Java_app_skein_inference_service_LlamaNative_tokenize(
    JNIEnv *env, jobject /*thiz*/, jlong model_handle, jstring text, jboolean add_bos, jboolean parse_special) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return nullptr;
    }
    if (text == nullptr) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "text is null");
        return nullptr;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const std::string input = skein::JStringToUtf8(env, text);

    /* Worst case is one token per byte, plus BOS/EOS. Sized up front rather
     * than probed with a negative-return round trip: the probe costs a second
     * full tokenization of the prompt. */
    std::vector<llama_token> tokens(input.size() + 2);
    int32_t n = llama_tokenize(vocab, input.data(), static_cast<int32_t>(input.size()), tokens.data(),
                               static_cast<int32_t>(tokens.size()), add_bos == JNI_TRUE, parse_special == JNI_TRUE);
    if (n < 0) {
        tokens.resize(static_cast<std::size_t>(-n));
        n = llama_tokenize(vocab, input.data(), static_cast<int32_t>(input.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), add_bos == JNI_TRUE, parse_special == JNI_TRUE);
        if (n < 0) {
            ThrowLlama(env, ErrorCode::kTokenizeFailed, "tokenization failed");
            return nullptr;
        }
    }

    jintArray result = env->NewIntArray(n);
    if (result == nullptr) {
        return nullptr; /* pending OutOfMemoryError */
    }
    static_assert(sizeof(llama_token) == sizeof(jint), "llama_token must be a 32-bit int for this cast");
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint *>(tokens.data()));
    return result;
    SKEIN_JNI_CATCH(nullptr)
}

/* Thread: any. Returns raw bytes: a piece is frequently a fragment of a UTF-8
 * sequence, and only the caller knows where the sequence ends (Utf8Buffer,
 * E4.I3). Converting here would substitute U+FFFD for every split character. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_skein_inference_service_LlamaNative_tokenToPieceBytes(
    JNIEnv *env, jobject /*thiz*/, jlong model_handle, jint id) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return nullptr;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (id < 0 || id >= llama_vocab_n_tokens(vocab)) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "token id out of range");
        return nullptr;
    }

    std::vector<char> buf(64);
    int32_t n = llama_token_to_piece(vocab, id, buf.data(), static_cast<int32_t>(buf.size()), 0, false);
    if (n < 0) {
        buf.resize(static_cast<std::size_t>(-n));
        n = llama_token_to_piece(vocab, id, buf.data(), static_cast<int32_t>(buf.size()), 0, false);
        if (n < 0) {
            ThrowLlama(env, ErrorCode::kTokenizeFailed, "token_to_piece failed");
            return nullptr;
        }
    }

    jbyteArray result = env->NewByteArray(n);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, n, reinterpret_cast<const jbyte *>(buf.data()));
    return result;
    SKEIN_JNI_CATCH(nullptr)
}

/* Thread: any. */
extern "C" JNIEXPORT jstring JNICALL
Java_app_skein_inference_service_LlamaNative_applyChatTemplate(
    JNIEnv *env, jobject /*thiz*/, jlong model_handle, jobjectArray roles, jobjectArray contents,
    jboolean add_assistant) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return nullptr;
    }
    if (roles == nullptr || contents == nullptr) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "roles/contents is null");
        return nullptr;
    }
    const jsize n_msg = env->GetArrayLength(roles);
    if (n_msg == 0 || n_msg != env->GetArrayLength(contents)) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "roles and contents must be non-empty and the same length");
        return nullptr;
    }

    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        ThrowLlama(env, ErrorCode::kTemplateUnsupported, "GGUF embeds no chat template");
        return nullptr;
    }

    /* Owned copies: the llama_chat_message array holds bare pointers, so the
     * std::strings must outlive the call. */
    std::vector<std::string> storage;
    storage.reserve(static_cast<std::size_t>(n_msg) * 2);
    std::vector<llama_chat_message> messages;
    messages.reserve(static_cast<std::size_t>(n_msg));
    std::size_t total_chars = 0;
    for (jsize i = 0; i < n_msg; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (role == nullptr || content == nullptr) {
            ThrowLlama(env, ErrorCode::kInvalidArgument, "a role or content entry is null");
            return nullptr;
        }
        storage.push_back(skein::JStringToUtf8(env, role));
        storage.push_back(skein::JStringToUtf8(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        total_chars += storage[storage.size() - 2].size() + storage.back().size();
    }
    for (jsize i = 0; i < n_msg; ++i) {
        messages.push_back(llama_chat_message{storage[static_cast<std::size_t>(i) * 2].c_str(),
                                              storage[static_cast<std::size_t>(i) * 2 + 1].c_str()});
    }

    /* llama.cpp's own recommendation is 2x the total message length; the
     * negative return below is the authoritative resize anyway. */
    std::vector<char> buf(total_chars * 2 + 256);
    int32_t n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), add_assistant == JNI_TRUE,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<std::size_t>(n));
        n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), add_assistant == JNI_TRUE, buf.data(),
                                      static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        ThrowLlama(env, ErrorCode::kTemplateUnsupported, "llama.cpp cannot render this chat template");
        return nullptr;
    }
    return skein::Utf8ToJString(env, buf.data(), static_cast<std::size_t>(n));
    SKEIN_JNI_CATCH(nullptr)
}

/* Thread: any. */
extern "C" JNIEXPORT jboolean JNICALL
Java_app_skein_inference_service_LlamaNative_isEog(JNIEnv *env, jobject /*thiz*/, jlong model_handle, jint token) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (token < 0 || token >= llama_vocab_n_tokens(vocab)) {
        return JNI_FALSE;
    }
    return llama_vocab_is_eog(vocab, token) ? JNI_TRUE : JNI_FALSE;
    SKEIN_JNI_CATCH(JNI_FALSE)
}

/* ------------------------------------------------------------------------ */
/* Group 4: decode and sampling.                                             */
/* ------------------------------------------------------------------------ */

namespace {

/*
 * Maps llama_decode's return code onto the error vocabulary, checking the
 * cancel flag first: an aborted graph reports 2, but a decode that failed for
 * another reason while a cancel was pending is still, from the caller's point
 * of view, a cancellation.
 */
void ThrowForDecode(JNIEnv *env, Entry *entry, int32_t rc) {
    if (entry->cancel_flag.load(std::memory_order_relaxed) || rc == 2) {
        ThrowLlama(env, ErrorCode::kCancelled, "decode cancelled");
        return;
    }
    switch (rc) {
        case 1:
            ThrowLlama(env, ErrorCode::kContextFull, "no KV slot for the batch (context full)");
            return;
        case -1:
            ThrowLlama(env, ErrorCode::kInvalidArgument, "invalid batch");
            return;
        default:
            ThrowLlama(env, ErrorCode::kDecodeFailed, "llama_decode failed");
            return;
    }
}

}  // namespace

/* Thread: the inference worker thread that owns ctx, exclusively. */
extern "C" JNIEXPORT jint JNICALL
Java_app_skein_inference_service_LlamaNative_decodePrompt(
    JNIEnv *env, jobject /*thiz*/, jlong ctx_handle, jintArray tokens, jint n_past) {
    SKEIN_JNI_TRY
    Entry *entry = ContextEntryOf(env, ctx_handle);
    if (entry == nullptr) {
        return 0;
    }
    auto *ctx = static_cast<llama_context *>(entry->ptr);
    if (tokens == nullptr) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "tokens is null");
        return 0;
    }
    const jsize n_tokens = env->GetArrayLength(tokens);
    if (n_tokens == 0 || n_past < 0) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "empty token batch or negative n_past");
        return 0;
    }

    std::vector<llama_token> ids(static_cast<std::size_t>(n_tokens));
    env->GetIntArrayRegion(tokens, 0, n_tokens, reinterpret_cast<jint *>(ids.data()));

    const auto n_batch = static_cast<jsize>(llama_n_batch(ctx));
    const jsize chunk_size = std::max<jsize>(1, n_batch);
    llama_batch batch = llama_batch_init(chunk_size, 0, 1);

    jint pos = n_past;
    for (jsize offset = 0; offset < n_tokens; offset += chunk_size) {
        /* Between-chunk check: the ggml abort callback only reaches CPU graphs
         * (llama.h says so), so a Vulkan decode relies on this to bound the
         * cancellation latency to one chunk. */
        if (entry->cancel_flag.load(std::memory_order_relaxed)) {
            llama_batch_free(batch);
            ThrowLlama(env, ErrorCode::kCancelled, "decode cancelled");
            return 0;
        }

        const jsize count = std::min<jsize>(chunk_size, n_tokens - offset);
        const bool is_last_chunk = (offset + count) >= n_tokens;
        batch.n_tokens = static_cast<int32_t>(count);
        for (jsize i = 0; i < count; ++i) {
            batch.token[i] = ids[static_cast<std::size_t>(offset + i)];
            batch.pos[i] = pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            /* Logits for the final token only: that is all sampleNext reads,
             * and asking for more costs an n_vocab-sized copy per token. */
            batch.logits[i] = (is_last_chunk && i == count - 1) ? 1 : 0;
        }

        const int32_t rc = llama_decode(ctx, batch);
        if (rc != 0) {
            llama_batch_free(batch);
            ThrowForDecode(env, entry, rc);
            return 0;
        }
        pos += count;
    }
    llama_batch_free(batch);
    return pos;
    SKEIN_JNI_CATCH(0)
}

/* Thread: the inference worker thread that owns ctx, exclusively. */
extern "C" JNIEXPORT jint JNICALL
Java_app_skein_inference_service_LlamaNative_sampleNext(
    JNIEnv *env, jobject /*thiz*/, jlong ctx_handle, jlong sampler_handle) {
    SKEIN_JNI_TRY
    Entry *entry = ContextEntryOf(env, ctx_handle);
    if (entry == nullptr) {
        return 0;
    }
    llama_sampler *sampler = SamplerOf(env, sampler_handle);
    if (sampler == nullptr) {
        return 0;
    }
    auto *ctx = static_cast<llama_context *>(entry->ptr);
    /* -1 = the last token with logits. llama_sampler_sample accepts the token
     * into the chain itself, so the repetition history advances with it. */
    return static_cast<jint>(llama_sampler_sample(sampler, ctx, -1));
    SKEIN_JNI_CATCH(0)
}

/* Thread: the inference worker thread. */
extern "C" JNIEXPORT jlong JNICALL
Java_app_skein_inference_service_LlamaNative_newSampler(
    JNIEnv *env, jobject /*thiz*/, jfloat temp, jint top_k, jfloat top_p, jfloat min_p, jfloat repeat_penalty,
    jlong seed) {
    SKEIN_JNI_TRY
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(params);
    if (chain == nullptr) {
        ThrowLlama(env, ErrorCode::kOutOfMemory, "sampler chain allocation failed");
        return 0;
    }

    /* A negative seed means "pick one"; LLAMA_DEFAULT_SEED is llama.cpp's
     * own sentinel for that. */
    const auto native_seed =
        (seed < 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(static_cast<uint64_t>(seed) & 0xFFFFFFFFu);

    if (repeat_penalty != 1.0f) {
        /* penalty_last_n = 64: llama.cpp's own default window. freq/present
         * penalties stay off — SamplingParams (E0.I10) exposes neither.
         * n_vocab = 0 because this signature has no model to ask: upstream
         * uses it only to size the *backend* sampling graph's inputs, and this
         * chain is a CPU chain (llama_context_params::samplers is left null),
         * so the field is never read on the path we take. */
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(0, 64, repeat_penalty, 0.0f, 0.0f));
    }

    if (temp <= 0.0f) {
        /* Greedy: no top-k/top-p/min-p, because they are no-ops once argmax
         * decides, and E4.I2's golden-sequence test pins this exact chain. */
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        }
        if (top_p > 0.0f && top_p < 1.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        }
        if (min_p > 0.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
        }
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(native_seed));
    }
    return static_cast<jlong>(Handles().Add(HandleKind::kSampler, chain));
    SKEIN_JNI_CATCH(0)
}

/* Thread: the inference worker thread. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_freeSampler(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    SKEIN_JNI_TRY
    void *ptr = Handles().Remove(static_cast<std::int64_t>(handle), HandleKind::kSampler);
    if (ptr == nullptr) {
        ThrowBadHandle(env, "sampler");
        return;
    }
    llama_sampler_free(static_cast<llama_sampler *>(ptr));
    SKEIN_JNI_CATCH()
}

/* ------------------------------------------------------------------------ */
/* Group 5: embeddings, KV hygiene, cancellation.                            */
/* ------------------------------------------------------------------------ */

/* Thread: the inference worker thread that owns ctx, exclusively. */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_skein_inference_service_LlamaNative_embed(
    JNIEnv *env, jobject /*thiz*/, jlong ctx_handle, jintArray tokens) {
    SKEIN_JNI_TRY
    Entry *entry = ContextEntryOf(env, ctx_handle);
    if (entry == nullptr) {
        return nullptr;
    }
    auto *ctx = static_cast<llama_context *>(entry->ptr);
    if (tokens == nullptr) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "tokens is null");
        return nullptr;
    }
    const jsize n_tokens = env->GetArrayLength(tokens);
    if (n_tokens == 0) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "empty token batch");
        return nullptr;
    }

    const llama_model *model = llama_get_model(ctx);
    const int32_t n_embd = llama_model_n_embd(model);
    if (n_embd <= 0) {
        ThrowLlama(env, ErrorCode::kEmbeddingsUnavailable, "model reports no embedding width");
        return nullptr;
    }

    std::vector<llama_token> ids(static_cast<std::size_t>(n_tokens));
    env->GetIntArrayRegion(tokens, 0, n_tokens, reinterpret_cast<jint *>(ids.data()));

    llama_memory_t memory = llama_get_memory(ctx);
    if (memory != nullptr) {
        llama_memory_clear(memory, true);
    }

    llama_batch batch = llama_batch_init(static_cast<int32_t>(n_tokens), 0, 1);
    batch.n_tokens = static_cast<int32_t>(n_tokens);
    for (jsize i = 0; i < n_tokens; ++i) {
        batch.token[i] = ids[static_cast<std::size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        /* Every token outputs: mean pooling needs them all. */
        batch.logits[i] = 1;
    }
    const int32_t rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        ThrowForDecode(env, entry, rc);
        return nullptr;
    }

    std::vector<float> pooled(static_cast<std::size_t>(n_embd), 0.0f);
    /* `auto`, not `enum llama_pooling_type`: llama.h declares a function and an
     * enum with the same name, and the function hides the type in C++. */
    const auto pooling = llama_pooling_type(ctx);
    if (pooling != LLAMA_POOLING_TYPE_NONE && pooling != LLAMA_POOLING_TYPE_UNSPECIFIED) {
        const float *seq = llama_get_embeddings_seq(ctx, 0);
        if (seq == nullptr) {
            ThrowLlama(env, ErrorCode::kEmbeddingsUnavailable, "no pooled embedding for the sequence");
            return nullptr;
        }
        std::copy(seq, seq + n_embd, pooled.begin());
    } else {
        /* No pooling layer: mean-pool by hand, as llama.cpp's embedding
         * example does for models that do not pool internally. */
        int32_t counted = 0;
        for (jsize i = 0; i < n_tokens; ++i) {
            const float *row = llama_get_embeddings_ith(ctx, i);
            if (row == nullptr) {
                continue;
            }
            for (int32_t d = 0; d < n_embd; ++d) {
                pooled[static_cast<std::size_t>(d)] += row[d];
            }
            ++counted;
        }
        if (counted == 0) {
            ThrowLlama(env, ErrorCode::kEmbeddingsUnavailable,
                       "context has no embedding output (created with embeddings = false?)");
            return nullptr;
        }
        for (float &v : pooled) {
            v /= static_cast<float>(counted);
        }
    }

    double norm_sq = 0.0;
    for (const float v : pooled) {
        norm_sq += static_cast<double>(v) * static_cast<double>(v);
    }
    if (norm_sq > 0.0) {
        const auto inv = static_cast<float>(1.0 / std::sqrt(norm_sq));
        for (float &v : pooled) {
            v *= inv;
        }
    }

    /* The embedded text's KV state is not wanted afterwards — neither for a
     * later generation (it would pollute it) nor at rest (§4.5's spirit). */
    if (memory != nullptr) {
        llama_memory_clear(memory, true);
    }

    jfloatArray result = env->NewFloatArray(n_embd);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, n_embd, pooled.data());
    return result;
    SKEIN_JNI_CATCH(nullptr)
}

/* Thread: the inference worker thread that owns ctx, exclusively. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_kvClear(JNIEnv *env, jobject /*thiz*/, jlong ctx_handle) {
    SKEIN_JNI_TRY
    Entry *entry = ContextEntryOf(env, ctx_handle);
    if (entry == nullptr) {
        return;
    }
    llama_memory_t memory = llama_get_memory(static_cast<llama_context *>(entry->ptr));
    if (memory != nullptr) {
        /* data = true zeroes the buffers as well as dropping the metadata:
         * between turns this is hygiene, not just a reset. */
        llama_memory_clear(memory, true);
    }
    SKEIN_JNI_CATCH()
}

/* Thread: ANY — by design, concurrently with a decode on the worker thread. */
extern "C" JNIEXPORT void JNICALL
Java_app_skein_inference_service_LlamaNative_setCancelFlag(
    JNIEnv *env, jobject /*thiz*/, jlong ctx_handle, jboolean flag) {
    SKEIN_JNI_TRY
    Entry *entry = ContextEntryOf(env, ctx_handle);
    if (entry == nullptr) {
        return;
    }
    entry->cancel_flag.store(flag == JNI_TRUE, std::memory_order_relaxed);
    SKEIN_JNI_CATCH()
}

/* ------------------------------------------------------------------------ */
/* Group 6: metadata and debug accessors.                                    */
/* ------------------------------------------------------------------------ */

/* Thread: any. */
extern "C" JNIEXPORT jstring JNICALL
Java_app_skein_inference_service_LlamaNative_modelMeta(
    JNIEnv *env, jobject /*thiz*/, jlong model_handle, jstring key) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return nullptr;
    }
    if (key == nullptr) {
        ThrowLlama(env, ErrorCode::kInvalidArgument, "key is null");
        return nullptr;
    }
    const std::string native_key = skein::JStringToUtf8(env, key);
    std::string value;
    if (!ReadMeta(model, native_key.c_str(), &value)) {
        return nullptr; /* absent key -> Kotlin null */
    }
    return skein::Utf8ToJString(env, value);
    SKEIN_JNI_CATCH(nullptr)
}

/* Thread: any. */
extern "C" JNIEXPORT jboolean JNICALL
Java_app_skein_inference_service_LlamaNative_modelHasVision(
    JNIEnv *env, jobject /*thiz*/, jlong model_handle) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    /* llama.cpp exposes no "has vision" predicate — the vision tower lives in
     * a separate mmproj GGUF and is loaded by mtmd (E4.I11). What a text GGUF
     * *can* tell us is whether it carries vision metadata, which is what
     * E4.I4's ModelManager needs to decide an mmproj companion is required. */
    const int32_t count = llama_model_meta_count(model);
    std::vector<char> key(256);
    for (int32_t i = 0; i < count; ++i) {
        int32_t n = llama_model_meta_key_by_index(model, i, key.data(), key.size());
        if (n < 0) {
            key.resize(static_cast<std::size_t>(-n) + 1);
            n = llama_model_meta_key_by_index(model, i, key.data(), key.size());
            if (n < 0) {
                continue;
            }
        }
        const std::string name(key.data(), static_cast<std::size_t>(n));
        if (name.rfind("clip.", 0) == 0 || name.find(".vision.") != std::string::npos) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
    SKEIN_JNI_CATCH(JNI_FALSE)
}

/* Thread: any. */
extern "C" JNIEXPORT jint JNICALL
Java_app_skein_inference_service_LlamaNative_modelNEmbd(JNIEnv *env, jobject /*thiz*/, jlong model_handle) {
    SKEIN_JNI_TRY
    llama_model *model = ModelOf(env, model_handle);
    if (model == nullptr) {
        return 0;
    }
    return static_cast<jint>(llama_model_n_embd(model));
    SKEIN_JNI_CATCH(0)
}

/* Thread: any. */
extern "C" JNIEXPORT jint JNICALL
Java_app_skein_inference_service_LlamaNative_handleCount(JNIEnv *env, jobject /*thiz*/) {
    SKEIN_JNI_TRY
    return static_cast<jint>(Handles().Count());
    SKEIN_JNI_CATCH(0)
}

/* Thread: any. */
extern "C" JNIEXPORT jint JNICALL
Java_app_skein_inference_service_LlamaNative_secureFreeCount(JNIEnv *env, jobject /*thiz*/) {
    SKEIN_JNI_TRY
    return static_cast<jint>(g_secure_free_count.load(std::memory_order_relaxed));
    SKEIN_JNI_CATCH(0)
}
