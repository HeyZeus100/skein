# EXECUTIVE_RECOMMENDATION

OfflineLLM **5.1.1** (`e81091e86013c0605381d15a1ad7276a4be0b92b`, Apache-2.0), reviewed
2026-09-23. Directive §17's ten questions, then §19. Evidence in the twelve analysis documents;
classifications in `ADOPTION_MATRIX.md` (55 findings: **7 ADOPT, 10 ADAPT, 20 REFERENCE,
18 REJECT**). Escalations lead `README.md`.

---

**1. What should Skein adopt immediately?**

Three things, all small, all verified against Skein's own pinned llama.cpp rather than
OfflineLLM's:

- **OL-01 — restrict `llama_model_params.devices` to CPU+ACCEL when `nGpuLayers <= 0`**
  (`skein_jni.cpp`, both load entry points, ~12 lines). Today Skein's "CPU" configuration on a
  Vulkan-compiled binary still puts the Mali iGPU in `model->devices`, still initialises a Vulkan
  backend into the scheduler, still routes every ≥ 32-token prompt batch through it, and still
  allocates the CPU compute buffer from Vulkan host memory.
- **OL-07 — `ctx_params.n_outputs_max = 1`** (one line). Skein reserves
  `512 × n_vocab × 4` bytes of logits it never reads: **~297 MiB** on Qwen 2.5 3B, **~512 MiB**
  on a Gemma-class vocabulary.
- **OL-05 — a backend/CPU-feature diagnostic over AIDL**. Not a fix but the instrument: Skein
  cannot currently observe, from a release build, which backend a context holds or which ARM
  kernels were compiled in. Every test that would prove the first two depends on it.

**2. What should Skein adapt later?**

`n_threads`/`n_threads_batch` as separate knobs (OL-29); model-load errors carrying llama.cpp's
first WARN/ERROR lines, capped and redacted, instead of a constant string (OL-19); the warm-up
decode and `no_perf` (OL-09, OL-08); the bounded-join unload invariant as a test (OL-24); a raised
`GGML_CPU_ARM_ARCH` as the *simpler equivalent* of the seven-variant plugin ladder (OL-27); the
four-step context-cap shape `min(declared, computed, override)` (OL-13); the
stable-prefix/volatile-suffix KV design (OL-16/OL-17); `--gc-sections` for size (OL-47); the in-app
bench primitive with a thermal gate (OL-54); the CPU retry on GPU load failure, raised above the
IPC boundary (OL-04).

**3. What should only be used as reference?**

Twenty findings, chiefly: the `Vulkan_Host`-versus-`CPU_REPACK` ordering knowledge and the
`no_host` lever (OL-02, OL-03); mmap-off's second rationale (OL-10); the staged-import and
copy-in confirmations (OL-31, OL-34); the Khronos/SPIRV build solutions that independently match
Skein's (OL-50); the Mali field guidance (OL-55); the recorded negative results — no arch flags on
the JNI TU (OL-30), no shader trimming anywhere (OL-53).

**4. What should Skein explicitly avoid?**

Eighteen. The load-bearing ones: parsing GGUF in the process that holds user data (OL-41);
`parse_special = true` over message content and the `<start_of_turn>` template bypass (OL-42 — a
real injection primitive); four-magic-byte validation (OL-32); raw `jlong` pointers as handles
(OL-20); `n_batch = n_ctx` (OL-14); deleting a registry row because a file moved (OL-35); the whole
build-plumbing model — CMake version discovered by scanning the SDK, SPIRV-Headers at unpinned
`HEAD`, no wrapper hash, no CI (OL-48, OL-49, OL-51); `secureDelete` on flash and
`sanitizePrompt`'s silent truncation (OL-44, OL-43); `useLegacyPackaging` (OL-06).

**5. Are any current Skein M0 assumptions wrong?**

One, and it is an assumption of *interpretation*, not of engineering. The M0 harness measures
`llama-bench-cpu` (a binary with Vulkan not compiled in) and `llama-bench-vulkan` at `-ngl 99`.
**The shipping APK is neither**: it is Vulkan-compiled with `gpuLayers = 0` — a third
configuration nobody has measured. Separately, the Termux binaries are native-host builds and
carry DOTPROD/MATMUL_INT8, while the APK is pinned at `armv8-a` with the ARM repack kernels
compiled out entirely. The Termux CPU number is an **upper bound** on the APK, not an estimate
of it. Neither invalidates a target; both change what a cell means.

**6. Are any current M0 benchmarks potentially misleading?**

Yes, in the specific sense above — a reader who takes the `cpu` cell as "what the app will do on
CPU" will be wrong in two independent directions. The remedy is additive, not a target change: a
third cell (`cpu-on-vulkan-build`, R-5) and a labelling note. Memory figures are separately
inflated by OL-07's unread logits reservation, which matters most because `skein-f9zu`'s estimator
would otherwise be fitted to it.

**7. Are there bugs OfflineLLM has already solved that Skein is at risk of reproducing?**

Skein is not *at risk* of two of them — it has them now: the device-list bug (OL-01) and the
logits reservation (OL-07). Both are live in the tree today. Four more that OfflineLLM solved and
Skein has not yet reached: the quadratic prompt re-prefill (OL-16), the uninformative model-load
error (OL-19), the thread split (OL-29), and 16 KiB page alignment (OL-46, which `skein-xth5`
will detect and which OfflineLLM supplies the remedy for).

**8. What work can be deleted or avoided because upstream already solved it?**

Little, and that is itself the finding. The seven-variant CPU ladder is **upstream llama.cpp**
(`GGML_CPU_ALL_VARIANTS`), so Skein need not build dispatch — but it cannot use the ladder either
(it requires `GGML_BACKEND_DL`, which Skein's single-`.so` isolated-process posture rejects), so
the saving is "do not write a dispatcher", replaced by one CMake line. `op_offload` already
implements prompt→GPU / generation→CPU, so §6's hybrid policy is not new machinery to build but an
existing default to configure — a genuinely large reduction in the Inference Planner's scope.
Beyond that: nothing. There is no upstream memory estimator, no bounded GGUF pre-check, no shader
trimming, no provenance model, no reproducible build, and no context rollover. **Skein's remaining
work in those areas has no upstream substitute.**

**9. What are the five highest-value changes for Skein?**

1. **OL-01 — restrict the CPU device list.** Makes "CPU-only" true, and makes every in-app CPU
   measurement meaningful. ~12 lines.
2. **OL-07 — cap `n_outputs_max`.** ~297–512 MiB per context, one line.
3. **OL-05 — the backend/feature diagnostic over AIDL.** Converts "which backend and which kernels
   am I actually running?" from unknowable to asserted; unblocks R-1, R-3, R-4 and the Planner.
4. **OL-29 + OL-27 — the CPU path taken seriously**: split `n_threads_batch` from `n_threads`, and
   resolve the `armv8-a` floor via `skein-5hr`. Sequenced *after* (1), which is what makes the
   measurement honest.
5. **OL-19 — actionable model-load errors.** Turns `INVALID_MODEL` into "missing tensor
   blk.0.attn_q.weight", redacted, without weakening isolation. The largest usability gain per
   line in the review.

**10. Should any of those changes occur before M0 is declared complete?**

**(1) and (2): yes, if M0 will be declared on any in-app number.** Both are single-site, local and
reversible, and both change what a measurement *means* rather than what a target *is*. Declaring
M0 while the app's CPU path silently runs prompt batches on the GPU, and while every context
carries a third of a gigabyte of unread logits, would pin a baseline that has to be re-measured as
soon as either is fixed.

**(3): yes in its minimal form** — enough of a backend report to assert R-1 and R-6. Without it,
"we fixed it" is a claim about source, not about the device.

**(4) and (5): no.** Both are M1. (4) is a `docs/MEASUREMENTS.md` decision with a device-exclusion
consequence and must not be rushed; (5) is pure usability.

**If M0 is instead declared purely on the Termux CLI matrix**, all five can follow M0 — provided
the M0 record states explicitly that its CPU cell does not predict the APK's CPU path, for the two
reasons in answer 5. Either route is defensible; leaving the ambiguity is not.

---

## §19 — What has OfflineLLM already learned that Skein should not rediscover?

Six things, in the order they would cost Skein time:

1. **On Android, "CPU-only" is a device-list decision, not an `n_gpu_layers` decision.** A GPU
   backend in the registry changes buffer selection, scheduler membership and where the compute
   buffer is allocated — even at `n_gpu_layers = 0`. Upstream will not fix this, because for
   desktop dGPUs it is correct behaviour; `params.devices` is the intended escape hatch.
2. **`n_outputs_max` defaults to `n_batch`, and on large-vocabulary models that is hundreds of
   megabytes of logits nobody reads.** A one-token-at-a-time sampler must say so explicitly.
3. **The ARM kernel question is settled upstream and its Android form is a packaging problem.**
   The seven-variant ladder exists; making it work needs `useLegacyPackaging = true` and a
   load-from-`nativeLibraryDir` call. If you cannot pay that (Skein cannot), one `-march` line is
   the whole of the alternative.
4. **You cannot debug any of this without a backend report.** ggml logs its variant scores only
   under `GGML_LOG_DEBUG`, so a release build that fell back to baseline is indistinguishable from
   one that did not. Build the report before the optimisation.
5. **Prompt processing and generation want different resources** — different thread counts,
   plausibly different backends. Treating "backend" as one session-wide setting is the wrong
   shape, and llama.cpp's `op_offload` already implements the split for you.
6. **Link `llama.h`, not `llama-common`.** `common` carries HTTP and download machinery that has
   no place in a zero-network app, and the handful of helpers worth having are twenty lines each.

And one thing Skein should **not** learn from it. OfflineLLM's JNI boundary carries a whole
conversational turn: the chat template, the message history, the stop strings and the UTF-8
reassembly all live in C++. That makes a chat app easy and makes retrieval, editing, token
counting and per-turn sampling impossible. Skein's boundary carries primitives — tokenize, decode
a batch, sample one id — and everything above it is Kotlin. **That is the difference between a
chat app and a knowledge system, and it is the one architectural decision in this comparison that
must not move.** Directive §15, confirmed by counterexample.

---

### What could not be determined

- **Any on-device magnitude.** No adb, no ssh, no benchmark runs (guardrail). Every number here is
  derived from source — `512 × n_vocab × 4` is arithmetic, not a measurement. The size of E-2's
  contamination is unknown until R-5 runs.
- **Whether the Termux `llama-bench-cpu` binary was in fact built with `GGML_NATIVE=ON`.** It is
  the default for a native build and the inference is strong, but the build flags were not
  recorded in the handoff and could not be read from here. Checking it is one `strings`/`--version`
  call on the device and it changes how E-1 is worded.
- **Whether Skein's shipped `.so` files are already 16 KiB-aligned** under NDK 27.3.
  `skein-xth5`'s check answers it in one CI run.
- **The GrapheneOS interaction with `memtagMode="sync"`** — whether it composes with GrapheneOS's
  own per-app memory-tagging controls and hardened allocator, and what `sync` costs on the Tensor
  G3. Both need the device.
- **OfflineLLM's APK and `.so` sizes.** No build was run; upstream publishes no figures. The §14
  size comparison is therefore qualitative.
