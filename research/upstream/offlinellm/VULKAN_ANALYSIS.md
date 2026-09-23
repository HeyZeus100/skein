# VULKAN_ANALYSIS — the 5.1.1 CPU-only fix, and whether Skein has the same bug

Directive §5 (HIGH PRIORITY). Upstream `jegly/OfflineLLM` @
`e81091e86013c0605381d15a1ad7276a4be0b92b` (tag `5.1.1`), Apache-2.0, reviewed 2026-09-23.

**Answer up front: yes. Skein's shipping binary can suffer the same buffer-selection effect and
does suffer the same scheduler effect, and it is demonstrable from Skein's own pinned llama.cpp
without touching a device.** Details in §3–§5; the remedy is one `std::vector` in
`skein_jni.cpp`; the tests are in `REGRESSION_TEST_PROPOSAL.md`.

---

## 1. Exactly what the 5.1.1 fix changed

`git diff 5.1.0 5.1.1 -- smollm/src/main/cpp/LLMInference.cpp` yields four hunks. The one §5
asks about is this, inserted between `model_params.n_gpu_layers = nGpuLayers` and
`llama_model_load_from_file`
([`LLMInference.cpp#L126-L149`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L126-L149)):

```cpp
    // CPU mode must actually mean CPU: with a GPU backend registered (Vulkan is
    // always loaded by initBackends), llama.cpp still adds it to the scheduler at
    // n_gpu_layers=0 and routes large-batch prompt processing through it, copying
    // weights to the GPU per batch. On desktop dGPUs that's a win; on mobile the
    // copies are so slow that "CPU" chats crawl, and a large Vulkan compute buffer
    // gets reserved for a chat that never touches the GPU. It also pushes
    // Vulkan_Host ahead of the CPU repack buffer type, so quantized weights are
    // never repacked into the layouts ARM's dotprod/i8mm GEMM kernels need.
    // Restricting the device list keeps CPU chats pure CPU end to end; GPU mode
    // (nGpuLayers > 0) is unaffected. The vector only needs to outlive the load
    // call — llama copies the device list into the model.
    std::vector<ggml_backend_dev_t> cpuOnlyDevices;
    if (nGpuLayers <= 0) {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            enum ggml_backend_dev_type devType = ggml_backend_dev_type(dev);
            if (devType == GGML_BACKEND_DEVICE_TYPE_CPU ||
                devType == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
                cpuOnlyDevices.push_back(dev);
            }
        }
        cpuOnlyDevices.push_back(nullptr);
        model_params.devices = cpuOnlyDevices.data();
    }
```

That is the entire fix: **twelve lines, one new llama.cpp parameter, no patch to upstream.**
`params.devices` is a `NULL`-terminated array of `ggml_backend_dev_t`; supplying one replaces
llama.cpp's default device discovery outright. The `ACCEL` inclusion is correct and deliberate —
`ACCEL` devices (BLAS-class) are CPU-adjacent and belong in a CPU-only list; excluding them would
be a behaviour change beyond the fix's scope.

The other three hunks in the same commit are unrelated API catch-up and are analysed elsewhere:
`use_mmap`/`use_mlock` → `load_mode` (`MEMORY_ANALYSIS.md` §4), `n_outputs_max = 1`
(`MEMORY_ANALYSIS.md` §2), `llama_sampler_init_penalties` gaining a leading `n_vocab`
(`JNI_ANALYSIS.md` §6), plus one new log line.

### The llama.cpp API and version involved

| | |
|---|---|
| API | `llama_model_params::devices` (`const ggml_backend_dev_t *`, `NULL`-terminated), plus the `ggml_backend_dev_count` / `ggml_backend_dev_get` / `ggml_backend_dev_type` registry triple |
| Version OfflineLLM fixed against | `60eeeb6082c1126bb8bc72902c83123cd056811b` (2026-08-17) |
| Version Skein pins | `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` = **v0.4.1**, 2026-09-14 — **newer** |
| Status upstream | **not fixed.** The behaviour is intentional llama.cpp design (it is correct for desktop dGPUs); the `devices` parameter *is* the intended escape hatch. There is no version Skein could bump to that removes the need for it. |

This is the single most important structural fact in this document: the problem is **not** a
llama.cpp bug that a newer pin sweeps away. Skein's newer pin has it too.

---

## 2. What "Vulkan is always loaded" means in each project

OfflineLLM builds with `GGML_BACKEND_DL=ON` and loads plugin `.so`s at runtime from
`nativeLibraryDir`
([`smollm/build.gradle.kts#L93`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L93),
[`smollm.cpp#L12-L29`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L12-L29)).
`initBackends` is called unconditionally before every load
([`ModelManager.kt#L204-L206`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L204-L206)),
so `libggml-vulkan.so` registers whether or not the user enabled GPU.

Skein builds with `GGML_BACKEND_DL=OFF` and `BUILD_SHARED_LIBS=OFF`
(`native/llama/CMakeLists.txt:627`, `:578`) — one static `libskein_llama.so`. ggml's backend
registry then registers the compiled-in backends at static-init time. With `GGML_VULKAN=ON`
(default on arm64-v8a, `native/llama/CMakeLists.txt:641` / `:182`), **the Vulkan device appears
in the registry with no call required and no way to opt out at runtime.**

The two mechanisms differ; the resulting condition is identical: *a GPU-type device is in the
registry while the user has asked for CPU*. Skein's is arguably harder to escape, because
OfflineLLM could in principle skip `initBackends`, whereas Skein's registration is a static
initializer.

---

## 3. Mechanism 1 — the Vulkan backend joins the scheduler at `n_gpu_layers = 0`

`n_gpu_layers` never reaches device selection. `llama_prepare_model_devices` builds
`model->devices` from the registry when `params.devices == nullptr`, appending every GPU and —
if no discrete GPU was found, which on a phone is always — every iGPU
([`src/llama.cpp#L221-L285`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama.cpp#L221-L285)).
Mali-G715 reports `eIntegratedGpu`, so ggml-vulkan types it `GGML_BACKEND_DEVICE_TYPE_IGPU`
([`ggml-vulkan.cpp#L19114-L19116`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19114-L19116)),
and it lands in the list.

`n_gpu_layers` is consulted only afterwards, for *layer placement*:

```cpp
const int i_gpu_start     = std::max(n_layer_all + 1 - n_gpu_layers, 0);
const int act_gpu_layers  = devices.empty() ? 0 : std::min(n_gpu_layers, n_layer_all + 1);
```
([`src/llama-model.cpp#L1496-L1497`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-model.cpp#L1496-L1497)).
At `n_gpu_layers = 0`, `act_gpu_layers = 0` and every layer is assigned `cpu_buft_list` — the
weights stay on the CPU, which is the part that "works". But `model->devices` is still non-empty,
and `llama_context` iterates it:

```cpp
// GPU backends
for (const auto & dev : model.devices) {
    ggml_backend_t backend = ggml_backend_dev_init(dev.dev, nullptr);
    ...
    backends.emplace_back(backend);
}
```
([`src/llama-context.cpp#L331-L338`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L331-L338)).

So at `ngl=0` on a Vulkan-capable build Skein pays, per context: a `VkDevice`, its queues and
command pools, ggml-vulkan's pipeline creation, and a Vulkan entry in
`ggml_backend_sched_new(...)`
([`#L605`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L605)).

### And the scheduler then uses it

`cparams.op_offload` defaults `true`
([`#L3651`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L3651)),
and the scheduler's assignment pass reads:

```cpp
if (sched->op_offload && src_backend_id == sched->n_backends - 1 && ggml_backend_buffer_is_host(src->buffer)) {
    for (int b = 0; b < src_backend_id; b++) {
        if (ggml_backend_supports_op(sched->backends[b], tensor) && ggml_backend_offload_op(sched->backends[b], tensor)) {
```
([`ggml/src/ggml-backend.cpp#L970-L973`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-backend.cpp#L970-L973)).

Read it precisely: *when an op's inputs live in a **host** buffer (which is exactly where CPU
weights live) and the CPU is the last backend, try to move the op to an earlier (GPU) backend.*
ggml-vulkan's `offload_op` answers:

```cpp
static bool ggml_backend_vk_device_offload_op(ggml_backend_dev_t dev, const ggml_tensor * op) {
    return ggml_vk_get_op_batch_size(op) >= dev_ctx->op_offload_min_batch_size;
}
```
([`ggml-vulkan.cpp#L19823-L19827`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19823-L19827)),
with

```cpp
const int min_batch_size = getenv("GGML_OP_OFFLOAD_MIN_BATCH") ? atoi(getenv("GGML_OP_OFFLOAD_MIN_BATCH")) : 32;
```
([`#L19986`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19986)).

**32 tokens.** Skein decodes prompts in chunks of `PROMPT_BATCH_TOKENS = 512`
(`inference-service/.../InferenceService.kt:780`, used at `:286` as `nBatch` and at `:395` as the
chunk stride). Every prompt chunk is sixteen times the threshold, so on a Vulkan-capable build
with `gpuLayers = 0`:

- **prompt processing** is scheduled onto Vulkan, with the weights copied host→device *per
  batch* because they were never resident there;
- **token generation** (batch size 1) stays on the CPU, correctly.

This is precisely OfflineLLM's "on desktop dGPUs that's a win; on mobile the copies are so slow
that 'CPU' chats crawl". It also explains the shape of the M0 smoke anomaly (handoff §9): pp64
23.47 tok/s but pp512 **6.49** tok/s on the same Vulkan binary. Batch-size-dependent transfer
cost is the natural reading. I have not measured it and do not claim it as the explanation — but
it is now a hypothesis with a mechanism and a test (`REGRESSION_TEST_PROPOSAL.md` R-5).

Note the environment-variable escape hatch: `GGML_OP_OFFLOAD_MIN_BATCH` lets a *benchmark* turn
offload off without a rebuild. That is directly useful to R-2.

---

## 4. Mechanism 2 — `Vulkan_Host` is inserted ahead of the CPU repack buffer type

This is the "repack suppression" §5 asks about. `make_cpu_buft_list` documents its own priority
in a header comment, and the order is unambiguous:

```cpp
// CPU: ACCEL -> GPU host -> CPU extra -> CPU
static buft_list_t make_cpu_buft_list(const std::vector<llama_device> & devices, bool use_extra_bufts, bool no_host) {
    ...
    if (!no_host) {
        for (const auto & dev : devices) {
            ggml_backend_buffer_type_t buft = ggml_backend_dev_host_buffer_type(dev.dev);
            if (buft) { buft_list.emplace_back(dev.dev, buft); break; }
        }
    }
    if (use_extra_bufts) { /* ... ggml_backend_cpu_repack_buffer_type() ... */ }
```
([`src/llama-model.cpp#L1035-L1085`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-model.cpp#L1035-L1085)).

`devices` here is `model->devices` — the same list §3 showed contains the Vulkan iGPU at
`ngl = 0`. ggml-vulkan unconditionally supplies a host buffer type
([`ggml-vulkan.cpp#L19108-L19110`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19108-L19110),
registered at `#L19957`). `select_buft` walks the list front to back and takes the first that
supports the tensor
([`#L2188-L2200`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-model.cpp#L2188)).
`Vulkan_Host` is ordinary pinned host memory and supports everything — so it wins, and
`CPU_REPACK` is never reached.

Two relevant defaults, both in Skein's favour for the *escape*:

- `llama_model_default_params()` sets `use_extra_bufts = true` and `no_host = false`
  ([`src/llama-model.cpp#L2762`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-model.cpp#L2762)).
  `no_host = true` is a *second, independent* lever that suppresses the host buffer type without
  touching the device list — `llama.h` describes it as "bypass host buffer allowing extra buffers
  to be used" ([`include/llama.h#L347`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/include/llama.h#L347)).
- `llama_model_default_params()` also sets `n_gpu_layers = -1` — **all layers to GPU**. Skein is
  safe because it always writes the field, but any future path that forgets to would silently
  full-offload.

### The Skein-specific nuance, which matters

There is a third gate OfflineLLM does not have. The ARM repack GEMM kernels are **compile-time**
gated:

```cpp
#if defined(__aarch64__) && defined(__ARM_NEON) && (defined(__ARM_FEATURE_MATMUL_INT8) || defined(__ARM_FEATURE_DOTPROD))
```
([`ggml/src/ggml-cpu/arch/arm/repack.cpp#L27`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-cpu/arch/arm/repack.cpp#L27)).

Skein forces `GGML_NATIVE OFF` and leaves `GGML_CPU_ARM_ARCH` unset with
`GGML_CPU_ALL_VARIANTS` unset (`native/llama/CMakeLists.txt:623`, `:627`, and
`native/llama/README.md` §5, which says so explicitly). With that combination the ARM branch of
`ggml-cpu/CMakeLists.txt` appends **no** `-march` flags
([`ggml/src/ggml-cpu/CMakeLists.txt#L170-L214`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-cpu/CMakeLists.txt#L170-L214)),
so the NDK's `arm64-v8a` default (`armv8-a`) stands and neither feature macro is defined.

**Consequence: in Skein's APK today the repack kernels do not exist, so nothing is lost by
`Vulkan_Host` displacing them.** The suppression is real but currently costless — and it becomes
costly the instant `skein-5hr` raises the arch floor, which is the one change most likely to be
made *on the strength of a CPU benchmark* that was measured with the Termux binary where the
kernels do exist. That is the trap: the benchmark would promise a win the APK cannot deliver,
and the fix that unlocks the win is silently cancelled by a Vulkan device nobody asked for.

Both levers must land together: restrict `devices` (or set `no_host`) **and** raise the arch
floor. Either alone is a no-op or a regression.

---

## 5. Mechanism 3 — GPU memory reserved for a chat that never touches the GPU

Two allocations follow from §3, neither of which `n_gpu_layers = 0` prevents:

1. **The Vulkan device itself.** `ggml_backend_dev_init` on the Vulkan device creates the
   `VkDevice`, its command pools and ggml-vulkan's pipeline objects. On a unified-memory SoC
   this is host RAM charged to the process.
2. **The CPU backend's scheduler buffer, relocated.** `llama_context` explicitly swaps the CPU
   backend's scheduler buffer type for the first device's host buffer type:

   ```cpp
   if (backend_type == GGML_BACKEND_DEVICE_TYPE_CPU && !model.devices.empty()) {
       // use the host buffer of the first device CPU for faster transfer of the intermediate state
       const auto & dev = model.devices[0];
       auto * host_buft = ggml_backend_dev_host_buffer_type(dev.dev);
       if (host_buft) { buft = host_buft; }
   }
   ```
   ([`src/llama-context.cpp#L410-L417`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/src/llama-context.cpp#L410-L417)).
   `model.devices[0]` at `ngl=0` on a phone *is* the Vulkan iGPU. So the compute buffer — the
   one whose size §E-3 shows is dominated by the 512-position logits reservation — is allocated
   from `ggml_backend_vk_host_buffer_type`
   ([`ggml-vulkan.cpp#L16913-L16955`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L16913)),
   i.e. `vkAllocateMemory` host-visible memory, not `malloc`.

The second point compounds E-3 and is the reason both should be fixed in the same change:
Skein currently reserves ~297 MiB (Qwen 2.5) to ~512 MiB (Gemma-class) of logits it never reads,
**inside a Vulkan host allocation**, on a path the user selected as CPU-only.

`ggml-vulkan` also reports `mmap_support = !is_integrated_gpu`
([`#L19132`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19132)),
so an integrated device in the list advertises *no* mmap support — another way the presence of
the device perturbs a "CPU" load. Skein forces `load_mode` explicitly
(`skein_jni.cpp:368`), so this does not currently change Skein's behaviour; it is one more reason
the device list should not contain a device the caller did not ask for.

---

## 6. Can Skein suffer this? — line by line

`native/llama/jni/skein_jni.cpp`, `loadModelFromFd` (the path `InferenceService` actually uses):

```cpp
llama_model_params params = llama_model_default_params();
params.n_gpu_layers = n_gpu_layers;
params.load_mode = (use_mmap == JNI_TRUE) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
llama_model *model = llama_model_load_from_file_ptr(file, params);
```
(`skein_jni.cpp:426-432`; the path-based `loadModel` is identical at `:363-370`.)

| Question | Skein today | Consequence |
|---|---|---|
| Is a GPU device in the ggml registry when CPU is selected? | **Yes** — `GGML_VULKAN=ON` is the arm64 default and backends are statically registered (`GGML_BACKEND_DL=OFF`) | Mechanisms 1–3 all armed |
| Is `params.devices` set? | **No** — left `nullptr` | Vulkan iGPU enters `model->devices` |
| Is `params.no_host` set? | **No** — default `false` | `Vulkan_Host` enters `cpu_buft_list` ahead of `CPU_REPACK` |
| Is `params.use_extra_bufts` set? | **No** — default `true` | repack bufts are *offered*, then displaced |
| Is `op_offload` disabled? | **No** — `llama_context_default_params()` leaves it `true`; `skein_jni.cpp:477-484` sets only `n_ctx`, `n_batch`, `n_ubatch`, `n_threads`, `n_threads_batch`, `embeddings` | prompt chunks ≥ 32 tokens routed to Vulkan |
| Prompt chunk size | `PROMPT_BATCH_TOKENS = 512` | 16× the offload threshold |
| Are the ARM repack kernels compiled in? | **No** — `armv8-a` floor | repack half currently dormant (see §4) |

**Verdict: Skein reproduces mechanisms 1 and 3 today, and mechanism 2 is latent behind
`skein-5hr`.** The APK's "CPU" is not CPU.

### Compare against the M0 Fold measurements — carefully

Directive §5 says do not assume OfflineLLM's Pixel results apply to the Pixel 9 Pro Fold. They
do not, and no number from OfflineLLM is imported here. What transfers is the **mechanism**,
which is device-independent because it lives in llama.cpp's scheduler and buffer-type logic, not
in a driver.

What the mechanism implies about the M0 numbers:

- The M0 harness's **CPU cell is clean** — it uses a *separate CPU-only binary*
  (`llama-bench-cpu`, `tools/m0-benchmark/models.yaml`). No Vulkan device exists in that process.
- The M0 harness's **Vulkan cell is a genuine Vulkan measurement** at `ngl=99`
  (`run.sh:289`), which is what it claims to be.
- **Neither cell measures what the APK does.** The APK's CPU path is "CPU config on a Vulkan
  build", a third configuration the harness never runs. Its prompt-processing number should be
  expected to sit *between* the two cells, dominated by host↔device copies.
- The Mali-G715 caveat in OfflineLLM's own README is worth reading before any backend decision:
  *"on many mobile GPUs — Mali especially — token generation is memory-bandwidth-bound and the
  CPU kernels can be faster"*
  ([`README.md#L100`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L100)).
  The M0 smoke's flat 5.61 tok/s tg across both configurations is consistent with a
  bandwidth ceiling. Field guidance, not evidence — but it points at the hybrid policy in
  `INFERENCE_PLANNER_PROPOSAL.md`.

This does **not** invalidate the M0 targets and nothing here proposes changing them. It changes
what a cell is *labelled* and adds a third cell. See `REGRESSION_TEST_PROPOSAL.md` R-5.

---

## 7. The other Vulkan-adjacent things 5.1.x got right

**Automatic CPU fallback on GPU load failure**
([`InferenceEngine.kt#L63-L80`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/InferenceEngine.kt#L63-L80)):
catch, log, retry once with `params.copy(nGpuLayers = 0)`. Twelve lines. This is the remedy for
PocketPal's PP-55 (GPU-offload benchmark crashes, upstream #198) and for the "flaky Vulkan
driver" class generally. Skein's `InferenceService.loadInternal` maps a `LlamaException` to an
`ErrorCode` and stops (`InferenceService.kt:306-312`). **ADAPT** — but the retry belongs in
`:app`, above the IPC boundary, not inside the isolated service: Skein's service should report
`gpuLayers` in the failure so `:app` can decide, and a silent downgrade must be visible in the
diagnostics surface, not swallowed.

**A backend diagnostics surface**
([`smollm.cpp#L88-L140`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L88-L140)):
`getBackendReport()` walks `ggml_backend_reg_count()`, pulls `ggml_backend_get_features` via
`ggml_backend_reg_get_proc_address`, and prints each backend with its live feature flags, then
every device with its type and description. The comment explains why it exists — ggml logs the
variant scores only under `GGML_LOG_DEBUG`, so on a release build a device that fell back to
baseline is indistinguishable from one that did not. This is the single most portable idea in the
repository and is **ADOPT** for Skein (see `CPU_DISPATCH_ANALYSIS.md` §5 and
`INFERENCE_PLANNER_PROPOSAL.md` §5): it is also the only way R-1 and R-3 can be asserted at all.

**Vulkan capability probing without initialising anything**
([`VulkanDetector.kt`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/utils/VulkanDetector.kt),
24 lines): `PackageManager.hasSystemFeature(FEATURE_VULKAN_HARDWARE_LEVEL)` plus the
`android.hardware.vulkan.version` feature, bit-unpacked to major/minor/patch. Cheap, safe in the
app process, no native call. **REFERENCE** — useful for the Planner's device profile.

---

## 8. Recommendation

Three changes, none of them in this bead's scope to make:

1. **`params.devices` restricted to CPU/ACCEL when `nGpuLayers <= 0`**, in both
   `loadModel` and `loadModelFromFd` in `native/llama/jni/skein_jni.cpp`. Port of
   [`LLMInference.cpp#L137-L149`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L137-L149),
   Apache-2.0, attribution required. ~12 lines. Matrix **OL-01**, ADOPT, **M0**.
   - *Alternative considered and rejected as insufficient:* `params.no_host = true` alone. It
     fixes mechanism 2 only; the Vulkan backend still joins the scheduler and still takes the
     prompt batches. Set both if belt-and-braces is wanted, but `devices` is the load-bearing one.
2. **Assert it**, because a silent revert is otherwise invisible: R-1 (`getBackendReport`-style
   device enumeration proves the context has one backend), R-2 (`GGML_OP_OFFLOAD_MIN_BATCH`
   A/B), R-3 (buffer-type names in the load log contain `CPU`/`CPU_REPACK`, never `Vulkan_Host`).
3. **Add the third benchmark cell** — `cpu-config-on-vulkan-build` — so the APK's real CPU path
   is measured once rather than inferred. R-5. This is an addition to the matrix, not a change to
   any target.

Sequencing note for §18: (1) is small, local, reversible, verifiable on host by reading the load
log, and it is a **precondition for trusting any in-app CPU measurement**. If M0 is to be
declared on in-app numbers, it belongs before that declaration. If M0 is declared purely on the
Termux CLI matrix, it can follow — but then M0's CPU number must be labelled as not predictive of
the app.
