# Pinned host vocabulary probe

This later evidence extends the untouched identity-only report in the parent
directory. `native-readiness.json` is exact probe stdout; `template.utf8` is its
exact native template output; `provenance.json` records source/toolchain, exit
code and independently verified complete model/template hashes. `SHA256SUMS`
covers those three files. The full GGUF remains outside Git.

The probe used the existing pinned host tokenizer harness, a generated CMake
driver, CPU vocabulary-only loading and two build jobs. It created no decoding
context and performed no generation. Four benign render/tokenization cases pass.
All three observed controls (`<|im_end|>`, `<|endoftext|>`, `</s>`) are native EOG;
the last is the pinned upstream spelling-based normalization of its stored type.

This does not prove Android JNI behavior, selected app-private bytes, generated
stopping, answer quality or demo readiness. The source SHA identifies the tracked
probe/native source; unrelated app/client lifecycle edits were pending during the
host run. Build logs remain in the inference worktree's `build/agent-logs/`.

Run `shasum -a 256 -c SHA256SUMS` here to verify the preserved raw evidence.
