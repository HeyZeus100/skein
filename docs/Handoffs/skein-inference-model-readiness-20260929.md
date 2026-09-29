# Inference model readiness, 2026-09-29

Audit baseline: `9fa9f6fdda8a6f624d0e9b248e7a17e92f01890f`. The coordinator's
`20260929/coordinator.json` and `issues-before.json` supply the current issue
records. No device, emulator, CI, artifact download, model replacement, retrieval
policy change or embedding activation was performed. **Fold remains HOLD.**
Newer UX/retrieval execution reports supersede the provisioning handoff's claims
about the shipped APK; the original artifacts remain unchanged.

## Locally available identity evidence

The targeted inventory found the public tiny GGUF in both
`app/src/androidTest/assets/tiny.gguf` and
`inference-service/src/androidTest/assets/tiny.gguf`. Both complete files were
independently hashed during this audit; each is 88,202,080 bytes:

| Identity | Verified value |
|---|---|
| Complete model SHA-256 | `741ad12b64088fedc17c33aacb22e48be1972ef36a39f03666dd68bd15614fb9` |
| Raw 368-byte chat template SHA-256 | `872be49dbb638044ad01b60388f48d469ff2980e5f0dccdc22ec907db54d0788` |
| Raw tokenizer metadata SHA-256 | `12f2daa6c7965ba929ba3b14b5ba2e057ef538bf9fcc25f3a25be1e6c538e715` |
| Tokenizer overlay `PINS.txt` SHA-256 | `71aba69a0fba8abc2df0a8f74f537182b79b29d60b13e74b7dc901e52dc36f29` |
| Pinned llama.cpp source | `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` |

The tokenizer digest is SHA-256 over the concatenated raw GGUF key/type/value
entries whose keys begin `tokenizer.`, in file order, including the template.
It excludes the header and tensors. This additional host identity does not replace
the full model digest or the existing native parity report. The tokenizer is
`gpt2`, pre-tokenizer `smollm`, vocabulary size 49,152; declared BOS is 1, EOS is 2,
and `add_bos_token=false`. Tokens 0/1/2 are `<|endoftext|>`/`<|im_start|>`/
`<|im_end|>`, all declared type 3. These metadata declarations are not a native
`isEog` observation.

The preserved `56a46f5` answer rows were recounted: 12 OK, with **11 LENGTH stops
at four generated tokens and one application-owned abstention with no native
generation**. There is no observed EOS in that run. The recorded exact benign
token sequences and hostile control isolation establish structure only; they
do not establish current Qwen behavior, answer quality, PSS or thermal performance.

The Qwen Q3 smoke manifest records 1,590,475,744 bytes and SHA-256
`2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12`.
That is a historical manifest identity, not a freshly verified local full file or
the owner's currently selected artifact. No Qwen or Gemma GGUF was found in the
targeted repository asset/model paths; no claim is made about unsearched storage.
Qwen Q4 and both Gemma rows still have `TBD` size/hash, and `models/MANIFEST.md`
does not exist. Source revision and artifact-specific license verification remain
required; placeholder manifest labels are not independent license evidence.

## Repeatable host qualification

`tools/eval/qualify_model_artifact.py` reads a local artifact only. It verifies its
complete expected SHA-256 and optional size, boundedly reads metadata, and reports
architecture, tokenizer/template identities, declared token IDs and candidate
control spellings/types. It rejects duplicate keys, invalid declared IDs, mismatched
token/type arrays, over-limit or truncated metadata, and files changed during the
read. It emits no report on failure. A successful report explicitly labels native
load, native render parity, native EOG, generation stops and quality as unmeasured.
It is not a structural/tensor validator or an upstream-license verifier.

```sh
python3 -B tools/eval/qualify_model_artifact.py \
  --model /absolute/path/to/public.gguf \
  --expected-sha256 FULL_VERIFIED_SHA256 --expected-size VERIFIED_BYTE_COUNT \
  --control-token '<artifact-specific-control>'
```

Retain stdout under a fresh worktree-local run directory after replacing all
placeholders with actual reviewed identity values. Native instrumentation still
needs to observe that each intended control spelling maps to the expected token
and that intended end tokens satisfy `LlamaNative.isEog`.

## Requests for the coordinator

1. **Artifact qualification (`skein-bxk`, `.28`, `.30`).** Obtain or identify public
   workstation copies in this order: current Q3 control, planned abliterated Q4,
   original unmodified instruction-tuned Qwen baseline, then Gemma E2B/E4B if
   compatible. Record full SHA-256/BLAKE3, bytes, immutable upstream/conversion
   revision, source/license URLs, lineage, quantization and companion identities.
   Use the host qualifier above and retain its JSON. A Q3/Q4 comparison is only a
   quantization comparison when weights, tokenizer, template and conversion
   revision match; otherwise explicitly report those confounders. Acquiring an
   evaluation candidate does not change the accepted default-model policy.
2. **Native compatibility (`.28`).** On the coordinator-released exact source head,
   after inspecting actual ordinary instrumentation XML, use the opt-in
   `SyntheticTemplateParityTest` for verified Qwen and retain its complete JSON
   plus real XML. Request numeric native EOG observations for declared EOS/EOT
   and Qwen 151643/151645 only if those IDs are valid in the actual vocabulary;
   preserve observed classifications rather than assume IDs from the model name.
   Record stop reasons from isolated generation with `stop=[]` before considering
   any literal stop fallback. Do not mask a failure by adding stop strings.
3. **Gemma compatibility prerequisite (`.28`, `.30`).** The current parity test
   requires `<|im_start|>` to be one token, so it cannot qualify a non-ChatML
   artifact as written. Add an artifact-specific control-spelling input with the
   same single-token and literal-content isolation assertions before requesting
   Gemma parity. Pinned `llama-chat.cpp` detects `<start_of_turn>` using its legacy
   Gemma renderer, which trims contents and merges system text. Production
   `ChatTemplating` correctly refuses unprovable/mutated contents. Architecture
   support (`gemma4` is present) does not establish actual Gemma 4 template or
   thinking-mode compatibility. Compare the artifact's exact template rendering,
   all four existing benign cases, its genuine hostile control spellings, and
   native EOG before any model-quality claim. Preserve fail-closed behavior.
4. **Answer evaluation (`.30`).** Use `answer_eval.py validate/export` and
   `prepare_android_benchmark.py` as documented in
   [ANDROID_SYNTHETIC.md](../../tools/eval/ANDROID_SYNTHETIC.md). Begin with all
   12 development cases, seed 17, fixed 4096 context, four threads and the existing
   bounded 256-token greedy configuration with `stop=[]`; its 180-second default
   case deadline is a recorded limit, not a throughput promise. If the development
   run cannot complete, record failures and define a new explicitly versioned
   profile before comparisons. Freeze the comparison configuration and reserved
   fixture identity, then run all 80 reserved cases at seeds 17, 41 and 73 for each
   eligible artifact. Retain every attempted row, native stop reason, source
   finalization status, complete hashes, manifests and actual XML/JSON. Grade
   successful answers independently using the existing human-review schema and
   report all failures in the denominator. Supplied evidence does not test live
   retrieval. No four-token result substitutes for this request.
5. **Memory/thermal and formal M0 (`skein-7s1`, `skein-9cg`, `skein-5hr`).** Remain
   pending while Fold is held. Before release, coordinate the already documented
   content-free case timing and PID-specific PSS/thermal sampler. The M0
   orchestrator currently leaves `ttft_ms` and `peak_rss_mb` null; it also sends
   `--context` to `llama-bench -p` as prompt count and records that requested value
   as `context_length`, without independently measuring allocation. Resolve those
   measurement gaps before claiming TTFT, peak memory or actual context capacity.
   Both Gemma manifest entries currently lack `device_path` and have
   `formal_m0_candidate=false`; formal Gemma runs require a reviewed qualified
   manifest, not bypassing the guard. Retain original frozen baseline files and
   record all failure cells without silent retries.

## CPU/Vulkan decisions and acceptance gates

The coordinator's issue snapshot still leaves `.26` and `.27` open. The historical
September 23 decision retained Vulkan and raised the native-library budget; `.27`
explicitly reopened the Vulkan choice after the isolated process's GPU denial.
There is no recorded newer approval choosing an alternative in this snapshot.
Current code retains `GGML_NATIVE=OFF`, the NDK `armv8-a` floor, static backends,
arm64 Vulkan compilation and the already completed Debug `-O2` fix. Runtime
`InferenceConfig.gpuLayers` deliberately remains zero. The existing backend report
re-derives device selection and counts from load parameters; do not describe it
as an independent GPU-offload measurement.

For `.26`, request an explicit supported-device compatibility decision and a paired
isolated-APK baseline before changing the CPU floor. The recorded proposal is
`armv8.2-a+dotprod+fp16`; i8mm/runtime dispatch is a separate decision. Release
bytes, two-cold-build reproducibility and documented hashes would need renewed
verification. For `.27`, request an explicit choice between retaining compiled
Vulkan with honest CPU reporting and a CPU-only build/default. Neither option
permits weakening isolation. Termux Vulkan results cannot become APK promises.

| Gate | Evidence still required; remains open |
|---|---|
| `skein-bxk` | Complete acquired artifact/companion inventory, immutable source revisions, verified digests/sizes/licenses, real draft manifests and NOTICE linkage. Tiny CI model alone is insufficient. |
| `skein-7s1` | Both default models through real isolated APK import/load/256-token stream/cancel/swap, actual process UID and file-backed memory observations, load/TTFT/decode/PSS/thermal metrics, no app ANR, measured comparison or filed deviations. Preserve completed FD-loading regressions; do not restart that implementation. |
| `skein-9cg` | Qualified Q4 candidates, CPU/Vulkan formal cells with at least three repeats and cooldown, initial 4K/8K/16K coverage, retained failure cells, measured TTFT/memory/context evidence and reproducible binary provenance. Extend 24K/32K only after the first sweep. CLI and isolated APK remain separately labeled. |
| `skein-5hr` | Artifact-cited authoritative decisions and human approval, including inference/backend settings, embedder path, thermal policy and other existing acceptance rows. Neither `docs/MEASUREMENTS.md` nor a backend decision for production embeddings exists at this baseline. |

The newer retrieval evidence still reports full hybrid **INELIGIBLE**, no production
embedder and unresolved reserved relevance failures. This work changes no gold
labels, thresholds, retrieval policy, default models, CPU compatibility, Vulkan
policy or process isolation.
