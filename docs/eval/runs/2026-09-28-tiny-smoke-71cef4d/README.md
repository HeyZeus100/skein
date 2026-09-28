# First real tiny-model smoke: failed

Unmodified synthetic artifacts from GitHub Actions run
[36384222843](https://github.com/HeyZeus100/skein/actions/runs/36384222843),
build `71cef4d5f562130b15f3270b1c93a3bb49ceba9c`.

- `answers.jsonl`: all 12 development rows, including 10 timeouts. The two OK
  statuses describe execution, not factual correctness; one is app abstention.
- `run_manifest.json`: model/template/APK hashes and actual runtime configuration.
- `native-parity.json`: four passing native comparisons and the unchanged
  Unicode-whitespace mismatch, including both token sequences.
- `smoke_summary.json`: failure status and complete row accounting.

No user-vault content is present. This tiny model and emulator profile do not
represent the owner's Qwen model on the Fold. No factuality or hybrid-retrieval
gate is established by this run. Timeouts remain failures in any denominator.

The first harness version fills `used_sources` from the finalized pipeline
outcome. A timeout's empty list therefore means that finalization metadata was
unavailable, not that no source content was passed to the model. Null generation
or memory fields are unmeasured. These original artifacts are retained as-is;
later improvements must not rewrite the failed baseline.
