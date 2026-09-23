# INFERENCE_PLANNER_PROPOSAL — architecture only

Directive §6 (Inference Planner), §7 (Resource Planner) and §12 (backend diagnostics).
**"Do not implement this yet. Produce an architecture proposal only."** Nothing here is code and
nothing here is a bead to start now; every proposed bead is M2 or later and is listed in
`ADOPTION_MATRIX.md` §3.

Upstream evidence from `jegly/OfflineLLM` @ `e81091e86013c0605381d15a1ad7276a4be0b92b`,
Apache-2.0, reviewed 2026-09-23.

---

## 1. Why a planner, in one paragraph

Skein currently treats backend as a **static build-time default** (`SKEIN_LLAMA_DEFAULT_BACKEND`,
one word in `inference-service/build.gradle.kts`) plus a **static per-load integer**
(`LoadRequest.gpuLayers`), and context as a **constant** (`InferenceConfig.contextLengthCap =
16_384`). Three facts from this review say that is not enough:

1. CPU and Vulkan win at *different phases* of the same request — prompt processing is
   compute-bound, generation is bandwidth-bound — so one backend per session is the wrong shape
   (§3).
2. Whether "CPU" is actually CPU is currently a property of llama.cpp's device-list default, not
   of Skein's configuration (`VULKAN_ANALYSIS.md`). A planner that cannot observe the real
   backend plans nothing.
3. The right context window depends on weights, layer count, cache type and device RAM, none of
   which Skein consults (`MEMORY_ANALYSIS.md` §5).

Neither PocketPal nor OfflineLLM has a planner. This is differentiating work, and the useful thing
this review provides is the **primitives** — a diagnostic surface and a bench entry point — plus
one strong field claim about Mali.

---

## 2. What OfflineLLM has

**Static, user-facing, no measurement.** `useGpu: Boolean` (default false), `gpuLayers: Int`
(default 99 = all)
([`SettingsRepository.kt#L238-L245`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/data/repository/SettingsRepository.kt#L238-L245)),
read into `nGpuLayers = if (useGpu) gpuLayers else 0`
([`ModelManager.kt#L181`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/src/main/java/com/jegly/offlineLLM/ai/ModelManager.kt#L181)).
A toggle plus a slider, with per-layer offload control, and nothing that measures.

The recommendation is delivered as **prose in the README**:

> *"Biggest wins on Adreno-class GPUs and anything with cooperative-matrix support; Mali midrange
> may tie the CPU. … Note that on many mobile GPUs — Mali especially — token generation is
> memory-bandwidth-bound and the CPU kernels can be *faster*; if the GPU toggle feels slower, it
> probably is, so turn it back off."*
> ([`README.md#L100`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L100))

That is the field-guidance form of the planner's job, and it is directly on-point for Skein's
development device: **Mali-G715**. It is not a measurement and must not be cited as one — but it
is a maintainer's aggregated field experience, it matches the M0 smoke's flat 5.61 tok/s tg
across configurations (handoff §9), and it argues that `SKEIN_LLAMA_DEFAULT_BACKEND = "vulkan"`
is a hypothesis `skein-5hr` must test rather than a settled answer.
**Matrix OL-55: REFERENCE.**

---

## 3. Phase-split backend selection

The strongest architectural idea available, and it is compounded from two sources.

**From OfflineLLM's threading comment** ([`LLMInference.cpp#L161-L164`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L161-L164)):
prompt processing is compute-bound and scales across all cores; generation is memory-bound and
prefers the big cores. Two phases, two optimal configurations, already acknowledged at the
*thread* level.

**From llama.cpp's own scheduler** (`VULKAN_ANALYSIS.md` §3): `op_offload` already implements
phase-split backend selection, keyed on batch size with a 32-token threshold
([`ggml-vulkan.cpp#L19823-L19827`](https://github.com/ggml-org/llama.cpp/blob/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L19823),
`#L19986`). *Prompt processing → GPU, generation → CPU* is not a policy Skein must build — **it is
llama.cpp's default behaviour when the Vulkan device is in `model->devices`.**

That reframes §6's hybrid-policy question completely. The hybrid policy is the *status quo*; the
open question is whether it is a good one on this hardware, and the knobs already exist:

| Knob | Effect | Where |
|---|---|---|
| `model_params.devices` | which backends exist at all | `llama_model_params` — the OL-01 fix |
| `context_params.op_offload` | phase-split on/off | `llama_context_params` (default `true`) |
| `GGML_OP_OFFLOAD_MIN_BATCH` | the phase boundary, in tokens | env var, ggml-vulkan |
| `n_gpu_layers` | weight residency | `llama_model_params` |
| `n_threads` / `n_threads_batch` | CPU phase split | `llama_context_params` |
| `no_host` | CPU buffer-type ordering | `llama_model_params` |

**Six levers, four of which Skein does not currently touch.** A planner is a policy over these,
not new machinery. That is a much smaller proposal than §6 implies, and it is the finding.

The three profiles worth defining:

| Profile | `devices` | `n_gpu_layers` | `op_offload` | Intent |
|---|---|---|---|---|
| `CPU_ONLY` | CPU+ACCEL only | 0 | n/a | genuinely CPU; the OL-01 configuration |
| `HYBRID` | default (CPU + iGPU) | 0 | `true` | weights on CPU, prompt batches to GPU — **what Skein does today by accident** |
| `GPU_OFFLOAD` | default | 99 | `true` | weights on GPU |

`HYBRID` deserves a name precisely because it is the current unlabelled state, and naming it is
what turns escalation E-2 from a bug into a configuration.

---

## 4. The measurement primitive

`benchModel(pp, tg, pl, nr)` calls llama.cpp's own bench loop
([`LLMInference.cpp#L462-L536`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/LLMInference.cpp#L462-L536)),
exposed through JNI to the UI. It clears memory between the pp and tg phases
(`#L478`, `#L486`, `#L499`), computes mean and sample standard deviation across `nr` repeats
(`#L517-L523`), and returns a formatted string with model description, size and parameter count.

This is PocketPal's **PP-51** exactly — same `bench(pp, tg, pl, nr)` shape, same absence of a
thermal gate, and here the standard deviation is at least computed rather than discarded. Three
weaknesses for planner purposes:

1. **No thermal gate.** A bench on a warm phone measures the throttle. Skein's
   `tools/m0-benchmark/lib/thermal.sh` and `core/inference/thermal/ThermalGovernor` already solve
   this; a planner bench must gate on `ThermalStatusSource` before and after, and discard a run
   that crossed a threshold.
2. **Returns a formatted string.** A planner needs numbers. Skein's equivalent must return a
   parcelable.
3. **The tg loop reuses token id 0 at `pos = i` across `pl` parallel sequences**
   (`batchAdd(g_batch, 0, i, { j }, true)`, `#L491`). Fine for throughput; it measures nothing
   about real sampling.

**Matrix OL-54: ADAPT.** The value is the shape — `(pp, tg, pl, nr)` with clears between phases
and a reported standard deviation — as the *in-app* bench primitive, with a thermal gate and a
typed result. `tools/m0-benchmark` stays the authority for `docs/MEASUREMENTS.md`; the in-app
bench exists to plan, not to publish. PP-51's conclusion stands: Skein's harness is already the
stricter one.

---

## 5. The diagnostic surface (§12)

**Prerequisite for everything above.** Without it, a planner's inputs are unobservable and every
regression test in `REGRESSION_TEST_PROPOSAL.md` is unwritable.

OfflineLLM's `getBackendReport()`
([`smollm.cpp#L88-L140`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/smollm.cpp#L88-L140))
and `getGpuDeviceName()` (`#L60-L79`) are the model: walk `ggml_backend_reg_count()`, pull
`ggml_backend_get_features` via `ggml_backend_reg_get_proc_address`, list every device with type
and description. Analysed in `CPU_DISPATCH_ANALYSIS.md` §4.

§12's requested fields, mapped to a source:

| Field | Source | Privacy |
|---|---|---|
| backend(s) registered, with feature flags | `ggml_backend_get_features` | safe |
| device list: name, type, description | `ggml_backend_dev_*` | safe (GPU model name) |
| CPU capabilities (DOTPROD, I8MM, SVE, SME) | same feature list | safe |
| llama.cpp revision | `skein_llama_version()` — **already exported** (`native/llama/skein_llama_backend.c`) | safe |
| effective backend default | `skein_llama_default_backend()` — **already exported** | safe |
| Vulkan compiled in | `skein_llama_vulkan_available()` — **already exported** | safe |
| context, KV cache type, GPU layers, threads, n_batch | `LoadRequest` + `llama_n_ctx` | safe |
| model digest, quantization, architecture | manifest `sha256` + `skein-hewz`'s `general.file_type` / `general.architecture` | **digest and quant only — never the file name, never the path, never the manifest `name`** |
| model RAM, buffer sizes | llama.cpp load log / `skein-f9zu` | safe |
| pp tok/s, tg tok/s, TTFT | §4's bench + the existing `GenerateResult` timings | safe |
| thermal state, memory pressure | `ThermalStatusSource`, `MemoryMonitor`-equivalent | safe |
| **excluded, always** | prompts, vault contents, document names, conversation titles, model display names, file paths | — |

Two design rules, both from §12's "privacy-safe diagnostic artifact" requirement:

- **Allowlist, never denylist.** Build the report from an explicit field list so a future field
  cannot leak by default. The same discipline `LlamaLogRedactor` applies to logs.
- **The report is a parcelable, and its `toString()`/export is a separate, tested projection.**
  OfflineLLM returns a display string from C++, which makes "is this safe to share?" a code-review
  question every time it changes.

Shape: `IInferenceService.backendReport(): BackendReport` — a new AIDL method beside
`SKEIN_HUB.md` §3.3's `inspect`, returning `{ backends: List<BackendInfo>, devices:
List<DeviceInfo>, llamaVersion: String, effectiveBackend: String, vulkanCompiledIn: Boolean }`.
No model required, so it can be called at process start.

---

## 6. The Resource Planner (§7)

`model + device → recommended context`, with an advanced override.

**Inputs, and where each comes from:**

| Input | Source | Bead |
|---|---|---|
| `<arch>.context_length` (declared) | `skein-hewz` pre-check / `inspect` | `skein-hewz` |
| `general.file_type` (quantization) | same | `skein-hewz` |
| `general.architecture` | same | `skein-hewz` |
| `n_layer`, head dims, `n_embd` | `inspect` (llama.cpp metadata, not a hand parser) | `SKEIN_HUB.md` §3.3 |
| weights size | manifest `size_bytes` | exists |
| KV bytes/token | PP-45's cache-type table | `skein-f9zu` |
| SWA / sliding-window clamp | PP-45 | `skein-f9zu` |
| compute buffer | `n_ubatch × n_vocab × 4` **only if `n_outputs_max` is uncapped** — see below | this review |
| device RAM, current availability | `ActivityManager.MemoryInfo`, with PP-46's caveats | `skein-f9zu` |
| learned ceiling | PP-46 `largestSuccessfulLoad`, cold start `min(60 % RAM, RAM − 1.2 GB)` | `skein-f9zu` |

**The policy** — the shape transfers from OfflineLLM, the content does not:

```
recommended = min(
    declaredContextLength,          // from the GGUF
    fitsInBudget(weights, kv(ctx), compute, availableRam, learnedCeiling)
)
effective   = userOverride ?: recommended         // advanced setting always wins
```

OfflineLLM implements exactly this with `fitsInBudget` replaced by a three-rung file-size ladder
([`SmolLM.kt#L53-L66`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/java/com/jegly/offlineLLM/smollm/SmolLM.kt#L53-L66),
two of whose rungs are the same value). **Matrix OL-13: ADAPT the four-step shape; reject the
ladder.**

**Three constraints this review adds to `skein-f9zu`:**

1. **Do not fit the model before OL-07 and OL-01 land.** The compute-buffer term is currently
   inflated by 297–512 MiB of unread logits, inside a Vulkan host allocation. An estimator
   calibrated today encodes the bug (escalation E-3).
2. **The compute-buffer term is a function of `n_outputs_max`**, which is a Skein-controlled
   parameter. Once capped at 1 it collapses to a small constant. Model it as a parameter, not a
   measurement.
3. **Report a verdict, never silently clamp.** Skein's `ContextBudget` computes a budget; the
   Resource Planner computes a *ceiling* and must surface why. OfflineLLM's silent
   `minOf(rawContextSize, maxContextBySize)` means a user who set 32k and got 4k has no way to
   find out. PP-46's three-state verdict (fits / tight / will not fit) is the right surface.

---

## 7. Where the planner runs, and what it must not become

**In `:app`, not `:inference`.** The planner needs device RAM, thermal state, the learned ceiling
(persisted), and user preferences — none of which belong in an isolated process that should stay
stateless between requests. It consumes `inspect()` and `backendReport()` results over AIDL and
produces a `LoadRequest`. That keeps the isolated service a pure executor, which is what makes
`KV_CACHE_ANALYSIS.md` §4's prefix-cache design and `IsolatedSessionGate` work.

**Three things it must not become**, all §15 risks:

- **A benchmark that runs on the user's device at load time.** A short bench costs seconds and
  heat. The planner should use a *profile table* keyed on (device model, model digest, profile)
  populated by `tools/m0-benchmark` and by explicit user-initiated benchmarks, falling back to a
  conservative default. PocketPal's **PP-74** (a versioned device-rules document) is the shape —
  and its **PP-75** (fetched unsigned from a CDN) is the anti-pattern; Skein's table ships in the
  APK or arrives through the Hub with the Hub's own trust gates. Never a bare HTTP fetch.
- **Telemetry.** No result leaves the device. PocketPal's PP-54 is the line.
- **A policy engine that outgrows six knobs.** The whole planner is a function from
  `(DeviceProfile, ModelProfile, ThermalState)` to a small record of llama.cpp parameters. If it
  needs more than that, the complexity is in the wrong place.

---

## 8. Proposed sequencing

| Stage | Depends on | Milestone |
|---|---|---|
| Backend/feature diagnostic over AIDL (OL-05) | — | **M1** |
| `CPU_ONLY` profile made real (OL-01) | — | **M0** |
| Name `HYBRID` and measure it as a third cell (R-5) | OL-01, OL-05 | M1 |
| In-app bench primitive with a thermal gate (OL-54) | OL-05, `ThermalGovernor` | M2 |
| Resource Planner: `min(declared, computed, override)` (OL-13) | `skein-f9zu`, `skein-hewz`, OL-07 | M2 |
| Inference Planner: profile table + selection policy | all of the above, `docs/MEASUREMENTS.md` | M2/M3 |

The first two are the only ones that touch M0, and the first is a prerequisite for trusting any
number the others produce.
