# `native/llama` — `libskein_llama.so`

The Android shared object that carries llama.cpp and ggml into Skein's
isolated inference process (`:inference-service`, `android:isolatedProcess`).
One `.so`, statically linked, no runtime `dlopen` of backends, no network at
build time.

Owned by **E1.I4 / bd `skein-ca2`**. The JNI bindings on top of it are
**E4.I1 / bd `skein-3aw`** — `jni_stub.cpp` here is a deliberate placeholder.

---

## 1. What gets built

| ABI | Flavor | Backends | Stripped size (release) |
|---|---|---|---|
| `arm64-v8a` | `foss`, `dev` | CPU + Vulkan | 25,270,688 B = **24.10 MiB** |
| `arm64-v8a` | (`-DSKEIN_LLAMA_VULKAN=OFF`) | CPU | 4,469,616 B = 4.26 MiB |
| `x86_64` | `dev` only | CPU | 4,987,624 B = 4.76 MiB |

`x86_64` exists for the emulator lane only. Android emulators expose at best a
software Vulkan ICD, and no measurement backs running inference on one, so that
ABI is CPU-only by construction (`native/llama/CMakeLists.txt` §3) rather than
by configuration.

> **Open size question.** E1.I4's acceptance criteria budget **≤ 12 MB** for
> the Vulkan `.so`, and the real figure is **24.10 MiB**. This is not slack in
> our build: `.rodata` is 19.27 MiB of embedded SPIR-V — 1115 shader variants
> that `vulkan-shaders-gen` emits for every quantisation type crossed with
> every matmul/FA variant. llama.cpp v0.4.1 exposes no option to narrow that
> set, and it is already trimmed as far as upstream allows (the coopmat /
> coopmat2 / bfloat16 / FP8 / FP4 / integer-dot families are excluded because
> the NDK's glslc cannot compile them — which also matches Mali-G715, which
> exposes none of them). See §7 for the options and the bead tracking it.

## 2. Pinned inputs

Everything that reaches the `.so` is pinned, and the pins live in exactly one
file: [`PINNED_COMMIT`](PINNED_COMMIT). `tools/ci/check-submodules.sh` fails
the build if the checked-out commit or the recorded gitlink disagrees with it,
and `CMakeLists.txt` §2 re-checks it at configure time so a stale
`git submodule update` fails with a named error instead of a confusing compile
error three minutes in.

| Input | Pin | Why this form |
|---|---|---|
| `third_party/llama.cpp` | `v0.4.1` = `b29c606e…` | submodule |
| `third_party/Vulkan-Headers` | `vulkan-sdk-1.4.350.0` | submodule |
| `third_party/SPIRV-Headers` | `vulkan-sdk-1.4.350.0` | submodule |
| NDK | `27.3.13750724` (r27c) | `inference-service/build.gradle.kts` `ndkVersion` |
| `glslc` | the NDK's own `shader-tools` | pinned transitively by `ndkVersion` |

**Why submodules and not sha256-pinned tarballs**, when `native/sqlite` fetches
OpenSSL as a tarball: OpenSSL publishes stable, signed release tarballs whose
sha256 is a durable identifier. llama.cpp and the Khronos header repos do not
— the only downloadable archives are GitHub's auto-generated ones, whose bytes
are a function of the server's compression settings and have changed under
their tags before. A git commit is already a content hash of the whole tree, so
a submodule pin is the *stronger* guarantee here, not the weaker one. E1.I4's
acceptance criteria call for a submodule as well.

**Why `v0.4.1` specifically.** It is the tag the plan names, and independently
it is the exact revision the M0 Fold binaries were built from:
`tools/m0-benchmark/models.yaml` records
`llama_cpp_commit: b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` for all four
`llama-bench` / `llama-cli` artifacts, and `v0.4.1^{}` resolves to that same
object. The app and the device measurements therefore share one llama.cpp
revision — a device number means something about the shipped library.

**Why Khronos headers at all** (E1.I4 says "no external Vulkan SDK download"):
no Vulkan SDK is installed or downloaded, and the Vulkan *loader* we link is
the NDK's own `libvulkan.so`. But NDK r27c's sysroot ships
`VK_HEADER_VERSION 275` (Vulkan 1.3.275, Jan 2024) and **no Vulkan-Hpp at
all**, while `ggml-vulkan.cpp` does `#include <vulkan/vulkan.hpp>` and uses API
added long after 1.3.275. Khronos' two header-only repos are the minimum that
closes that gap, and they are pinned like everything else.

## 3. Build recipe

```bash
git submodule update --init --recursive
./gradlew :inference-service:assembleFossRelease   # arm64-v8a, CPU + Vulkan
./gradlew :inference-service:assembleDevDebug      # + x86_64, CPU only
```

The `.so` lands in the AAR's `jni/<abi>/` and, from there, in the APK's
`lib/<abi>/`. Nothing else is required — no Vulkan SDK, no `glslc` on `PATH`,
no network access beyond the one-time submodule clone.

### Shaders

Vulkan shaders are **compiled from source at build time**, not vendored as
pre-generated SPIR-V. llama.cpp builds its own `vulkan-shaders-gen` host tool
(an `ExternalProject` inside `ggml-vulkan`) and drives the NDK's `glslc` over
`ggml/src/ggml-vulkan/vulkan-shaders/*.comp`. That keeps the chain honest:
`same glslc + same .comp + same -D set ⇒ same SPIR-V`, with no blob in the
repo whose provenance has to be re-established, and nothing extra to hash.
`glslc` is pinned by `ndkVersion`.

NDK r27c's `glslc` is `shaderc v2022.3`. ggml-vulkan feature-probes it and
silently drops the shader families it cannot compile —
`GL_KHR_cooperative_matrix`, `GL_NV_cooperative_matrix2`,
`GL_EXT_integer_dot_product`, `GL_EXT_bfloat16`, `GL_EXT_float_e2m1`,
`GL_EXT_float_e4m3`. That is the correct outcome for this target rather than a
compromise: Mali-G715 (Tensor G3, the Pixel 9 Pro Fold) exposes none of those
extensions either. It does mean a future NDK bump can change both the shipped
shader set and the `.so`'s size — which is one more reason `ndkVersion` is
pinned and `check-submodules.sh` exists.

### The backend default

`SKEIN_LLAMA_DEFAULT_BACKEND` (`vulkan` | `cpu`) is set in one place —
`inference-service/build.gradle.kts` — and feeds both the native build and
`BuildConfig.LLAMA_DEFAULT_BACKEND`, so Kotlin and C cannot drift.

It currently reads `vulkan`, on the strength of the M0 Fold smoke (23.47 pp /
5.61 tg on Qwen 2.5 3B Q3_K_M over Mali-G715 — handoff §9). **That is not the
final answer**: `inference_backend` is a `docs/MEASUREMENTS.md` decision
(bd `skein-5hr`), and flipping it is one word.

Two accessors, both exported:

```c
const char *skein_llama_default_backend(void);  /* "vulkan" | "cpu" */
int         skein_llama_vulkan_available(void); /* compiled in? */
const char *skein_llama_version(void);          /* "0.4.1" */
```

`skein_llama_default_backend()` returns the **effective** value for the ABI
that is actually loaded — `"cpu"` on `x86_64` even when `vulkan` was requested,
because that ABI has no Vulkan backend compiled in. When it and
`BuildConfig.LLAMA_DEFAULT_BACKEND` disagree, the native one is the truth about
the library in the process.

### Exported symbols

`-fvisibility` is deliberately **not** forced to hidden. llama.cpp compiled
statically leaves `LLAMA_API` / `GGML_API` expanding to nothing, i.e. every
symbol has default visibility — which is what lets a version script name them
(a version script cannot re-expose an `STV_HIDDEN` symbol; same reason
`native/sqlite` forces `-fvisibility=default` on the amalgamation, skein-6rwv).
The generated `skein_llama.exports.ld` then trims the set with `local: *;`.

The whitelist is scraped at configure time from the public headers
(`include/llama.h`, `ggml/include/*.h`) for identifiers with the `llama_`,
`ggml_` and `gguf_` prefixes, plus our own `skein_*` and the `JNI_On{Load,
Unload}` hooks E4.I1 will need. 875 symbols; deduped and sorted so the script
bytes — and therefore the `.so` bytes — reproduce identically. A configure-time
assertion fails the build if the scrape loses any of `llama_backend_init`,
`llama_model_load_from_file`, `llama_decode` or `llama_sampler_chain_init`.

`-Wl,--undefined-version` is set because the headers declare APIs whose
definitions the option set compiles out; the exported set stays bounded by
"whitelist ∩ defined", so nothing leaks.

## 4. Reproducibility

Same contract as [`native/sqlite/README.md`](../sqlite/README.md), and the
same flags:

| Flag | What it prevents |
|---|---|
| `-ffile-prefix-map` (repo root, build dir, NDK) | absolute paths in `__FILE__` and DWARF `comp_dir` |
| `-fno-ident` | toolchain version string in `.comment` |
| `-Wdate-time -Werror=date-time` | any `__DATE__`/`__TIME__`/`__TIMESTAMP__` |
| `-Wl,--build-id=none` | a content hash note that also hashes paths |
| `-Wl,--version-script` | an export set that drifts with internals |
| `GGML_CCACHE=OFF` | object bytes depending on cache state, not inputs |
| `GGML_NATIVE=OFF` | `-march` depending on the *host* CPU |
| `LLAMA_BUILD_IS_DEV=OFF` | a `-dev` suffix that tracks nothing |
| `SOURCE_DATE_EPOCH` honoured (fallback `1767225600`) | timestamps |

These are applied with `add_compile_options` / `add_link_options` **before**
`add_subdirectory(llama.cpp)`, which is the only way to reach ~900 upstream
translation units we do not own.

`add_subdirectory` also satisfies skein-doq0's "never build in a dirty source
tree": the submodules are consumed strictly read-only, and every generated
byte — SPIR-V, the `vulkan-shaders-gen` host tool, every object file — lands
under `inference-service/.cxx/`, which `./gradlew clean` wipes. Nothing in this
directory writes into `third_party/`.

### The ELF guard

`cmake/verify_llama_elf.cmake` runs as a `POST_BUILD` step on **every** build
and fails it if the freshly linked `.so`:

1. is not for this `ANDROID_ABI` (skein-doq0's guard, ported — cross-ABI
   object pollution used to surface only as an opaque
   `ld.lld: … is incompatible with aarch64linux`);
2. carries a GNU build-id note (i.e. something appended `--build-id` after our
   `--build-id=none`);
3. embeds any absolute path from this machine.

(3) is the one that earns its keep. A *single* upstream translation unit
compiled outside the `-ffile-prefix-map` scope is enough to make the `.so`'s
sha256 depend on where the repo happens to live, which silently defeats E1.I8
(bd `skein-ddp`) and leaks the developer's home directory into a FOSS artifact.

### Proving it

```bash
./gradlew clean :inference-service:assembleFossRelease
shasum -a 256 inference-service/build/intermediates/stripped_native_libs/\
fossRelease/stripFossReleaseDebugSymbols/out/lib/arm64-v8a/libskein_llama.so
```

twice in the same checkout, and once more in a **separate `git clone` at a
different path**. A `git worktree` is not a valid second location for this
check (skein-8jtj): it shares object storage and AGP treats it differently
from a real clone.

Recorded for `b29c606e` / NDK r27c / macOS arm64:

| Build | sha256 of `lib/arm64-v8a/libskein_llama.so` |
|---|---|
| A, first clean build | `76fd7ce5dc9cb9774c6dcc64401d508830486e9427648286686161fdf779e2cf` |
| A, second clean build | `76fd7ce5dc9cb9774c6dcc64401d508830486e9427648286686161fdf779e2cf` |
| B, separate clone, different path | `76fd7ce5dc9cb9774c6dcc64401d508830486e9427648286686161fdf779e2cf` |

25,270,688 bytes in all three. `strings` on the result finds no machine-specific
path: the only absolute-looking strings are the constant, remapped
`/skein/third_party/llama.cpp/…` that ggml's `GGML_ASSERT` bakes in from
`__FILE__`, and those are identical on every machine by construction — which
is the whole point of `-ffile-prefix-map`.

## 5. What is deliberately NOT here

- **JNI bindings.** `jni_stub.cpp` declares no `Java_…` methods. E4.I1 owns
  the contract; an empty stub cannot accidentally freeze one.
- **`llama_log_set` wiring.** `LlamaLogRedactor.kt` (E1.I11 / bd `skein-4je`)
  exists and is tested, but routing ggml/llama log callbacks through it is part
  of E4.I1. A half-wired sink that fell through to `__android_log_write` would
  ship unredacted prompt text in the meantime.
- **On-device runs.** Anything touching the Fold is the hardware runner's
  (bd `skein-k3b2`). Host-side reproducibility and symbol checks are this
  bead's gate.
- **`GGML_CPU_ARM_ARCH`.** Left at the NDK default (`armv8-a`). Raising it to
  `armv8.5-a+fp16+i8mm` would likely help on Tensor G3, but it is a measured
  decision for `docs/MEASUREMENTS.md`, not a build-bead one, and it would
  exclude older arm64 devices.

## 6. Options the build exposes

| Option | Default | Notes |
|---|---|---|
| `SKEIN_LLAMA_VULKAN` | `ON` on arm64-v8a, `OFF` on x86_64 | the CPU-only fallback if the size budget wins |
| `SKEIN_LLAMA_DEFAULT_BACKEND` | `vulkan` | set from `inference-service/build.gradle.kts` |
| `SKEIN_LLAMA_SKIP_PIN_CHECK` | `OFF` | for source exports with no `.git` |

Upstream options are forced in `CMakeLists.txt` §6. The four the acceptance
criteria name — `LLAMA_CURL`, `LLAMA_BUILD_EXAMPLES`, `LLAMA_BUILD_TESTS`,
`LLAMA_BUILD_SERVER` — are all `OFF`, as are `LLAMA_BUILD_UI` and
`LLAMA_USE_PREBUILT_UI` (which would otherwise **download** a prebuilt web UI
bundle at configure time), `LLAMA_OPENSSL`, `LLAMA_SUBPROCESS` and
`GGML_RPC` (a genuine network backend).

## 7. Known issue: the Vulkan size budget

24.10 MiB against a 12 MB budget, of which ~19.3 MiB is embedded SPIR-V. This
also pressures E1.I10 (bd `skein-jn3`, ≤ 30 MB `foss` release APK), which the
12 MB figure exists to protect. Options, none of which belong to this bead:

1. **Ship CPU-only on arm64** (`-DSKEIN_LLAMA_VULKAN=OFF`): 4.26 MiB,
   comfortably inside both budgets, at the cost of the prompt-processing win
   the M0 smoke measured. One flag.
2. **Trim the shader set**: patch `vulkan-shaders-gen` to emit only the
   quantisations Skein actually ships (the formal M0 candidate is a single
   Q4_K_M model). Plausibly a ~75% cut, at the cost of carrying a patch
   against upstream and of refusing to run other GGUFs.
3. **Raise the budget**, if the measured Vulkan win justifies ~20 MB of APK.

This is a `docs/MEASUREMENTS.md` question (bd `skein-5hr`) plus an APK-budget
question (bd `skein-jn3`), and it is filed separately rather than decided here.
The build is arranged so that whichever way it goes, the change is one line.
