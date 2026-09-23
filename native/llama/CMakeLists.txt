cmake_minimum_required(VERSION 3.22)
project(skein_llama CXX C)

# -----------------------------------------------------------------------------
# Skein native inference: llama.cpp + ggml (CPU, and Vulkan on arm64-v8a)
# statically linked into one Android shared object `libskein_llama.so`, loaded
# by the isolated `:inference-service` process.
#
# This productionizes the E0.I2 bench build. Recipe, pinned revisions and the
# reproducibility contract: `native/llama/README.md`.
#
# House reproducibility flags (mirrors native/sqlite/CMakeLists.txt —
# skein-qz5/6rwv/doq0/ej1b):
#   * `-ffile-prefix-map`         strips absolute build paths from __FILE__
#                                 and from DWARF comp_dir
#   * `-fno-ident`                drops the `.comment` toolchain string
#   * `-Wdate-time -Werror=date-time`  fails on any __DATE__/__TIME__
#   * `-Wl,--build-id=none`       no ELF build-id note
#   * `SOURCE_DATE_EPOCH` honoured, with a deterministic fallback constant
#   * a generated linker version script bounds the exported symbol set
#   * an ELF guard proves the produced .so is for this ABI and leaks no paths
#
# doq0's lesson ("never build in a dirty source tree") is satisfied
# structurally here rather than by copying: every submodule under
# `third_party/` is consumed read-only via `add_subdirectory`, and every
# generated file (shader SPIR-V, the vulkan-shaders-gen host tool, all object
# files) lands under `${CMAKE_CURRENT_BINARY_DIR}`, which AGP places in
# `inference-service/.cxx/<config>/<hash>/<abi>/` — wiped by `./gradlew clean`.
# Nothing in this file writes into a submodule.
# -----------------------------------------------------------------------------

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED SKEIN_LLAMA_ROOT)
  set(SKEIN_LLAMA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
endif()
get_filename_component(SKEIN_REPO_ROOT "${SKEIN_LLAMA_ROOT}/../.." ABSOLUTE)

# -----------------------------------------------------------------------------
# 1. Toolchain sanity
# -----------------------------------------------------------------------------
if(NOT ANDROID)
  message(FATAL_ERROR
    "native/llama/CMakeLists.txt is built only inside AGP's externalNativeBuild "
    "(ANDROID must be defined). There is no host build of libskein_llama.so.")
endif()

if(NOT DEFINED ANDROID_NDK)
  message(FATAL_ERROR "ANDROID_NDK is required (AGP normally sets it).")
endif()

if(CMAKE_HOST_APPLE)
  set(_NDK_HOST_TAG "darwin-x86_64")
elseif(CMAKE_HOST_WIN32)
  set(_NDK_HOST_TAG "windows-x86_64")
else()
  set(_NDK_HOST_TAG "linux-x86_64")
endif()
set(_NDK_TOOLCHAIN_BIN "${ANDROID_NDK}/toolchains/llvm/prebuilt/${_NDK_HOST_TAG}/bin")
if(NOT EXISTS "${_NDK_TOOLCHAIN_BIN}")
  message(FATAL_ERROR "NDK toolchain bin dir not found: ${_NDK_TOOLCHAIN_BIN}")
endif()

# SOURCE_DATE_EPOCH: honour an ambient value from the Gradle build (E1.I8),
# otherwise fall back to the same deterministic constant native/sqlite uses so
# two clean builds on the same machine still produce identical bytes.
if(DEFINED ENV{SOURCE_DATE_EPOCH})
  set(_SOURCE_DATE_EPOCH "$ENV{SOURCE_DATE_EPOCH}")
else()
  # 2026-01-01T00:00:00Z — arbitrary, but stable.
  set(_SOURCE_DATE_EPOCH "1767225600")
endif()

# -----------------------------------------------------------------------------
# 2. Submodule pin enforcement
# -----------------------------------------------------------------------------
# `native/llama/PINNED_COMMIT` is the single source of truth for what
# `third_party/llama.cpp`, `third_party/Vulkan-Headers` and
# `third_party/SPIRV-Headers` must be checked out at.
# `tools/ci/check-submodules.sh` enforces the same file in CI; this block is
# the developer-facing copy, so a stale `git submodule update` surfaces as a
# named configure error rather than as a confusing compile failure three
# minutes in. It costs one `git rev-parse` per submodule.
set(SKEIN_LLAMA_PINNED_COMMIT_FILE "${SKEIN_LLAMA_ROOT}/PINNED_COMMIT")
if(NOT EXISTS "${SKEIN_LLAMA_PINNED_COMMIT_FILE}")
  message(FATAL_ERROR "Missing ${SKEIN_LLAMA_PINNED_COMMIT_FILE}.")
endif()

option(SKEIN_LLAMA_SKIP_PIN_CHECK
  "skein: skip the third_party submodule pin check (source exports without .git)" OFF)

# ENCODING UTF-8: without it `file(STRINGS)` behaves like strings(1) and
# breaks a line at the first non-ASCII byte, which turns a prose comment into
# two records and fails the parse below.
file(STRINGS "${SKEIN_LLAMA_PINNED_COMMIT_FILE}" _pin_lines ENCODING UTF-8)
set(SKEIN_LLAMA_PINS "")
foreach(_pin_line IN LISTS _pin_lines)
  string(STRIP "${_pin_line}" _pin_line)
  if(_pin_line STREQUAL "" OR _pin_line MATCHES "^#")
    continue()
  endif()
  # NB: CMake's regex engine has no `{n}` repetition operator, so the commit
  # field is matched loosely and its length checked separately.
  if(NOT _pin_line MATCHES "^([^ \t]+)[ \t]+([0-9a-f]+)[ \t]+([^ \t]+)$")
    message(FATAL_ERROR
      "PINNED_COMMIT: unparsable record (expected `<path> <40-hex> <tag>`):\n  ${_pin_line}")
  endif()
  string(LENGTH "${CMAKE_MATCH_2}" _pin_commit_len)
  if(NOT _pin_commit_len EQUAL 40)
    message(FATAL_ERROR
      "PINNED_COMMIT: `${CMAKE_MATCH_2}` is not a full 40-hex commit (abbreviated "
      "SHAs are ambiguous and must not be used as pins):\n  ${_pin_line}")
  endif()
  list(APPEND SKEIN_LLAMA_PINS "${CMAKE_MATCH_1}=${CMAKE_MATCH_2}=${CMAKE_MATCH_3}")
endforeach()
if(SKEIN_LLAMA_PINS STREQUAL "")
  message(FATAL_ERROR "PINNED_COMMIT lists no submodules.")
endif()

find_package(Git QUIET)
foreach(_pin IN LISTS SKEIN_LLAMA_PINS)
  string(REPLACE "=" ";" _pin_parts "${_pin}")
  list(GET _pin_parts 0 _pin_path)
  list(GET _pin_parts 1 _pin_commit)
  list(GET _pin_parts 2 _pin_tag)
  set(_pin_dir "${SKEIN_REPO_ROOT}/${_pin_path}")
  if(NOT EXISTS "${_pin_dir}/.git")
    message(FATAL_ERROR
      "Submodule ${_pin_path} is not checked out (${_pin_dir} has no .git).\n"
      "Run:  git submodule update --init --recursive")
  endif()
  if(SKEIN_LLAMA_SKIP_PIN_CHECK OR NOT GIT_FOUND)
    continue()
  endif()
  execute_process(
    COMMAND "${GIT_EXECUTABLE}" -C "${_pin_dir}" rev-parse HEAD
    OUTPUT_VARIABLE _pin_head
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
    RESULT_VARIABLE _pin_rc)
  if(NOT _pin_rc EQUAL 0)
    message(FATAL_ERROR "Could not `git rev-parse HEAD` in ${_pin_dir}.")
  endif()
  if(NOT _pin_head STREQUAL "${_pin_commit}")
    message(FATAL_ERROR
      "Submodule pin MISMATCH for ${_pin_path}:\n"
      "  expected ${_pin_commit}  (${_pin_tag}, per native/llama/PINNED_COMMIT)\n"
      "  actual   ${_pin_head}\n"
      "libskein_llama.so's bytes are defined by these revisions. Either run "
      "`git submodule update --init --recursive`, or — if you meant to bump the "
      "pin — update native/llama/PINNED_COMMIT and native/llama/README.md in "
      "the same commit (tools/ci/check-submodules.sh enforces this in CI).")
  endif()
endforeach()
message(STATUS "skein_llama: third_party submodule pins verified")

set(LLAMA_CPP_DIR       "${SKEIN_REPO_ROOT}/third_party/llama.cpp")
set(VULKAN_HEADERS_DIR  "${SKEIN_REPO_ROOT}/third_party/Vulkan-Headers")
set(SPIRV_HEADERS_DIR   "${SKEIN_REPO_ROOT}/third_party/SPIRV-Headers")

# -----------------------------------------------------------------------------
# 3. Backend policy (bd skein-ca2 coordinator note; final call is skein-5hr)
# -----------------------------------------------------------------------------
# arm64-v8a carries CPU *and* Vulkan; x86_64 (the `dev` flavor's emulator ABI)
# is always CPU-only — Android emulators expose a software Vulkan ICD at best
# and there is no measurement backing it.
#
# `SKEIN_LLAMA_DEFAULT_BACKEND` is the *requested* default and is deliberately
# a single flip: `vulkan` is what the M0 Fold smoke proved (23.47 pp / 5.61 tg
# on Qwen 2.5 3B Q3_K_M via Mali-G715 — handoff §9), but the binding decision
# belongs to docs/MEASUREMENTS.md (`inference_backend`, bd skein-5hr). Changing
# the default is one word in `inference-service/build.gradle.kts`; nothing else
# in this file or in Kotlin hard-codes a backend.
#
# The *effective* default is the requested one intersected with what this ABI
# actually builds, and is what `skein_llama_default_backend()` returns.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(SKEIN_LLAMA_EXPECTED_ELF_MACHINE "AArch64")
  set(_SKEIN_LLAMA_VULKAN_DEFAULT ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(SKEIN_LLAMA_EXPECTED_ELF_MACHINE "Advanced Micro Devices X86-64")
  set(_SKEIN_LLAMA_VULKAN_DEFAULT OFF)
else()
  message(FATAL_ERROR
    "Unsupported ANDROID_ABI=${ANDROID_ABI}. Only arm64-v8a (production) and "
    "x86_64 (dev/emulator) are wired.")
endif()

option(SKEIN_LLAMA_VULKAN
  "skein: build the ggml Vulkan backend into libskein_llama.so"
  ${_SKEIN_LLAMA_VULKAN_DEFAULT})

set(SKEIN_LLAMA_DEFAULT_BACKEND "vulkan" CACHE STRING
  "skein: backend libskein_llama.so reports as its default (vulkan|cpu)")
set_property(CACHE SKEIN_LLAMA_DEFAULT_BACKEND PROPERTY STRINGS vulkan cpu)
if(NOT SKEIN_LLAMA_DEFAULT_BACKEND MATCHES "^(vulkan|cpu)$")
  message(FATAL_ERROR
    "SKEIN_LLAMA_DEFAULT_BACKEND must be `vulkan` or `cpu`, got "
    "`${SKEIN_LLAMA_DEFAULT_BACKEND}`.")
endif()

if(SKEIN_LLAMA_DEFAULT_BACKEND STREQUAL "vulkan" AND NOT SKEIN_LLAMA_VULKAN)
  set(SKEIN_LLAMA_EFFECTIVE_BACKEND "cpu")
  message(STATUS
    "skein_llama: ${ANDROID_ABI} has no Vulkan backend; effective default "
    "backend is `cpu` (requested `vulkan`)")
else()
  set(SKEIN_LLAMA_EFFECTIVE_BACKEND "${SKEIN_LLAMA_DEFAULT_BACKEND}")
endif()
if(SKEIN_LLAMA_VULKAN)
  set(_skein_llama_backend_summary "CPU + Vulkan")
else()
  set(_skein_llama_backend_summary "CPU")
endif()
message(STATUS
  "skein_llama: ${ANDROID_ABI} backends = ${_skein_llama_backend_summary}, "
  "default = ${SKEIN_LLAMA_EFFECTIVE_BACKEND}")

# -----------------------------------------------------------------------------
# 4. Reproducibility flags for EVERY translation unit, ours and llama.cpp's
# -----------------------------------------------------------------------------
# `add_compile_options` / `add_link_options` at this scope are inherited by the
# `add_subdirectory(llama.cpp)` below, which is the only way to reach ~900
# upstream translation units we do not own. They must come before it.
#
# `-ffile-prefix-map` is applied for three roots, longest-match-wins order
# irrelevant (clang applies all):
#   * the repo root      — submodule sources and this directory
#   * the CMake binary dir — generated shader .cpp/.hpp under .cxx/
#   * the NDK            — sysroot headers referenced from DWARF
# Without the NDK entry a stripped .so is clean but a debug one still carries
# `/Users/<name>/android-sdk/...` in DWARF, which makes the *debug* artifact
# machine-dependent and leaks the developer's home directory.
add_compile_options(
  -fno-ident
  "-ffile-prefix-map=${SKEIN_REPO_ROOT}=/skein"
  "-ffile-prefix-map=${CMAKE_CURRENT_BINARY_DIR}=/skein/build"
  "-ffile-prefix-map=${ANDROID_NDK}=/skein/ndk"
  # Fail the build if any TU compiles __DATE__/__TIME__/__TIMESTAMP__ into a
  # string. llama.cpp v0.4.1's `src/` and `ggml/src/` are clean (checked);
  # this guards future bumps.
  -Wdate-time
  -Werror=date-time
)
add_link_options(
  # Determinism: no build-id note, no DT_RUNPATH path leakage.
  "-Wl,--build-id=none"
)

# -----------------------------------------------------------------------------
# 5. Vulkan wiring — NDK loader, pinned Khronos headers, NDK glslc
# -----------------------------------------------------------------------------
# No LunarG Vulkan SDK is installed or downloaded. Three inputs, all already
# on disk after `git submodule update --init`:
#
#   headers  third_party/Vulkan-Headers (pinned)  — the NDK sysroot's are
#            VK_HEADER_VERSION 275 and ship no Vulkan-Hpp, which ggml-vulkan
#            requires; see PINNED_COMMIT for the full rationale.
#   loader   $ANDROID_NDK sysroot `libvulkan.so` — the real Android loader
#            stub we link against. Never vendored.
#   glslc    $ANDROID_NDK/shader-tools/<host>/glslc — the NDK's own shaderc.
#            Shaders are compiled from source at build time (deterministic:
#            same glslc + same .comp + same -D set ⇒ same SPIR-V), so no
#            pre-generated SPIR-V blobs are vendored and there is nothing to
#            hash separately. The NDK is already pinned by
#            `core/vault/build.gradle.kts`'s `ndkVersion`, which this module
#            repeats, so glslc is pinned with it.
#
# ggml-vulkan's CMake feature-probes glslc for GL_KHR_cooperative_matrix,
# GL_NV_cooperative_matrix2, GL_EXT_integer_dot_product, GL_EXT_bfloat16 and
# the FP8/FP4 extensions and drops the variants it cannot compile. NDK r27c's
# glslc (shaderc v2022.3) supports none of them, so those shader families are
# excluded — which happens to match the target: Mali-G715 exposes none of
# these extensions either (handoff §7).
if(SKEIN_LLAMA_VULKAN)
  set(_glslc "${ANDROID_NDK}/shader-tools/${_NDK_HOST_TAG}/glslc")
  if(CMAKE_HOST_WIN32)
    set(_glslc "${_glslc}.exe")
  endif()
  if(NOT EXISTS "${_glslc}")
    message(FATAL_ERROR
      "NDK glslc not found at ${_glslc}. Install the NDK's `shader-tools` "
      "component, or configure with -DSKEIN_LLAMA_VULKAN=OFF to build a "
      "CPU-only libskein_llama.so.")
  endif()

  # ---------------------------------------------------------------------------
  # 5a. Shader source patches (bd skein-ylux)
  # ---------------------------------------------------------------------------
  # NDK r27c's glslc (`shaderc v2022.3`) mis-folds an `int -> float ->
  # float16_t` constant conversion in a large shader: for
  #
  #     data_d[...] = D_TYPE(0);          // D_TYPE == float16_t
  #
  # it emits `OpFConvert %half %float_<uninitialised>` — an `OpConstant
  # %float` whose 32-bit literal is whatever was in memory. Observed values
  # for one unchanged command line: 0, -8, +inf, -nan. That makes the .spv,
  # and therefore libskein_llama.so, not a function of its inputs (measured:
  # 8 cold builds at one path produced 4 distinct sha256, every differing byte
  # inside `tri_f16` / `diag_f16`), and it is also a miscompile — the else
  # branch stores +inf instead of zero. Writing the literal as `0.0` removes
  # the conversion and glslang folds it correctly and deterministically.
  #
  # We cannot fix glslc (it is an NDK prebuilt) and we do not write into
  # `third_party/`. So: copy the shader sources into the build tree, patch the
  # COPY, and point the glslc that ggml-vulkan invokes at a wrapper that
  # redirects reads of the pristine directory to the copy. The submodule stays
  # byte-for-byte pristine; `git status` in it is empty after a build.
  #
  # Every input and output of that rewrite is sha256-pinned in
  # `patches/PINS.txt`, so a submodule pin bump that touches a patched shader
  # fails here by name instead of applying a stale hunk. Same shape as
  # native/sqlite's amalgamation pins (docs/design/AMALGAMATION_POLICY.md):
  # cheap hashes on every configure, and a loud, attributable failure.
  option(SKEIN_LLAMA_SHADER_PATCHES
    "skein: apply native/llama/patches/*.patch to a build-tree copy of llama.cpp's Vulkan shader sources"
    ON)

  set(_skein_shaders_src     "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/vulkan-shaders")
  set(_skein_shaders_patched "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-patched")
  # What ggml-vulkan will actually be told to run. Unpatched builds keep the
  # NDK binary directly, so `-DSKEIN_LLAMA_SHADER_PATCHES=OFF` is a true
  # bypass — which is exactly how tools/rb/so-determinism.sh reintroduces the
  # defect to prove it can still detect it.
  set(_glslc_effective "${_glslc}")

  if(SKEIN_LLAMA_SHADER_PATCHES)
    if(CMAKE_HOST_WIN32)
      message(FATAL_ERROR
        "SKEIN_LLAMA_SHADER_PATCHES needs a POSIX /bin/sh for the glslc "
        "wrapper and there is no Windows equivalent wired here. Configure "
        "with -DSKEIN_LLAMA_SHADER_PATCHES=OFF to build without it — but note "
        "that libskein_llama.so is then NOT reproducible (bd skein-ylux).")
    endif()

    find_program(SKEIN_PATCH_EXECUTABLE NAMES patch)
    if(NOT SKEIN_PATCH_EXECUTABLE)
      message(FATAL_ERROR
        "`patch` was not found on PATH. It applies native/llama/patches/*.patch "
        "to the build-tree copy of llama.cpp's Vulkan shaders (bd skein-ylux). "
        "Install it (it is in POSIX base on macOS and in `patch`/`diffutils` on "
        "Linux), or configure with -DSKEIN_LLAMA_SHADER_PATCHES=OFF and accept "
        "a non-reproducible libskein_llama.so.")
    endif()

    # 1. Copy the shader tree into the build dir. `configure_file(COPYONLY)`
    #    rather than `file(COPY)` because it registers each source file as a
    #    CMake reconfigure dependency: an upstream shader whose CONTENT changes
    #    under a pin bump re-triggers configure, which re-copies from pristine
    #    and re-checks the pins. (`file(GLOB CONFIGURE_DEPENDS)` only notices
    #    the file SET changing, which is not enough here.) The copy is
    #    overwritten from pristine on every configure, so patching is
    #    idempotent rather than cumulative.
    file(GLOB _skein_shader_srcs LIST_DIRECTORIES false "${_skein_shaders_src}/*")
    file(GLOB _skein_shader_ft   LIST_DIRECTORIES false "${_skein_shaders_src}/feature-tests/*")
    if(_skein_shader_srcs STREQUAL "")
      message(FATAL_ERROR
        "No files under ${_skein_shaders_src} — upstream moved the Vulkan "
        "shader directory. Review native/llama/README.md §4 before bumping the pin.")
    endif()
    foreach(_f IN LISTS _skein_shader_srcs)
      get_filename_component(_n "${_f}" NAME)
      configure_file("${_f}" "${_skein_shaders_patched}/${_n}" COPYONLY)
    endforeach()
    foreach(_f IN LISTS _skein_shader_ft)
      get_filename_component(_n "${_f}" NAME)
      configure_file("${_f}" "${_skein_shaders_patched}/feature-tests/${_n}" COPYONLY)
    endforeach()

    # 2. Parse patches/PINS.txt into three buckets. Checked in phases, because
    #    a `before` hash is only meaningful before any patch has been applied
    #    and an `after` hash only after all of them have.
    set(_skein_patch_dir "${SKEIN_LLAMA_ROOT}/patches")
    set(_skein_pins_file "${_skein_patch_dir}/PINS.txt")
    if(NOT EXISTS "${_skein_pins_file}")
      message(FATAL_ERROR "Missing ${_skein_pins_file}.")
    endif()
    file(STRINGS "${_skein_pins_file}" _skein_pin_lines ENCODING UTF-8)
    set(_skein_pin_patch "")
    set(_skein_pin_before "")
    set(_skein_pin_after "")
    foreach(_line IN LISTS _skein_pin_lines)
      string(STRIP "${_line}" _line)
      if(_line STREQUAL "" OR _line MATCHES "^#")
        continue()
      endif()
      # CMake's regex engine has no `{n}`, so the hash is matched loosely and
      # its length checked separately (same idiom as the PINNED_COMMIT parser).
      if(NOT _line MATCHES "^(patch|before|after)[ \t]+([0-9a-f]+)[ \t]+([^ \t]+)$")
        message(FATAL_ERROR
          "patches/PINS.txt: unparsable record (expected `<patch|before|after> "
          "<64-hex> <name>`):\n  ${_line}")
      endif()
      set(_kind "${CMAKE_MATCH_1}")
      set(_want "${CMAKE_MATCH_2}")
      set(_name "${CMAKE_MATCH_3}")
      string(LENGTH "${_want}" _want_len)
      if(NOT _want_len EQUAL 64)
        message(FATAL_ERROR
          "patches/PINS.txt: `${_want}` is not a full sha256:\n  ${_line}")
      endif()
      list(APPEND _skein_pin_${_kind} "${_want}=${_name}")
    endforeach()
    if(_skein_pin_patch STREQUAL "" OR _skein_pin_before STREQUAL ""
       OR _skein_pin_after STREQUAL "")
      message(FATAL_ERROR
        "patches/PINS.txt must list at least one `patch`, one `before` and one "
        "`after` record; a patch with no pinned before/after hashes is not a "
        "pin at all.")
    endif()

    # 3. Verify the patch files and the pristine copies, then apply, then
    #    verify the results.
    macro(_skein_check_sha _ck_kind _ck_record _ck_root)
      string(REPLACE "=" ";" _rec_parts "${_ck_record}")
      list(GET _rec_parts 0 _rec_want)
      list(GET _rec_parts 1 _rec_name)
      set(_rec_path "${_ck_root}/${_rec_name}")
      if(NOT EXISTS "${_rec_path}")
        message(FATAL_ERROR
          "patches/PINS.txt names `${_rec_name}` (${_ck_kind}) but ${_rec_path} "
          "does not exist.")
      endif()
      file(SHA256 "${_rec_path}" _rec_got)
      if(NOT _rec_got STREQUAL "${_rec_want}")
        message(FATAL_ERROR
          "Shader patch pin MISMATCH (${_ck_kind}) for `${_rec_name}`:\n"
          "  expected ${_rec_want}\n"
          "  actual   ${_rec_got}\n"
          "  file     ${_rec_path}\n"
          "libskein_llama.so's bytes are defined by these files. If you bumped "
          "third_party/llama.cpp, regenerate native/llama/patches/*.patch "
          "against the new upstream source, re-run tools/rb/so-determinism.sh, "
          "and update native/llama/patches/PINS.txt in the same commit. See "
          "native/llama/README.md §4.")
      endif()
    endmacro()

    foreach(_rec IN LISTS _skein_pin_patch)
      _skein_check_sha("patch" "${_rec}" "${_skein_patch_dir}")
    endforeach()
    foreach(_rec IN LISTS _skein_pin_before)
      _skein_check_sha("before" "${_rec}" "${_skein_shaders_patched}")
    endforeach()

    foreach(_rec IN LISTS _skein_pin_patch)
      string(REPLACE "=" ";" _rec_parts "${_rec}")
      list(GET _rec_parts 1 _patch_name)
      execute_process(
        COMMAND "${SKEIN_PATCH_EXECUTABLE}" -p1 --batch --forward
                -i "${_skein_patch_dir}/${_patch_name}"
        WORKING_DIRECTORY "${_skein_shaders_patched}"
        RESULT_VARIABLE _patch_rc
        OUTPUT_VARIABLE _patch_out
        ERROR_VARIABLE _patch_err)
      if(NOT _patch_rc EQUAL 0)
        message(FATAL_ERROR
          "Applying native/llama/patches/${_patch_name} to "
          "${_skein_shaders_patched} failed (rc=${_patch_rc}):\n"
          "${_patch_out}\n${_patch_err}")
      endif()
    endforeach()

    foreach(_rec IN LISTS _skein_pin_after)
      _skein_check_sha("after" "${_rec}" "${_skein_shaders_patched}")
    endforeach()

    list(LENGTH _skein_pin_patch _skein_patch_count)
    list(LENGTH _skein_pin_after _skein_patched_file_count)
    message(STATUS
      "skein_llama: applied ${_skein_patch_count} shader patch(es) to "
      "${_skein_patched_file_count} file(s) in ${_skein_shaders_patched}")

    # 4. The wrapper that makes ggml-vulkan read the patched copy.
    set(SKEIN_GLSLC_ORIG_DIR    "${_skein_shaders_src}")
    set(SKEIN_GLSLC_PATCHED_DIR "${_skein_shaders_patched}")
    set(SKEIN_GLSLC_REAL        "${_glslc}")
    set(_skein_glslc_wrapper "${CMAKE_CURRENT_BINARY_DIR}/skein-glslc")
    configure_file(
      "${SKEIN_LLAMA_ROOT}/cmake/skein-glslc.sh.in"
      "${_skein_glslc_wrapper}"
      @ONLY)
    file(CHMOD "${_skein_glslc_wrapper}"
      PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
                  GROUP_READ GROUP_EXECUTE
                  WORLD_READ WORLD_EXECUTE)
    set(_glslc_effective "${_skein_glslc_wrapper}")
  else()
    message(WARNING
      "skein_llama: SKEIN_LLAMA_SHADER_PATCHES=OFF — libskein_llama.so will be "
      "built from unpatched Vulkan shader sources and is NOT reproducible "
      "(bd skein-ylux). This is only ever correct as a deliberate negative "
      "control.")
  endif()

  set(_vk_include "${VULKAN_HEADERS_DIR}/include")
  if(NOT EXISTS "${_vk_include}/vulkan/vulkan.hpp")
    message(FATAL_ERROR
      "third_party/Vulkan-Headers is missing vulkan/vulkan.hpp — submodule not "
      "checked out? Run `git submodule update --init --recursive`.")
  endif()

  # The Android loader stub for this ABI/API level. AGP sets
  # CMAKE_ANDROID_ARCH_ABI + ANDROID_PLATFORM; the NDK toolchain file exposes
  # the per-API lib dir as CMAKE_ANDROID_NDK_TOOLCHAIN_HOST_TAG-independent
  # sysroot paths. Ask the compiler rather than guessing the triple.
  if(DEFINED ANDROID_PLATFORM)
    string(REGEX REPLACE "^android-" "" _android_api "${ANDROID_PLATFORM}")
  else()
    set(_android_api "30")
  endif()
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(_android_triple "aarch64-linux-android")
  else()
    set(_android_triple "x86_64-linux-android")
  endif()
  set(_vk_library
    "${_NDK_TOOLCHAIN_BIN}/../sysroot/usr/lib/${_android_triple}/${_android_api}/libvulkan.so")
  get_filename_component(_vk_library "${_vk_library}" ABSOLUTE)
  if(NOT EXISTS "${_vk_library}")
    message(FATAL_ERROR "NDK libvulkan.so not found at ${_vk_library}")
  endif()

  # Pre-seed FindVulkan's cache variables so `find_package(Vulkan COMPONENTS
  # glslc REQUIRED)` inside ggml-vulkan resolves to the pinned headers and the
  # NDK loader/glslc instead of searching the host (where it would find
  # MoltenVK or a LunarG SDK and silently build against the wrong ABI). The
  # Android toolchain file's CMAKE_FIND_ROOT_PATH_MODE_* settings would
  # otherwise make the library search fail outright.
  set(Vulkan_INCLUDE_DIR      "${_vk_include}" CACHE PATH "" FORCE)
  set(Vulkan_INCLUDE_DIRS     "${_vk_include}" CACHE PATH "" FORCE)
  set(Vulkan_LIBRARY          "${_vk_library}" CACHE FILEPATH "" FORCE)
  # NB: `_glslc_effective`, not `_glslc` — see §5a. With shader patches on
  # this is the wrapper in the build tree, which redirects shader reads to the
  # patched copy and then execs the NDK binary.
  set(Vulkan_GLSLC_EXECUTABLE "${_glslc_effective}" CACHE FILEPATH "" FORCE)

  # `find_package(SPIRV-Headers CONFIG REQUIRED)` in ggml-vulkan wants an
  # installed CMake package. The submodule is header-only and is never
  # actually linked, so rather than running its `install()` into the build
  # tree we point the search at a two-line config shim that defines the
  # imported target against the submodule's include dir.
  set(SPIRV-Headers_DIR "${SKEIN_LLAMA_ROOT}/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
  set(SKEIN_SPIRV_HEADERS_INCLUDE_DIR "${SPIRV_HEADERS_DIR}/include" CACHE PATH "" FORCE)

  # ggml-vulkan.cpp does `#include <spirv/unified1/spirv.hpp>` but never links
  # the `SPIRV-Headers::SPIRV-Headers` target it just found -- upstream relies
  # on the distro package having dropped those headers somewhere already on
  # the default include path (`/usr/include/spirv/...`). Cross-compiling
  # against an NDK sysroot there is no such place, so add the pinned
  # submodule's include dir here, where it is inherited by the
  # add_subdirectory below.
  if(NOT EXISTS "${SPIRV_HEADERS_DIR}/include/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR
      "third_party/SPIRV-Headers is missing spirv/unified1/spirv.hpp -- "
      "submodule not checked out? Run `git submodule update --init --recursive`.")
  endif()
  include_directories(SYSTEM "${SPIRV_HEADERS_DIR}/include")
endif()

# -----------------------------------------------------------------------------
# 6. llama.cpp / ggml options
# -----------------------------------------------------------------------------
# Everything is forced into the cache *before* add_subdirectory so upstream's
# `option()` calls do not get to pick their own defaults. The four the
# acceptance criteria name explicitly are LLAMA_CURL, LLAMA_BUILD_EXAMPLES,
# LLAMA_BUILD_TESTS and LLAMA_BUILD_SERVER; the rest of this block exists so
# the build reaches the network exactly never and links exactly the object
# files we intend.
macro(skein_llama_opt name value)
  set(${name} ${value} CACHE INTERNAL "skein: forced by native/llama/CMakeLists.txt")
endmacro()

# One .so: every llama.cpp/ggml target is a static archive we whole-archive in.
skein_llama_opt(BUILD_SHARED_LIBS   OFF)

# --- acceptance-criteria switches ---
# LLAMA_CURL became a deprecation shim in v0.4.x (HTTPS moved to
# LLAMA_OPENSSL), but it is still read by `llama_option_depr`, so setting it
# OFF is both required by the AC and the thing that keeps the shim quiet.
skein_llama_opt(LLAMA_CURL          OFF)
skein_llama_opt(LLAMA_BUILD_EXAMPLES OFF)
skein_llama_opt(LLAMA_BUILD_TESTS   OFF)
skein_llama_opt(LLAMA_BUILD_SERVER  OFF)

# --- nothing but the library ---
skein_llama_opt(LLAMA_BUILD_COMMON  OFF)
skein_llama_opt(LLAMA_BUILD_TOOLS   OFF)
skein_llama_opt(LLAMA_BUILD_APP     OFF)
skein_llama_opt(LLAMA_TOOLS_INSTALL OFF)
skein_llama_opt(LLAMA_TESTS_INSTALL OFF)
# LLAMA_BUILD_UI/LLAMA_USE_PREBUILT_UI would fetch a prebuilt web UI bundle
# from a Hugging Face bucket during configure. Off: the build must not touch
# the network (and a FOSS build ships no blobs it did not compile).
skein_llama_opt(LLAMA_BUILD_UI      OFF)
skein_llama_opt(LLAMA_USE_PREBUILT_UI OFF)
# HTTPS client + subprocess spawning live in `common`/`tools`, which we do not
# build; off anyway so a future upstream refactor cannot pull OpenSSL or
# fork/exec into the isolated inference process.
skein_llama_opt(LLAMA_OPENSSL       OFF)
skein_llama_opt(LLAMA_SUBPROCESS    OFF)
skein_llama_opt(LLAMA_LLGUIDANCE    OFF)
# Release tag, not a nightly: makes `llama_print_system_info`-style version
# strings read "0.4.1" rather than "0.4.1-dev".
skein_llama_opt(LLAMA_BUILD_IS_DEV  OFF)
skein_llama_opt(LLAMA_ALL_WARNINGS  OFF)
skein_llama_opt(LLAMA_FATAL_WARNINGS OFF)

# --- ggml ---
skein_llama_opt(GGML_ALL_WARNINGS   OFF)
skein_llama_opt(GGML_FATAL_WARNINGS OFF)
skein_llama_opt(GGML_BUILD_TESTS    OFF)
skein_llama_opt(GGML_BUILD_EXAMPLES OFF)
# ccache would make object bytes depend on cache state rather than on inputs.
skein_llama_opt(GGML_CCACHE         OFF)
# GGML_NATIVE probes the *host* CPU; meaningless and non-reproducible when
# cross-compiling. GGML_CPU_ARM_ARCH is deliberately left at the NDK default
# (armv8-a) — raising it to armv8.5-a+i8mm is a measured decision that belongs
# to docs/MEASUREMENTS.md (skein-5hr), not to this bead.
skein_llama_opt(GGML_NATIVE         OFF)
skein_llama_opt(GGML_LTO            OFF)
# Backends are compiled in, not dlopen'd: GGML_BACKEND_DL needs shared libs and
# would put loose .so files next to ours for the isolated process to dlopen.
skein_llama_opt(GGML_BACKEND_DL     OFF)
skein_llama_opt(GGML_CPU            ON)
skein_llama_opt(GGML_OPENMP         OFF)
skein_llama_opt(GGML_ACCELERATE     OFF)
skein_llama_opt(GGML_BLAS           OFF)
skein_llama_opt(GGML_METAL          OFF)
skein_llama_opt(GGML_CUDA           OFF)
skein_llama_opt(GGML_HIP            OFF)
skein_llama_opt(GGML_SYCL           OFF)
skein_llama_opt(GGML_OPENCL         OFF)
skein_llama_opt(GGML_WEBGPU         OFF)
skein_llama_opt(GGML_KOMPUTE        OFF)
# RPC is a remote-tensor transport — an actual network backend. Never.
skein_llama_opt(GGML_RPC            OFF)
skein_llama_opt(GGML_VULKAN         ${SKEIN_LLAMA_VULKAN})

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------------
# 7. libskein_llama.so
# -----------------------------------------------------------------------------
# Values interpolated into skein_llama_config.h. `LLAMA_VERSION_BASE` is set
# by llama.cpp's own CMakeLists, but in the *subdirectory* scope, so it is not
# visible here — derive it from the pinned tag instead, which is the same
# string and keeps PINNED_COMMIT the one place a version is written down.
foreach(_pin IN LISTS SKEIN_LLAMA_PINS)
  string(REPLACE "=" ";" _pin_parts "${_pin}")
  list(GET _pin_parts 0 _pin_path)
  if(_pin_path STREQUAL "third_party/llama.cpp")
    list(GET _pin_parts 2 _pin_tag)
    string(REGEX REPLACE "^v" "" LLAMA_VERSION_BASE "${_pin_tag}")
  endif()
endforeach()
if(NOT DEFINED LLAMA_VERSION_BASE)
  message(FATAL_ERROR "PINNED_COMMIT has no `third_party/llama.cpp` record.")
endif()

if(SKEIN_LLAMA_VULKAN)
  set(SKEIN_LLAMA_VULKAN_01 1)
else()
  set(SKEIN_LLAMA_VULKAN_01 0)
endif()

configure_file(
  "${SKEIN_LLAMA_ROOT}/skein_llama_config.h.in"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/skein_llama_config.h"
  @ONLY)

# E4.I1 (bd skein-3aw) replaced `jni_stub.cpp` with the real bindings. The
# stub's other job — forcing the C++ toolchain (and with it the static libc++
# llama.cpp/ggml need) into the link — is served by `skein_jni.cpp`, which is
# C++ as well.
add_library(skein_llama SHARED
  "${SKEIN_LLAMA_ROOT}/jni/skein_jni.cpp"
  "${SKEIN_LLAMA_ROOT}/skein_llama_backend.c"
)

target_include_directories(skein_llama PRIVATE
  "${CMAKE_CURRENT_BINARY_DIR}/generated"
  "${SKEIN_LLAMA_ROOT}/jni"
  "${LLAMA_CPP_DIR}/include"
  "${LLAMA_CPP_DIR}/ggml/include"
)

target_compile_options(skein_llama PRIVATE -O2)

# The archives to absorb whole. A version script cannot export a symbol the
# linker never pulled in, and `jni_stub.cpp` (intentionally) references none of
# llama.cpp — so without --whole-archive the .so would link to ~30 KB of
# nothing. Enumerated explicitly rather than via a generator expression so the
# set is reviewable and so this works on AGP's bundled CMake 3.22.
set(_skein_llama_archives llama ggml ggml-base ggml-cpu)
if(SKEIN_LLAMA_VULKAN)
  list(APPEND _skein_llama_archives ggml-vulkan)
endif()
foreach(_archive IN LISTS _skein_llama_archives)
  if(NOT TARGET ${_archive})
    message(FATAL_ERROR
      "Expected llama.cpp target `${_archive}` does not exist. Upstream "
      "renamed or restructured a target — review native/llama/README.md §3 "
      "before bumping the pin.")
  endif()
  target_link_options(skein_llama PRIVATE
    "-Wl,--whole-archive" "$<TARGET_FILE:${_archive}>" "-Wl,--no-whole-archive")
  add_dependencies(skein_llama ${_archive})
endforeach()

# Still declare the normal link edges so CMake propagates usage requirements
# (Vulkan loader, log, android, the C++ runtime) and orders the build.
target_link_libraries(skein_llama PRIVATE llama ggml log android)

# -----------------------------------------------------------------------------
# 7a. Linker version script
# -----------------------------------------------------------------------------
# llama.cpp compiled as a static library leaves `LLAMA_API` / `GGML_API` /
# `GGML_BACKEND_API` expanding to nothing, i.e. every symbol in every archive
# has default visibility. That is what lets a version script name them (the
# same reason native/sqlite forces `-fvisibility=default` on the amalgamation,
# skein-6rwv) — but it also means the unfiltered .so would export thousands of
# internal C++ symbols. `local: *;` trims everything not on the whitelist.
#
# The whitelist is generated at configure time by scraping the public headers,
# so it tracks upstream instead of drifting:
#   include/llama.h            `LLAMA_API`        → the E4.I1 JNI surface
#   ggml/include/*.h           `GGML_API` / `GGML_BACKEND_API`
#   plus our own `skein_*`
# Names are deduped and sorted so the script bytes — and therefore the .so
# bytes — reproduce identically across clean builds.
set(_SKEIN_LLAMA_EXPORTS_LD "${CMAKE_CURRENT_BINARY_DIR}/skein_llama.exports.ld")

set(_skein_llama_api_headers
  "${LLAMA_CPP_DIR}/include/llama.h"
  "${LLAMA_CPP_DIR}/ggml/include/ggml.h"
  "${LLAMA_CPP_DIR}/ggml/include/ggml-alloc.h"
  "${LLAMA_CPP_DIR}/ggml/include/ggml-backend.h"
  "${LLAMA_CPP_DIR}/ggml/include/ggml-cpu.h"
  "${LLAMA_CPP_DIR}/ggml/include/ggml-opt.h"
  "${LLAMA_CPP_DIR}/ggml/include/gguf.h"
)
if(SKEIN_LLAMA_VULKAN)
  list(APPEND _skein_llama_api_headers "${LLAMA_CPP_DIR}/ggml/include/ggml-vulkan.h")
endif()

set(_skein_llama_exports "")
foreach(_header IN LISTS _skein_llama_api_headers)
  if(NOT EXISTS "${_header}")
    message(FATAL_ERROR "Public header ${_header} not found — pin bump broke the export scrape.")
  endif()
  # Each API line is one of:
  #   `    LLAMA_API <type> IDENT(<args>);`        function (args may wrap)
  #   `    GGML_API  <type> IDENT[];` / `IDENT;`   variable
  # In every shape the identifier is the last identifier-ish token before the
  # first `(`, `[` or `;`.
  file(STRINGS "${_header}" _api_lines
       REGEX "(LLAMA_API|GGML_API|GGML_BACKEND_API)[ \t]" ENCODING UTF-8)
  foreach(_line IN LISTS _api_lines)
    # Skip comments and, importantly, preprocessor lines: the headers define
    # the very macros we are grepping for (`#define GGML_API __declspec(...)`,
    # `#define GGML_DEPRECATED(func, hint) func __attribute__((deprecated)))`)
    # and those lines yield `__declspec`, `extern`, `DEPRECATED`… as "symbols",
    # which ld.lld rejects with a bare `Unknown language`.
    if(_line MATCHES "^[ \t]*(#|//|\\*|/\\*)")
      continue()
    endif()
    string(REGEX REPLACE "[ \t]*[([;].*$" "" _stripped "${_line}")
    if(_stripped MATCHES "[* \t]([A-Za-z_][A-Za-z0-9_]*)$")
      # Capture before the next `MATCHES`: any `if(... MATCHES ...)` rewrites
      # CMAKE_MATCH_<n>, so reading it after the prefix test below would yield
      # the prefix ("llama_") instead of the symbol.
      set(_candidate "${CMAKE_MATCH_1}")
      # Accept only the upstream public prefixes. Belt and braces with the
      # preprocessor filter above, and it is what keeps the exported set
      # literally "the llama_*/ggml_*/gguf_* API plus our own skein_*".
      if(_candidate MATCHES "^(llama_|ggml_|gguf_)")
        list(APPEND _skein_llama_exports "${_candidate}")
      endif()
    endif()
  endforeach()
endforeach()

# Our own surface.
#   skein_llama_*            E1.I4's build-introspection accessors
#   skein_ctx_free_secure    E4.I1's KV-zeroing context free
#                            (docs/design/LOCK_POLICY_INDEXING.md §4.5, called
#                            by E4.I3's onLocked path)
#   JNI_On{Load,Unload}      found by the ART runtime at System.loadLibrary
#   Java_…LlamaNative_*      the JNI entry points (E4.I1)
#
# The JNI entry points are matched with a glob rather than enumerated: their
# names are mechanically derived from `LlamaNative.kt`, and a hand-kept list of
# twenty mangled names in CMake would drift the first time a function is added.
# Bounding the set is not this glob's job anyway — `local: *;` plus
# "whitelist ∩ defined" already does that, and `tools/ci/jni-symbols.sh`
# asserts the stronger property the acceptance criteria actually name: the
# `Java_` symbols in the .so are EXACTLY the `external fun`s declared in
# `LlamaNative.kt`, no more and no fewer.
list(APPEND _skein_llama_exports
  skein_llama_default_backend
  skein_llama_vulkan_available
  skein_llama_version
  skein_ctx_free_secure
  JNI_OnLoad
  JNI_OnUnload
  "Java_app_skein_inference_service_LlamaNative_*"
)

list(REMOVE_DUPLICATES _skein_llama_exports)
list(SORT _skein_llama_exports)

# Sanity: the four symbols the acceptance criteria name must be on the list, or
# the scrape silently regressed and `nm -D` would only tell us after a build.
foreach(_required_sym
    llama_backend_init llama_model_load_from_file llama_decode
    llama_sampler_chain_init)
  if(NOT "${_required_sym}" IN_LIST _skein_llama_exports)
    message(FATAL_ERROR
      "Export scrape did not find `${_required_sym}` in include/llama.h. "
      "Either the header moved or the regex broke — libskein_llama.so would "
      "not satisfy E1.I4's acceptance criteria.")
  endif()
endforeach()

set(_skein_llama_exports_body "")
foreach(_sym IN LISTS _skein_llama_exports)
  string(APPEND _skein_llama_exports_body "    ${_sym};\n")
endforeach()
file(WRITE "${_SKEIN_LLAMA_EXPORTS_LD}"
"# GENERATED by native/llama/CMakeLists.txt (skein-ca2). Do not hand-edit.
# Regenerated at every CMake configure from llama.cpp's public headers.
SKEIN_LLAMA_1.0 {
  global:
${_skein_llama_exports_body}  local:
    *;
};
")
list(LENGTH _skein_llama_exports _skein_llama_export_count)
message(STATUS
  "skein_llama: version script exports ${_skein_llama_export_count} symbols -> ${_SKEIN_LLAMA_EXPORTS_LD}")

target_link_options(skein_llama PRIVATE
  "-Wl,--build-id=none"
  "-Wl,--no-rosegment"
  # 16 KiB ELF LOAD-segment alignment (bd skein-gg11.5 / skein-xth5, PP-48,
  # OfflineLLM row OL-46). NDK r27c's default `max-page-size` is 4 KiB, which
  # SIGBUSes on load on a 16 KiB-page-size Android 16 kernel — exactly the
  # mmap Skein performs to map a GGUF model. OL-46's preferred remedy,
  # `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, is an NDK-toolchain-file
  # variable that must be supplied as a `-D` at the *initial* `cmake`
  # invocation (before `project()` triggers the toolchain file) — so it would
  # have to be duplicated into both inference-service/build.gradle.kts's and
  # core/vault/build.gradle.kts's `externalNativeBuild.cmake.arguments`, and
  # again into anything invoking cmake outside Gradle. These two explicit
  # linker flags do the same job (set PT_LOAD's `p_align`) from inside the one
  # CMakeLists.txt every build path already shares: AGP's externalNativeBuild
  # for both `libskein_llama.so` and `libskein_sqlite_jni.so`, and
  # tools/rb/so-determinism.sh / the reproducible-build workflow, which build
  # through `./gradlew … assembleFossRelease` rather than around it. One
  # source of truth, no Gradle-arguments duplication. `max-page-size` is what
  # the dynamic linker reads; `common-page-size` is set identically so a
  # tool/linker version that instead consults it agrees.
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
  "-Wl,--version-script,${_SKEIN_LLAMA_EXPORTS_LD}"
  # The headers declare APIs whose definitions are compiled out by the option
  # set above (backend-specific entry points, training/opt helpers, …). Their
  # names are still scraped. `--undefined-version` makes the linker skip a
  # whitelist entry with no matching definition instead of erroring; the
  # exported set stays bounded by "whitelist ∩ defined", so nothing leaks.
  # Same rationale as native/sqlite (skein-6rwv).
  "-Wl,--undefined-version"
)

set_target_properties(skein_llama PROPERTIES
  LINK_DEPENDS "${_SKEIN_LLAMA_EXPORTS_LD}"
  # AGP looks for `.so` files under `<cxxBuildDir>/<abi>/`.
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

# -----------------------------------------------------------------------------
# 8. Post-link ELF guard (mirrors native/sqlite's skein-doq0 guard)
# -----------------------------------------------------------------------------
# Proves, at build time and on every build, that the artifact we are about to
# ship (a) is for this ABI, (b) carries no build-id note, and (c) embeds no
# absolute path from this machine. (c) is the check that actually catches a
# regression: a single upstream TU compiled without `-ffile-prefix-map` is
# enough to make the .so's sha256 depend on where the repo lives, which is
# exactly what E1.I8 (skein-ddp) and the second-checkout proof rely on not
# happening.
add_custom_command(TARGET skein_llama POST_BUILD
  COMMAND "${CMAKE_COMMAND}"
    "-DSO_FILE=$<TARGET_FILE:skein_llama>"
    "-DLLVM_READELF=${_NDK_TOOLCHAIN_BIN}/llvm-readelf"
    "-DTARGET_ABI=${ANDROID_ABI}"
    "-DEXPECTED_MACHINE=${SKEIN_LLAMA_EXPECTED_ELF_MACHINE}"
    "-DFORBIDDEN_PREFIXES=${SKEIN_REPO_ROOT}|${ANDROID_NDK}|${CMAKE_CURRENT_BINARY_DIR}"
    -P "${SKEIN_LLAMA_ROOT}/cmake/verify_llama_elf.cmake"
  VERBATIM
  COMMENT "Verifying libskein_llama.so ABI / build-id / path hygiene")
