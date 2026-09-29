# Native vocabulary and template probe

`model-readiness.cpp` loads only the vocabulary of an already hash-verified
public GGUF. It creates no context and does no decoding or sampling. Its JSON
reports the pinned native EOS/EOT IDs, native EOG/control classifications for
`<|im_end|>`, `<|endoftext|>` and `</s>`, and exact token arrays for the same four
benign ChatML cases used by `SyntheticTemplateParityTest`. It also writes the
exact native template bytes to a new file for independent SHA-256 verification.

This is classification and template/tokenization evidence. It does **not** prove
that a generated answer emits EOS, stops correctly, answers coherently, meets a
latency target, or uses the exact app-private model selected on the Fold.
`generated_stop_behavior` and `answer_quality` remain `unmeasured` in its JSON.
The original `tokenizer-parity.cpp` tiny-model exact 30-token regression is
unchanged and remains a separate test.

The host probe compares the native renderer with fixed, synthetic ChatML
scaffold boundaries. A mismatch fails; it never substitutes a template for the
model. The Android opt-in parity test separately exercises the production
`ChatTemplating` boundary proof. Its new top-level `native_eog` object calls the
actual JNI `isEog` function for the verified public model's declared EOS and
control-spelling token IDs. It does not infer observed generated stopping from
that classification.

## Build without editing repository CMake files

The coordination owner must hold `build.lock` before configuring/building this
probe. Initialize pinned submodules through the approved repository workflow.
Use the repository's chosen CMake/compiler toolchain. The generated project below
reuses the existing host tokenizer project and its hash-checked tokenizer
overlay. It does not change app CPU compatibility, Vulkan policy or isolation.

Run from the isolated inference worktree. Choose a fresh generated source/build
directory if these already contain evidence from another run:

```sh
mkdir -p build/model-readiness-source
cat > build/model-readiness-source/CMakeLists.txt <<'CMAKE'
cmake_minimum_required(VERSION 3.22)
project(skein_model_readiness CXX C)
if(NOT DEFINED SKEIN_REPO)
  message(FATAL_ERROR "Pass an absolute SKEIN_REPO")
endif()
file(STRINGS "${SKEIN_REPO}/native/llama/PINNED_COMMIT" PIN_LINE
  REGEX "^third_party/llama[.]cpp[ \t]+")
string(REGEX REPLACE "^[^ \t]+[ \t]+([0-9a-f]+).*" "\\1" LLAMA_PIN "${PIN_LINE}")
execute_process(COMMAND git -C "${SKEIN_REPO}/third_party/llama.cpp" rev-parse HEAD
  OUTPUT_VARIABLE LLAMA_HEAD OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
if(NOT LLAMA_HEAD STREQUAL LLAMA_PIN)
  message(FATAL_ERROR "llama.cpp checkout does not match PINNED_COMMIT")
endif()
file(SHA256 "${SKEIN_REPO}/native/llama/tokenizer-patches/PINS.txt" OVERLAY_SHA)
add_subdirectory("${SKEIN_REPO}/native/llama/tests/tokenizer" tokenizer-host)
add_executable(model-readiness "${SKEIN_REPO}/native/llama/tests/tokenizer/model-readiness.cpp")
target_include_directories(model-readiness PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/tokenizer-host/tokenizer-src")
target_link_libraries(model-readiness PRIVATE llama)
target_compile_features(model-readiness PRIVATE cxx_std_17)
target_compile_definitions(model-readiness PRIVATE
  SKEIN_LLAMA_CPP_COMMIT="${LLAMA_HEAD}"
  SKEIN_TOKENIZER_OVERLAY_SHA256="${OVERLAY_SHA}")
CMAKE
cmake -S build/model-readiness-source -B build/model-readiness-host \
  -DSKEIN_REPO="$PWD" -DCMAKE_BUILD_TYPE=Release
cmake --build build/model-readiness-host --target model-readiness -j 2
```

Set `PUBLIC_MODEL` to the fully verified Qwen artifact path recorded in the
coordination `demo.json`, and retain that qualification report alongside these
results. Always create a new evidence directory. The template output path must
not exist; the probe refuses to overwrite it.

```sh
PUBLIC_MODEL='/absolute/path/to/verified/public.gguf'
mkdir -p build/agent-logs
EVIDENCE_DIR=$(mktemp -d "$PWD/build/agent-logs/qwen-native-readiness.XXXXXX")
shasum -a 256 "$PUBLIC_MODEL" > "$EVIDENCE_DIR/model.sha256"
build/model-readiness-host/model-readiness "$PUBLIC_MODEL" \
  "$EVIDENCE_DIR/template.utf8" > "$EVIDENCE_DIR/native-readiness.json"
shasum -a 256 "$EVIDENCE_DIR/template.utf8" > "$EVIDENCE_DIR/template.sha256"
python3 -m json.tool "$EVIDENCE_DIR/native-readiness.json"
```

Retain command exit codes, source SHA, compiler/CMake identity, full model hash,
raw JSON and template bytes. Compare the emitted template hash with the prior
full-model qualification. A host pass is not Android runtime proof. Request the
coordinator's actual Android JSON/XML for the same model SHA, then perform the
approved short Qwen answer, natural stopping, Stop and lock demo checks through
the designated device runner.
