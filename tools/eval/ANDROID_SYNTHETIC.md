# Opt-in Android synthetic benchmark

This harness has two separate paths:

- `app.skein.benchmark.SyntheticAnswerBenchmarkTest` uses the real `SendPipeline`,
  prompt assembly, citation parser, exact prompt fitting, and `LlamaCppEngine`
  through the isolated APK service. Evidence and history come exclusively from
  the gold-free host fixture; the repository is in memory. It does not open the
  owner's vault, registry, chat history, or Activity. Its mode is
  `supplied_evidence`, not a retrieval/index/lifecycle evaluation.
- `app.skein.inference.service.SyntheticTemplateParityTest` runs in the separate
  library test app, which has no vault access. It compares complete native token
  ID sequences produced by production segmented tokenization against the native
  full-template reference for fixed benign synthetic conversations, and checks
  literal ChatML control-token isolation. This is not a generation quality score.

Neither class is included in an ordinary test APK. Build with
`-Pskein.syntheticBenchmark=true` and invoke the exact class explicitly. The
runtime also requires `synthetic_enabled=true`; missing inputs fail instead of
producing a successful no-op or assumption skip.

## Build and local checks

Use the repository's pinned JDK and SDK. These commands do not touch a device:

```sh
./gradlew -Pskein.syntheticBenchmark=true --max-workers=2 \
  :app:testFossDebugUnitTest --tests app.skein.benchmark.SyntheticFixturesTest \
  :app:ktlintCheck :inference-service:ktlintCheck \
  :app:compileDevDebugAndroidTestKotlin :inference-service:compileDevDebugAndroidTestKotlin
python3 -m unittest discover -s tools/eval -p test_prepare_android_benchmark.py
```

Assemble the exact reviewed source head's app and test APKs only when the
coordinator releases that head. The ordinary emulator reports must be read
first. Device access remains serialized through the dedicated runner under
`docs/DEVICE_RUNNER.md`. Install updates with `adb install -r`; never uninstall,
clear app data, reset a vault, change screen-lock settings, or weaken isolation.
Instrumentation restarts its target process, so run only in an owner-approved
idle window. These instructions do not authorize a concurrent owner interruption.

## Prepare public artifacts on the host

For a repeatable identity-only inventory before preparation, run
`qualify_model_artifact.py --model /absolute/public/model.gguf --expected-sha256 FULL_SHA256`
with optional `--expected-size BYTES` and artifact-specific `--control-token TEXT`.
It verifies the full local hash and reports bounded tokenizer/template metadata,
declared end-token IDs and vocabulary types. It does not load the model or prove
native EOG, template compatibility or answer quality. See the
[2026-09-29 readiness audit](../../docs/Handoffs/skein-inference-model-readiness-20260929.md)
for outstanding Qwen/Gemma evaluation requests and the current ChatML-only parity
test limitation.

Export inputs with `answer_eval.py export`; gold judgments remain on the host.
Use `answer_eval.py validate` to obtain `case_set_sha256`. Verify the public model's
full SHA256, source and licence. A header digest cannot replace the full model hash.

`prepare_android_benchmark.py` hashes the entire supplied public GGUF, checks its
expected digest, then traverses only bounded GGUF metadata to hash the exact raw
`tokenizer.chat_template` bytes. No GGUF parsing is added to the app process. The
app independently verifies the same full model hash before the ordinary isolated
load gates verify it again. This binds host template metadata to device model bytes.

```sh
python3 tools/eval/prepare_android_benchmark.py \
  --model /absolute/public/model.gguf --model-sha256 FULL_SHA256 \
  --model-license VERIFIED_MANIFEST_LICENSE --apk /absolute/app-dev-debug.apk \
  --fixture /absolute/development-input.jsonl --case-set-sha256 CASE_SET_SHA256 \
  --build-sha FULL_BUILD_SHA --llama-sha FULL_PINNED_LLAMA_SHA \
  --tokenizer-overlay-sha256 SHA256_OF_TOKENIZER_PATCH_PINS \
  --run-id development-baseline-01 --context-length 4096 --seeds 17 \
  --output-dir /absolute/new-run-directory
```

The helper only writes host files. It never invokes ADB, installs, or generates.
It writes `config.json`, the exact `input.jsonl`, and `model_metadata.json`.
Supply `--sampling /absolute/sampling.json` to change all sampler fields; its
schema is `temperature`, `top_k`, `top_p`, `min_p`, `repeat_penalty`, `max_tokens`,
`stop`. The default is bounded greedy smoke configuration (256 answer tokens),
not a claimed quality optimum. Reserved comparisons use seeds 17, 41 and 73;
greedy results may be identical across seeds, which is recorded without retries.

## Stage into dedicated test storage

The app accepts only:

- configuration and fixture files below `files/synthetic-benchmark/input/`;
- a public model copy at `files/synthetic-benchmark/models/<full-sha256>/model.gguf`;
- a fresh result directory `files/synthetic-benchmark/output/<run_id>/`.

Canonical path checks reject traversal and symlink escapes. Existing owner model
paths and vault paths are outside this allowlist. Only the dedicated public model
copy is made read-only (0400 file, 0500 directory). The model directory must
contain only `model.gguf`, preserving the ordinary manifest-coverage checks.
Do not overwrite an existing public artifact or result directory. Use a new run
ID; an existing verified model copy can be reused unchanged.

The runner may stage through `adb shell -T run-as app.skein dd of=<dedicated-path>`
with the host public file on stdin, after checking destination absence, or use
another reviewed binary-safe app-private copy path. No storage permissions or
SAF grants are required. Transfer only the three explicit public/synthetic files;
never list, copy, or read vault contents. A device user's app-private absolute path
may differ; `--device-root` controls it and permits only the app's benchmark root.

After staging, invoke only the benchmark class (substitute the exact observed
instrumentation component and dedicated configuration path):

```sh
adb -s SERIAL shell am instrument -w \
  -e class app.skein.benchmark.SyntheticAnswerBenchmarkTest \
  -e synthetic_enabled true -e synthetic_config DEDICATED_CONFIG_PATH \
  app.skein.test/androidx.test.runner.AndroidJUnitRunner
```

For native token parity, install the inference-service opt-in test APK into its
separate test package, stage the same public model beneath that package's own
`files/synthetic-benchmark/models/`, and use:

```sh
adb -s SERIAL shell am instrument -w \
  -e class app.skein.inference.service.SyntheticTemplateParityTest \
  -e synthetic_enabled true -e synthetic_model_file DEDICATED_PUBLIC_MODEL_PATH \
  -e synthetic_model_sha256 FULL_SHA256 -e synthetic_run_id native-baseline-01 \
  app.skein.inference.service.test/androidx.test.runner.AndroidJUnitRunner
```

Read the installed test manifest to verify component/package names. Do not run
all tests against the Qwen artifact: the ordinary tiny-model golden sequence is
specific to SmolLM2 and to its measured backend.

## Results and limits

Pull only the named output directory's result files with `run-as ... cat`.
The answer run writes `run_manifest.json` and one `answers.jsonl` row per attempted
case/seed, including timeout, OOM and error rows. There is no retry of a failed
case. Rows preserve synthetic answer text, supplied/used source identities,
citations and available generation statistics. `exact_quotes` is omitted because
the production citation parser exposes no validated quote-claim records. An empty
citation/quote list is not evidence that generated quotations are accurate.

`used_sources_status` is `finalized_prompt` when the pipeline supplied its completed
turn outcome. If a timeout or error prevents finalization, it is
`unavailable_before_finalization`; the schema-compatible `used_sources: []` then
means the post-budget source set is unavailable, not that the prompt used no
sources. `provided_sources` still records the host input. Older retained timeout
rows without this status also have unavailable post-budget evidence metadata and
must not be interpreted as verified empty source sets. Their files remain unchanged.

The manifest distinguishes declared host build/llama metadata from verified
installed APK and full-model hashes. `tokenizer_overlay_sha256` is the SHA256 of
the exact `native/llama/tokenizer-patches/PINS.txt` in the reviewed build checkout;
it identifies the tokenizer patch and its pinned input/output files separately
from the upstream `llama_sha`. Both source identities are declared host provenance,
not independently read back from the APK. The smoke runner computes the overlay
digest before installing and checks it against the returned manifest. Older
retained runs without this field predate the overlay and remain unchanged.
The smoke also compares the app's returned `test_apk_sha256` with the host test
APK digest. Before native parity starts, it obtains the native test package's
installed APK path from Android, reads those APK bytes through `adb exec-out`,
and hashes them on the host. The observed digest is retained as
`smoke_summary.json`'s `installed_apk_sha256.native_test`, including on a mismatch.
This lane installs monolithic APKs; missing, split, or unexpected package paths
are refused rather than identifying a split installation by its base APK alone.
`context.allocated` comes from the native
context through the isolated measurement API. Measurement count is checked
against generation statistics, but that count consistency is distinct from the
separate exact token-ID parity test. Memory is null because this harness does not
sample RSS/PSS; no memory estimate is substituted. A coroutine case deadline
cannot preempt an uninterruptible native call or Binder transaction; a stalled
run must remain a recorded timeout/incomplete run, never a fabricated result.

The native parity run writes `native-parity.json` before failing any sequence
mismatch assertion. It contains actual/reference token IDs for the fixed
synthetic cases and a template digest observed through native model metadata.
It does not claim the host renderer and Android backend have identical logits.

Use `answer_eval.py score` with independent manual reviews, as documented in
`docs/ANSWER_EVAL.md`. Successful harness execution is not a factuality score or
an Astra/ChatGPT-equivalence claim.

## Released emulator smoke workflow

`.github/workflows/synthetic-smoke.yml` is manual-only. Dispatch it against the
reviewed pushed head after the coordinator releases the run. It assembles the
opt-in APKs, provisions only the public tiny model pinned in
`tools/models/test-model.lock`, exports all development cases without gold, and
runs both exact classes. The helper `run_android_smoke.py` requires the full
expected source commit and an explicit `emulator-NNNN` serial whose
`ro.kernel.qemu` property is `1`; it refuses physical devices before installing.
It installs with `-r` and never resets data or security settings.

The `tiny-structural-v2` smoke profile uses all 12 development cases, seed 17,
a 1024 requested context, 2 CPU threads, 4 answer tokens, and a 60-second case
deadline. It records that profile in `smoke_summary.json`. The public-model
benchmark preparation defaults and caller-supplied sampling are unchanged.
The smoke fails on missing/duplicate rows, runtime errors, prompt-count
inconsistency, provenance mismatch, or native token-ID mismatch. It preserves
answer rows (including failures), instrumentation output, both manifests, and
native ID arrays as a workflow artifact. It does not grade generated content;
`quality_assessed` is always false. The ordinary emulator suite remains separate
and must pass on the same source head before Fold consideration.

The earlier [run 36384222843](https://github.com/HeyZeus100/skein/actions/runs/36384222843)
used context 4096, 4 threads and 64 answer tokens with the same 60-second deadline.
It retained all 12 rows: 2 OK and 10 timeouts, plus 4/5 passing native parity cases
(the Unicode whitespace case differed). Those failures remain part of the record.
The smaller profile is a structural runtime check, not a retry of an equivalent
quality/performance workload; its timings cannot establish a speed improvement.

### Fold telemetry follow-through

The current harness deliberately leaves `memory` null. Before a Fold comparison,
the released runner should record content-free case start/end events using device
monotonic time, then align bounded host samples to each case/seed. Collect PSS for
the exact observed `app.skein` and `app.skein:inference` PIDs, re-resolving the latter
after any reload, and thermal status plus battery temperature before, during, and
after each case. Record the sampling interval, unavailable samples, charging
state, cooldown criteria, and observed maximum; a sampled maximum is not a proven
instantaneous peak. No private vault inspection or broad app log collection is
needed. Retain raw numeric observations separately from answer scoring, and keep
memory/thermal findings explicitly unavailable until those measurements exist.
