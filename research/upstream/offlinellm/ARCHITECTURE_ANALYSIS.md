# ARCHITECTURE_ANALYSIS — Kotlin → JNI → C++ → llama.cpp → backend → hardware

Directive §3 (the path), §15 (do not become a chat app). Upstream `jegly/OfflineLLM` @
`e81091e86013c0605381d15a1ad7276a4be0b92b` (tag `5.1.1`), Apache-2.0, reviewed 2026-09-23.
JNI-boundary detail is in `JNI_ANALYSIS.md`; this document is the shape of the whole.

---

## 1. The complete path

```
 ┌── process: com.jegly.offlineLLM (ONE process — see §3) ────────────────────┐
 │                                                                            │
 │  ChatScreen.kt (Compose)                                                   │
 │       ↓ ChatViewModel.kt                                                   │
 │  ModelManager.kt ── SettingsRepository (EncryptedSharedPreferences)        │
 │       │             ChatRepository → Room (chat.db, PLAINTEXT)             │
 │       ↓                                                                    │
 │  InferenceEngine.kt   — coroutines, ReentrantLock, stop/cancel policy      │
 │       ↓                                                                    │
 │  SmolLM.kt            — the AAR's Kotlin face; System.loadLibrary("smollm")│
 │       ↓  external fun (11 methods, all instance, all on a raw jlong)       │
 │ ═══════════════════════ JNI boundary ═════════════════════════════════════ │
 │  smollm.cpp           — 11 JNI entry points, thin                          │
 │       ↓                                                                    │
 │  LLMInference.cpp     — the whole engine: load, template, decode, sample   │
 │       ↓  llama.h C API only (llama-common deliberately NOT linked)         │
 │  libllama.a / libggml*.a  (static inside libsmollm.so)                     │
 │       ↓  ggml backend registry, populated by ggml_backend_load_all_from_path│
 │  libggml-cpu-android_*.so  ×7   +   libggml-vulkan.so    (dlopen'd)        │
 │       ↓                                                                    │
 │  ARM64 CPU (one scored variant)  |  Vulkan 1.2 driver → GPU                │
 └────────────────────────────────────────────────────────────────────────────┘

 second native library, same process:
  GGUFReader.kt → libggufreader.so → gguf_init_from_file() on the model file
```

The corresponding Skein path, for contrast:

```
 ┌── :app ───────────────────┐        ┌── :inference (isolatedProcess=true) ──┐
 │ ChatViewModel             │ Binder │ InferenceService.kt                   │
 │ ModelManager              │ ─────► │   ChatTemplating / TokenBatcher /     │
 │ ImmutableModelStore       │  AIDL  │   StopStringMatcher / Utf8Buffer      │
 │ ContextBudget/TokenCounter│  + fd  │      ↓ LlamaBackend → LlamaNative     │
 │ ModelVerifier (2 gates)   │        │ ════ JNI ════ skein_jni.cpp           │
 │ vault (SQLCipher)         │        │   handle registry (typed, generation) │
 └───────────────────────────┘        │      ↓ libskein_llama.so (ONE .so,    │
                                      │        llama+ggml+vulkan static)      │
                                      └───────────────────────────────────────┘
```

Two differences dominate everything below: **Skein has a process boundary where OfflineLLM has a
function call**, and **Skein has one statically linked `.so` where OfflineLLM has a loader and
nine plugins**.

---

## 2. Layer by layer

### 2.1 UI → engine

`ChatViewModel` → `ModelManager.loadModel(modelId, systemPrompt, conversationHistory, …)`
([`ModelManager.kt#L147-L202`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L147-L202)).
Every runtime knob is read from `SettingsRepository` at load time and passed as one
`SmolLM.InferenceParams` value; the settings screen's own comment — and the README — say
*"All performance settings apply the next time a model is loaded"*
([`README.md#L104`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L104)).
There is no live reconfiguration and no attempt at one.

**For Skein:** the same rule is already implicit — `LoadRequest` is immutable and a change means
a reload — but it is worth stating in `SKEIN_HUB.md` §7's Automatic/Advanced split as an explicit
contract rather than an accident. Sampling params, by contrast, are per-request in Skein
(`GenerateRequest.sampling`) and load-time in OfflineLLM (the sampler chain is built inside
`loadModel`, [`LLMInference.cpp#L188-L212`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L188-L212)).
Skein's split is better and should not move: a per-turn temperature is a real product
requirement, and OfflineLLM pays for its choice with a full model reload to change one slider.

### 2.2 Engine → JNI

`InferenceEngine` owns exactly one `SmolLM` instance for the process lifetime
([`InferenceEngine.kt#L19`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/InferenceEngine.kt#L19)),
guarded by a `ReentrantLock` for state transitions and two `@Volatile Job`s (`loadJob`,
`generationJob`). Load and generate each `CoroutineScope(Dispatchers.Default).launch` — a fresh,
unstructured scope per call, with the `Job` kept by hand.

That is the weakest structural choice in the app: `CoroutineScope(...)` without a parent means
cancellation is manual and a leaked job is invisible. Skein's `InferenceWorker` — a single
dedicated thread that owns the context, with `submitBlocking` — is stricter and matches
llama.cpp's actual threading contract better (a `llama_context` is not thread-safe and must not
migrate). **REJECT** OfflineLLM's model; it is a smaller app getting away with it.

### 2.3 JNI → C++

Eleven `external fun` on `SmolLM`
([`SmolLM.kt#L201-L218`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L201-L218)):
`loadModel`, `initBackends`, `getGpuDeviceName`, `getBackendReport`, `addChatMessage`,
`getResponseGenerationSpeed`, `getContextSizeUsed`, `close`, `startCompletion`, `completionLoop`,
`stopCompletion`, `benchModel`. Skein's `LlamaNative` exposes a comparable but finer-grained set
(`tokenize`, `decodePrompt`, `sampleNext`, `tokenToPieceBytes`, `isEog`, `applyChatTemplate`,
`kvClear`, `embed`, …).

**The boundary sits at a different altitude, and that is the most important architectural
observation in this document.**

| | OfflineLLM | Skein |
|---|---|---|
| What crosses | a whole *turn* (`startCompletion(query)` then `completionLoop()` per token) | *primitives* (tokenize, decode a batch, sample one id, id→bytes) |
| Where the chat template is applied | C++ (`llama_chat_apply_template` inside `startCompletion`) | Kotlin-driven, C++-executed, with the render split into scaffold/content spans before tokenizing (`ChatTemplating`) |
| Where stop strings live | C++ hard-coded list + a Kotlin regex pass | Kotlin `StopStringMatcher`, per-request |
| Where UTF-8 is reassembled | C++ (`_cacheResponseTokens` + `_isValidUtf8`) | Kotlin `Utf8Buffer` |
| Where sampling params live | C++, fixed at load | Kotlin, per request |

OfflineLLM's altitude makes a chat app easy and makes anything else impossible: there is no way
to ask it for logits, to insert retrieved context between scaffold and content, to count tokens
without generating, or to run two prompts against one loaded model with different stop sets.
Directive §15 is exactly this risk, and Skein's `native/llama/README.md` §5 already states the
rule ("inference policy … all Kotlin; the JNI layer is a thin translation of types, nothing
more"). **Confirmed by counterexample. Do not raise Skein's JNI altitude.**

### 2.4 C++ → llama.cpp

`LLMInference` holds `llama_model*`, `llama_context*`, `llama_sampler*`, one `llama_batch` used
as a non-owning view (`_batch.token = _promptTokens.data()`,
[`#L340-L341`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L340)),
a `std::vector<llama_chat_message>` of `strdup`'d role/content pairs, and `_prevLen`.

Notably it links **only** `llama.h` — `LLAMA_BUILD_COMMON=OFF`
([`smollm/build.gradle.kts#L87`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L87))
with the reason spelled out in the CMake:

> *"deliberately NOT linking llama-common — it contains upstream's HTTP/download machinery, which
> has no place in a zero-network app. The few helpers the wrapper needed are reimplemented locally
> on the llama.h C API."*
> ([`smollm/src/main/cpp/CMakeLists.txt#L62-L64`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/CMakeLists.txt#L62-L64))

Skein reached the same conclusion independently and goes further, also forcing `LLAMA_OPENSSL`,
`LLAMA_SUBPROCESS`, `GGML_RPC`, `LLAMA_BUILD_UI` and `LLAMA_USE_PREBUILT_UI` off
(`native/llama/CMakeLists.txt:590-604`). **Independent corroboration of a decision Skein already
made.** Worth citing in `native/llama/README.md` §6 as prior art, nothing more.

### 2.5 llama.cpp → backend → hardware

Covered in `VULKAN_ANALYSIS.md` (device selection, scheduler, buffer types) and
`CPU_DISPATCH_ANALYSIS.md` (the seven `ggml-cpu` variants and their load-time scoring). The one
architectural point belonging here: OfflineLLM's `GGML_BACKEND_DL=ON` makes backend
*availability* a runtime fact discovered by scanning `nativeLibraryDir`, which is why
`useLegacyPackaging = true` is mandatory
([`app/build.gradle.kts#L48-L54`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/build.gradle.kts#L48-L54)
— the comment explains that with modern packaging the libs stay compressed in the APK and the
directory is empty, so no backend loads at all). Skein's static build has no such failure mode
and no such directory dependency. **Skein's posture is better for an isolated process** (an
isolated uid `dlopen`ing loose `.so` files is a strictly larger surface) and should not change;
`native/llama/CMakeLists.txt:625-627` already records the reasoning.

---

## 3. The single-process decision, and what it costs OfflineLLM

Everything above runs in one process. `AndroidManifest.xml` declares one `<application>`, one
`<activity>` and **no `<service>` at all**
([`AndroidManifest.xml`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml)).

Consequences, each of which Skein's `:inference` boundary removes:

1. **Untrusted GGUF is parsed in the process holding the user's data.** `gguf_init_from_file`
   ([`GGUFReader.cpp#L10`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/GGUFReader.cpp#L10))
   and `llama_model_load_from_file` both run beside `EncryptedSharedPreferences` and the Room
   chat database. This is PocketPal's PP-16 again, independently.
2. **A native crash takes the app down**, losing the conversation. There is no supervision, no
   restart, and no `IsolatedSessionGate` equivalent.
3. **Model memory is charged to the UI process**, so `onTrimMemory` pressure and the foreground
   LMK policy apply to a 2 GB allocation the user is actively using.
4. **`android:memtagMode="sync"` is doing real work here.** It is the one mitigation present
   ([`AndroidManifest.xml#L24`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml#L24)),
   and it is a good idea Skein has not taken — see `SECURITY_ANALYSIS.md` §6. Skein's isolation
   makes it less necessary and not less valuable.

**No change to Skein.** The comparison's value is that it is a working, shipping app built the
other way, and every one of the four costs above is visible in its code rather than hypothetical.

---

## 4. What would simplify or harden Skein's native layer

Directive §3's actual question. Five items, in descending value:

1. **Restrict the device list on the CPU path** (`VULKAN_ANALYSIS.md` §8, matrix OL-01). Not a
   simplification — a correctness fix Skein is missing. **M0.**
2. **Cap `n_outputs_max`** (`MEMORY_ANALYSIS.md` §2, OL-07). One line,
   ~297–512 MiB. **M0.**
3. **A backend/feature report over the AIDL boundary** (OL-05). Skein cannot today answer "which
   CPU kernels am I running, and is the Vulkan device in this context?" from a release build at
   all. Every regression test in `REGRESSION_TEST_PROPOSAL.md` depends on it existing. **M0/M1.**
4. **`no_perf = true` on the context and the sampler chain** (OL-08,
   [`LLMInference.cpp#L180`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L180)
   and `#L189`). Skein leaves both at their defaults, so llama.cpp accumulates timing state and
   prints a perf summary on free. Free, and it removes a per-token timing call from the hot loop.
   **M1.**
5. **A warm-up decode after load** (OL-09,
   [`#L237-L247`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L237-L247)):
   decode the BOS token once, then `llama_memory_clear`, so backend buffer allocation and (on
   Vulkan) pipeline creation happen during "Loading…" rather than inside the user's first TTFT.
   Adapt with care — it must run on Skein's worker thread inside `submitBlocking`, and the
   `llama_memory_clear` afterwards is not optional. **M1.**

Nothing else in OfflineLLM's native layer is worth importing. Skein's handle registry, abort
callback, between-chunk cancellation, secure context free, log redaction and UTF-8 buffering are
each strictly stronger than the upstream equivalent — see `JNI_ANALYSIS.md`.

---

## 5. Above the inference layer: what §15 protects

OfflineLLM's `app/` module is a chat application and nothing else: Room with `Conversation`,
`Message`, `ModelInfo` (three entities, 61 lines total), five system-prompt personas
([`SystemPrompts.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/SystemPrompts.kt)),
a translator mode, TTS, markdown rendering and 526 lines of theme colours. There is no
retrieval, no embedding, no document model, no graph, no tooling, no CLI and no vault — the chat
database is **plaintext Room**, with encryption applied only to `SharedPreferences`.

Two concrete traps that a copy-first approach would import, both of which Skein's architecture
already refuses:

- **Conversation state lives in C++.** `_messages` is the source of truth for the prompt; the
  Room rows are a UI mirror replayed into the engine on load
  ([`ModelManager.kt#L186-L191`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L186-L191)
  → `InferenceEngine.kt#L86-L88`). Skein's isolated service is deliberately stateless between
  requests; keeping it that way is what lets RAG, editing and regeneration work at all
  (`KV_CACHE_ANALYSIS.md` §4).
- **Model-family knowledge is scattered across four layers.** The `"gemma"` /
  `<start_of_turn>` sniff appears in `loadModel`
  ([`#L224-L233`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L224-L233)),
  again in `startCompletion` (`#L291`), again in the C++ stop list (`#L402-L404`), and again in
  Kotlin (`InferenceEngine.kt#L206-L219`). Four places, no shared constant. Skein's
  `StopStringMatcher` takes its set from `req.sampling.stop`; keep that.

**Directive §15 verdict: adopt at the llama.cpp-interfacing layer only.** Nothing above
`LlamaNative` should come from this repository.
