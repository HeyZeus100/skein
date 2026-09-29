# Ordinary instrumentation evidence — run 36551469886

Run: https://github.com/HeyZeus100/skein/actions/runs/36551469886 (attempt 1).
Source: `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`.

Actual XML acceptance passed: **281 tests, 281 passed, zero failures, errors or skips**.
The app contributes 38 cases, core/vault 204, and inference-service 39.
Every job and step succeeded. There are no missing or duplicate identities.
All 19 native loader failures from run 36546817687 passed on this run, as did
all six critical cancellation and lock-lifecycle cases. The complete 281-case
identity multiset matches the prior run; instrumentation test source was unchanged.

## Retained evidence

- `connected-test-reports-11025557649.zip` is the unchanged original GitHub artifact,
  including all reports, XML, logcat and ancillary files (356 entries).
  Its 1,200,508 bytes and SHA-256
  `edb5dde75920e189897f2fb75c9c92cb0841d6cf3d7bd8281212acce3863b4ef`
  match `artifacts-final.json` and GitHub's API digest.
- `raw/` extracts the source manifest and every one of its four recorded files:
  the three module XML files and the runner's instrumentation review. All four
  sizes and SHA-256 hashes were independently verified against the manifest.
- `evidence-review.json` records independent testcase counts, all six critical
  outcomes, all 19 repaired outcomes, step conclusions and integrity checks.
- `identity-comparison.json` contains the exact prior case identities and the
  current multiset comparison. `expected-case-identities.json` pins the 19 prior
  failures and six critical cases to the original failed evidence.
- `all-actual-testcases.json.gz` contains every current testcase identity and outcome.
- `status-final.json` and `artifacts-final.json` preserve GitHub metadata.
- `run-36551469886-attempt-1.log.gz` preserves the complete workflow log.
- `source/`, `target-test-source.json.gz` and `native-repair-at-source.diff`
  retain the exact-source workflow, manifest/reviewer scripts, target tests and repair.
- `original-retained-file-hashes.json.gz` is the original 391-file evidence
  inventory. Its paths refer to the original evidence tree; the original artifact
  ZIP retains all artifact members, while this portable directory extracts only
  the manifest-covered records. Early polling/provisional review files remain
  preserved in the original tree.
- `portable-copy-verification.json` verifies each copied file or losslessly
  decompressed gzip member against its original bytes.
- `SHA256SUMS` covers every file in this portable directory except itself.

## Verification and limits

Run `shasum -a 256 -c SHA256SUMS` from this directory. Gzip files decompress with
standard `gzip -dc`; timestamps were fixed to zero for deterministic compression.
Original evidence remains untouched in the worker's
`build/agent-logs/remote-36551469886/` directory.

APK hashes in the source manifest are host-declared build outputs. APK bytes
are not included here, and installed bytes were not independently attested.
This evidence satisfies the ordinary API 35 x86_64 instrumentation gate; it does
not establish foldable/physical-device, retrieval-quality or embedding acceptance.

Root publication additionally compresses `native-repair-at-source.diff` losslessly. `ORIGINAL-SHA256SUMS` preserves the initial portable index; the current index includes the gzip copy. Source worker originals remain unchanged.
