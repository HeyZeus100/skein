# verify_openssl_abi.cmake — post-install ABI guard for libcrypto.a (skein-doq0)
#
# Run in CMake script mode (`cmake -D... -P`) as the last INSTALL_COMMAND of
# `ExternalProject_Add(openssl_ep)` in native/sqlite/CMakeLists.txt.
#
# Cross-ABI object pollution (skein-doq0) used to surface only at the final
# link, as an `ld.lld: error: ...(foo.o) is incompatible with aarch64linux`
# several minutes into the build and with no hint about the cause. This guard
# reads the ELF header of the first real member of the freshly installed
# libcrypto.a and fails immediately, naming the ABI mismatch, if it was not
# compiled for ANDROID_ABI.
#
# Required -D arguments:
#   LIBCRYPTO         absolute path to the installed libcrypto.a
#   LLVM_AR           absolute path to the NDK's llvm-ar
#   LLVM_READELF      absolute path to the NDK's llvm-readelf
#   TARGET_ABI        ANDROID_ABI the archive is supposed to be built for
#   EXPECTED_MACHINE  ELF `Machine:` string llvm-readelf prints for TARGET_ABI
#   SCRATCH_DIR       writable directory for the extracted probe object

cmake_minimum_required(VERSION 3.22)

foreach(_required IN ITEMS
    LIBCRYPTO LLVM_AR LLVM_READELF TARGET_ABI EXPECTED_MACHINE SCRATCH_DIR)
  if(NOT DEFINED ${_required} OR "${${_required}}" STREQUAL "")
    message(FATAL_ERROR "verify_openssl_abi.cmake: -D${_required}=<value> is required")
  endif()
endforeach()

if(NOT EXISTS "${LIBCRYPTO}")
  message(FATAL_ERROR
    "OpenSSL ABI guard: ${LIBCRYPTO} does not exist after `make install_dev`.")
endif()

foreach(_tool LLVM_AR LLVM_READELF)
  if(NOT EXISTS "${${_tool}}")
    message(FATAL_ERROR
      "OpenSSL ABI guard: ${${_tool}} not found (expected in the NDK toolchain "
      "bin dir).")
  endif()
endforeach()

execute_process(
  COMMAND "${LLVM_AR}" t "${LIBCRYPTO}"
  OUTPUT_VARIABLE _members
  ERROR_VARIABLE _ar_err
  RESULT_VARIABLE _ar_rc
)
if(NOT _ar_rc EQUAL 0)
  message(FATAL_ERROR
    "OpenSSL ABI guard: `llvm-ar t ${LIBCRYPTO}` failed (${_ar_rc}): ${_ar_err}")
endif()

# First member that is an actual object file; skip the archive symbol table
# entries some ar implementations list (`__.SYMDEF`, `/`, `//`).
string(REPLACE "\n" ";" _member_list "${_members}")
set(_member "")
foreach(_candidate IN LISTS _member_list)
  string(STRIP "${_candidate}" _candidate)
  if(_candidate MATCHES "\\.o$")
    set(_member "${_candidate}")
    break()
  endif()
endforeach()
if(_member STREQUAL "")
  message(FATAL_ERROR
    "OpenSSL ABI guard: ${LIBCRYPTO} contains no object members — the OpenSSL "
    "build produced an empty archive.")
endif()

file(MAKE_DIRECTORY "${SCRATCH_DIR}")
set(_probe "${SCRATCH_DIR}/abi-probe-${TARGET_ABI}.o")
execute_process(
  COMMAND "${LLVM_AR}" p "${LIBCRYPTO}" "${_member}"
  OUTPUT_FILE "${_probe}"
  ERROR_VARIABLE _extract_err
  RESULT_VARIABLE _extract_rc
)
if(NOT _extract_rc EQUAL 0)
  message(FATAL_ERROR
    "OpenSSL ABI guard: could not extract ${_member} from ${LIBCRYPTO} "
    "(${_extract_rc}): ${_extract_err}")
endif()

execute_process(
  COMMAND "${LLVM_READELF}" -h "${_probe}"
  OUTPUT_VARIABLE _header
  ERROR_VARIABLE _readelf_err
  RESULT_VARIABLE _readelf_rc
)
file(REMOVE "${_probe}")
if(NOT _readelf_rc EQUAL 0)
  message(FATAL_ERROR
    "OpenSSL ABI guard: `llvm-readelf -h` failed on ${_member} "
    "(${_readelf_rc}): ${_readelf_err}")
endif()

if(NOT _header MATCHES "Machine:[ \t]+([^\r\n]+)")
  message(FATAL_ERROR
    "OpenSSL ABI guard: no `Machine:` line in the ELF header of ${_member}:\n${_header}")
endif()
string(STRIP "${CMAKE_MATCH_1}" _machine)

if(NOT _machine STREQUAL "${EXPECTED_MACHINE}")
  message(FATAL_ERROR
    "OpenSSL ABI guard FAILED: ${LIBCRYPTO}\n"
    "  member   ${_member}\n"
    "  built for  ${_machine}\n"
    "  expected   ${EXPECTED_MACHINE}  (ANDROID_ABI=${TARGET_ABI})\n"
    "\n"
    "libcrypto.a was compiled for the wrong architecture. Historically this "
    "meant cross-ABI object pollution (skein-doq0): build products left in "
    "native/sqlite/third_party/openssl-*/ by a previous ABI or by the manual "
    "recipe in native/sqlite/README.md §4 leaking into this ABI's build tree. "
    "The per-ABI tree is now extracted fresh from the pinned tarball, so if "
    "you are seeing this, something else is reusing a stale build or install "
    "directory. Delete core/vault/.cxx and core/vault/build/intermediates/cxx "
    "and rebuild; if it persists, file a bug with this message.")
endif()

message(STATUS
  "OpenSSL ABI guard: ${TARGET_ABI} libcrypto.a is ${_machine} (checked ${_member})")
