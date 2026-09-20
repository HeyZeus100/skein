cmake_minimum_required(VERSION 3.22)
project(skein_sqlite C)

# -----------------------------------------------------------------------------
# Skein native SQLite: SQLCipher (OpenSSL-backed) + sqlite-vec + FTS5, statically
# linked into a single shared object. Consumed by the vendored androidx.sqlite
# JNI driver (see native/sqlite/androidx-jni/, filed as follow-up).
#
# Spike E0.I7 (skein-1ld) proved the recipe. Full production integration is
# E1.I5.
# -----------------------------------------------------------------------------

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED SKEIN_ROOT)
  set(SKEIN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
endif()

# Where prebuilt OpenSSL static libs live, per ABI.
# Point OPENSSL_ROOT at .../openssl-android-<abi>/ containing include/ and lib/.
if(NOT DEFINED OPENSSL_ROOT)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OPENSSL_ROOT "${SKEIN_ROOT}/build/openssl-android-arm64")
  elseif(ANDROID_ABI STREQUAL "x86_64")
    set(OPENSSL_ROOT "${SKEIN_ROOT}/build/openssl-android-x86_64")
  else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI=${ANDROID_ABI}. Add an OpenSSL build for it.")
  endif()
endif()

add_library(crypto_static STATIC IMPORTED GLOBAL)
set_target_properties(crypto_static PROPERTIES
  IMPORTED_LOCATION "${OPENSSL_ROOT}/lib/libcrypto.a"
  INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_ROOT}/include"
)

add_library(skein_sqlite SHARED
  "${SKEIN_ROOT}/amalgamation/sqlite3.c"
  "${SKEIN_ROOT}/third_party/sqlite-vec-0.1.9/sqlite-vec.c"
  "${SKEIN_ROOT}/skein_extra_init.c"
)

target_compile_definitions(skein_sqlite PRIVATE
  SQLITE_HAS_CODEC
  SQLITE_TEMP_STORE=2
  SQLITE_ENABLE_FTS5
  SQLITE_OMIT_LOAD_EXTENSION
  SQLITE_THREADSAFE=1
  SQLCIPHER_CRYPTO_OPENSSL
  SQLITE_EXTRA_INIT=skein_extra_init
  SQLITE_EXTRA_SHUTDOWN=skein_extra_shutdown
  SQLITE_CORE
  HAVE_STDINT_H=1
  HAVE_INTTYPES_H=1
)

target_include_directories(skein_sqlite PRIVATE
  "${SKEIN_ROOT}/amalgamation"
  "${SKEIN_ROOT}/third_party/sqlite-vec-0.1.9"
)

target_compile_options(skein_sqlite PRIVATE
  -O2 -fvisibility=hidden -fno-strict-aliasing
  -Wno-pointer-bool-conversion
  -Wno-unused-function
  -Wno-unused-variable
  -Wno-unused-but-set-variable
  # Reproducibility: strip absolute build paths from __FILE__
  "-ffile-prefix-map=${SKEIN_ROOT}=/src"
)

# Strip everything not explicitly exported. sqlite3_* is exported via the
# amalgamation's SQLITE_API macro; we add JNI symbol scope in the vendored
# androidx JNI layer (E1.I5).
set_target_properties(skein_sqlite PROPERTIES
  C_VISIBILITY_PRESET hidden
)

target_link_libraries(skein_sqlite PRIVATE crypto_static)

if(ANDROID)
  target_link_libraries(skein_sqlite PRIVATE log android)
endif()

# Emit .so and manifest of build inputs for auditing.
set_target_properties(skein_sqlite PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
)
