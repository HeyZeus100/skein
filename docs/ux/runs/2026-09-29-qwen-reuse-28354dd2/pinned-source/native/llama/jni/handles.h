/*
 * handles.h — the opaque handle registry behind `LlamaNative`'s `Long`s
 * (E4.I1, bd skein-3aw).
 *
 * WHY NOT RAW POINTERS. The obvious JNI idiom is `reinterpret_cast<jlong>(ptr)`
 * and back. It makes every native entry point a memory-safety hazard: a stale
 * handle (freed, then used), a handle of the wrong kind (a sampler passed where
 * a context belongs), or a value the caller simply made up all dereference
 * something. In a process whose entire reason to exist is isolation, that is
 * the wrong default.
 *
 * Instead a handle is a monotonically increasing token minted here and never
 * reused within a process. Every lookup is a map lookup under a mutex, and a
 * miss — stale, wrong kind, forged, or `0` — is reported to Kotlin as
 * `IllegalStateException`, which is exactly the behaviour the bead's
 * acceptance criteria require of a `0` handle. The cost is one lock and one
 * hash lookup per call, against `llama_decode`'s milliseconds.
 *
 * It also makes `handleCount()` — the acceptance criteria's leak check —
 * trivially correct: it is `map.size()`, not a bookkeeping counter that can
 * drift from reality.
 *
 * THREADING. Every method is safe from any thread. `cancel_flag` lives in the
 * entry rather than in a side table because `setCancelFlag` is called from a
 * different thread than the decode it aborts; `std::atomic<bool>` and a stable
 * entry address are what make that safe. Entries are held by
 * `std::unique_ptr`, so an entry's address does not move when the map rehashes
 * and a raw `Entry*` handed to a `ggml_abort_callback` stays valid until the
 * handle is removed.
 */

#ifndef SKEIN_LLAMA_JNI_HANDLES_H
#define SKEIN_LLAMA_JNI_HANDLES_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace skein {

enum class HandleKind : std::uint8_t {
    kModel = 1,
    kContext = 2,
    kSampler = 3,
};

struct Entry {
    HandleKind kind;
    void *ptr;
    /*
     * A second owned resource whose lifetime is tied to `ptr` (E4.I3, bd
     * skein-nxk). Used by kModel loaded from a descriptor: llama.cpp's
     * FILE*-based loader does NOT take ownership of the stream
     * (llama_file's impl(FILE*) sets owns_fp = false and its destructor only
     * fcloses when that is true), so the FILE* must outlive the model and be
     * closed by us, after llama_model_free. Parking it here rather than in a
     * side map means it cannot be orphaned by a handle that is removed on some
     * other path. nullptr for every other handle kind.
     */
    void *aux;
    /* Only meaningful for kContext; the address is passed to
     * llama_set_abort_callback and must outlive every decode on that context. */
    std::atomic<bool> cancel_flag;

    Entry(HandleKind k, void *p, void *a) : kind(k), ptr(p), aux(a), cancel_flag(false) {}
};

class HandleRegistry {
  public:
    /* Mints a handle for `ptr`, optionally owning `aux` alongside it. Never returns 0. */
    std::int64_t Add(HandleKind kind, void *ptr, void *aux = nullptr) {
        std::lock_guard<std::mutex> guard(mutex_);
        const std::int64_t id = next_id_++;
        entries_.emplace(id, std::make_unique<Entry>(kind, ptr, aux));
        return id;
    }

    /* The entry for `id` if it exists and is of `kind`, else nullptr. */
    Entry *Get(std::int64_t id, HandleKind kind) {
        std::lock_guard<std::mutex> guard(mutex_);
        auto it = entries_.find(id);
        if (it == entries_.end() || it->second->kind != kind) {
            return nullptr;
        }
        return it->second.get();
    }

    /*
     * Detaches `id` and returns the pointer it held, or nullptr if it was not
     * a live handle of `kind`. The caller then destroys the object — removal
     * and destruction are separate so no llama.cpp teardown runs while the
     * registry lock is held.
     */
    void *Remove(std::int64_t id, HandleKind kind, void **aux_out = nullptr) {
        std::lock_guard<std::mutex> guard(mutex_);
        auto it = entries_.find(id);
        if (it == entries_.end() || it->second->kind != kind) {
            if (aux_out != nullptr) {
                *aux_out = nullptr;
            }
            return nullptr;
        }
        void *ptr = it->second->ptr;
        if (aux_out != nullptr) {
            *aux_out = it->second->aux;
        }
        entries_.erase(it);
        return ptr;
    }

    std::size_t Count() {
        std::lock_guard<std::mutex> guard(mutex_);
        return entries_.size();
    }

  private:
    std::mutex mutex_;
    /* 1, not 0: 0 is the "no handle" value Kotlin initialises fields to. */
    std::int64_t next_id_ = 1;
    std::unordered_map<std::int64_t, std::unique_ptr<Entry>> entries_;
};

/* The one registry. Function-local static: no static-init-order problem, and
 * it is intentionally never destroyed — llama.cpp state outliving `main` is
 * not a concern in an Android process that is killed, not unwound. */
inline HandleRegistry &Handles() {
    static HandleRegistry *registry = new HandleRegistry();
    return *registry;
}

}  // namespace skein

#endif  // SKEIN_LLAMA_JNI_HANDLES_H
