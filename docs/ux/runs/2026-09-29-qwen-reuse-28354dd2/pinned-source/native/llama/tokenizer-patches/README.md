# Provenance-aware native tokenization

The upstream revision remains the commit in `../PINNED_COMMIT`. Skein applies
`scaffold-ranges.patch` to a generated build-tree copy of `llama.cpp/src`.
`PINS.txt` binds the patch and both pristine/transformed source hashes; configure
fails on a mismatch. The checked-out submodule is never modified. All llama
translation units use the copied headers, with upstream source properties and
normalized include/PCH paths preserved. The existing Android prefix maps cover
the generated directory as `/skein/build`.

The new internal `llama_vocab::tokenize_scaffold` passes explicit, per-call
scaffold byte ranges through upstream special-token partitioning. It does not
use TLS, modify vocabulary state, split ordinary text at provenance boundaries,
or replace the upstream tokenizer. A CONTROL/UNKNOWN spelling is recognized only
if its entire UTF-8 byte span lies within a verified scaffold range. A spelling
that overlaps any message content stays literal. USER_DEFINED tokens retain the
upstream `parse_special=false` semantics. This is the same content-token policy
as before, while preserving native BPE merges, whitespace processing,
SentencePiece state and sequence-level BOS/EOS handling.

The JNI entry validates UTF-16, sorted/disjoint bounds and UTF-8 code-point
alignment. Kotlin rejects malformed message strings instead of replacing them
while computing ranges. Rendering still uses the loaded model's template and
verified placeholder boundaries; unsupported templates fail before tokenization.
Measurement and generation share this exact tokenization path.

Record these separately in every model-quality benchmark manifest:

- `llama_cpp_commit`: the unchanged upstream revision from `PINNED_COMMIT`.
- `tokenizer_overlay_sha256`: SHA256 of this directory's `PINS.txt`.
- The Skein source commit and native-library hash, as usual.

Do not label the resulting tokenizer as unmodified upstream. The overlay hash
is independent of model/template hashes. Per-kind token counts were removed
from prefill diagnostics because tokens can cross provenance boundaries; span
counts and the exact total `n_tokens` remain content-free.

## Reproduce the regression without a device

Use the repository-pinned public tiny GGUF, never a private vault or model. Run
`./gradlew :inference-service:fetchTestModel` separately from `check` (the current
fetch task and Android lint model have an implicit-input dependency if combined).
Verify the file against `tools/models/test-model.lock` before using it:

```sh
shasum -a 256 inference-service/src/androidTest/assets/tiny.gguf
cmake -S native/llama/tests/tokenizer -B build/tokenizer-host -DCMAKE_BUILD_TYPE=Release
cmake --build build/tokenizer-host --target tokenizer-parity -j 2
build/tokenizer-host/tokenizer-parity inference-service/src/androidTest/assets/tiny.gguf
```

The test pins the original native reference IDs for synthetic
`"  Café 日本語 🧶\n\n"`: the old segmented tokenizer returns 31 tokens, while
native whole-prompt tokenization and the corrected implementation return the
same 30 IDs. It also checks all/empty authorization against upstream,
control spellings across provenance boundaries, literal message controls, and
concurrent opposite authorization policies. Android `LlamaNativeTest` covers
those JNI cases plus invalid byte ranges and malformed Unicode. These tests
establish tokenization parity and isolation; they do not establish answer quality
or parity with a larger hosted model.

When bumping llama.cpp, review the partition semantics, regenerate all three
hash classes, and rerun the host and Android native parity tests. Do not update
expected token IDs to hide a mismatch.
