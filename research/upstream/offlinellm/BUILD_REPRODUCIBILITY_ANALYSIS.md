# BUILD_REPRODUCIBILITY_ANALYSIS

Directive §14, including the 24 MiB Vulkan size question. Upstream `jegly/OfflineLLM` @
`e81091e86013c0605381d15a1ad7276a4be0b92b` (tag `5.1.1`), Apache-2.0, reviewed 2026-09-23.

**Summary: OfflineLLM's build is not reproducible and makes no attempt to be — Skein should adopt
nothing from its plumbing. Two narrow exceptions are worth taking (a 16 KiB page-size flag, and
`-ffunction-sections`/`--gc-sections`), and one question §14 asks gets a clear negative answer:
OfflineLLM has no shader-trimming technique, because it ships all ~1 400 shaders uncut.**

---

## 1. Toolchain pinning

| Input | OfflineLLM | Skein |
|---|---|---|
| NDK | `27.2.12479018`, pinned in one place and reused for `ndkVersion`, the `glslc` path and the shader toolchain ([`smollm/build.gradle.kts#L15-L19`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L15-L19)) | `27.3.13750724` (`inference-service/build.gradle.kts:38`) |
| CMake | **"newest present in the SDK"** — a directory scan, version-sorted ([`#L21-L35`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L21-L35)) | version pinned via AGP |
| Gradle wrapper | `gradle-9.7.0-bin.zip`, **no `distributionSha256Sum`** ([`gradle-wrapper.properties`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/gradle/wrapper/gradle-wrapper.properties)) | — |
| llama.cpp | git submodule at `60eeeb6…`, correctly pinned | `third_party/llama.cpp` submodule at `b29c606…` + a `PINNED_COMMIT` file the CMake **verifies at configure time** (`native/llama/CMakeLists.txt:87-158`) |
| Vulkan-Headers | `git clone --branch v1.4.351 --depth 1`, into the project root, **git-ignored** ([`README.md#L130`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L130), [`.gitignore#L26-L27`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/.gitignore)) | submodule, pinned, `PINS.txt` recorded |
| SPIRV-Headers | `git clone` — **no tag, no SHA, unpinned `HEAD`** ([`README.md#L131`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L131)) | submodule, pinned |
| Host compiler for `vulkan-shaders-gen` | **`/usr/bin/gcc` and `/usr/bin/g++`, hard-coded** ([`smollm/build.gradle.kts#L57-L58`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L57-L58)) | NDK-hosted, prefix-mapped |
| `SOURCE_DATE_EPOCH` | not honoured | honoured, fallback `1767225600` |
| CI | none (`.github/` = `FUNDING.yml`) | `.github/workflows/ci.yml` with symbol and ELF guards |

The CMake line is the disqualifying one. `cmakeVersionPinned` is computed by listing
`$sdkDir/cmake`, filtering for directories containing `bin/ninja`, and taking the numerically
highest — so **the compiler-adjacent toolchain depends on what the developer happened to install
last.** The comment frames this as a feature ("so installing a newer one from the SDK Manager is
picked up without editing this file"), which it is, for convenience. It is the exact opposite of
what a reproducible build needs, and `GGML_CCACHE` is left at its default on top of that.

SPIRV-Headers at unpinned `HEAD` is the second: two people building the same tag on different
days can compile against different SPIR-V grammar headers.

Skein's `native/llama/README.md` §4 table is the correct posture and is already in place
(`-ffile-prefix-map` ×3 roots, `-fno-ident`, `-Wdate-time -Werror=date-time`,
`-Wl,--build-id=none`, a version script, `GGML_CCACHE=OFF`, `GGML_NATIVE=OFF`,
`LLAMA_BUILD_IS_DEV=OFF`, `SOURCE_DATE_EPOCH`, the `patches/` + `glslc` wrapper). **No change.**

**Matrix OL-48/OL-49/OL-51: REJECT.** The comparison's value is as evidence: *this is what a
llama.cpp Android build looks like when reproducibility is not designed in*, and the gap is not a
few flags — it is the toolchain discovery model.

### F-Droid status

`com.jegly.offlineLLM.yml` ([permalink](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/com.jegly.offlineLLM.yml))
declares one `Builds:` entry — `versionName: 5.0.2`, `versionCode: 7`, `ndk: r27c`, with
`CurrentVersion: 5.0.2`. The repository is 5.1.1 / versionCode 9. So **the Vulkan-era releases are
not built by F-Droid**, and the build recipe there predates the Khronos-header requirement the
5.1.x README introduces — an F-Droid build of 5.1.x from that recipe would fail at configure.
Consequence for this review: **OfflineLLM's published 5.1.1 APK is not an independently
reproduced artifact**, and no claim in this directory rests on a binary. Every finding here is
from source. **Matrix OL-52: REFERENCE.**

---

## 2. NDK / ABI / packaging

`abiFilters += "arm64-v8a"` in both modules
([`app/build.gradle.kts#L20-L22`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/build.gradle.kts#L20),
[`smollm/build.gradle.kts#L72-L74`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L72)),
`minSdk = 33`, `compileSdk = 37`, `targetSdk = 37`. One ABI only, stated in the README as a
requirement.

Skein additionally builds `x86_64` (with `SKEIN_LLAMA_VULKAN` forced `OFF` there,
`native/llama/CMakeLists.txt:184-185`) for emulator work. That is a real cost OfflineLLM does not
pay and a real capability it does not have. **No change.**

`useLegacyPackaging = true` ([`app/build.gradle.kts#L48-L54`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/build.gradle.kts#L48))
is required by `GGML_BACKEND_DL` and analysed in `CPU_DISPATCH_ANALYSIS.md` §1. **Matrix OL-06:
REJECT for Skein** — it un-compresses every native library in the APK, which works directly
against `skein-jn3`'s ≤ 30 MB target, and Skein's static build does not need it.

`isMinifyEnabled = true` + `isShrinkResources = true` for the app's release build
([`app/build.gradle.kts#L25-L34`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/app/build.gradle.kts#L25)),
but `isMinifyEnabled = false` for the `smollm` library
([`smollm/build.gradle.kts#L148-L153`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L148)) —
correct: R8 must not rename classes whose names are encoded in JNI symbol strings
(`Java_com_jegly_offlineLLM_smollm_SmolLM_loadModel`). Skein has the same hazard and handles it
with a version script plus `tools/ci/jni-symbols.sh`. **Observation; no change.**

---

## 3. The Vulkan build

OfflineLLM's `smollm/build.gradle.kts` solves the same four problems Skein's
`native/llama/CMakeLists.txt` §5 solves, and lands on the same answers:

| Problem | OfflineLLM | Skein |
|---|---|---|
| NDK sysroot has no Vulkan-Hpp | `-DVulkan_INCLUDE_DIR=<Vulkan-Headers/include>` ([`#L111-L124`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L111)) | pinned submodule, with the VK_HEADER_VERSION reasoning in `native/llama/README.md` §5 |
| `FindVulkan` can't find `glslc` when cross-compiling | `-DVulkan_GLSLC_EXECUTABLE=$sdkDir/ndk/…/shader-tools/linux-x86_64/glslc` ([`#L109`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L109)) — **hard-codes `linux-x86_64`**, so a macOS host cannot build | host-tag detection (`_NDK_HOST_TAG`, `native/llama/CMakeLists.txt:56-62`) |
| `vulkan-shaders-gen` must run on the host | a generated `vulkan-host-toolchain.cmake` pinning `/usr/bin/gcc`, `/usr/bin/g++` and the SDK's `ninja` ([`#L37-L61`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L37-L61)) | same shape, NDK-hosted |
| `ggml-vulkan.cpp` includes `<spirv/unified1/spirv.hpp>` directly | `include_directories(SYSTEM ...)` before `add_subdirectory`, **added in 5.1.1** ([`smollm/src/main/cpp/CMakeLists.txt#L4-L12`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/CMakeLists.txt#L4-L12)) | `include_directories(SYSTEM "${SPIRV_HEADERS_DIR}/include")` with an existence check (`native/llama/CMakeLists.txt:555-562`) — **the same fix, arrived at independently** |

The 5.1.1 SPIRV hunk carries an argument Skein's own CMake makes in parallel and which is worth
recording as cross-validated:

> *"The include dir arrives as a cache var rather than through cppFlags: cppFlags lands in
> `CMAKE_CXX_FLAGS`, which is whitespace-split (so it breaks on any checkout path containing a
> space) and is applied globally to every C++ target rather than just the one that needs it."*
> ([`smollm/src/main/cpp/CMakeLists.txt#L4-L9`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/CMakeLists.txt#L4-L9))

Skein does not use AGP `cppFlags` for this either. **Independent corroboration of an existing
decision — no change, but worth a citation in `native/llama/README.md` §5.** **Matrix OL-50:
REFERENCE.**

**Shader determinism: OfflineLLM has nothing.** Skein carries
`patches/0001-vulkan-shaders-float16-zero-literal.patch` and a `glslc` wrapper specifically
because *"the shader compiler is not deterministic"* (`native/llama/README.md` §4a). OfflineLLM
does not address it and, having no reproducibility goal, does not need to. **No help available
from this direction.**

---

## 4. The 24 MiB question — §14's actual ask

> *Determine whether OfflineLLM contains techniques that could later reduce Skein's native
> footprint without restricting arbitrary GGUF support, carrying fragile patches, harming Vulkan
> performance, or damaging reproducibility.*

**Answer: essentially no.** OfflineLLM ships the full shader set and never attempts a trim:

> *"First build compiles llama.cpp from source, including ~1,400 Vulkan compute shaders and seven
> CPU-variant libraries (~20–30 min)."*
> ([`README.md#L141`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/README.md#L141))

`grep` across `smollm/build.gradle.kts` and `smollm/src/main/cpp/CMakeLists.txt` for any shader
subsetting option (`GGML_VULKAN_*`, a shader allowlist, a quantization filter): **no hits.**
OfflineLLM's answer to the size question is to pay it — nine `.so` files, all uncompressed on disk
because of `useLegacyPackaging`. It publishes no APK size figure and no `.so` size figure, and
none was measured here (no build was run; §1's guardrail).

So `native/llama/README.md` §7's option 2 ("trim the shader set… plausibly a ~75 % cut, at the
cost of carrying a patch against upstream and of refusing to run other GGUFs") gets **no upstream
precedent, no reference implementation, and no evidence that anyone has done it.** That is a
genuine finding: it means option 2 is bespoke engineering with an unbounded maintenance cost, and
`native/llama/README.md` §7's tentative choice — **raise the budget and keep Vulkan** — is the one
a comparable project also effectively made. It is weak evidence, but it is evidence, and it points
the same way as the directive's own tentative decision.

### What *is* worth taking: four link/compile flags

```cmake
target_compile_options(llama PUBLIC -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_options(llama PUBLIC -ffunction-sections -fdata-sections)
target_link_options(llama PRIVATE -Wl,--gc-sections -flto -Wl,--exclude-libs,ALL)
```
([`smollm/src/main/cpp/CMakeLists.txt#L22-L37`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/CMakeLists.txt#L22-L37),
repeated for the `smollm` and `ggufreader` targets at `#L55-L74` and `#L84-L97`.)

Skein applies `add_compile_options(-fno-ident, -ffile-prefix-map×3, -Wdate-time)` and
`add_link_options(-Wl,--build-id=none)` before `add_subdirectory`
(`native/llama/CMakeLists.txt:237-251`), plus a version script at link
(`:849`). It does **not** use `-ffunction-sections -fdata-sections -Wl,--gc-sections`.

Assessment against §14's four constraints:

- **`-ffunction-sections -fdata-sections` + `-Wl,--gc-sections`** — genuinely useful. Skein
  whole-archives every llama.cpp/ggml static library into one `.so`, so any unreferenced function
  is dead weight; section-level GC removes it. It does **not** restrict GGUF support (it is
  reachability-based, not feature-based), needs no patch, and cannot affect Vulkan performance.
  Reproducibility impact should be **nil but must be verified**: section GC is deterministic given
  identical inputs, and Skein already has the mechanism to prove it (`native/llama/README.md` §4's
  double-build check and the recorded `.so` hashes). The ~19.3 MiB of SPIR-V is *referenced* data,
  so the saving comes off the ~4.8 MiB of code, not off the shaders — **expect single-digit MiB at
  best, possibly under 1 MiB.** Worth a measurement, not a promise. **Matrix OL-47: ADAPT, M2**,
  gated on the double-build hash check.
- **`-flto`** — Skein already sets `GGML_LTO OFF` (`native/llama/CMakeLists.txt:625`), and LTO is
  a known reproducibility hazard (link-order and partition-count sensitivity). **Do not take.**
- **`-fvisibility=hidden`** — Skein achieves the same with a version script, and
  `native/llama/CMakeLists.txt:723-724` records that forcing default visibility is what lets the
  script name symbols. **Do not take**; it would conflict.
- **`-Wl,--exclude-libs,ALL`** — largely redundant given the version script. Harmless, no benefit.

---

## 5. `LLAMA_BUILD_COMMON=OFF` and network-reachability

Already noted in `ARCHITECTURE_ANALYSIS.md` §2.4: OfflineLLM disables `common` explicitly because
it *"contains upstream's HTTP/download machinery, which has no place in a zero-network app"*
([`smollm/src/main/cpp/CMakeLists.txt#L62-L64`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/src/main/cpp/CMakeLists.txt#L62-L64),
flag at [`smollm/build.gradle.kts#L85-L87`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L85)).

Skein forces the same plus `LLAMA_CURL`, `LLAMA_OPENSSL`, `LLAMA_SUBPROCESS`, `GGML_RPC`,
`LLAMA_BUILD_UI` and `LLAMA_USE_PREBUILT_UI` off, the last two with the note that they would
*download a prebuilt web UI bundle at configure time* (`native/llama/CMakeLists.txt:588-604`).
**Skein's set is a strict superset.** Two independent zero-network projects converging on
`LLAMA_BUILD_COMMON=OFF` is worth one citation in `native/llama/README.md` §6. **Matrix OL-21:
REFERENCE.**

---

## 6. 16 KiB page alignment — the one flag to take

```kotlin
arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
```
([`smollm/build.gradle.kts#L82`](https://github.com/jegly/OfflineLLM/blob/e81091e86013c0605381d15a1ad7276a4be0b92b/smollm/build.gradle.kts#L82))

This is the NDK's supported switch for producing shared libraries whose `PT_LOAD` segments are
aligned for 16 KiB page-size devices. It is PocketPal's **PP-48** (`pocketpal-ai#512`: `SIGBUS` on
load from 4 KiB-aligned `.so` files on 16 KiB-page Android 16 devices) with the *remedy* attached
rather than just the symptom.

Skein sets neither it nor `-Wl,-z,max-page-size=16384` anywhere — verified by grepping
`native/llama/`, `native/sqlite/`, `inference-service/build.gradle.kts` and `tools/ci/`.
`skein-xth5` exists to add the **check** (`tools/ci/elf-alignment.sh`, `p_align >= 16384` on every
`PT_LOAD` of both shipped `.so` files) and already names `-Wl,-z,max-page-size=16384` as the fix.

Two things this review adds to `skein-xth5`:

1. **A second, more idiomatic remedy.** `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` is an NDK-level
   CMake variable that sets the right linker flags for the toolchain, rather than a raw `-Wl,-z`
   Skein would have to maintain. Prefer it.
2. **NDK r27 may already do this by default for `arm64-v8a`.** Skein is on NDK 27.3 and
   OfflineLLM on 27.2, and OfflineLLM sets the flag explicitly anyway. Whether Skein's current
   `.so` files are already 16 KiB-aligned is a question `skein-xth5`'s check answers in one CI
   run — and the right sequence is **check first, then add the flag only if the check fails**, so
   the flag is not added as cargo. Skein's `native/llama/README.md` §4 also records `.so` hashes,
   so adding the flag means re-recording them; another reason to check before changing.

**Matrix OL-46: ADOPT (conditionally), M1** — as an amendment to `skein-xth5` rather than a new
bead.

---

## 7. Findings

| Id | Item | Class | Milestone |
|---|---|---|---|
| OL-46 | `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` | **ADOPT** (conditional on `skein-xth5`'s check failing) | M1 |
| OL-47 | `-ffunction-sections -fdata-sections` + `-Wl,--gc-sections` | **ADAPT** — measure the saving and re-verify the double-build hash; do **not** take `-flto` or `-fvisibility=hidden` | M2 |
| OL-06 | `useLegacyPackaging = true` for `GGML_BACKEND_DL` | **REJECT** | — |
| OL-21 | `LLAMA_BUILD_COMMON=OFF` for zero-network | **REFERENCE** — Skein's set is a superset | — |
| OL-48 | CMake version = newest in the SDK | **REJECT** | — |
| OL-49 | SPIRV-Headers cloned at unpinned `HEAD`; hard-coded `/usr/bin/gcc`; `linux-x86_64` glslc path | **REJECT** | — |
| OL-50 | `vulkan-host-toolchain.cmake` for `vulkan-shaders-gen`; SPIRV include via cache var not `cppFlags` | **REFERENCE** — corroborates Skein's identical solutions | — |
| OL-51 | No `distributionSha256Sum`, no CI | **REJECT** | — |
| OL-52 | F-Droid metadata pinned at 5.0.2 while the repo is 5.1.1 | **REFERENCE** — published 5.1.1 APK is unreproduced; all findings here are from source | — |
| OL-53 | ~1 400 shaders shipped uncut; no trimming attempted | **REFERENCE** — no upstream precedent for `native/llama/README.md` §7 option 2 | — |
