# CPU_DISPATCH_ANALYSIS — ARM64 runtime dispatch

Directive §4, with its eight-point checklist answered in §3. Upstream `jegly/OfflineLLM` @
`e81091e86013c0605381d15a1ad7276a4be0b92b` (tag `5.1.1`), Apache-2.0, reviewed 2026-09-23.

**Summary: OfflineLLM ships seven ARM kernel variants and picks one at load time. That is an
upstream llama.cpp feature (`GGML_CPU_ALL_VARIANTS`), not OfflineLLM engineering — OfflineLLM's
contribution is the two Android packaging details that make it actually work, plus the diagnostic
that proves which variant won. Skein ships one variant at the `armv8-a` floor and therefore has
no dotprod, no i8mm and no ARM repack kernels at all. Skein should not adopt the plugin
mechanism (it contradicts the single-`.so` isolated-process posture) but must resolve the arch
floor, and must build the diagnostic first so that the decision is measured rather than assumed.**

---

## 1. What OfflineLLM does

Three CMake flags, in `smollm/build.gradle.kts`, each with its reasoning in a comment:

```kotlin
// Backends stay runtime-loadable so the Vulkan plugin can register (or
// fail) independently of the CPU one.
arguments += "-DGGML_BACKEND_DL=ON"
// One ggml-cpu plugin per ARM feature level, scored against the device
// at load time — this is what puts dotprod/fp16/i8mm/SVE kernels on the
// SoCs that have them, while still running on armv8.0. Mutually exclusive
// with a fixed GGML_CPU_ARM_ARCH pin, which would trade the whole ladder
// for one hard-coded floor.
arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
```
([`smollm/build.gradle.kts#L92-L99`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L92-L99);
`-DGGML_VULKAN=ON` at `#L90`.)

At runtime, one JNI call loads every plugin `.so` from the app's native library directory:

```cpp
// Loads the ggml backend plugins (libggml-cpu-android_*.so variants + libggml-vulkan.so)
// from the app's nativeLibraryDir. ggml scores each CPU variant against the device's
// actual features (dotprod/fp16/i8mm/SVE...) and keeps only the best one. Idempotent.
ggml_backend_load_all_from_path(dirCstr);
```
([`smollm.cpp#L9-L29`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L9-L29))

Guarded by a `static std::atomic_bool` exchange for idempotence (`#L14-L17`) — necessary because
`ensureBackendsLoaded()` is called on every load and from both diagnostic accessors
([`ModelManager.kt#L204-L222`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L204-L222)).
There is an explicit fallback branch with a warning when `nativeLibraryDir` is empty, which the
comment calls out as *"likely loads nothing on Android"* (`smollm.cpp#L25-L28`).

And the piece almost everyone gets wrong:

```kotlin
jniLibs {
    // ggml discovers its backend plugins (libggml-cpu-android_*.so,
    // libggml-vulkan.so) by scanning nativeLibraryDir at runtime. With the
    // modern default (libs left compressed inside the APK) that directory
    // is empty and no backend can load — so extract them to disk.
    useLegacyPackaging = true
}
```
([`app/build.gradle.kts#L48-L54`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/build.gradle.kts#L48-L54))

**That comment is the single most useful line in the repository for anyone attempting
`GGML_BACKEND_DL` on Android.** Since AGP 4.2 / `extractNativeLibs=false`, libraries opened by
`System.loadLibrary` are read from the APK without extraction and `nativeLibraryDir` is empty.
`ggml_backend_load_all_from_path` scans a directory, so it finds nothing and every plugin
silently fails to register — leaving a working app that has quietly fallen back to whatever is
statically linked. It fails *silently*, which is why the diagnostic in §4 exists.

---

## 2. Whose engineering is it?

Directive §4 point 8. Unambiguous: **the ladder is upstream llama.cpp.** Skein's own pinned tree
has an Android-specific list of exactly seven variants:

```cmake
ggml_add_cpu_backend_variant(android_armv8.0_1)
ggml_add_cpu_backend_variant(android_armv8.2_1    DOTPROD)
ggml_add_cpu_backend_variant(android_armv8.2_2    DOTPROD FP16_VECTOR_ARITHMETIC)
ggml_add_cpu_backend_variant(android_armv8.6_1    DOTPROD FP16_VECTOR_ARITHMETIC MATMUL_INT8)
ggml_add_cpu_backend_variant(android_armv9.0_1    DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE2)
ggml_add_cpu_backend_variant(android_armv9.2_1    DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE SME)
ggml_add_cpu_backend_variant(android_armv9.2_2    DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE SVE2 SME)
```
([`ggml/src/CMakeLists.txt#L533-L540`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/CMakeLists.txt#L533-L540))

— seven, matching OfflineLLM's README claim *"the APK bundles seven `ggml-cpu` kernel variants
(armv8.0 → armv9.2)"*
([`README.md#L99`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L99)).
The list is Android-specific because the generic one uses `-march` flags the NDK rejects; the
separation is upstream's, not OfflineLLM's.

Upstream also enforces the constraints OfflineLLM's comment describes:

```cmake
if (GGML_CPU_ALL_VARIANTS)
    if (NOT GGML_BACKEND_DL)
        message(FATAL_ERROR "GGML_CPU_ALL_VARIANTS requires GGML_BACKEND_DL")
    elseif (GGML_CPU_ARM_ARCH)
        message(FATAL_ERROR "Cannot use both GGML_CPU_ARM_ARCH and GGML_CPU_ALL_VARIANTS")
```
([`ggml/src/CMakeLists.txt#L487-L492`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/CMakeLists.txt#L487-L492))

**`GGML_CPU_ALL_VARIANTS` requires `GGML_BACKEND_DL`. There is no static form of the ladder.**
That single line decides the question for Skein — see §6.

**OfflineLLM's own engineering** is three things, all real:
(a) discovering and documenting `useLegacyPackaging = true`;
(b) `ggml_backend_load_all_from_path(nativeLibraryDir)` with the empty-dir fallback and
idempotence;
(c) `getBackendReport()` — §4 — which is the only way to know the ladder worked.

A fourth, smaller: the comment recording that arch flags on the JNI wrapper translation unit
never mattered, because all the arithmetic is in the plugins:

> *"Single JNI wrapper library. CPU-specific optimisation lives in the ggml-cpu plugin variants
> (GGML_CPU_ALL_VARIANTS) selected at runtime, not here — the wrapper is thin glue and arch flags
> on it never affected inference speed."*
> ([`smollm/src/main/cpp/CMakeLists.txt#L39-L41`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/CMakeLists.txt#L39-L41))

That is a *negative* result someone measured, which is worth as much as a positive one: don't
spend effort tuning `-march` on `skein_jni.cpp`.

---

## 3. The eight-point checklist

| § | Question | OfflineLLM 5.1.1 | Skein today |
|---|---|---|---|
| 4.1 | How are CPU capabilities detected? | ggml's own scoring at plugin load — each variant exports a score function; the highest-scoring registrable variant wins. Never `/proc/cpuinfo`, never `Build.SUPPORTED_ABIS`. | N/A — no detection. Compile-time `-march` only. |
| 4.2 | How is the implementation selected? | `ggml_backend_load_all_from_path` loads all plugins; ggml keeps the best CPU one and discards the rest ([`smollm.cpp#L9-L11`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L9)) | N/A — one static `ggml-cpu`. |
| 4.3 | Build time, load time, or runtime? | **Load time**, once per process, at first `ensureBackendsLoaded()`. Not per-op runtime dispatch. | **Build time**, fixed at `armv8-a`. |
| 4.4 | APK size impact | Seven `libggml-cpu-android_*.so` + `libggml-vulkan.so`, all uncompressed on disk because of `useLegacyPackaging = true`. The README budgets *"~20–30 min"* for the first build of "~1,400 Vulkan compute shaders and seven CPU-variant libraries" ([`README.md#L141`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L141)). No size figure is published and none was measured here (no build run). | One `libskein_llama.so`, **24.10 MiB**, ~19.3 MiB of it SPIR-V (`native/llama/README.md` §7). |
| 4.5 | Native library organisation | Nine `.so` files in `jniLibs`, one of them (`libsmollm.so`) `System.loadLibrary`'d, eight `dlopen`'d by path. | One `.so`, plus `libskein_sqlite.so`. Version-scripted exports. |
| 4.6 | Reproducible-build implications | **Bad.** Seven more compiled artifacts with no pinned toolchain (`cmakeVersionPinned` = newest in the SDK, [`smollm/build.gradle.kts#L25-L35`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L25-L35)) and no hash recording anywhere. See `BUILD_REPRODUCIBILITY_ANALYSIS.md`. | Strong; the ELF guard and recorded hashes cover exactly one `.so`. Adding eight would require extending `tools/ci/` to all of them. |
| 4.7 | GrapheneOS compatibility | Untested here. `dlopen` from `nativeLibraryDir` is standard and permitted. The real question is **the isolated process**, not GrapheneOS: AOSP's `isolated_app` SELinux domain is tightly restricted, and an isolated uid `dlopen`ing loose `.so` files is a surface Skein deliberately closed (`native/llama/CMakeLists.txt:625-627`). GrapheneOS's hardened `malloc` and `memtagMode` apply either way. | N/A. |
| 4.8 | Upstream or OfflineLLM-specific? | **Upstream** (`GGML_CPU_ALL_VARIANTS`, the seven `android_*` variants, the scoring). OfflineLLM-specific: `useLegacyPackaging`, the load-from-path call with its fallback, and the report. | — |

---

## 4. The diagnostic — the portable part

```cpp
// Which backends actually registered, and which ggml-cpu variant won the runtime
// scoring. GGML_CPU_ALL_VARIANTS picks one plugin per device silently, and ggml
// only logs the scores under NDEBUG-disabled GGML_LOG_DEBUG — so on a release
// build a device that fell back to the armv8.0 baseline (no DOTPROD/MATMUL_INT8,
// several times slower on quantized models) is indistinguishable from one that
// picked the right variant.
```
([`smollm.cpp#L81-L87`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L81-L87))

The implementation walks `ggml_backend_reg_count()`, fetches `ggml_backend_get_features` through
`ggml_backend_reg_get_proc_address`, and prints each backend with its live feature flags — with a
nice detail: flag-valued features print bare, value-carrying ones (`SVE_CNT`) print `name=value`
(`#L106-L110`). Then every device with its type and description (`#L118-L137`). The whole thing
is 52 lines with no allocation beyond one `std::string`, and it needs no model loaded.

Surfaced in the UI as selectable text so it can be pasted into a bug report
([`README.md#L76`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L76))
and given a field-usable reading rule:

> *"`DOTPROD` plus `MATMUL_INT8` means the fast quantized-matmul kernels are running; a device
> showing only `NEON` fell back to the armv8.0 baseline and will be several times slower."*
> ([`README.md#L103`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L103))

**Matrix OL-05: ADOPT, adapted.** `ggml_backend_get_features` reports what the *linked*
`ggml-cpu` was compiled with, so it answers Skein's question — "is this build at `armv8-a` or
not?" — without any of the plugin machinery. It is also the mechanism every test in
`REGRESSION_TEST_PROPOSAL.md` needs: R-1 and R-3 cannot be written without a device/backend
enumeration crossing the AIDL boundary.

Skein shape (design only, not written here): extend `ModelInspection`-style reporting with a
`BackendReport` parcelable — `{backends: List<{name, features: List<String>}>, devices:
List<{name, type, description}>}` — behind a new `IInferenceService.backendReport()`. It must be
**privacy-safe by construction** (§12): ggml feature names and device descriptions only, no
paths, no model names, no prompt. That is the same constraint the diagnostics surface in
`INFERENCE_PLANNER_PROPOSAL.md` §5 carries.

Relationship to PocketPal PP-58 (typed capability reason rather than a boolean): this is the same
idea sourced from ggml itself instead of hand-maintained, and it is strictly better because it
cannot drift from what was actually linked.

---

## 5. Skein's position today, stated precisely

```cmake
# GGML_NATIVE probes the *host* CPU; meaningless and non-reproducible when
# cross-compiling. GGML_CPU_ARM_ARCH is deliberately left at the NDK default
# (armv8-a) — raising it to armv8.5-a+i8mm is a measured decision that belongs
# to docs/MEASUREMENTS.md (skein-5hr), not to this bead.
skein_llama_opt(GGML_NATIVE         OFF)
...
skein_llama_opt(GGML_BACKEND_DL     OFF)
```
(`native/llama/CMakeLists.txt:619-627`; restated in `native/llama/README.md` §5.)

Reading upstream's ARM branch with that combination — `GGML_NATIVE` off, `GGML_CPU_ARM_ARCH`
unset, `GGML_CPU_ALL_VARIANTS` unset — the `else()` body is entered and **neither sub-branch
fires**, so no `-march` flag is appended at all
([`ggml/src/ggml-cpu/CMakeLists.txt#L170-L214`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-cpu/CMakeLists.txt#L170-L214)).
The NDK's `arm64-v8a` default (`armv8-a`) stands, so `__ARM_FEATURE_DOTPROD` and
`__ARM_FEATURE_MATMUL_INT8` are undefined, and the ARM repack GEMM kernels are compiled out
entirely
([`ggml/src/ggml-cpu/arch/arm/repack.cpp#L27`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-cpu/arch/arm/repack.cpp#L27),
and again at `#L231`, `#L590`, `#L729`, `#L883`, `#L1042`, …).

Three consequences, each of which matters elsewhere in this review:

1. **The APK's CPU path is the baseline path** — the one OfflineLLM's README describes as
   "several times slower" on quantized models. Q4_K_M matmul is precisely the case the dotprod
   and i8mm kernels exist for.
2. **The Termux `llama-bench-cpu` binary does *not* have this limitation**, because a native
   Termux build leaves `GGML_NATIVE` at its default `ON` and probes the Tensor G3. The M0 CPU
   cell is therefore an upper bound on the APK, not an estimate of it — escalation E-1.
3. **The `Vulkan_Host`-displaces-`CPU_REPACK` effect (`VULKAN_ANALYSIS.md` §4) is currently
   costless and becomes costly the moment (1) is fixed.** The two must be sequenced: raise the
   arch floor *and* restrict the device list, or the arch change buys nothing.

The Tensor G3 (Pixel 9 Pro Fold) is Cortex-X3 + A715 + A510 — Armv9.0-A, so DOTPROD,
FP16, MATMUL_INT8 and SVE/SVE2 are all present. It would select `android_armv9.0_1` from
upstream's ladder. Every kernel Skein is leaving on the table exists on this device.

---

## 6. Should Skein adopt it?

Directive §4's four options.

**Adopt the plugin ladder now — NO.** `GGML_CPU_ALL_VARIANTS` *requires* `GGML_BACKEND_DL`
([`ggml/src/CMakeLists.txt#L489`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/CMakeLists.txt#L489)),
which requires shared libs, which means eight loose `.so` files that an isolated uid must
`dlopen` by path. That contradicts a decision `native/llama/CMakeLists.txt:625-627` records
deliberately, multiplies the reproducibility surface by eight (§3, point 4.6), and forces
`useLegacyPackaging = true` on the whole APK — which also un-compresses the 19.3 MiB SPIR-V blob
and works directly against `skein-jn3`'s ≤ 30 MB target. Four costs, none of them small.

**Schedule it after M0 — NO, not as such.** The ladder's benefit on Skein's single target device
is *one* variant: `android_armv9.0_1`. Paying the plugin architecture to select among seven when
the development device always picks the same one is the wrong trade for v1.

**Implement a simpler equivalent — YES. This is the answer.** Set `GGML_CPU_ARM_ARCH` to a raised
floor (`armv8.2-a+dotprod+fp16` as the conservative choice, `armv8.6-a+i8mm` as the aggressive
one) and keep the single static `.so`. Cost: **one CMake line.** Benefit: the repack kernels
compile in and the quantized-matmul path exists. Cost of the aggressive floor: devices below
Armv8.6 will `SIGILL`. Skein's `minSdk` is 30 and its stated target is the Pixel 9 Pro Fold, so
`skein-5hr` is genuinely a measurement question and not a compatibility one *for v1* — but the
decision must be recorded with its device-exclusion consequence, exactly as
`native/llama/README.md` §5 already frames it.

**Leave it to llama.cpp — NO.** llama.cpp has no per-op ARM runtime dispatch. Without the
variant ladder, `-march` *is* the dispatch.

### Sequencing, which is the actual recommendation

1. **Build the diagnostic first (OL-05).** Without it there is no way to confirm on device which
   features the linked `ggml-cpu` has — and the whole point of `skein-5hr` is a measured
   decision. M0/M1.
2. **Restrict the CPU device list (OL-01, `VULKAN_ANALYSIS.md`).** Must precede any CPU
   measurement, or the measurement is of the Vulkan path.
3. **Then measure `armv8-a` vs a raised floor**, with R-4 asserting that the repack buffer type
   is actually reached. That is `skein-5hr`'s job and this document does not pre-empt it.

Steps 1 and 2 are prerequisites for step 3 being meaningful. That ordering is the finding.

---

## 7. Two smaller carry-overs

**`n_threads_batch` as a separate knob** (OL-29) — covered in `JNI_ANALYSIS.md` §7. It belongs
here too, because "prompt processing uses every core including the efficiency ones, generation
sticks to the big cores" is a *CPU dispatch* decision in everything but name, and it is the knob
that matters most once prompt processing genuinely runs on the CPU.

**`GGML_OPENMP=OFF` is load-bearing.** OfflineLLM's README asserts that using ggml's own thread
pool rather than OpenMP is *"what makes the CPU-thread settings actually take effect and keeps
generation off the efficiency cores"*
([`README.md#L102`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L102)).
Skein already sets it (`native/llama/CMakeLists.txt:629`) — independent corroboration that the
flag has a behavioural consequence beyond dependency hygiene, and a reason for the comment there
to say so.
