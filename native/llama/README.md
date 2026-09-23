# `native/llama` — `libskein_llama.so`

The Android shared object that carries llama.cpp and ggml into Skein's
isolated inference process (`:inference-service`, `android:isolatedProcess`).
One `.so`, statically linked, no runtime `dlopen` of backends, no network at
build time.

The build is owned by **E1.I4 / bd `skein-ca2`**; the JNI bindings under
`jni/` by **E4.I1 / bd `skein-3aw`**, which replaced `jni_stub.cpp` with
`jni/skein_jni.cpp` (see [§8](#8-the-jni-layer-e4i1)).

---

## 1. What gets built

| ABI | Flavor | Backends | Stripped size (release) |
|---|---|---|---|
| `arm64-v8a` | `foss`, `dev` | CPU + Vulkan | 25,303,280 B = **24.13 MiB** |
| `arm64-v8a` | (`-DSKEIN_LLAMA_VULKAN=OFF`) | CPU | 4,469,616 B = 4.26 MiB |
| `x86_64` | `dev` only | CPU | 5,020,936 B = 4.79 MiB |

(The first and third rows were re-measured after E4.I1 added the JNI layer:
+32,592 B and +33,312 B respectively. The CPU-only arm64 row is E1.I4's
figure — that configuration is not built by any variant, so it was not
re-measured; expect the same ~33 KB.)

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
`ggml_` and `gguf_` prefixes, plus our own `skein_*` (`skein_llama_*` and
E4.I1's `skein_ctx_free_secure`), the `JNI_On{Load,Unload}` hooks, and the
glob `Java_app_skein_inference_service_LlamaNative_*` for E4.I1's JNI entry
points. 876 named symbols plus that one glob; deduped and sorted so the script
bytes — and therefore the `.so` bytes — reproduce identically. A configure-time
assertion fails the build if the scrape loses any of `llama_backend_init`,
`llama_model_load_from_file`, `llama_decode` or `llama_sampler_chain_init`.

The `Java_…` entries are a glob rather than twenty hand-mangled names because
those names are mechanically derived from `LlamaNative.kt` and a CMake list of
them would drift on the first added function. The glob does not loosen the
export set — `local: *;` plus "whitelist ∩ defined" still bounds it — and
`tools/ci/jni-symbols.sh` asserts the stronger property E4.I1's acceptance
criteria name: the `.so`'s `Java_` symbols are *exactly* the `external fun`s
`LlamaNative.kt` declares, in both directions.

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
| `patches/` + the `glslc` wrapper (§5a) | a shader compiler that mis-folds a constant into uninitialised memory |

These are applied with `add_compile_options` / `add_link_options` **before**
`add_subdirectory(llama.cpp)`, which is the only way to reach ~900 upstream
translation units we do not own.

`add_subdirectory` also satisfies skein-doq0's "never build in a dirty source
tree": the submodules are consumed strictly read-only, and every generated
byte — SPIR-V, the `vulkan-shaders-gen` host tool, every object file — lands
under `inference-service/.cxx/`, which `./gradlew clean` wipes. Nothing in this
directory writes into `third_party/`.

### The shader compiler is not deterministic, and what we do about it

Everything in the table above addresses *environmental* nondeterminism —
paths, clocks, caches, host CPUs. `libskein_llama.so` was nondeterministic
anyway (bd `skein-ylux`): eight cold builds of one commit, at one path, with
one `SOURCE_DATE_EPOCH`, produced **four** distinct sha256.

The diff is small enough to quote. Two differing libraries are the same size,
and `cmp -l` finds four differing bytes:

```
20284477   0 370
20284478   0 377
20284479   0 377
20284480   0 377
```

`llvm-readelf -S` puts that offset in `.rodata`, and searching the build's
`vulkan-shaders.spv/` for the surrounding bytes names the shader: `tri_f16`.
Disassembled, the difference is one operand:

```
%float_0         = OpConstant %float 0              # build A
%float_0x1p_128  = OpConstant %float 0x1p+128       # build B  (+inf)
       ...
%467 = OpFConvert %half %float_0                    # the live store
```

That constant is `D_TYPE(0)` — `vulkan-shaders/tri.comp:40`, and the same
line at `vulkan-shaders/diag.comp:26`. `D_TYPE` is `float16_t` for the `_f16`
variants, so the integer literal has to be converted `int -> float ->
float16_t`, and **NDK r27c's `glslc` (`shaderc v2022.3`) mis-folds that
conversion**: it emits an `OpConstant %float` whose 32-bit literal is
uninitialised memory. Forty compiles of one unchanged command line produced
five distinct `.spv`, with and without `-O`; the observed garbage included
`0`, `-8`, `+inf` and `-nan`. It is a small enough shader in a big enough
module that the `_f32` variants, which need no conversion, are unaffected
(40/40 identical).

So it was never an ordering bug in `vulkan-shaders-gen` — that generator
already `std::sort`s its table before emitting. It is a compiler defect, and
it is also a **miscompile**: the else branch of `tri`/`diag` stored `+inf`
instead of zero whenever the garbage was not 0.

We cannot fix `glslc` (an NDK prebuilt) and we do not write into
`third_party/`. §5a of `CMakeLists.txt` therefore:

1. copies `ggml/src/ggml-vulkan/vulkan-shaders/` into the `.cxx` build tree
   (`configure_file(COPYONLY)`, so a content change upstream re-triggers
   configure);
2. checks the pristine files against the `before` hashes in
   `patches/PINS.txt`, applies `patches/*.patch` to the **copy**, and checks
   the result against the `after` hashes;
3. generates `skein-glslc`, a wrapper that rewrites any argument naming a
   file under the pristine shader directory to the copy and then execs the
   real `glslc` — and *refuses to compile* a `.comp`/`.glsl` from anywhere
   else, so a silent bypass is a build failure rather than a returning flake;
4. points `Vulkan_GLSLC_EXECUTABLE` at that wrapper before
   `add_subdirectory(llama.cpp)`.

The patch itself is one character per site (`D_TYPE(0)` → `D_TYPE(0.0)`).
glslang then folds it correctly, and the emitted SPIR-V is byte-identical to
the *correct* output of the unpatched source — so the fix removes the flake
without changing the library the build was supposed to produce. The recorded
sha256 in "Proving it" below is unchanged by it.

`git status` inside `third_party/llama.cpp` is empty after a build. Nothing
here writes into the submodule.

**When you bump the pin**, a `before`/`after` mismatch in `patches/PINS.txt`
is the expected failure if upstream touched `tri.comp` or `diag.comp`.
Regenerate the patch against the new source, re-run
`tools/rb/so-determinism.sh -n 20`, and update `PINS.txt` in the same commit.
If upstream has taken the fix (or the NDK's glslang has), drop the patch
entirely — but prove it with the loop, not by reading the changelog.

### Checking it

```bash
tools/rb/so-determinism.sh                      # 2 cold builds, compared
tools/rb/so-determinism.sh -n 20 --keep /tmp/so # the acceptance run
tools/rb/so-determinism.sh --negative-control   # put the defect back
tools/rb/so-determinism.sh --self-test          # no SDK needed
```

`--negative-control` builds with `-Pskein.llama.shaderPatches=false`, i.e.
with §5a bypassed, and **expects to fail** — it is what makes a green run
from the check mean something. CI runs the two-build form in the
`native-determinism` job of `.github/workflows/reproducible-build.yml`, and
the `--self-test` in that workflow's `self-tests` job.

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

The `.so`'s bytes are a function of its sources, so **this table changes
whenever `jni/` changes** and must be re-recorded in the same commit.

Recorded for `b29c606e` / NDK r27c / macOS arm64, **as of E4.I1 (bd
`skein-3aw`, JNI bindings)**:

| Build | sha256 of `lib/arm64-v8a/libskein_llama.so` |
|---|---|
| A1, clean build | `99d1eb4fb2e558bfd69d132b656855a0ba4e9f40039c2510ccf0dae05abd309b` |
| A2, second clean build | `99d1eb4fb2e558bfd69d132b656855a0ba4e9f40039c2510ccf0dae05abd309b` |
| A3…A22, `tools/rb/so-determinism.sh -n 20` (bd `skein-ylux`) | `99d1eb4f…` ×20 |

**As of E4.I3 (bd `skein-nxk`, the isolated service; `jni/` grew to 24
exported symbols — `tools/ci/jni-symbols.sh`)** the value moved, as this
section says it must: two consecutive clean builds on the same toolchain
gave `79981c08ae595f7db85d128322c7029ec16dd4b7c2f414dc222a4d0ddad66fb2`,
25,304,664 bytes (recorded by the coordinator at `ad98b7b`; the 20-run
`so-determinism.sh` series has not been repeated since). The table above
is kept as the E4.I1 record.

25,303,280 bytes in all of the E4.I1 builds — and note that the `skein-ylux` fix did not
move this value: the patched shader sources compile to the same SPIR-V the
unpatched ones produced when glslc happened to get the constant right. What
changed is that "happened to" became "always". `strings` on the result finds no machine-specific
path: the only absolute-looking strings are the constant, remapped
`/skein/third_party/llama.cpp/…` that ggml's `GGML_ASSERT` bakes in from
`__FILE__`, and those are identical on every machine by construction — which
is the whole point of `-ffile-prefix-map`.

The **B, separate clone at a different path** leg is E1.I4's and was *not*
re-run for E4.I1: that bead's work happened in a `git worktree`, which
skein-8jtj established is not a valid second location for this check. What
E1.I4 proved — that no absolute path from the build machine reaches the
artifact — is a property of the compile/link flags (§4) and of the POST_BUILD
ELF guard that still runs on every build, neither of which E4.I1 touched.
Re-running leg B against this revision is bd `skein-7zsq`.

**As of skein-gg11.1 (E-2/E-3 fix: CPU-only device restriction in `loadModel`/
`loadModelFromFd`, `n_outputs_max = 1` in `newContext`; `jni/` changed, still
24 exported symbols — `tools/ci/jni-symbols.sh` green)** the value moved
again. The `Reproducible build check` run 35844509126 on `609ea95`
("Two cold builds of libskein_llama.so, compared", arm64-v8a, shader
patches ON, `SOURCE_DATE_EPOCH` 1790156516, same `b29c606e` pin) produced
`92353e595be12f46eb01d87038ea4df236e5e969c9391e35c71d4ffd6951b457`
from **both** cold builds — that is the A1/A2 pair this section's recipe
asks for, on the Linux runners. For reference, the implementing agent's
single `assembleFossRelease` on a macOS arm64 host (NDK r27c, no `clean`)
gave `8ed1d2d782f0195043fe2baa924611bd4c4631747c94922931325b89f55eed94`,
25,305,800 bytes; the host build is not expected to match the runner
build byte for byte and is not a record. Leg B (separate clone) is still
bd `skein-7zsq`. `.github/workflows/reproducible-build.yml` does not parse
or assert against this table; this entry is documentation, not a CI gate.

**As of skein-gg11.2 (`backendReport` diagnostic: one new external in
`jni/skein_jni.cpp`, plus a 3-line `n_outputs_max` stash in `newContext`'s
existing `Handles().Add`; no other native change — the redacted load-error
capture is Kotlin-only, in `LlamaNative.kt`)** the declared/exported symbol
count moved again: `jni/` now has **25** exported symbols, still exactly
matching `tools/ci/jni-symbols.sh`'s dynamically-derived expectation (that
script greps `LlamaNative.kt` for `external fun` rather than hard-coding a
count, so it needed no edit for this bead — see its own header). A fresh
clean-build hash pair was not re-recorded here; the coordinator's
`native-determinism` CI artifact is authoritative for the next entry, per
this section's own convention.

Previous record, for reference: E1.I4 / `jni_stub.cpp` produced
`76fd7ce5dc9cb9774c6dcc64401d508830486e9427648286686161fdf779e2cf`
(25,270,688 B) across A1/A2/B.

**As of skein-gg11.5 (16 KiB ELF LOAD-segment alignment: `-Wl,-z,max-page-size=16384`
and `-Wl,-z,common-page-size=16384` added to `target_link_options(skein_llama
…)` in `CMakeLists.txt` §7a — see §4's guard section above; no `jni/` change,
still 25 exported symbols — `tools/ci/jni-symbols.sh` green)** the value
moves again, because the added flags change the ELF program headers.
`tools/ci/elf-alignment.sh` now PASSES for both `libskein_llama.so` and
`libskein_sqlite_jni.so`, every built ABI (`arm64-v8a`, and the `dev`
flavor's `x86_64`). The previous CI-recorded value was
`92353e595be12f46eb01d87038ea4df236e5e969c9391e35c71d4ffd6951b457` at `609ea95`
(run 35844509126, per the skein-gg11.1 entry above).

**Local host build only — not a CI record.** A single
`./gradlew clean :inference-service:assembleFossRelease` on this macOS arm64
host (NDK r27c, `SOURCE_DATE_EPOCH` unset → the deterministic fallback
`1767225600`) gave:

| Build | sha256 of `lib/arm64-v8a/libskein_llama.so` |
|---|---|
| host build (this bead, skein-gg11.5), macOS arm64, NDK r27c | `530b83ad5126f81fc82fff6bc232506b2acd20e5de8f2b8551744b5b6e2e447c` (25,309,976 B) |

As this section's own convention states, a single host build is not the
record — the coordinator re-records the authoritative A1/A2 pair from the
`native-determinism` / `reproducible-build.yml` two-cold-build CI artifact
once this branch lands, the same way the skein-gg11.1 entry above was
superseded by run 35844509126's value. This row exists only so a reviewer
can see that the flag change produces *a* new, self-consistent hash on a
real build, not that this specific hash is the one to trust.

## 5. What is deliberately NOT here

- **Inference policy.** Batching, stop strings, UTF-8 stream buffering,
  sampling defaults, thermal backoff: all Kotlin, in `E4.I3`/`E4.I6`. The JNI
  layer added by E4.I1 (§8) is a thin translation of types, nothing more.
- **`mtmd` / vision.** `modelHasVision()` is a metadata probe only; the
  multimodal entry points are `E4.I11`.
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

## 8. The JNI layer (E4.I1)

`jni/skein_jni.cpp` replaced `jni_stub.cpp` in bd `skein-3aw`. Four files, no
third-party JNI dependency (spec §6):

| File | What it is |
|---|---|
| `jni/skein_jni.cpp` | one `Java_…` entry point per `external fun`, `JNI_OnLoad`/`JNI_OnUnload`, the `llama_log_set` sink, and `skein_ctx_free_secure` |
| `jni/skein_jni.h` | the error-code vocabulary shared with `LlamaException.kt`, and `skein_ctx_free_secure`'s declaration |
| `jni/handles.h` | the opaque handle registry |
| `jni/utf8.h` | real UTF-8 ⇄ UTF-16 conversion (JNI's "UTF" is *modified* UTF-8; llama.cpp's is not) |

The Kotlin half is
`inference-service/src/main/kotlin/app/skein/inference/service/{LlamaNative,LlamaException}.kt`.
(Package note: the plan's file list originally said `us.aherrera.skein.inference.service`
for contract modules while this implementation module's namespace was always
`app.skein.inference.service`; skein-376c renamed every contract module onto
the same `app.skein.*` prefix, so both now agree.)

**Handles are not pointers.** A `Long` handed to Kotlin is a monotonically
increasing token minted by `handles.h`, never reused, resolved through a mutex
-guarded map. A stale, forged, `0` or wrong-kind handle is a map miss and
raises `IllegalStateException`; nothing is dereferenced. That also makes
`handleCount()` — the acceptance criteria's leak check — literally `map.size()`
rather than a counter that can drift.

**Nothing crosses the boundary uncaught, and no message carries content.**
Every entry point is wrapped in `SKEIN_JNI_TRY`/`SKEIN_JNI_CATCH`, which
rethrows as `LlamaException(code, message)`. Messages are built from fixed
strings and numbers only — never `e.what()`, never llama.cpp's own error
strings, both of which can quote the input that failed (spec §9).

**Logging.** `setLogCallback()` installs the `llama_log_set` sink that E1.I4
deliberately left unwired. It maps `ggml_log_level` to `LlamaLogLevel`, calls
`LlamaLogRedactor.forward` (E1.I11), and forwards the survivors to `SkeinLog`.
`GGML_LOG_LEVEL_NONE`/`CONT` map to DEBUG and are therefore dropped, as is
everything at DEBUG/INFO. The JNI sources contain no `std::cout`, `printf` or
`__android_log_*`; `tools/ci/jni-symbols.sh` greps for them.

**Loading from a descriptor** (E4.I3, bd `skein-nxk`, closing the defect bd
`skein-lnp2` found). `loadModelFromFd(fd, nGpuLayers, useMmap)` is the entry
point the isolated service uses; `loadModel(path)` remains for dev harnesses
and `LlamaNativeTest`. The isolated `:inference` process cannot open the model
by ANY path — not the store path (app-private, `0400`, owned by the app uid)
and not `/proc/self/fd/<n>` either, because opening that is a fresh `open(2)`
whose DAC and SELinux checks run against the isolated uid, and AOSP's
`isolated_app` policy denies `app_data_file` opens outright. Only a descriptor
inherited over Binder works.

**No llama.cpp patch was needed for it.** Upstream at the pinned commit already
exposes `llama_model_load_from_file_ptr(FILE *, llama_model_params)`
(`include/llama.h`), and the `FILE *` threads all the way down —
`llama_model_loader`'s `file != nullptr` branch calls
`gguf_init_from_file_ptr` and constructs `llama_file(FILE *)`, whose
`file_id()` is `fileno(fp)`, which is exactly what `llama_mmap` maps. No path
is resolved anywhere on that route. So the JNI side is all of it:
`dup(fd)` (the caller's descriptor belongs to its `PinnedModelFile`), then
`fdopen`, then the public loader. **Ownership matters here**: llama.cpp sets
`owns_fp = false` for that constructor and never closes the stream, so the
`FILE *` is parked in the handle registry's `aux` slot and `freeModel` closes
it *after* `llama_model_free` — before would pull the mapping out from under
the model. `third_party/` is untouched, and the version-script scrape already
exports the symbol because it matches the `LLAMA_API` grep.

**Secure context free** (`docs/design/LOCK_POLICY_INDEXING.md` §4.5, consumed
by E4.I3's `onLocked`): `skein_ctx_free_secure(ctx)` calls
`llama_memory_clear(mem, data = true)` — which `ggml_backend_buffer_clear(buf, 0)`s
every KV buffer — *before* `llama_free(ctx)`. `freeContext` and
`freeContextSecure` both route through it: there is deliberately no
non-zeroing free path, because every context in this process has held
decrypted prompt and retrieved-context tokens. `secureFreeCount()` is the
dev-visible counter E4.I3's lock tests assert on.

**CI gate.** `tools/ci/jni-symbols.sh` (wired into `ci.yml` after
`assembleFossDebug`) fails if the `.so`'s `Java_` symbols are not exactly the
`external fun`s in `LlamaNative.kt` — in either direction — or if the JNI
sources grow a direct-output call. Run it by hand with

```bash
./gradlew :inference-service:assembleDevDebug
tools/ci/jni-symbols.sh          # all built ABIs
```

**What is not covered host-side.** The behavioural acceptance criteria
(tokenize/piece round-trip, chat template, decode + sample, embed, the
sub-100 ms cancel, `INVALID_MODEL` with `handleCount() == 0`) live in
`inference-service/src/androidTest/.../LlamaNativeTest.kt`, which needs the
tiny GGUF that bd `skein-80p` (E4.I2) fetches and an emulator or the Fold. The
test compiles on every CI run and skips itself when the asset is absent.
