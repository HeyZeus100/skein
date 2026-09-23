# OfflineLLM 5.1.1 — source-level engineering review

**Authority:** `docs/research/OFFLINELLM_RECON_BRIEF.md` (owner directive, 2026-09-23). Bead: bd
`skein-o5f7`, parent epic `skein-rkrq`. This directory is the §17 deliverable.

---

## ESCALATIONS (directive §18) — read this first

§18 names seven triggers. Each is answered below with **evidence or none**, the SHA-pinned
permalink, and the Skein file it concerns. Three are live. Nothing here was implemented; §18
says document, do not change.

> Every claim in this section was verified by reading **Skein's own pinned llama.cpp**
> (`third_party/llama.cpp` at `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4`, v0.4.1, 2026-09-14),
> not OfflineLLM's (`60eeeb6082c1126bb8bc72902c83123cd056811b`, 2026-08-17). Skein's pin is a
> month **newer** and still exhibits every mechanism described. OfflineLLM is the source of the
> *diagnosis*; the *verification* is against Skein's own tree.

### E-1 — "our benchmark is invalid" → **EVIDENCE (qualified): the harness is sound, its CPU cell does not predict the app**

`tools/m0-benchmark` measures the CPU cell with a **separate CPU-only binary**
(`llama-bench-cpu`, sha `de85775a…`) and the Vulkan cell with `llama-bench-vulkan`
(`cd5b6b4d…`) — `tools/m0-benchmark/models.yaml` `binaries:`, and
`tools/m0-benchmark/run.sh:289` (`gpu_layers_for_backend`: `vulkan→99`, `cpu→0`).

The shipping Skein APK has **one** `libskein_llama.so` with Vulkan statically linked
(`native/llama/CMakeLists.txt:641`, `GGML_VULKAN ${SKEIN_LLAMA_VULKAN}`, default `ON` on
arm64-v8a — `native/llama/CMakeLists.txt:182`) and selects CPU *by configuration*
(`LoadRequest.gpuLayers = 0` → `InferenceService.kt:277` → `skein_jni.cpp:427`).

**"CPU-only build" and "CPU configuration on a Vulkan build" are not the same binary and do not
behave the same.** The difference is exactly E-2. The harness therefore remains valid for
**backend selection** (its stated purpose, handoff §14/§22) and is **not** a valid predictor of
the in-app CPU path.

A second, independent divergence: the Termux binaries are *native host* builds, so
`GGML_NATIVE` defaults `ON` and `-march` is probed from the Tensor G3 — they get
`DOTPROD`/`MATMUL_INT8`. Skein's APK forces `GGML_NATIVE OFF`
(`native/llama/CMakeLists.txt:623`) and leaves `GGML_CPU_ARM_ARCH` at the NDK default
`armv8-a` (`native/llama/README.md` §5). The ARM repack GEMM kernels are compile-time gated on
`__ARM_FEATURE_DOTPROD` / `__ARM_FEATURE_MATMUL_INT8`
([`ggml/src/ggml-cpu/arch/arm/repack.cpp#L27`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-cpu/arch/arm/repack.cpp#L27)),
so in the APK they are **not compiled in at all**. The Termux CPU number is an upper bound on
the APK's CPU number, not an estimate of it.

**Skein files concerned:** `tools/m0-benchmark/models.yaml`, `tools/m0-benchmark/run.sh`,
`native/llama/CMakeLists.txt` §6, `docs/Handoffs/skein-fold-m0-hardware-handoff.md` §7/§9/§14.
**Remedy:** `REGRESSION_TEST_PROPOSAL.md` R-1 and R-5. Not a target change; a labelling and
coverage change.

### E-2 — "Vulkan/CPU measurements are contaminated" → **EVIDENCE (live in Skein's in-app path)**

OfflineLLM 5.1.1's headline fix
([`LLMInference.cpp#L126-L149`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L126-L149))
restricts `llama_model_params.devices` to CPU/ACCEL devices whenever `n_gpu_layers <= 0`. Its
stated reasons are three. All three are **verified true in Skein's pinned llama.cpp**:

1. **The Vulkan backend is initialised and added to the scheduler at `n_gpu_layers = 0`.**
   `llama_prepare_model_devices` only consults `n_gpu_layers` to decide *layer placement*, never
   *device membership*; with `params.devices == nullptr` an integrated GPU is appended to
   `model->devices` unconditionally
   ([`src/llama.cpp#L262-L285`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama.cpp#L262-L285)),
   and `llama_context` then does `for (const auto & dev : model.devices) ggml_backend_dev_init(...)`
   ([`src/llama-context.cpp#L331-L338`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L331-L338)).
2. **Large batches are then offloaded to it.** `cparams.op_offload` defaults to `true`
   ([`src/llama-context.cpp#L3651`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L3651)),
   the scheduler honours it
   ([`ggml/src/ggml-backend.cpp#L970-L973`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-backend.cpp#L970-L973)),
   and ggml-vulkan's threshold is **32 tokens**
   ([`ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19823-L19827`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19823-L19827)
   and `#L19986`). Skein's `PROMPT_BATCH_TOKENS = 512`
   (`inference-service/.../InferenceService.kt:780`) is **16× that threshold**: every prompt
   chunk qualifies.
3. **The CPU scheduler buffer is moved into Vulkan host memory, and the CPU repack buffer type
   is displaced.** `make_cpu_buft_list` documents its own order as *"ACCEL → GPU host → CPU
   extra → CPU"* and inserts the first device's host buffer type ahead of the repack ("extra")
   buffer types
   ([`src/llama-model.cpp#L1035-L1085`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-model.cpp#L1035-L1085));
   ggml-vulkan always supplies one
   ([`ggml-vulkan.cpp#L19108-L19110`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19108-L19110)).
   Separately, `llama_context` replaces the CPU backend's scheduler buft with the first device's
   host buft whenever `model.devices` is non-empty
   ([`src/llama-context.cpp#L410-L417`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L410-L417)).

**Skein passes `n_gpu_layers` and nothing else** — `params.devices`, `params.no_host` and
`params.use_extra_bufts` are left at their defaults (`nullptr`, `false`, `true`) at
`native/llama/jni/skein_jni.cpp:363-368` and `:426-428`. Skein therefore reproduces effects 1
and 3 exactly and effect 2 at every prompt chunk.

Effect 3's *repack* half is currently **dormant** in Skein only because the ARM repack kernels
are not compiled in at `armv8-a` (see E-1). It becomes live the moment `skein-5hr` raises
`GGML_CPU_ARM_ARCH` — i.e. the ordering trap is latent and will bite precisely during the CPU
optimisation it would otherwise reward.

**Skein files concerned:** `native/llama/jni/skein_jni.cpp` (`loadModel`, `loadModelFromFd`),
`inference-service/src/main/kotlin/app/skein/inference/service/InferenceService.kt`,
`native/llama/CMakeLists.txt` §6. **Full analysis:** `VULKAN_ANALYSIS.md`. **Tests:**
`REGRESSION_TEST_PROPOSAL.md` R-1…R-4.

### E-3 — "memory measurements are misleading" → **EVIDENCE (live)**

`llama_context_params.n_outputs_max` defaults to `0`, which llama.cpp resolves to `cparams.n_batch`
([`src/llama-context.cpp#L249`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L249)).
The worst-case compute-graph reserve then uses
`n_outputs_pp = min(n_tokens, cparams.n_outputs_max)` with
`n_tokens = min(n_ctx, n_ubatch)`
([`#L596`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L596)
and [`#L630`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L630)).

Skein sets `n_batch = 512` and clamps `n_ubatch` to it (`skein_jni.cpp:479-482`), and never
touches `n_outputs_max`. So Skein reserves a logits tensor for **512 positions** in the compute
buffer on every context:

| Model | `n_vocab` | Reserved, never read |
|---|---:|---:|
| Qwen 2.5 3B (the M0 candidate) | 151 936 | **297 MiB** |
| Gemma 3/4 class | 262 144 | **512 MiB** |

Skein already asks for last-position logits only — `skein_jni.cpp:783`
(`batch.logits[i] = (is_last_chunk && i == count - 1)`) — and samples with
`llama_sampler_sample(sampler, ctx, -1)` (`:813`). The reservation is pure waste. OfflineLLM
caps it with one line
([`LLMInference.cpp#L165-L173`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L165-L173)),
quoting ~485 MiB on a 248k-vocab model — consistent with the table above.

**Why this is an escalation and not just an optimisation:** any RSS/PSS figure taken from a
Skein load today is inflated by 0.3–0.5 GiB of buffer that the code cannot use, and
`skein-f9zu`'s memory estimator (PP-45/PP-46) would be **calibrated against that inflated
baseline** if it is written before the cap lands. That bakes the error into the model.

**Skein files concerned:** `native/llama/jni/skein_jni.cpp` (`newContext`), bd `skein-f9zu`.
**Full analysis:** `MEMORY_ANALYSIS.md`. **Test:** R-6.

### E-4 — "current native code has a serious correctness issue" → **NO CONFIRMED DEFECT; one hardening gap named**

Reviewed `native/llama/jni/skein_jni.cpp` in full against OfflineLLM's equivalents. Skein's JNI
is the stronger of the two in every respect examined (typed handle registry with kind tags vs
OfflineLLM's raw `reinterpret_cast<LLMInference*>(jlong)`
([`smollm.cpp#L176`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L176));
length-returning `tokenToPieceBytes` vs OfflineLLM's fixed 256-byte stack buffer with the
negative return silently clamped to zero
([`LLMInference.cpp#L31-L40`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L31-L40));
an abort callback plus a between-chunk cancel check that OfflineLLM has no analogue for).

**The gap, not a defect:** `InferenceService.generateInternal` starts every request at
`nPast = 0` (`InferenceService.kt:387`) and never calls the `kvClear` that exists in the backend
interface (`LlamaBackend.kt:104`, `skein_jni.cpp:1001`). Correctness then rests on upstream's
implicit purge-on-overwrite invariant
([`src/llama-kv-cache.cpp#L1160-L1178`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-kv-cache.cpp#L1160-L1178))
plus causal masking of higher-positioned stale cells. I read that code and believe it holds; I
could **not** prove it on device (no-hardware guardrail), and Skein has no test that asserts it.
Recommendation: an explicit `kvClear` at the top of `generateInternal` and test R-7. Filed as a
matrix row (OL-16/OL-17 context), **not** escalated as a defect.

### E-5 — "current architecture creates a security vulnerability" → **NO EVIDENCE against Skein; one vulnerability found in OfflineLLM**

Nothing in OfflineLLM 5.1.1 revealed a weakness in Skein's architecture. The comparison runs the
other way on every axis (isolated `:inference` process with `isolatedProcess="true"`
vs OfflineLLM's single process; two-gate SHA-256 + BLAKE3 hashing vs a four-byte magic check).

**Found in OfflineLLM, reported here for the record:** `startCompletion` bypasses the chat
template entirely and tokenizes the raw user string with `parse_special = true` whenever the
query contains `<turn|` or `<start_of_turn>`
([`LLMInference.cpp#L291-L292`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L291-L292)),
and the templated path tokenizes the whole rendered prompt — content included — with
`parse_special = true`
([`#L335-L336`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L335-L336)).
A user message containing `<start_of_turn>model` becomes a real control token. This is exactly
the primitive `skein-0ztk` closed; Skein's `ChatTemplating` (scaffold `parseSpecial=true`,
content `parseSpecial=false`, fail-closed segmentation) is **confirmed correct by
counterexample**. No Skein change. See `SECURITY_ANALYSIS.md` §4.

### E-6 — "model hashes/provenance are incorrect" → **NO EVIDENCE**

OfflineLLM records no hash, no licence and no source for any model
([`ModelInfo.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/local/entities/ModelInfo.kt),
16 lines: name, path, sizeBytes, contextSize, chatTemplate, isBundled). It therefore says nothing
about any Skein hash. Skein's `model-manifest.schema.json` (sha256 + blake3 + `license.spdx`
required + `source.url`/`source.revision` + sigstore attestation) has no upstream counterpart.
Skein's own recorded hashes were not re-derived here (no device access); nothing contradicts them.

One provenance caution, about **OfflineLLM's** own artifacts, not Skein's: its F-Droid metadata
pins `CurrentVersion: 5.0.2` / `versionCode: 7`
([`com.jegly.offlineLLM.yml`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/com.jegly.offlineLLM.yml))
while the repo is 5.1.1 / versionCode 9. The 5.1.x releases are **not** F-Droid-reproducible
builds. Treat OfflineLLM's published APKs as unreproduced. See `BUILD_REPRODUCIBILITY_ANALYSIS.md`.

### E-7 — "reproducibility is compromised" → **NO EVIDENCE against Skein**

Skein's reproducibility posture survives the comparison intact and is materially ahead:
`-ffile-prefix-map` (three roots), `-fno-ident`, `-Werror=date-time`, `--build-id=none`,
`SOURCE_DATE_EPOCH`, `GGML_CCACHE=OFF`, `GGML_NATIVE=OFF`, a version script, an ELF guard, the
`glslc` determinism wrapper and submodule-pinned Khronos headers
(`native/llama/README.md` §4, `native/llama/CMakeLists.txt:237-251`).

OfflineLLM is the opposite and is useful only as the negative example: its CMake version is
*whatever is newest in the SDK*
([`smollm/build.gradle.kts#L25-L35`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L25-L35)),
SPIRV-Headers is cloned at unpinned `HEAD`
([`README.md#L131`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L131)),
the Gradle wrapper has no `distributionSha256Sum`
([`gradle/wrapper/gradle-wrapper.properties`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/gradle/wrapper/gradle-wrapper.properties)),
and there is no CI (`.github/` contains only `FUNDING.yml`). **Adopt nothing from its build
plumbing.** One exception, a single flag: `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`
([`smollm/build.gradle.kts#L82`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L82)),
which Skein does not set anywhere and which is the remedy `skein-xth5` (PP-48) will need if its
check fails.

---

## Provenance

| | |
|---|---|
| **Upstream repository** | `https://github.com/jegly/OfflineLLM` |
| **Tag reviewed** | `5.1.1` |
| **Exact commit** | `e81091e86013c0605381d15a1ad7276a4be0b92b` |
| **Commit date / subject** | 2026-08-18 09:30:59 +1000 — *"v5.1.1 - backend diagnostics, CPU-only inference fix, llama.cpp API updates"* |
| **Tag is repository HEAD** | yes (`origin/HEAD` == `e81091e…` at clone time) |
| **Previous tag** | `5.1.0` = `d54e0bb` — *"v5.1.0 - Vulkan GPU acceleration, runtime CPU dispatch, incremental prompting, Ptyxis/Catppuccin theming, Gemma 4"* |
| **SPDX identifier** | `Apache-2.0` |
| **Licence file** | `LICENSE` (14 417 bytes; an ASCII-art banner followed by the verbatim Apache License 2.0 text, ending with the standard appendix). Declared `License: Apache-2.0` in `com.jegly.offlineLLM.yml` and in `README.md` §License. |
| **Attribution terms** | Apache-2.0 §4: retain the licence, retain copyright/patent/attribution notices, state significant changes in modified files, and carry any `NOTICE` content. **There is no `NOTICE` file in the tree** — so for Skein the obligation reduces to: keep an `Apache-2.0` attribution to *jegly / OfflineLLM* with this commit SHA alongside any derived source, and mark modifications. |
| **Third-party attribution inside OfflineLLM** | `README.md` §License: *"llama.cpp backend: MIT. Native wrapper adapted from [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android) (Apache 2.0)."* The `smollm/` module is a derivative of SmolChat-Android; **any Skein reuse of `smollm/` code inherits that chain and both attributions.** |
| **llama.cpp submodule pin** | `60eeeb6082c1126bb8bc72902c83123cd056811b` (2026-08-17, *"cuda : skip UMA override for HIP builds (#27083)"*), URL `https://github.com/ggerganov/llama.cpp.git` |
| **Skein's llama.cpp pin, for comparison** | `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` — **v0.4.1**, 2026-09-14. Newer than upstream's. |
| **Reviewed** | 2026-09-23 |
| **Reviewed by** | Opus research agent, bd `skein-o5f7` |
| **Clone location** | `research/clones/offlinellm/` — git-ignored (`.gitignore:116`, `research/clones/`), verified with `git check-ignore -v`. **Never committed.** |

### Permalink convention used throughout

| Prefix | Base |
|---|---|
| OfflineLLM | `https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/<path>#L<n>` |
| llama.cpp (Skein's pin) | `https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/<path>#L<n>` |
| llama.cpp (OfflineLLM's pin) | `https://github.com/ggml-org/llama.cpp/blob/60eeeb6082c1126bb8bc72902c83123cd056811b/<path>#L<n>` |

Skein paths are repo-relative and unpinned (the tree is the reviewer's context, not the
evidence). Skein's Kotlin packages are mid-rename `us.aherrera.skein.*` → `app.skein.*`; both
spellings denote the same type throughout these documents.

---

## What OfflineLLM is

An Android 13+, arm64-v8a-only, single-process, zero-network GGUF chat app. **~8 100 lines
total** across Kotlin, C++, CMake and Gradle — it is small enough that this review read
essentially all of it rather than sampling. Jetpack Compose + Hilt + Room; a `smollm/` AAR module
carrying a ~840-line C++/JNI wrapper over llama.cpp (derived from SmolChat-Android) and two
native libraries (`libsmollm.so`, `libggufreader.so`).

Its relevance to Skein is narrow and real: it is a **mature, currently-maintained reference for
the llama.cpp-on-Android backend-selection problem**, and 5.1.1 in particular is a release whose
headline item is a bug Skein's current code reproduces. It is *not* a reference for model
provenance, security architecture, build reproducibility, or anything above the inference layer,
where Skein is substantially ahead. Directive §15 applies without qualification.

## The documents

| File | Covers | Directive |
|---|---|---|
| `README.md` | escalations, provenance, licence, index | §18, §1 |
| `ARCHITECTURE_ANALYSIS.md` | Kotlin → JNI → C++ → llama.cpp → backend → hardware, end to end | §3 |
| `JNI_ANALYSIS.md` | the JNI boundary, lifecycle, cancellation, streaming, error propagation | §3 |
| `CPU_DISPATCH_ANALYSIS.md` | `GGML_CPU_ALL_VARIANTS`, the eight-point checklist, Skein's `armv8-a` floor | §4 |
| `VULKAN_ANALYSIS.md` | **priority** — the 5.1.1 fix, exactly; the three mechanisms; Skein's exposure | §5 |
| `MEMORY_ANALYSIS.md` | logits allocation, mmap, KV quantisation, the broader Android audit | §8 |
| `KV_CACHE_ANALYSIS.md` | incremental prompting, invalidation, and what survives contact with RAG | §9 |
| `GGUF_IMPORT_ANALYSIS.md` | SAF → validate → register → load, against Skein's Model Manager | §10 |
| `MODEL_PROVENANCE_ANALYSIS.md` | what OfflineLLM records (almost nothing) and what that tells us | §11 |
| `SECURITY_ANALYSIS.md` | permissions, untrusted GGUF, JNI surface, the injection primitive | §13 |
| `BUILD_REPRODUCIBILITY_ANALYSIS.md` | CMake/Gradle/NDK/shaders/ABI; the 24 MiB budget question | §14 |
| `INFERENCE_PLANNER_PROPOSAL.md` | architecture only: model + device → profile | §6, §7 |
| `REGRESSION_TEST_PROPOSAL.md` | R-1…R-12 against `tools/m0-benchmark` and the isolated service | §5, §8 |
| `ADOPTION_MATRIX.md` | 55 findings, one classification, eleven fields; proposed beads | §16 |
| `EXECUTIVE_RECOMMENDATION.md` | the ten questions, under two pages | §17, §19 |

## Method

1. `git clone` into `research/clones/offlinellm/` (git-ignored), `git checkout 5.1.1`, SHA
   recorded above.
2. Read the tag's full source — all `.kt`, `.cpp`, `.h`, `CMakeLists.txt`, `*.gradle.kts`,
   `AndroidManifest.xml`, `com.jegly.offlineLLM.yml`, `.gitignore`, `README.md`.
3. `git diff 5.1.0 5.1.1` on the native paths and `git log` across the tag range, to isolate the
   Vulkan-registration fix (§5), the logits change (§8) and the context cap (§7). **Note on
   granularity:** OfflineLLM publishes *squashed release commits* — `5.1.0..5.1.1` is three
   commits, two of which are README edits. There is no finer-grained history to walk; the diff
   *is* the commit history for these changes, and it is quoted in full in `VULKAN_ANALYSIS.md`
   and `MEMORY_ANALYSIS.md`.
4. Verified every behavioural claim against **Skein's own** `third_party/llama.cpp` rather than
   OfflineLLM's, so the conclusions bind to what Skein actually ships.
5. Read the Skein comparands named in the bead: the M0 handoff, `native/llama/`,
   `inference-service/`, `core/verify/`, `core/inference/`, the manifest schema,
   `MODEL_STORE.md`, `SKEIN_HUB.md` §3–§4, `POCKETPAL_RECON.md`, `tools/m0-benchmark/` and beads
   `skein-f9zu` / `brwf` / `hewz` / `wt92` / `r8ah` / `xth5`.

**Constraints honoured:** no device contact (no adb, no ssh, no benchmark runs); no product
code, build file, spec, plan, `tools/m0-benchmark` or M0-target changes; no new dependencies; no
beads filed (proposed only, in `ADOPTION_MATRIX.md`).

## Relationship to `docs/research/POCKETPAL_RECON.md`

PocketPal findings are **not** restated. Where OfflineLLM bears on one, the PP-id is cited and
the relationship stated as *confirms*, *extends* or *contradicts*. The index:

| PP-id | OfflineLLM's bearing | Where |
|---|---|---|
| PP-11 / PP-16 (bounded GGUF reader; parse in-process) | **confirms** both — OfflineLLM validates four magic bytes and hands the file to `gguf_init_from_file` **in the app process** | `GGUF_IMPORT_ANALYSIS.md` §3, `SECURITY_ANALYSIS.md` §5 |
| PP-14 / PP-29 / PP-30 (integrity, `.part`, headroom) | **confirms** — OfflineLLM independently arrived at `.part` + declared-size agreement + atomic rename after the truncated-import bug | `GGUF_IMPORT_ANALYSIS.md` §2 |
| PP-41 (mmap off for repackable quants) | **extends** — OfflineLLM defaults `useMmap` off for **all** models, for a *different* reason (page eviction → TTFT stall). Two independent rationales now point the same way | `MEMORY_ANALYSIS.md` §4 |
| PP-45 / PP-46 (memory estimator, learned ceiling) | **neither** — OfflineLLM has no estimator at all; a file-size ladder stands in. Its absence is the argument for `skein-f9zu` | `MEMORY_ANALYSIS.md` §6 |
| PP-48 (16 KiB ELF alignment) | **confirms** — OfflineLLM sets `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; Skein sets nothing | `BUILD_REPRODUCIBILITY_ANALYSIS.md` §6 |
| PP-51 (in-app bench) | **confirms** — same `bench(pp,tg,pl,nr)` shape, no thermal gate; Skein's harness remains the stricter one | `INFERENCE_PLANNER_PROPOSAL.md` §4 |
| PP-55 (GPU-offload bench crashes) | **confirms + remedies** — OfflineLLM catches the GPU load failure and retries on CPU | `VULKAN_ANALYSIS.md` §7 |
| PP-58 / PP-59 (typed capability reason; thread heuristic) | **extends** — `getBackendReport()` is the typed-reason surface, from ggml itself; `n_threads_batch = all cores` splits the thread question in two | `CPU_DISPATCH_ANALYSIS.md` §5 |
| PP-72 / PP-73 (SAF copy-in; path re-anchoring) | **confirms** PP-72; **reproduces** PP-73's failure — OfflineLLM deletes the registry row when the file is missing | `GGUF_IMPORT_ANALYSIS.md` §4 |
| PP-33 / PP-34 (fail-open templating; second template engine) | **worse** — OfflineLLM fails open *and* tokenizes content with `parse_special=true` | `SECURITY_ANALYSIS.md` §4 |
| PP-10 / PP-15 (hashing that proves nothing) | **worse** — no hashing at all | `MODEL_PROVENANCE_ANALYSIS.md` §2 |
