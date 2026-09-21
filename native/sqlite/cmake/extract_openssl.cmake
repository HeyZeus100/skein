# extract_openssl.cmake — pristine per-ABI OpenSSL build tree (skein-doq0)
#
# Run in CMake script mode (`cmake -D... -P`) as the first CONFIGURE_COMMAND of
# `ExternalProject_Add(openssl_ep)` in native/sqlite/CMakeLists.txt.
#
# OpenSSL only supports in-tree builds, so each ABI needs its own copy of the
# source. This script materialises that copy by extracting the sha256-pinned
# tarball directly into OPENSSL_BUILD_DIR, instead of copying the shared
# (gitignored) extraction under third_party/openssl-<version>/.
#
# Why extraction rather than "copy then clean": the shared source tree is a
# working directory that README.md §4 and host_harness.c both invite you to
# run `./Configure` + `make` in. A copy of it drags along whatever build
# products are lying there — 1,134 x86_64 `.o` files, a PLATFORM=android-x86_64
# Makefile, a stale configdata.pm, generated opensslconf.h/configuration.h —
# and `make` then considers them up to date, so an arm64 build happily
# installs a libcrypto.a full of x86_64 objects (skein-doq0). Enumerating
# every artifact to delete is a guessing game that a new OpenSSL release can
# silently invalidate; extracting from the pinned tarball makes the dirty-tree
# vector structurally impossible and guarantees every ABI starts from the exact
# bytes openssl.sha256 pins, which is what keeps rebuilds byte-identical.
#
# Required -D arguments:
#   OPENSSL_TARBALL       absolute path to the pinned openssl-<ver>.tar.gz
#   OPENSSL_SHA256        expected sha256 of that tarball
#   OPENSSL_ARCHIVE_ROOT  top-level directory name inside the tarball
#   OPENSSL_BUILD_DIR     absolute path of the per-ABI build tree to (re)create

cmake_minimum_required(VERSION 3.22)

foreach(_required IN ITEMS
    OPENSSL_TARBALL OPENSSL_SHA256 OPENSSL_ARCHIVE_ROOT OPENSSL_BUILD_DIR)
  if(NOT DEFINED ${_required} OR "${${_required}}" STREQUAL "")
    message(FATAL_ERROR "extract_openssl.cmake: -D${_required}=<value> is required")
  endif()
endforeach()

if(NOT EXISTS "${OPENSSL_TARBALL}")
  message(FATAL_ERROR
    "extract_openssl.cmake: pinned tarball missing at ${OPENSSL_TARBALL}. "
    "It is fetched at CMake configure time; re-run the configure step.")
endif()

# Re-verify here as well as at configure time: this script is what actually
# produces the bytes we compile, so it should not trust a tarball it did not
# hash itself.
file(SHA256 "${OPENSSL_TARBALL}" _actual_sha)
string(TOLOWER "${OPENSSL_SHA256}" _expected_sha)
if(NOT _actual_sha STREQUAL _expected_sha)
  message(FATAL_ERROR
    "extract_openssl.cmake: sha256 mismatch for ${OPENSSL_TARBALL}\n"
    "  expected ${_expected_sha}\n"
    "  actual   ${_actual_sha}")
endif()

# Stage beside (never inside) the build dir so the final swap is a single
# rename and a half-extracted tree can never be mistaken for a usable one.
set(_stage "${OPENSSL_BUILD_DIR}.extract-tmp")
file(REMOVE_RECURSE "${_stage}")
file(MAKE_DIRECTORY "${_stage}")

message(STATUS "Extracting pristine OpenSSL tree into ${OPENSSL_BUILD_DIR}")
file(ARCHIVE_EXTRACT INPUT "${OPENSSL_TARBALL}" DESTINATION "${_stage}")

if(NOT EXISTS "${_stage}/${OPENSSL_ARCHIVE_ROOT}/Configure")
  file(REMOVE_RECURSE "${_stage}")
  message(FATAL_ERROR
    "extract_openssl.cmake: ${OPENSSL_ARCHIVE_ROOT}/Configure not found in "
    "${OPENSSL_TARBALL} — the archive layout is not what we pinned.")
endif()

file(REMOVE_RECURSE "${OPENSSL_BUILD_DIR}")
file(RENAME "${_stage}/${OPENSSL_ARCHIVE_ROOT}" "${OPENSSL_BUILD_DIR}")
file(REMOVE_RECURSE "${_stage}")
