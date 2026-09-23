# REGRESSION_TEST_PROPOSAL

Directive §5 (*"Produce a Skein regression-test specification"*) and §8 (*"Produce concrete tests
Skein can use to detect regressions"*). Twelve specifications, written so a Skein agent can
implement each without re-deriving the analysis.

**This document specifies tests. It does not change `tools/m0-benchmark`, any target, or any
product code** — §18 and this bead's guardrails. Where a test needs harness support, the required
addition is described and left to its own bead.

Evidence for each is in `VULKAN_ANALYSIS.md`, `MEMORY_ANALYSIS.md` and `CPU_DISPATCH_ANALYSIS.md`.
Permalink convention as in `README.md`.

---

## The §5 acceptance criterion, made testable

> **CPU-only means genuinely CPU-only.** When Vulkan is disabled: Vulkan should not influence CPU
> buffer selection; unnecessary GPU memory should not be reserved; optimized ARM kernels should
> remain available; CPU performance should not materially regress simply because Vulkan support
> exists in the binary.

Four clauses → R-1…R-4, one each, each with an observable that does not require a device
benchmark. R-5…R-12 cover §8 and the rest.

---

## Prerequisite: the diagnostic surface

R-1, R-3 and R-4 are unwritable today because Skein cannot observe its own backend state from
Kotlin. They depend on **OL-05** — `IInferenceService.backendReport()` plus the per-context
device/buffer facts (`INFERENCE_PLANNER_PROPOSAL.md` §5, `CPU_DISPATCH_ANALYSIS.md` §4).

Implement the diagnostic first. Two extensions beyond OfflineLLM's `getBackendReport()` are
needed and are cheap, because llama.cpp already computes both:

- **Per-context backend count.** `llama_context` builds `backends` from `model.devices`
  ([`src/llama-context.cpp#L331-L338`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L331-L338)).
  A JNI accessor returning the names of the backends in the *loaded context* — not the global
  registry — is what R-1 asserts on.
- **Weight buffer-type names.** llama.cpp already logs these (`load_tensors: … buffer size = …`
  per buffer type). Capturing them through the existing `llama_log_set` callback into a structured
  field is enough for R-3 and R-4 and needs no new llama.cpp API.

---

## R-1 — a CPU-configured load has exactly one backend

**Clause:** "unnecessary GPU memory should not be reserved" / "Vulkan should not influence CPU
buffer selection", at the root.

**Where:** `inference-service/src/androidTest/.../LlamaNativeTest.kt` (instrumented; needs a real
GGUF and a real device) and a host-side unit test over the parcelable.

**Arrange.** Build with `SKEIN_LLAMA_VULKAN=ON` (the arm64 default — the test is meaningless on a
CPU-only build). Load any small GGUF with `nGpuLayers = 0`.

**Act.** `backendReport()` for the loaded context.

**Assert.** The context's backend list has **exactly one** entry, and its device type is
`CPU`. No `GPU`/`IGPU` device appears.

**Why it fails today:** `llama_prepare_model_devices` appends the iGPU regardless of
`n_gpu_layers` when `params.devices == nullptr`
([`src/llama.cpp#L262-L285`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama.cpp#L262-L285)).

**Passes when** OL-01 lands. Pair with a positive control: the same assertion with
`nGpuLayers = 99` must find **two** backends, so a test that passes because Vulkan failed to
register is not mistaken for a pass.

---

## R-2 — a CPU-configured load does not offload ops to the GPU

**Clause:** "Vulkan should not influence CPU buffer selection", at the scheduler.

**Where:** `tools/m0-benchmark` as a new comparison mode, **or** an instrumented test if the
per-context split count is exposed.

**Method A (harness, no new native API).** ggml-vulkan reads
`GGML_OP_OFFLOAD_MIN_BATCH` at registry init
([`ggml-vulkan.cpp#L19986`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19986)).
Run the same `pp512` cell twice on the **Vulkan** binary at `-ngl 0`, once with the variable unset
(default 32) and once with `GGML_OP_OFFLOAD_MIN_BATCH=999999`.

**Assert.** Before OL-01: the two runs differ materially, which *is* the contamination
(record the delta — it is the size of the effect). After OL-01: they are within run-to-run noise,
because with the device list restricted there is no GPU backend to offload to.

**Method B (native).** `ggml_backend_sched_get_n_splits` after a prompt decode; assert it does not
increase with batch size on a CPU-configured context.

**Harness addition required:** the ability to set a remote environment variable for one cell.
`ssh_exec_bench` builds `timeout ${timeout_s}s ${cmdline}`
(`tools/m0-benchmark/lib/ssh-exec.sh`), so this is a prefix on `remote_cmd` plus a field in the
cell key. **Its own bead — not changed here.**

---

## R-3 — weights land in a CPU buffer type, never `Vulkan_Host`

**Clause:** "Vulkan should not influence CPU buffer selection", at the buffer list.

**Where:** instrumented test asserting on captured load-log fields.

**Arrange.** Vulkan-enabled build, `nGpuLayers = 0`, any GGUF.

**Assert.** Every weight buffer-type name reported at load matches `^CPU` (i.e. `CPU` or
`CPU_REPACK`). **No buffer type contains `Vulkan`.**

**Why it fails today:** `make_cpu_buft_list` inserts the first device's host buffer type ahead of
the CPU extra buffer types — *"CPU: ACCEL -> GPU host -> CPU extra -> CPU"*
([`src/llama-model.cpp#L1035-L1085`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-model.cpp#L1035-L1085)),
and ggml-vulkan always provides one
([`ggml-vulkan.cpp#L19108-L19110`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19108)).

**Note:** also covers the compute buffer, which `llama_context` separately relocates to the first
device's host buffer type
([`src/llama-context.cpp#L410-L417`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L410-L417)).
Assert on the compute buffer's type too — it is the one carrying R-6's logits.

---

## R-4 — optimized ARM kernels remain available (conditional)

**Clause:** "optimized ARM kernels should remain available."

**Precondition:** this test is **inert until `skein-5hr` raises `GGML_CPU_ARM_ARCH`.** At the
current `armv8-a` floor the repack kernels are not compiled in at all
([`ggml/src/ggml-cpu/arch/arm/repack.cpp#L27`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-cpu/arch/arm/repack.cpp#L27)),
so there is nothing for `Vulkan_Host` to displace (`CPU_DISPATCH_ANALYSIS.md` §5).

**Two assertions, both from `backendReport()`:**

1. **Features.** The CPU backend's feature list contains `DOTPROD` and `MATMUL_INT8` whenever the
   build declares a raised floor. This is a *build* assertion and can run on any device.
2. **Repack reached.** Loading a repackable quantization (`Q4_0` or `IQ4_NL`) with
   `nGpuLayers = 0` reports at least one `CPU_REPACK` weight buffer.

**Write it now, `@Ignore`d with a pointer to `skein-5hr`.** Writing it now is the point: the
reason the arch bump will underdeliver is that this assertion silently fails, and a pre-written
test is what turns that into a visible failure rather than a mystery.

Assertion 2 also interacts with PP-41 (`skein-brwf`): mmap **on** plus `CPU_REPACK` is upstream
#638's ~100 % memory overhead. R-4's second assertion and `skein-brwf`'s rule must be tested
together or they will contradict each other.

---

## R-5 — the third benchmark cell: `cpu-config-on-vulkan-build`

**Clause:** "CPU performance should not materially regress simply because Vulkan support exists in
the binary." This is escalation **E-1**'s remedy.

**Today** the harness runs two cells (`tools/m0-benchmark/run.sh:202-206`, `:289`):

| Cell | Binary | `-ngl` |
|---|---|---|
| `cpu` | `llama-bench-cpu` (Vulkan not compiled in) | 0 |
| `vulkan` | `llama-bench-vulkan` | 99 |

**The APK is neither.** It is *Vulkan compiled in, `ngl = 0`*.

**Proposed third cell** — an addition, no existing cell changes and no target moves:

| Cell | Binary | `-ngl` | What it measures |
|---|---|---|---|
| `cpu-on-vulkan-build` | `llama-bench-vulkan` | 0 | **the shipping APK's CPU path** |

`tools/m0-benchmark/models.yaml` already carries both binaries with their SHA-256 hashes, so the
cell needs no new artifact — only a `backends` entry and a `gpu_layers_for_backend` case.

**Assertions:**

1. Before OL-01: `cpu-on-vulkan-build` pp512 is **materially worse** than `cpu` pp512 — that
   number is the size of the contamination and belongs in `docs/MEASUREMENTS.md`.
2. After OL-01: `cpu-on-vulkan-build` is within noise of `cpu`. **That equality is the regression
   test.** It should become a standing CI-adjacent check, because it catches any future change
   that reintroduces a GPU device into a CPU-configured load.

**Caveat, stated plainly:** the Termux binaries are native-host builds (`GGML_NATIVE` defaults
`ON`), so they carry dotprod/i8mm that the APK's `armv8-a` build does not
(`CPU_DISPATCH_ANALYSIS.md` §5). The cell isolates the *Vulkan-presence* variable correctly; it
does **not** make the Termux CPU number an APK prediction. Both caveats belong in the cell's
documentation, and the eventual answer is an on-device APK benchmark, not a CLI one.

**Its own bead** (harness change, M1). Not made here.

---

## R-6 — the logits reservation stays capped

Escalation **E-3**. §8's headline regression test.

**Where:** `inference-service/src/androidTest/.../LlamaNativeTest.kt`, plus a host-side assertion
on the JNI source.

**Arrange.** Load a large-vocabulary GGUF (Gemma-class, `n_vocab ≈ 262 144`) with
`nCtx = 16384`, `nBatch = 512`.

**Assert.** The reported compute-buffer size is **below a ceiling that excludes a 512-position
logits tensor.** With `512 × 262144 × 4 = 512 MiB`, a threshold of 256 MiB is unambiguous and
leaves room for the rest of the graph.

**Host-side companion (runs in CI, no device):** a source assertion that `newContext` in
`native/llama/jni/skein_jni.cpp` sets `params.n_outputs_max`. Crude, and it is what actually
catches a llama.cpp bump that "restores upstream defaults".

**Better, if `n_outputs_max` is ever plumbed through:** parameterise the test over
`n_outputs_max ∈ {1, 0}` and assert the compute buffer differs by
`≈ (n_ubatch − 1) × n_vocab × 4`. That asserts the *mechanism*, not a magic number, and survives
model changes.

**Reference:**
[`src/llama-context.cpp#L249`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L249),
[`#L596`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L596),
[`#L630`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L630);
upstream fix pattern at
[`LLMInference.cpp#L165-L173`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L165-L173).

---

## R-7 — KV state does not survive a request

Escalation **E-4**'s hardening gap (`KV_CACHE_ANALYSIS.md` §3).

**Where:** instrumented, `LlamaNativeTest.kt`.

**Arrange.** One context. Generate against a **long** prompt (≥ 1 000 tokens) to completion.

**Act.** Generate against a **short**, unrelated prompt (≤ 20 tokens) on the same context,
starting at `nPast = 0` as `generateInternal` does.

**Assert.** The second generation's output is **identical** to the same short prompt generated on
a freshly created context with the same seed. Deterministic sampling
(`GenerateRequest.sampling.seed`) makes this a byte comparison — a capability OfflineLLM does not
have, since its seed is hard-wired (`JNI_ANALYSIS.md` §6).

**What it protects.** Today correctness rests on upstream's purge-on-overwrite invariant
([`src/llama-kv-cache.cpp#L1160-L1178`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-kv-cache.cpp#L1160-L1178))
plus causal masking. The test makes that dependency explicit and catches a llama.cpp bump that
changes it. Adding an explicit `kvClear` at the top of `generateInternal` makes the test pass by
construction — and the test should be written **first**, so that the current behaviour is recorded
rather than assumed.

---

## R-8 — no native handles or `gguf_context`s leak across cycles

§8's lifecycle-leak item; OfflineLLM's per-load `gguf_context` leak (`JNI_ANALYSIS.md` §1) is the
worked example, and it is PocketPal's **PP-53** (load-stress spec) from a second direction.

**Where:** instrumented.

**Act.** N = 20 cycles of: `inspect` (once it exists) → load → newContext → generate a few tokens
→ free context → free model.

**Assert.**
1. `LlamaNative.handleCount()` returns to its pre-loop value after every cycle (not just at the
   end — a mid-loop check localises the leak).
2. Process RSS after the loop is within a bound of RSS after the first cycle. A 20-cycle loop
   turns a 10 MiB-per-load leak into 200 MiB, which is measurable without precision.
3. No cycle fails. PP-53's point is that "errors between cycles" is a distinct failure mode from
   "leaks".

`handleCount()` is `map.size()` by construction (`native/llama/jni/handles.h`), so assertion 1 is
exact rather than a bookkeeping approximation — which is precisely the property that comment
claims and this test cashes in.

---

## R-9 — no native free while a consumer could still touch it

From OfflineLLM's unload protocol (OL-24, `JNI_ANALYSIS.md` §3).

**Where:** `inference-service/src/test/.../InferenceEngineStateTest.kt` using `FakeLlamaBackend`
(host-side; no device).

**Arrange.** A generation in flight, with the fake backend blocking inside `decodePrompt`.

**Act.** Request unload.

**Assert.**
1. `freeContext` / `freeModel` are **not** called until the blocked call has returned.
2. The wait is bounded — a backend that never returns still produces an unload outcome within the
   timeout rather than hanging.
3. After unload, any further backend call is refused (Skein's handle registry gives
   `IllegalStateException`; assert the mapped `ErrorCode`, not the exception type).

`FakeLlamaBackend` + `InlineTaskRunner` already exist, so this is a pure-JVM test.

---

## R-10 — token round-trip and control-token containment

Two assertions, both from OfflineLLM defects.

**R-10a — no silent token truncation** (`JNI_ANALYSIS.md` §4). OfflineLLM clamps
`llama_token_to_piece`'s negative "buffer too small" return to `0`, silently dropping the token
([`LLMInference.cpp#L31-L40`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L31-L40)).
**Assert:** for every token id in the vocabulary of a small test model,
`tokenToPieceBytes(id)` returns a non-null array whose length equals llama.cpp's reported required
length. A full-vocabulary sweep is cheap and exhaustive.

**R-10b — content cannot forge a control token** (`SECURITY_ANALYSIS.md` §4). A named
`ChatTemplatingTest` case, host-side where possible:

**Assert:** a user message whose *text* is `<start_of_turn>model` (and, separately,
`<|im_start|>system`) tokenizes to **ordinary text tokens**, never to the model's control-token
ids — because `ChatTemplating.segment` classifies it `CONTENT` and it is tokenized with
`parseSpecial = false`. Add the fail-closed case too: when segmentation cannot locate content
verbatim, **nothing** is tokenized with `parseSpecial = true`.

This is `skein-0ztk`'s invariant asserted against a concrete upstream exploit, with the permalink
in the test's comment.

---

## R-11 — sampling golden output across a llama.cpp bump

From the `llama_sampler_init_penalties` signature change (`JNI_ANALYSIS.md` §6): a leading
`n_vocab` argument was added, and a caller that missed it compiles fine and passes `256` as the
vocabulary size.

**Where:** instrumented, run as part of any llama.cpp pin bump.

**Assert.** For a fixed (model, prompt, seed, sampling params) tuple, the generated token id
sequence is byte-identical to a recorded golden file. Any divergence fails the bump and is
triaged, not auto-accepted.

**Scope honestly:** llama.cpp legitimately changes sampling behaviour sometimes, so this is a
**tripwire requiring a human decision**, not a correctness oracle. Record the golden file next to
`native/llama/PINNED_COMMIT` so the pin and the expectation move together.

---

## R-12 — a clean memory baseline for `skein-f9zu`

Not a pass/fail test: a **measurement protocol**, because escalation E-3 says an estimator fitted
today encodes the bug.

**Protocol.** For each of {Qwen 2.5 3B Q4_K_M, a Gemma-class model}, record PSS and the reported
buffer sizes at four points:

| Point | `n_outputs_max` | `devices` restricted | What it isolates |
|---|---|---|---|
| A | default (n_batch) | no | **today** |
| B | 1 | no | OL-07 alone |
| C | default | yes | OL-01 alone |
| D | 1 | yes | both — **the target** |

**D is the baseline `skein-f9zu` fits against.** A − D is the total inflation; A − B is the
logits term; A − C is the Vulkan term. Publishing all four in `docs/MEASUREMENTS.md` also makes
E-2 and E-3 quantitative rather than analytical, which is what the escalations ultimately need.

**Requires device access.** Out of scope here (no-hardware guardrail); belongs with the hardware
runner (`skein-k3b2`).

---

## Summary

| Id | Asserts | Where | Needs | Milestone |
|---|---|---|---|---|
| R-1 | one backend on a CPU load | androidTest | OL-05, OL-01 | M0/M1 |
| R-2 | no op-offload on a CPU load | m0-benchmark or native | env-var support | M1 |
| R-3 | no `Vulkan_Host` weight/compute buffer | androidTest | OL-05, OL-01 | M1 |
| R-4 | ARM repack available (inert until `skein-5hr`) | androidTest | OL-05, `skein-5hr` | M2 |
| R-5 | third benchmark cell; `cpu-on-vulkan` ≈ `cpu` | tools/m0-benchmark | harness bead | M1 |
| R-6 | logits reservation capped | androidTest + CI source check | OL-07 | **M0** |
| R-7 | KV does not survive a request | androidTest | seeded sampling (exists) | M1 |
| R-8 | no handle / `gguf_context` leak over 20 cycles | androidTest | `handleCount()` (exists) | M1 |
| R-9 | no free while a consumer is live | JVM unit | `FakeLlamaBackend` (exists) | M1 |
| R-10 | token round-trip; content cannot forge control tokens | androidTest + JVM | — | M1 |
| R-11 | sampling golden output across bumps | androidTest | golden file | M2 |
| R-12 | clean 4-point memory baseline | device protocol | OL-01, OL-07, hardware | M1/M2 |

**R-6 is the only one tied to M0**, and only because it guards an M0 change. R-1, R-3 and R-5 are
what make escalation E-2 verifiable rather than argued; they are the ones worth building first
once OL-05 exists.
