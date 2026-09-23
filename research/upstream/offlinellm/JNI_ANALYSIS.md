# JNI_ANALYSIS — the boundary, lifecycle, cancellation, streaming, error propagation

Directive §3. Upstream `jegly/OfflineLLM` @ `e81091e86013c0605381d15a1ad7276a4be0b92b` (tag
`5.1.1`), Apache-2.0, reviewed 2026-09-23. Compared against
`native/llama/jni/{skein_jni.cpp,handles.h,utf8.h}` and
`inference-service/src/main/kotlin/app/skein/inference/service/`.

**Summary: Skein's JNI layer is the stronger of the two on every axis examined. Three items are
worth taking (log capture into the thrown message; `no_perf`; the warm-up decode); one is a
correctness defect in OfflineLLM worth turning into a Skein test; the rest is confirmation that
existing Skein decisions were right.**

---

## 1. Handles and memory ownership

OfflineLLM:

```cpp
auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
llmInference->addChatMessage(messageCstr, roleCstr);
```
([`smollm.cpp#L176-L177`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L176-L177);
the same idiom at `#L184`, `#L190`, `#L196`, `#L204`, `#L217`, `#L229`, `#L236`).

A raw `jlong` cast back to a pointer, unvalidated, at eight entry points. A stale handle, a
forged value, or `0` all dereference. Kotlin's `verifyHandle()`
([`SmolLM.kt#L197-L199`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L197-L199))
checks only `nativePtr != 0L`, and several paths skip even that: `stop()` reads
`nativePtr` into a local and calls `stopCompletion(ptr)` without `verifyHandle`
([`#L149-L152`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L149-L152)),
and `getResponseAsFlow` re-reads `nativePtr` inside the loop after capturing it once
(`#L157-L163`) — a benign-looking race that only survives because `close()` zeroes the field
before freeing (`#L187-L193`, and the comment there says so).

Skein: a `HandleKind`-tagged registry with monotonic, never-reused ids, mutex-guarded lookup, and
a miss — "stale, wrong kind, forged, or `0`" — surfaced as `IllegalStateException`
(`native/llama/jni/handles.h`, header comment). `handleCount()` is `map.size()`, so the leak
check cannot drift. Entries are `unique_ptr`-held so an `Entry*` handed to a
`ggml_abort_callback` stays valid.

**Matrix OL-20: REJECT.** Skein's design is the reason several OfflineLLM footguns cannot exist.
No change. Worth one sentence in `handles.h`'s "WHY NOT RAW POINTERS" comment citing this tag as
the worked example.

### The leak OfflineLLM does have

`GGUFReader.cpp` returns a `gguf_context*` as a `jlong` and **never frees it**
([`GGUFReader.cpp`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/GGUFReader.cpp)
— 41 lines, three entry points, no `gguf_free`, and `GGUFReader.kt` exposes no `close()`). A
`GGUFReader` is constructed on every model registration
([`ModelManager.kt#L257-L260`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L257-L260))
and on every `SmolLM.load`
([`SmolLM.kt#L51-L52`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L51-L52)),
so the leak is per-load, unbounded across a session. It is bounded in size (`no_alloc = true`, so
tensor data is not mapped) but includes the full KV metadata — on a model with a 150k-entry
tokenizer array that is not small.

**Matrix OL-25: REJECT, and turn it into a test.** Skein's equivalent surface is the planned
`inspect` AIDL method (`SKEIN_HUB.md` §3.3), which will hold a `gguf_context` or a `llama_model`
in the isolated process. `handleCount()` already exists for exactly this; R-8 asserts it returns
to its pre-call value after N inspect/load/unload cycles. This is also PocketPal's PP-53
(load-stress spec) arriving from a second direction.

---

## 2. Model and context lifecycle

`LLMInference::loadModel` creates model, context, sampler and the formatted-message buffer in one
call and **throws `std::runtime_error` on either null**
([`LLMInference.cpp#L150-L186`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L150-L186)).
The JNI wrapper catches, releases both `GetStringUTFChars` borrows, `delete`s the half-built
object and throws `IllegalStateException`
([`smollm.cpp#L153-L163`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L153-L163)).
That cleanup is correct, and the ordering (release strings *before* `ThrowNew`) is right.

The destructor is not:

```cpp
LLMInference::~LLMInference() {
    for (llama_chat_message &message: _messages) { free(...role); free(...content); }
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
    if (_sampler) llama_sampler_free(_sampler);
}
```
([`#L451-L459`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L451-L459))

`_chatTemplate` is `strdup`'d when the caller supplies one
([`#L221`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L221))
and never freed — a second, smaller per-load leak. Also `addChatMessage(strdup(_response.data()), "assistant")`
at `#L415` `strdup`s a string that `addChatMessage` then `strdup`s again (`#L263`), leaking the
first copy once per generated turn.

Skein's ordering is the same and its coverage is complete: `freeModel` removes the entry, calls
`llama_model_free`, then `fclose`s the parked stream **after** the free because the mapping was
made from that descriptor (`skein_jni.cpp:840-856` region, and the `aux` field in `handles.h`).
`freeContextSecure` adds a scrub llama.cpp does not offer. **No change.**

**Order note worth keeping:** OfflineLLM frees the *context* before the *model*, which is
required (a context references its model) and which Skein also does — `InferenceService` frees
the context then the model on the load-failure path (`InferenceService.kt:291-293`).

---

## 3. Cancellation

OfflineLLM has **no native cancellation**. `completionLoop()` runs one `llama_decode` and
returns; the loop lives in Kotlin
([`SmolLM.kt#L154-L167`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L154-L167)),
so cancellation granularity is one token — acceptable for `tg`, useless for a long prompt, where
a single `llama_decode` over 512 tokens is the whole latency.

What it does have, and what is genuinely good, is the *unload* protocol
([`InferenceEngine.kt#L105-L124`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/InferenceEngine.kt#L105-L124)):

1. take the lock, cancel `loadJob`, clear `isModelLoaded`;
2. `instance.stop()` **first**, so the blocking JNI call returns promptly;
3. `jobToJoin?.cancel()`;
4. `withTimeoutOrNull(5_000) { jobToJoin?.join() }` — *wait for the coroutine to actually exit*;
5. only then `instance.close()`.

Step 4 is the part most implementations skip, and the 5-second bound on it is the part most
implementations that do it get wrong by making it unbounded. Plus `close()` zeroes `nativePtr`
*before* the native free "so flow loops see it immediately"
([`SmolLM.kt#L187-L193`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L187-L193)).

Skein already has the stronger machinery: an `Entry::cancel_flag` (`std::atomic<bool>`) read by a
`ggml_abort_callback` installed at context creation (`skein_jni.cpp:502`), **plus** an explicit
between-chunk check whose comment names the reason the callback alone is not enough:

> *"the ggml abort callback only reaches CPU graphs (llama.h says so), so a Vulkan decode relies
> on this to bound the cancellation latency to one chunk"* (`skein_jni.cpp:765-767`)

That is a sharper observation than anything in OfflineLLM and it is directly relevant to
`VULKAN_ANALYSIS.md`: it is a second place where "is the Vulkan backend in this context?" changes
behaviour on a CPU-configured load.

**Matrix OL-24: ADAPT the join-with-timeout.** Skein's `InferenceWorker.submitBlocking` already
serialises on one thread, so the shape differs, but the invariant — *never free native memory
until the coroutine that could still touch it has observably exited, with a bounded wait* —
should be an explicit test (R-9), not an emergent property.

---

## 4. Token streaming

OfflineLLM returns a `std::string` per call across JNI, with `"[EOG]"` as the sentinel
([`LLMInference.cpp#L414-L421`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L414-L421),
consumed at [`SmolLM.kt#L161`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L161)).
A sentinel *in the value domain* — a model that emits the literal text `[EOG]` ends the stream.
Skein returns bytes plus a typed `StopReason` over the callback; **no change**.

UTF-8: OfflineLLM accumulates pieces in `_cacheResponseTokens` and only emits when
`_isValidUtf8()` passes, returning `""` otherwise
([`#L431-L438`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L431-L438)).
The validator is a correct hand-rolled length-prefix walk
([`#L350-L377`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L350-L377))
but it rejects overlongs and surrogates only implicitly and will **loop forever accumulating** if
a model emits a genuinely invalid byte, since nothing ever flushes the buffer. Skein's
`Utf8Buffer` + `flush()` on completion (`InferenceService.kt:456-458`) has an explicit tail path.
**Matrix OL-23: REFERENCE.**

`tokenToPiece` is the one place OfflineLLM can silently corrupt output:

```cpp
char buf[256];
int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, special);
if (n < 0) { n = 0; }
return std::string(buf, n);
```
([`#L31-L40`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L31-L40))

llama.cpp returns `-required_size` when the buffer is too small. Clamping to `0` turns that into
a **silently dropped token**. 256 bytes is generous for a piece and the case may be unreachable
in practice, but the failure mode is invisible. Skein's `tokenToPieceBytes` returns a
length-correct byte array. **Matrix OL-22: REJECT**, and worth a Skein test that a token whose
piece exceeds any internal buffer round-trips (R-10).

**Batching.** OfflineLLM coalesces UI updates every 3 tokens with a comment about Compose
recompositions ([`InferenceEngine.kt#L161-L172`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/InferenceEngine.kt#L161-L172))
— a token count, not a time budget, so it is bursty at variable token rates. Skein's
`TokenBatcher` is time- and in-flight-aware, matching PocketPal's PP-63 (~33 Hz). **No change.**

---

## 5. Error propagation, and the one thing worth taking

This is 5.1.x's quiet win. `llama_log_set` installs a static callback that mirrors ggml output to
logcat **and** accumulates WARN/ERROR lines into a capped string:

```cpp
// Keep the first few lines: llama's load errors cascade from specific
// ("missing tensor X") to generic ("failed to load model"), and the
// earliest line is the informative one.
if (!line.empty() && _lastErrorLog.size() < 512) {
    if (!_lastErrorLog.empty()) { _lastErrorLog += " | "; }
    _lastErrorLog += line;
}
```
([`LLMInference.cpp#L74-L88`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L74-L88))

`_lastErrorLog` is cleared at the top of `loadModel` (`#L96`) and folded into the thrown message
(`#L153-L154`, `#L184-L185`). The header comment states the problem it solves: llama.cpp collapses
"unsupported arch", "corrupt file" and "alloc failure" into one null return
([`LLMInference.h#L53-L57`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.h#L53-L57)).

Skein has the identical problem and says so:

> *"llama.cpp collapses 'not a GGUF', 'truncated', 'unknown arch' and 'could not allocate' into
> one null return. INVALID_MODEL is the honest classification of the common case…"*
> (`skein_jni.cpp:369-373`)

Skein has the *pieces* — `llama_log_set` glue (`skein_jni.cpp:341-345`) and `LlamaLogRedactor`
with its level gate and `prompt:`/`text:` stripping — but does not join them: the redactor
forwards to `SkeinLog`, and the thrown `LlamaException` still carries the static string
`"model load failed (not a loadable GGUF)"`.

**Matrix OL-19: ADAPT.** Three specifics, all of which the Skein version must get right and
OfflineLLM's does not:

1. **Keep the first lines, not the last.** The cascade argument is correct and non-obvious.
2. **Cap it** (OfflineLLM: 512 chars). Unbounded accumulation from a hostile GGUF is a memory
   amplifier.
3. **Route it through `LlamaLogRedactor.redact` before it reaches the exception message**, which
   OfflineLLM has no equivalent of — its message goes to a Compose dialog verbatim. Skein's
   message crosses a Binder boundary into `:app`, so the no-raw-content rule (spec §9) applies to
   it exactly as it applies to logs. A model-load error should never be able to carry prompt text
   out of the isolated process; today it cannot because the message is a constant, and that
   property must be preserved by construction when the message becomes dynamic.

This turns Skein's `ErrorCode.INVALID_MODEL` from "something was wrong with the file" into
"missing tensor blk.0.attn_q.weight" without weakening isolation. It is the highest-value
*usability* item in this review.

---

## 6. Sampling

OfflineLLM builds the chain once, at load, in fixed order: penalties (if > 1.0) → top-k → top-p →
min-p → temp → dist
([`LLMInference.cpp#L188-L212`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L188-L212)),
each gated on a sentinel (`topK > 0`, `topP < 1.0f`, `minP > 0.0f`). Seed is hard-wired to
`LLAMA_DEFAULT_SEED` — **no reproducible sampling is possible.** Skein takes a per-request `seed`
(`GenerateRequest.sampling.seed` → `skein_jni.cpp` `newSampler`), which is a prerequisite for any
deterministic evaluation harness. **No change; the difference is worth noting in
`docs/MEASUREMENTS.md` as a capability Skein has and this reference does not.**

The 5.1.1 API catch-up is worth recording because Skein will hit it on its next llama.cpp bump:

```diff
-llama_sampler_chain_add(_sampler, llama_sampler_init_penalties(256, repeatPenalty, 0.0f, 0.0f));
+const int32_t nVocab = llama_vocab_n_tokens(llama_model_get_vocab(_model));
+llama_sampler_chain_add(_sampler, llama_sampler_init_penalties(nVocab, 256, repeatPenalty, 0.0f, 0.0f));
```
(`git diff 5.1.0 5.1.1 -- smollm/src/main/cpp/LLMInference.cpp`, and the result at
[`#L192-L197`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L192-L197)).
`llama_sampler_init_penalties` gained a leading `n_vocab`. A C caller that misses the change
compiles fine and passes `256` as the vocabulary size. Skein's pin is newer than OfflineLLM's, so
Skein is already on the new signature — but this is the class of silent breakage a llama.cpp bump
produces, and it argues for a sampling golden-output test pinned across bumps (R-11).

The `no_perf` flags are free wins Skein is not taking:
`sampler_params.no_perf = true` (`#L189`) and `ctx_params.no_perf = true` (`#L180`).
`llama_context_default_params()` and `llama_sampler_chain_default_params()` both leave perf
accounting **on**, which costs a timing call per sampler invocation and prints a summary on free.
**Matrix OL-08: ADOPT**, two lines in `newContext`/`newSampler`.

---

## 7. Thread handling

`ctx_params.n_threads = nThreads` for generation and
`ctx_params.n_threads_batch = (nThreadsBatch > 0) ? nThreadsBatch : nThreads`
([`LLMInference.cpp#L160-L164`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L160-L164)),
with the rationale in the code:

> *"Prompt processing is compute-bound and scales well across all cores (including efficiency
> cores); generation is memory-bound and prefers the smaller big-core count in nThreads."*

The app supplies `numThreadsBatch = Runtime.getRuntime().availableProcessors()`
([`ModelManager.kt#L180`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L180))
and `numThreads` defaults to `(availableProcessors() / 2).coerceIn(4, 8)`
([`SettingsRepository.kt#L76`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/repository/SettingsRepository.kt#L76)).

Skein sets `params.n_threads_batch = n_threads` — one value for both (`skein_jni.cpp:483-484`).
**Matrix OL-29: ADOPT.** This splits one knob into two with a physical justification, it is free
at the JNI layer (one extra `jint`), and it interacts with `VULKAN_ANALYSIS.md`: once prompt
processing genuinely stays on the CPU, `n_threads_batch` is what determines whether it uses the
Tensor G3's efficiency cores. Note the ordering dependency — measuring `n_threads_batch` before
OL-01 lands would measure the Vulkan path, not the CPU one.

Also relevant, from OfflineLLM's README
([`#L102`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L102)):
*"ggml's own thread pool is used rather than OpenMP, which is what makes the CPU-thread settings
actually take effect and keeps generation off the efficiency cores."* Skein forces
`GGML_OPENMP OFF` (`native/llama/CMakeLists.txt:629`) — **independent corroboration that the
setting is load-bearing, not cosmetic**, and a reason not to "simplify" it away later.
This is PocketPal PP-59's thread heuristic seen from the other end: PP-59 gives a *number*,
OfflineLLM gives the *split*. `skein-brwf` should carry both.

---

## 8. Native crash handling

Neither project installs a signal handler, and neither should — Android's `debuggerd` produces a
better tombstone than an in-process handler can. The difference is what a crash costs:
OfflineLLM loses the app (§3 of `ARCHITECTURE_ANALYSIS.md`); Skein loses `:inference` and the
`IsolatedSessionGate` epoch invalidates the session. **No change.**

`memtagMode="sync"` is OfflineLLM's only memory-safety mitigation
([`AndroidManifest.xml#L24`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/AndroidManifest.xml#L24))
and is the right one for a process whose job is to run a C++ tensor library over untrusted input.
Skein declares it nowhere. **Matrix OL-38: ADOPT for `:inference` and `:embedder`** — see
`SECURITY_ANALYSIS.md` §6 for the GrapheneOS interaction and the cost caveat.

---

## 9. What to take, ranked

| # | Item | Matrix | Effort | Milestone |
|---|---|---|---|---|
| 1 | Log-capture → thrown message, first-lines-kept, capped, redacted | OL-19 | S | M1 |
| 2 | `n_threads_batch` as a separate knob | OL-29 | S | M0/M1 |
| 3 | `no_perf = true` on context and sampler | OL-08 | XS | M1 |
| 4 | Warm-up decode + `llama_memory_clear` after load | OL-09 | S | M1 |
| 5 | Bounded join before native free, as a test | OL-24 | S | M1 |

Everything else in this document is either already better in Skein or a negative example. Nothing
here is M0-blocking; the M0-blocking items are in `VULKAN_ANALYSIS.md` and `MEMORY_ANALYSIS.md`.
