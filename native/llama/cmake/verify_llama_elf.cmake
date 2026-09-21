# verify_llama_elf.cmake — post-link ELF guard for libskein_llama.so (skein-ca2)
#
# Run in CMake script mode (`cmake -D... -P`) as a POST_BUILD command of the
# `skein_llama` target in native/llama/CMakeLists.txt. Three checks, each of
# which has a real failure mode behind it:
#
#   1. ABI            — the skein-doq0 guard, ported. Cross-ABI object
#                       pollution used to surface only as an opaque
#                       `ld.lld: ... is incompatible with aarch64linux`
#                       minutes into a build; here it is named immediately.
#   2. build-id       — `-Wl,--build-id=none` silently stops applying if a
#                       future toolchain or an AGP-injected link flag wins.
#                       A build-id note is a hash of the linked content
#                       *including* paths, so its presence alone can break
#                       byte-identical rebuilds.
#   3. path hygiene   — one upstream translation unit compiled without
#                       `-ffile-prefix-map` is enough to bake this machine's
#                       checkout path into the .so, which makes its sha256
#                       depend on *where the repo lives*. That defeats E1.I8
#                       (skein-ddp) and the second-checkout proof, and it
#                       leaks the developer's home directory into a FOSS
#                       artifact.
#
# Required -D arguments:
#   SO_FILE             absolute path to the freshly linked libskein_llama.so
#   LLVM_READELF        absolute path to the NDK's llvm-readelf
#   TARGET_ABI          ANDROID_ABI the .so is supposed to be built for
#   EXPECTED_MACHINE    ELF `Machine:` string llvm-readelf prints for TARGET_ABI
#   FORBIDDEN_PREFIXES  `|`-separated absolute path prefixes that must not
#                       appear anywhere in the file

cmake_minimum_required(VERSION 3.22)

foreach(_required IN ITEMS SO_FILE LLVM_READELF TARGET_ABI EXPECTED_MACHINE)
  if(NOT DEFINED ${_required} OR "${${_required}}" STREQUAL "")
    message(FATAL_ERROR "verify_llama_elf.cmake: -D${_required}=<value> is required")
  endif()
endforeach()

if(NOT EXISTS "${SO_FILE}")
  message(FATAL_ERROR "llama ELF guard: ${SO_FILE} does not exist after linking.")
endif()
if(NOT EXISTS "${LLVM_READELF}")
  message(FATAL_ERROR
    "llama ELF guard: ${LLVM_READELF} not found (expected in the NDK toolchain bin dir).")
endif()

# --- 1. ABI ------------------------------------------------------------------
execute_process(
  COMMAND "${LLVM_READELF}" -h "${SO_FILE}"
  OUTPUT_VARIABLE _header
  ERROR_VARIABLE _readelf_err
  RESULT_VARIABLE _readelf_rc)
if(NOT _readelf_rc EQUAL 0)
  message(FATAL_ERROR
    "llama ELF guard: `llvm-readelf -h` failed on ${SO_FILE} (${_readelf_rc}): ${_readelf_err}")
endif()
if(NOT _header MATCHES "Machine:[ \t]+([^\r\n]+)")
  message(FATAL_ERROR
    "llama ELF guard: no `Machine:` line in the ELF header of ${SO_FILE}:\n${_header}")
endif()
string(STRIP "${CMAKE_MATCH_1}" _machine)
if(NOT _machine STREQUAL "${EXPECTED_MACHINE}")
  message(FATAL_ERROR
    "llama ELF guard FAILED: ${SO_FILE}\n"
    "  built for  ${_machine}\n"
    "  expected   ${EXPECTED_MACHINE}  (ANDROID_ABI=${TARGET_ABI})\n"
    "\n"
    "Delete inference-service/.cxx and inference-service/build/intermediates/cxx "
    "and rebuild; a stale per-ABI build tree is the usual cause (skein-doq0).")
endif()

# --- 2. build-id -------------------------------------------------------------
execute_process(
  COMMAND "${LLVM_READELF}" -n "${SO_FILE}"
  OUTPUT_VARIABLE _notes
  ERROR_VARIABLE _notes_err
  RESULT_VARIABLE _notes_rc)
if(NOT _notes_rc EQUAL 0)
  message(FATAL_ERROR
    "llama ELF guard: `llvm-readelf -n` failed on ${SO_FILE} (${_notes_rc}): ${_notes_err}")
endif()
if(_notes MATCHES "Build ID")
  message(FATAL_ERROR
    "llama ELF guard FAILED: ${SO_FILE} carries a GNU build-id note.\n"
    "`-Wl,--build-id=none` is set in native/llama/CMakeLists.txt, so something "
    "is appending `--build-id` after it (an AGP link flag, an NDK default, or "
    "a toolchain change). A build-id makes two builds of identical sources "
    "differ, which breaks E1.I8's double-build check.")
endif()

# --- 3. path hygiene ---------------------------------------------------------
if(DEFINED FORBIDDEN_PREFIXES AND NOT "${FORBIDDEN_PREFIXES}" STREQUAL "")
  string(REPLACE "|" ";" _prefixes "${FORBIDDEN_PREFIXES}")
  # `file(STRINGS)` extracts printable runs the way `strings(1)` does; 4 is
  # strings(1)'s default minimum and keeps the scan cheap on a ~10 MB .so.
  file(STRINGS "${SO_FILE}" _so_strings LENGTH_MINIMUM 4)
  set(_leaks "")
  foreach(_prefix IN LISTS _prefixes)
    if("${_prefix}" STREQUAL "")
      continue()
    endif()
    foreach(_s IN LISTS _so_strings)
      string(FIND "${_s}" "${_prefix}" _pos)
      if(NOT _pos EQUAL -1)
        list(APPEND _leaks "${_prefix} -> ${_s}")
        break()
      endif()
    endforeach()
  endforeach()
  if(NOT _leaks STREQUAL "")
    string(REPLACE ";" "\n  " _leak_text "${_leaks}")
    message(FATAL_ERROR
      "llama ELF guard FAILED: ${SO_FILE} embeds absolute build paths:\n  ${_leak_text}\n"
      "\n"
      "Every translation unit must be compiled with the `-ffile-prefix-map` "
      "set in native/llama/CMakeLists.txt §4. A subproject that resets "
      "CMAKE_C_FLAGS/CMAKE_CXX_FLAGS, or a generated source compiled by a "
      "custom command outside that scope, will reintroduce the leak. See "
      "native/llama/README.md, \"Reproducibility\".")
  endif()
endif()

message(STATUS
  "llama ELF guard: ${TARGET_ABI} libskein_llama.so is ${_machine}, no build-id, no absolute paths")
