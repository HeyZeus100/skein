# `tools/models` — the CI/instrumented-test model fixture

This directory pins the **tiny GGUF used only by `LlamaNativeTest`**
(`inference-service/src/androidTest/`, bd `skein-80p` / E4.I2) — a
model-provenance record for a test fixture, not for anything Skein ships.
For the M0 benchmark and formal-candidate model provenance, see
`tools/m0-benchmark/models.yaml` and `models:`/`binaries:` there; this file
follows the same discipline (URL + sha256 + size + licence, hash-verified
before use, nothing loaded on trust) at a much smaller scale.

## Why a model is needed here at all

`native/llama/jni/skein_jni.cpp` (bd `skein-3aw`) is written and reviewable,
but its acceptance criteria — tokenize round-trips, chat-template
application, decode + sample, embeddings, the cancel-flag race, the
non-GGUF rejection path — can only be *proven* against a real GGUF and a
real `llama_decode`. `LlamaNativeTest` gates itself on the asset's presence
(`assumeModel()`) rather than failing when it is absent, so a checkout with
no network still compiles and passes; the emulator lane (`.github/workflows/
emulator.yml`, bd `skein-f0sy`) has network and is where these tests
actually run and assert something.

## What is pinned, and why this model

| Field | Value |
|---|---|
| Model | SmolLM2-135M-Instruct (HuggingFaceTB) |
| Quantisation | `Q2_K` GGUF, requantized by `bartowski` |
| Repo | <https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF> |
| File | `SmolLM2-135M-Instruct-Q2_K.gguf` |
| Size | 88,202,080 B ≈ **84.1 MiB** |
| SHA-256 | `741ad12b64088fedc17c33aacb22e48be1972ef36a39f03666dd68bd15614fb9` |
| Licence | Apache-2.0 (inherited from `HuggingFaceTB/SmolLM2-135M-Instruct`, `license: apache-2.0` in its model card) |

Chosen because:

* **Genuinely tiny.** 135M parameters is close to the smallest instruction-
  tuned causal LM with a maintained GGUF conversion; every quantisation
  `bartowski` publishes for it lands in the 84–105 MiB range (checked
  directly against the repo's file listing before picking one — most of the
  size is the 49,152-token embedding/output table, which quantises less
  aggressively than the transformer blocks on a model this small, so going
  below `Q2_K` buys almost nothing). `Q2_K` (84.1 MiB) is the smallest
  variant offered; a larger quant was not needed because this fixture never
  judges output *quality*, only that decode/sample/tokenize are wired
  correctly and produce the same bytes every time.
* **Permissively licensed, with real provenance.** Apache-2.0, from the
  model author's own org (`HuggingFaceTB`) with an explicit `license:
  apache-2.0` tag on the base model's card — not a third-party reupload of
  unclear provenance. The GGUF conversion itself is `bartowski`'s (a
  widely-used, actively-maintained quantizer whose repos are a common
  dependency across the llama.cpp ecosystem); the conversion changes only
  the tensor encoding, not the licence.
* **Has an embedded chat template.** `applyChatTemplateContainsTheUserMessage`
  (bd `skein-3aw`, already in `LlamaNativeTest`) calls
  `LlamaNative.applyChatTemplate`, which throws `LlamaErrorCode
  .TEMPLATE_UNSUPPORTED` on a GGUF with no embedded
  `tokenizer.chat_template` — unlike a raw base/"stories" toy model, the
  Instruct conversion carries one, so that test exercises the real path
  instead of skipping.
* **Stable URL.** `huggingface.co/<org>/<repo>/resolve/main/<file>` is
  Hugging Face's canonical, CDN-backed download URL; `fetchTestModel`
  verifies the sha256 regardless, so a future re-upload under the same path
  that changes the bytes fails loudly rather than silently loading a
  different model.
* **CI-runtime budget.** 84 MiB downloads in a few seconds on a GitHub-hosted
  runner, and a 135M CPU decode is fast — comfortably inside the < 90 s
  instrumented-test budget `skein-80p`'s acceptance criteria set, even
  stacked with APK install and emulator boot already charged to the lane.

## How it is fetched

`inference-service/build.gradle.kts` registers `fetchTestModel`
(`FetchTestModelTask`), which:

1. reads `url` / `sha256` from `test-model.lock`;
2. if the destination file already exists and already hashes to `sha256`,
   does nothing (repeat CI runs and repeat local builds don't re-download);
3. otherwise downloads to a temporary file, hashes it, and only then moves it
   into place — a mismatch deletes the temp file and fails the build with
   both hashes in the message;
4. writes to `inference-service/src/androidTest/assets/tiny.gguf`, which is
   git-ignored (`.gitignore`; also already covered by the repo-wide
   `*.gguf` rule) — **never commit this file**; run `git status` before
   committing anything in this area, as `CLAUDE.md` and this bead's working
   rules require.

The task is wired only into the `androidTest` asset-merge tasks (every
`merge*AndroidTestAssets` task depends on it), so `:app` and any non-test
variant never runs it and never needs network — spec §9's "the app never
does" holds regardless of what this fixture needs.

## `LlamaNativeTest`'s golden sequence

`goldenGreedySequenceIsPinned` asserts an exact 16-token id sequence from a
greedy (`temp = 0f`, pure-argmax, no RNG) decode of `"Hello world"` on this
model. See the `GOLDEN_GREEDY_IDS` comment in `LlamaNativeTest.kt` for the
full provenance note, including how it was computed (a host CPU-backend
harness mirroring the JNI call sequence, since no emulator was available in
this dispatch) and what a future mismatch means. `sameSeedTwiceProducesTheSameIds`
is the companion determinism check and needs no pinned value.
