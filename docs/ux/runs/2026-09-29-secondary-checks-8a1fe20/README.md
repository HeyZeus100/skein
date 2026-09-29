# Remote evidence review at 8a1fe20b

Exact source: `8a1fe20bd451314596e2f5ea75766f3dc41a64ef`, epoch `1790694605`.
Read `final-run-review.json.gz` for exact jobs, API-verified artifact hashes and boundaries.

- CI 36588129585: 494 XML files, 5167 cases, 5079 pass, 88 skips, zero failures/errors. All 21 populated test tasks came FROM-CACHE; two other tasks had NO-SOURCE. This is not fresh unit execution.
- Screenshots 36588129731: actual eight test tasks execute with --no-build-cache. 145 XML files, 1321 cases, 80 skips, zero failure/error. All 552 individual Roborazzi results are unchanged; all 705 manifest file hashes match. Goldens source tree unchanged since 2e1dcbaf; job-level continue-on-error was not used to conceal a failed step.
- Reproducible 36588129373: two release APKs are identical byte for byte, 100673247 bytes, SHA256 e44c1de591d6d2c3018d158874d727601035418cf99f3515667f636c38e39e15; all 826 ZIP entries match names/order/metadata/bytes. Two cold native builds execute, their AArch64 libraries match byte for byte and match the embedded release library. Negative control did not run; SQLCipher source job was skipped.
- Scheduled retrieval 36584176288 at 321a51fd failed during emulator SDK archive installation before measurement. Failure summary/log only was reviewed. Retrieval and embedding quality gates remain open.
- All 44 local workspace run1 skip identities match prior CI by module, variant, suite and case. The copied run1 report still contains the preserved capture-helper failure; root separately verified the app rerun. This comparison does not overwrite or relabel that failure.

This source predates the new workspace UI. No workspace, fresh ordinary instrumentation, physical-device, installed-candidate, retrieval or embedding acceptance follows from these runs.

Original ZIPs and extracted binary artifacts remain in the owning worker's `build/agent-logs/secondary-followup-8a1fe20b`. This portable copy retains the raw unit/screenshot archives, metadata, reviews, source snapshots and losslessly compressed logs. APK/SO bytes and their large containing ZIPs are intentionally excluded. `RAW_SHA256SUMS` indexes every original file; `SHA256SUMS` indexes this copy. `compression-map.json` maps gzip bytes back to original hashes. All six downloaded ZIP digests matched the GitHub artifact API.

`case-identity-by-variant.json` is the authoritative identity comparison. Earlier `case-identity-comparison.json` groups variants together and reports expected cross-flavor repetition. The original reviewer attempt with an incorrect count assumption about NO-SOURCE tasks is retained as `audit-final-attempt1.py` and its error note; the corrected final audit distinguishes those statuses.
