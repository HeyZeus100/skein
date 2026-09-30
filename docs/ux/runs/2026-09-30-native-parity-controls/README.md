# Native parity controls: bounded host harness evidence

`skein-gg11.30.1` adds an explicit, metadata-bound control contract to the
opt-in inference library test. Baseline is
`8f084343cf2b67395d2d707b33f6ee578d801c89`. This evidence qualifies the harness
implementation only. No model was acquired, loaded or executed, and no device
was accessed. No actual Gemma 4 E4B artifact is qualified by this work.

The host helper verifies full local artifact bytes, the exact embedded template,
raw tokenizer metadata and unique CONTROL token IDs before writing a new
manifest. It refuses unsupported identities, controls, duplicate spellings,
wrong full hashes/sizes and manifests larger than 16 KiB. The explicit Gemma 4
profile uses the controls documented in Google's
[prompt-formatting reference](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4).
Upstream names or declared hashes are not substituted for actual local bytes.
Synthetic GGUF metadata in tests contains no model tensors.

The opt-in Android fixture binds that manifest to its full verified staged model
and native metadata, checks exact control singleton IDs, and requires native EOG
classification for the explicit profile's turn-end and declared EOS controls.
It retains all four benign token parity cases and the literal isolation case.
The no-manifest ChatML inputs and diagnostic EOG behavior remain unchanged.
The fixture cannot configure production templates, runtime, default models or
stop policy. The tokenizer metadata digest is host provenance bound by the full
model hash, not an independent native token-table readback.

`source-inventory.json` freezes the exact nine changed source/documentation
files. `verification.json` was derived from all 32 actual JUnit XML files and
records 554 passes with zero failures/errors/skips, including six new methods
run under both variants. The verbose host log records 113 passing Python tests,
including seven new manifest preparation tests and the retained existing suites.
Both dev/foss opt-in AndroidTest Kotlin compilations executed successfully;
compilation is not instrumentation execution.

`raw-host-evidence.zip` contains the unmodified XML, logs, exit statuses,
source freeze and acquired/released build lease receipts. Its SHA256 is
`3512033f6a7c981db9af970657f9caf875e3847fe62cbed333c7921d5d5dc1ff`.
Initial AGP test-source accessor and formatting failures remain in the archive.
The initial shell rejected an unquoted glob before launching the first host
command; this is recorded separately in `verification.json`.

Actual artifact/runtime qualification belongs to `skein-gg11.30.2`; blinded
answer quality belongs to `.30.3`. Native template support, actual control/EOG
classification, generated stopping, integration and physical acceptance remain
unmeasured here. Fresh40 remains consumed regression evidence with its original
failures, labels and thresholds. Ordinary tiny-model smoke and existing runtime
tests are retained; none has been relabeled as E4B acceptance.
