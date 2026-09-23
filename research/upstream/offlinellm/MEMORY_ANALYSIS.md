# MEMORY_ANALYSIS — logits allocation and the wider Android audit

Directive §8, and §7's KV-estimation half. Upstream `jegly/OfflineLLM` @
`e81091e86013c0605381d15a1ad7276a4be0b92b` (tag `5.1.1`), Apache-2.0, reviewed 2026-09-23.
Context-cap policy is in §5; the Resource Planner design is in `INFERENCE_PLANNER_PROPOSAL.md` §6.

**Summary: the logits finding is real, it is one line, and Skein has the bug today at
~297–512 MiB depending on vocabulary size. Everything else in OfflineLLM's memory story is either
already better in Skein or a heuristic that `skein-f9zu` supersedes.**

---

## 1. The 5.1.1 logits change, verbatim

```cpp
    // Cap the logits buffer at a single position. n_outputs_max defaults to
    // n_batch, and n_batch is set to the context size above, so the graph
    // reserves logits for a whole ubatch: n_ubatch(512) * n_vocab * 4 bytes.
    // On a 248k-vocab model that is ~485 MiB of compute buffer to hold outputs
    // that are never read — this wrapper samples one token at a time and only
    // ever asks for the last position's logits (llama_sampler_sample(..., -1)).
    // Large-vocab models made this scale into serious memory pressure on phones.
    // Raise this if multi-token output is ever needed in one decode.
    ctx_params.n_outputs_max = 1;
```
([`LLMInference.cpp#L165-L173`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L165-L173);
added in `5.1.0..5.1.1`.)

---

## 2. Tracing it in Skein's own pinned llama.cpp

Every step verified against `b29c606e…` (v0.4.1), not OfflineLLM's pin.

**Step 1 — the default resolves to `n_batch`:**
```cpp
cparams.n_outputs_max = params.n_outputs_max == 0 || llama_model_has_encoder(&model) ? cparams.n_batch : params.n_outputs_max;
```
([`src/llama-context.cpp#L249`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L249))

**Step 2 — the worst-case graph reserve uses it:**
```cpp
const uint32_t n_tokens = std::min(cparams.n_ctx, cparams.n_ubatch);       // L596
...
const uint32_t n_outputs_pp = std::min(n_tokens, cparams.n_outputs_max);   // L630
auto * gf = graph_reserve(n_tokens, n_seqs, n_outputs_pp, mctx.get(), ...); // L634
```
([`#L596`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L596),
[`#L630`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L630))

`graph_reserve` is what sizes the **compute buffer**; a reserve for 512 outputs means a logits
tensor of `512 × n_vocab × sizeof(float)` inside it, allocated for the context's lifetime.

**Step 3 — the numbers.** Skein sets `n_batch = 512` and clamps `n_ubatch` to it
(`native/llama/jni/skein_jni.cpp:479-482`); `n_ctx` is 16384 by default
(`core/inference/.../InferenceConfig.kt`, `contextLengthCap = 16_384`). So
`n_tokens = min(16384, 512) = 512` and `n_outputs_pp = min(512, 512) = 512`.

| Model | `n_vocab` | `512 × n_vocab × 4` |
|---|---:|---:|
| Qwen 2.5 3B Instruct (the M0 formal candidate) | 151 936 | **297 MiB** |
| Llama-3 class | 128 256 | 251 MiB |
| Gemma 3 / Gemma 4 class | 262 144 | **512 MiB** |
| OfflineLLM's quoted case | ~248 000 | ~485 MiB ✓ |

The last row reproduces OfflineLLM's figure, which confirms the arithmetic and the mechanism.

**Step 4 — Skein cannot use any of it.** `decodePrompt` already asks for last-position logits
only:
```cpp
/* Logits for the final token only: that is all sampleNext reads,
 * and asking for more costs an n_vocab-sized copy per token. */
batch.logits[i] = (is_last_chunk && i == count - 1) ? 1 : 0;
```
(`skein_jni.cpp:781-783`), and `sampleNext` samples with `-1` (`skein_jni.cpp:813`). The output
*buffer* (`buf_output`) is sized dynamically from the outputs actually requested
([`src/llama-context.cpp#L2049-L2072`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L2049-L2072)),
so that part is already fine. **It is the compute-graph reservation that is oversized, and only
`n_outputs_max` controls it.**

**Step 5 — where it is allocated.** Per `VULKAN_ANALYSIS.md` §5, on a Vulkan-capable build the
CPU backend's scheduler buffer type is swapped for the first device's *host* buffer type
([`src/llama-context.cpp#L410-L417`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L410-L417)).
On a phone that is `ggml_backend_vk_host_buffer_type` — so today those hundreds of megabytes are
`vkAllocateMemory` host-visible memory, on a load the user configured as CPU-only.

### Verdict

**Matrix OL-07: ADOPT. One line in `newContext`:** `params.n_outputs_max = 1;` with a comment
pointing at `sampleNext(-1)` and at this document, and a guard for the day Skein wants multi-token
output (speculative decoding, batched eval). **M0.**

This is escalation **E-3**: it is not only an optimisation. Any PSS figure taken from a Skein load
today is inflated by that reservation, and `skein-f9zu`'s estimator — which PP-45/PP-46 says
should include a compute-buffer term — would be calibrated against the inflated number if it is
written first. Order matters: cap, then measure, then model.

### Does the optimisation exist in current llama.cpp?

`n_outputs_max` is an upstream parameter, present in `llama_context_params`
([`include/llama.h#L365-L366`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/include/llama.h#L365))
with the comment *"max outputs in a ubatch (0 = n_batch)"*. So the *capability* is upstream; the
*default* is the problem, and it is a default a JNI caller must override. There is no llama.cpp
version that fixes this for callers who leave it at zero.

### Can Skein accidentally recreate it?

Yes, in three ways, all worth a comment at the fix site:

1. Raising `PROMPT_BATCH_TOKENS` (it feeds `nBatch` at `InferenceService.kt:286`). With
   `n_outputs_max = 1` set, raising it no longer costs logits memory — that is part of the value.
2. Calling `newContext` from a future embedding or reranking path without the cap. Embedding
   contexts set `embeddings = true`, which changes which buffers are live but not this one.
3. Restoring `n_outputs_max = 0` "to match upstream defaults" during a llama.cpp bump. R-6 is the
   guard.

---

## 3. `n_batch = n_ctx` — the bug behind the bug

```cpp
ctx_params.n_ctx = contextSize;
ctx_params.n_batch = contextSize;
```
([`LLMInference.cpp#L158-L159`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L158-L159))

`n_batch = n_ctx` (up to 8192) is why `n_outputs_max`'s default was so damaging for OfflineLLM —
the cap is a workaround for a batch size that should never have been the context size. `n_ubatch`
stays at its 512 default, so the graph reserve is bounded at 512 anyway; the harm is in
`output_ids.resize(n_batch)`
([`src/llama-context.cpp#L2095`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L2095))
and in the logical batch's own bookkeeping.

Skein sets `n_batch = 512` explicitly and clamps `n_ubatch` to it with a precondition comment
(`skein_jni.cpp:479-482`). **Matrix OL-14: REJECT** — Skein is already right, and the reason it
is right should be recorded, because the two parameters look interchangeable and are not.

---

## 4. mmap, mlock, and the `load_mode` migration

**The API change**, which Skein has already made independently:

```cpp
// Upstream replaced the use_mmap/use_mlock booleans with a single load_mode
// enum; the two flags map onto its combinations one-for-one.
if (useMmap) {
    model_params.load_mode = useMlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
} else {
    model_params.load_mode = useMlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
}
```
([`LLMInference.cpp#L118-L124`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L118-L124))

Skein: `params.load_mode = (use_mmap == JNI_TRUE) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;`
(`skein_jni.cpp:365-368`). Same migration, and Skein exposes no `mlock` — correctly, for a
multi-gigabyte model on a phone.

**The default, which is the interesting part:**

```kotlin
// Defaults off: mapping the weights leaves them as file-backed pages the kernel
// can evict under pressure, so the first reply stalls re-faulting them back in.
// Reading the model into anonymous memory once is measurably quicker to first
// token on phones. Users who are tight on RAM can turn it back on.
var useMmap: Boolean
    get() = prefs.getBoolean(KEY_USE_MMAP, false)
```
([`SettingsRepository.kt#L247-L253`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/repository/SettingsRepository.kt#L247-L253))

**mmap OFF by default, for all models, on a TTFT argument.**

### Relationship to PP-41 — *extends*, does not contradict

PocketPal's PP-41 turns mmap off **for repackable quantizations only** (`Q4_0`, `IQ4_NL`),
because the mmapped file and the `CPU_REPACK` buffer coexist and model memory roughly doubles
(upstream #638). OfflineLLM turns it off **for everything**, for an unrelated reason: file-backed
pages are evictable under Android memory pressure, so the first token after a background/foreground
cycle re-faults the weights.

Two independent rationales, same direction. Neither is measured here and neither should be taken
on faith — but `skein-brwf` currently carries only PP-41's quant-conditional rule, and this is
evidence that the *unconditional* default deserves testing too. The trade is explicit and
device-dependent: mmap off costs ~1× model size in anonymous RSS and buys eviction immunity; mmap
on halves RSS on non-repackable quants and risks TTFT stalls.

**Skein today hard-codes `useMmap = true`** at the only call site
(`InferenceService.kt:278`), with `native/llama/README.md` citing spec §6 ("model file mmap'd
read-only"). Note that mmap is also **load-bearing for Skein's security model in a way it is not
for OfflineLLM's**: `loadModelFromFd` maps a descriptor handed over Binder because the isolated
uid cannot open the file by path (`skein_jni.cpp:384-405`). Turning mmap off does not break that —
`LLAMA_LOAD_MODE_NONE` still reads through the same `FILE*` — but it changes the memory profile
completely, and any `skein-brwf` rule must be expressed as a *default* that the isolated service
receives in `LoadRequest`, not as a constant in `InferenceService.kt`.

**Matrix OL-10: REFERENCE.** The finding is the second rationale and the unconditional default,
both of which belong in `skein-brwf`'s rule table as a case to measure. The plumbing change —
`useMmap` becoming a `LoadRequest` field rather than a literal — is a small, separable
prerequisite.

One more data point from the same file:
`ggml_backend_vk_device_get_props` sets `mmap_support = !is_integrated_gpu`
([`ggml-vulkan.cpp#L19132`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19132)),
so an integrated GPU in `model->devices` advertises no mmap support. Skein forces `load_mode`
explicitly so this does not bite today — another reason not to rely on `LLAMA_LOAD_MODE_AUTO`.

---

## 5. KV cache: quantisation and the context cap

**Q8_0 KV**, opt-in:

```cpp
if (kvCacheQ8) {
    // Halves KV-cache memory at long contexts. Requires flash attention,
    // which llama.cpp enables automatically where supported.
    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;
}
```
([`LLMInference.cpp#L174-L179`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L174-L179)),
default off ([`SettingsRepository.kt#L259-L261`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/repository/SettingsRepository.kt#L259)),
labelled "experimental" in the README.

This is PocketPal's **PP-42** (flash-attention × KV-type × backend compatibility matrix, upstream
#481's crash class) with the matrix replaced by a hope that "llama.cpp enables it automatically
where supported". Skein's `skein-brwf` already carries the stronger rule — *never combine a
quantized KV cache with flash attention on the shipped backends*. **Matrix OL-11: REFERENCE.**
Take the 2× KV saving as a known lever for the Resource Planner; do not take the implementation,
and keep `skein-brwf`'s refusal.

**The context cap** — directive §7's core question:

```kotlin
// Clamp the GGUF's declared context to something a phone can actually
// hold. Modern models advertise enormous training contexts — Qwen3.5-2B
// declares 262144 — and honouring that verbatim allocates a KV cache and
// compute buffers far past what the device has. The bigger the weights,
// the less headroom is left for the cache, so the cap tightens with file
// size. An explicit user setting (params.contextSize) still wins.
val fileSizeBytes = File(modelPath).length()
val maxContextBySize = when {
    fileSizeBytes > 2L * 1024 * 1024 * 1024 -> 4096L // >2 GB
    fileSizeBytes > 1L * 1024 * 1024 * 1024 -> 8192L // 1–2 GB
    else -> 8192L
}
val rawContextSize = ggufReader.getContextSize() ?: DefaultParams.CONTEXT_SIZE
val modelContextSize = minOf(rawContextSize, maxContextBySize)
```
([`SmolLM.kt#L53-L66`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L53-L66))

The policy: `min(GGUF-declared, size-ladder)`, explicit user setting overrides, fall back to 2048
when the GGUF has no `<arch>.context_length`. Note the ladder's middle and bottom rungs are the
same value — two branches, two outcomes. It uses **file size as a proxy for weight memory** and
**ignores device RAM, KV layout, head dimensions, cache type and current availability
entirely**. It is the cheapest possible thing that is better than nothing.

**Skein's position:** `InferenceConfig.contextLengthCap = 16_384`, a constant with a KDoc pointer
to `docs/MEASUREMENTS.md`'s future `context_length_cap`. It does not vary with model or device at
all. `ContextBudget` then allocates *within* that cap
(`core/inference/.../ContextBudget.kt`), and `req.contextLength` reaches `newContext(nCtx=…)`
(`InferenceService.kt:285`) → the KV allocation.

So on the specific question §7 asks — *how does model size influence the cap?* — **OfflineLLM has
an answer and Skein has none.** OfflineLLM's answer is crude and should not be copied
(`skein-f9zu` will produce a real estimate), but the *shape* transfers exactly:

1. read the GGUF's declared context;
2. compute a device-and-model ceiling;
3. take the min;
4. let an explicit advanced setting override both.

**Matrix OL-13: ADAPT.** Steps 1–4 are the Resource Planner's contract
(`INFERENCE_PLANNER_PROPOSAL.md` §6); step 2's *content* is `skein-f9zu`'s formula, not a file-size
ladder. The risk of copying the ladder is that it looks finished: a 1.4 GiB Q4_K_M with 28 layers
and a 4 GiB Q4_K_M with 48 layers get the same cap, and neither accounts for whether the device
has 8 GB or 16 GB.

**Failure behaviour.** OfflineLLM refuses *before* decoding:
```cpp
uint32_t contextSize = llama_n_ctx(_ctx);
_nCtxUsed = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
if (_nCtxUsed + _batch.n_tokens > (int) contextSize) {
    throw std::runtime_error("context size reached");
}
```
([`LLMInference.cpp#L381-L385`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L381-L385))
— a string, caught by the JNI wrapper and rethrown as `IllegalStateException`, matched by the UI
on message text. That is PocketPal's **PP-67** exactly (context exhaustion detected by string
matching). Skein has a typed `ErrorCode.CONTEXT_FULL` mapped to `StopReasons.LENGTH`
(`InferenceService.kt:465-466`). **Matrix OL-15: REFERENCE** — second independent instance of
PP-67, which strengthens `skein-r8ah`'s sibling assertion that Skein must never string-match it.

---

## 6. The broader Android memory audit (§8's second half)

| Technique | OfflineLLM 5.1.1 | Skein | Finding |
|---|---|---|---|
| Unnecessary copies at the JNI boundary | `GetStringUTFChars` for every string, released on every path including error paths ([`smollm.cpp#L157-L166`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L157)) | `GetIntArrayRegion` into a `std::vector` for tokens; `JStringToUtf8` for paths | Both correct. Skein's token path avoids a per-token `NewStringUTF`. No change. |
| Model mapping | `load_mode` from a user setting, default NONE (read into anon memory) | `LLAMA_LOAD_MODE_MMAP` hard-coded over a Binder-passed fd | §4 |
| KV allocation | `n_ctx` from the size ladder; optional Q8_0 | `n_ctx` from a constant 16384 | §5 |
| Logits allocation | capped at 1 | **uncapped** | §2 — **E-3** |
| Compute/temp buffers | reduced as a side effect of the logits cap | see §2 | §2 |
| Vulkan buffers | avoided on the CPU path by the device-list fix | **not avoided** | `VULKAN_ANALYSIS.md` §5 — **E-2** |
| Lifecycle leaks | `gguf_context` per load; `strdup`'d chat template; double-`strdup` per turn | handle registry + `handleCount()` | `JNI_ANALYSIS.md` §1 — take the **test**, R-8 |
| Context recreation | full model reload to change any runtime setting | per-request sampling; reload only for load-time params | Skein better. No change. |
| Android memory-pressure handling | `MemoryMonitor` polls `ActivityManager.MemoryInfo` every 3 s and raises warning/critical flags at 85 %/95 % ([`MemoryMonitor.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/MemoryMonitor.kt)) — **advisory only; nothing acts on it**. No `onTrimMemory`, no `ComponentCallbacks2`. | nothing yet | §7 |
| Warm-up | BOS decode then `llama_memory_clear` ([`LLMInference.cpp#L237-L247`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L237-L247)) | none | OL-09, ADAPT, M1 |
| Secure free | none | `skein_ctx_free_secure` (`skein_jni.cpp` group 2) | Skein only. No change. |

### On `MemoryMonitor` and PP-45/PP-46

`ActivityManager.MemoryInfo.availMem` is a *system-wide* figure that includes reclaimable page
cache; on Android it is a poor predictor of whether a specific allocation will succeed, and
polling it every 3 seconds from a `Dispatchers.Default` coroutine that nothing reads is close to
pure cost. **Matrix OL-12: REJECT.**

The relevant finding is the **absence**: OfflineLLM has no memory estimator, no
`largestSuccessfulLoad` ceiling and no fit verdict. A model that will not fit is discovered by
`llama_init_from_model` returning null, surfaced as a dialog. That is the whole policy.

This is the strongest available argument for **`skein-f9zu`** (PP-45/PP-46): two mature,
shipping Android llama.cpp apps were reviewed for this epic, and *neither* predicts memory before
loading. PocketPal has an estimator and a learned ceiling; OfflineLLM has a file-size ladder.
Skein has a constant. The estimator is genuinely differentiating work and nothing upstream does
it for us.

**Ordering, restated:** `skein-f9zu` should land **after** OL-07 (the logits cap) and **after**
OL-01 (the device restriction). Both change the compute-buffer term by hundreds of megabytes, and
an estimator fitted to today's numbers would encode the bug.

### On memory-pressure handling

Neither project implements `onTrimMemory` / `ComponentCallbacks2`. For Skein this is more
tractable than for OfflineLLM, because the model lives in `:inference`: a `TRIM_MEMORY_COMPLETE`
there can unload and let `IsolatedSessionGate` invalidate the session, without touching the
vault. PocketPal's **PP-65** (auto-release on background, reload on foreground) is the design;
nothing in OfflineLLM adds to it. Noted as a gap in both, **not** a new proposal.

---

## 7. What to take

| # | Item | Matrix | Change | Milestone |
|---|---|---|---|---|
| 1 | `n_outputs_max = 1` | OL-07 | one line in `newContext` | **M0** |
| 2 | Warm-up decode + `llama_memory_clear` | OL-09 | ~8 lines, on the worker thread | M1 |
| 3 | `useMmap` as a `LoadRequest` field, then measure off-by-default | OL-10 | plumbing + `skein-brwf` rule | M1/M2 |
| 4 | Context cap as `min(declared, computed, override)` | OL-13 | Resource Planner contract; formula from `skein-f9zu` | M2 |
| 5 | Q8_0 KV as a Planner lever, with `skein-brwf`'s FA refusal intact | OL-11 | design only | M2 |

Tests: R-6 (logits cap regression), R-8 (handle-count leak), R-12 (PSS baseline recorded before
and after OL-07 so the estimator has a clean fit). See `REGRESSION_TEST_PROPOSAL.md`.
