# SPIRV-HeadersConfig.cmake — config-package shim for third_party/SPIRV-Headers
#
# `ggml/src/ggml-vulkan/CMakeLists.txt` opens with
#
#     find_package(SPIRV-Headers CONFIG REQUIRED)
#
# and then never uses the result: the shader pipeline shells out to `glslc`,
# which carries its own copy of the SPIR-V grammar. Upstream's CMakeLists is
# nonetheless REQUIRED, so the package has to resolve or configure fails.
#
# Satisfying that with the real package would mean running SPIRV-Headers'
# `install()` into the build tree first — an extra ExternalProject, an extra
# staged directory, and one more absolute path baked into the build for no
# benefit. This file is pointed at by `SPIRV-Headers_DIR` (set in
# native/llama/CMakeLists.txt §5) and declares the same imported target
# upstream's generated config does, backed directly by the pinned submodule's
# header directory.
#
# `SKEIN_SPIRV_HEADERS_INCLUDE_DIR` is set by native/llama/CMakeLists.txt.

if(NOT DEFINED SKEIN_SPIRV_HEADERS_INCLUDE_DIR OR
   NOT EXISTS "${SKEIN_SPIRV_HEADERS_INCLUDE_DIR}/spirv/unified1/spirv.h")
  message(FATAL_ERROR
    "SPIRV-Headers shim: SKEIN_SPIRV_HEADERS_INCLUDE_DIR "
    "(`${SKEIN_SPIRV_HEADERS_INCLUDE_DIR}`) does not look like a checked-out "
    "third_party/SPIRV-Headers. Run `git submodule update --init --recursive`.")
endif()

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${SKEIN_SPIRV_HEADERS_INCLUDE_DIR}")
endif()

set(SPIRV-Headers_INCLUDE_DIRS "${SKEIN_SPIRV_HEADERS_INCLUDE_DIR}")
set(SPIRV-Headers_FOUND TRUE)
