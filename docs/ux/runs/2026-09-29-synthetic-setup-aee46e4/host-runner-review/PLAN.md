# v4 physical answer runner: test-only repair

Final host preparation only. The initial reviewed v4 and its original 28-test log are preserved in the unchanged v4 directory and the v4-initial sibling snapshot. This final revision has 31 mocked tests in host-safety-tests-final.log.

Host preparation only. No new device execution is authorized by this directory. Original v1–v3 helpers, releases and attempts remain unchanged.

A fresh immutable release must explicitly attest production `source_sha` and `ordinary_source_sha` as `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`, current app APK `1f83b71c5fe60a6dc26fcac7a4c75b49fbf009b6e8c9d972b2176c18081c1323`, and a different `test_build_sha` for the new opt-in test APK. The release template is NOT_RELEASED. Fill new test artifact identity and reviewed checks; do not rewrite the former release or claim ordinary tests ran against the new harness source. The pinned scope-review JSON must approve only the three exact opt-in files listed in its template.

`test_only_update=true` is mandatory for this successor; legacy releases are rejected before any command. Only the answer installation policy is available: pull and verify current public app APK against the released current identity, then install-r only `app.skein.test`. Any app mismatch stops before installation. It neither reinstalls the production app nor touches native test APK in this phase. All APK certificate and package validation remains active.

`parity_reuse` requires explicit approval and pinned prior successful summary, report and release. It checks actual complete/JUnit/stop facts, old release authorization/source, prior released production app APK, native APK, llama/overlay pins, model size/SHA and template, all five raw token cases, and exact native EOG facts. Declared EOS 151645 and singleton controls im_end 151645, endoftext 151643 and </s> 128247 must all classify true, scope must be classification-only, and generated_stop_behavior must remain unmeasured. This permits the separately reviewed new test source while preserving the old native evidence scope. It proves no generated stopping or answer quality.

`preparation_helper_sha256` pins the unchanged canonical host preparer. Its existing CLI runs unchanged. The runner then inserts only `test_build_sha` and strict `host_model_identity` into the newly created config, preserving app `build_sha`; existing fields are never overwritten. Host identity evidence is the pinned complete public-model record with full SHA/size/BLAKE3 and successful provenance. The device repair must independently bind the host B3 to full device SHA and byte count. Runtime manifest verification requires the distinct test source, actual test APK digest, B3 provenance and host evidence digest.

`setup-progress.jsonl` is collected first after instrumentation returns, including timeout/test-failure paths, before the success gate. Failed collection retains partial bytes and a separate `.collection-error.json`; `setup-progress-review.json` reports valid row count and last allowed phase without treating progress as success. Malformed/truncated bytes remain intact and are flagged. The existing 900-second outer timeout, exact workload teardown, current v3 minimized process-state proof, exclusive fresh input/output staging and four-case profile remain unchanged. No automatic retries.

Host-only validation (omit --execute) and a later explicitly released answer phase both require:

```text
python3 physical_synthetic.py --release NEW_RELEASE.json --phase answers --parity-summary PRIOR_SUCCESSFUL_NATIVE_SUMMARY.json --run-id FRESH_RUN_ID --output FRESH_ABSOLUTE_OUTPUT
```

Adding `--execute` is a physical action and remains pending root plus external candidate review. All answer rows still require separate manual factual/citation review; `quality_assessed=false`.
