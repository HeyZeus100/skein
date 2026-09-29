# Final retrieval repair evidence at 7601a20

This bundle records source `7601a20f7fe8a63109c21d1a69c0bbbc2630bb14` after the
coverage policy and its 0.5 threshold were frozen against the separate development
run at 890ad71. The reserved fixture, labels and ranking thresholds were unchanged.
No policy was tuned after these reserved results. Fold remains HOLD.

## Ordinary instrumentation and automatic workflows

[Ordinary run 36514921869](https://github.com/HeyZeus100/skein/actions/runs/36514921869)
has **270 actual passing testcases**: app 37, vault 197 and inference service 36.
There are zero XML failures, errors or skips, and the retrieval diagnostic class
is absent. The complete 270-case inventory matches the earlier 5cdd2f1 ordinary
run. All four new FTS acceptance cases, the row-specific replacement contract
and graph discovery case execute and pass; `ordinary/targeted-cases.json`
identifies them. The independently computed review matches the CI XML reviewer.
All retained ordinary XML/summary files match the complete artifact download.
The original download remains under
`build/agent-logs/final-ordinary-36514921869/artifacts/`; its 344 files are indexed
in `ordinary/full-download-file-index.json`.

Actual jobs at the same source SHA were inspected, including their completed
steps. [CI 36514897657](https://github.com/HeyZeus100/skein/actions/runs/36514897657)
passed ktlint, Android lint, unit tests, guards and foss debug assembly.
[Screenshots 36514897715](https://github.com/HeyZeus100/skein/actions/runs/36514897715)
passed all eight `verifyRoborazziDebug` tasks.
[Reproducibility 36514897631](https://github.com/HeyZeus100/skein/actions/runs/36514897631)
passed toolchain self-tests, native cold-build comparison, both unsigned release
APK builds and their comparison. The SQLCipher regeneration job is explicitly
tag-only and was **skipped** on this main push; it is not counted as executed.
The actual comparison reports all 826 entries and the entire 100,456,459-byte
APK identical, SHA-256
`0b479bc00258998ece7b49f5ed6160a4d74189da49a726b33c4ff453293e5057`.
Automatic workflow JSON, unmodified logs and an explicit job/step review are
retained separately.

## Dedicated retrieval result

[Run 36514924095](https://github.com/HeyZeus100/skein/actions/runs/36514924095)
completed with one actual passing instrumentation testcase and zero XML
failures/errors/skips. This is a successful measurement, not a claim that all
quality gates pass. Both reserved validation statuses remain **FAIL**, recorded
in the raw report and runner summary. Full hybrid remains **INELIGIBLE** without
a production embedder; this emulator run supplies no Qwen or Fold acceptance.

| Development mode | Recall@8 | nDCG@8 | Ranking gate | Absence rejected | False rejection |
|---|---:|---:|---|---:|---:|
| Lexical only | 0.600000 | 0.601604 | FAIL | 16/16 | 12/60 |
| Graph only | 0.200000 | 0.141962 | FAIL | 16/16 | 48/60 |
| Default lexical + graph | 0.800000 | 0.717357 | PASS | 16/16 | 12/60 |

Default development recall/nDCG match the ungated preliminary run; absence
rejection rises from 0/16 to 16/16, while false rejection rises from 2/60 to
12/60. Graph-only covers all 12 graph-category spans (category nDCG 0.709810).
All development scope, anchor, provenance, duplicate and determinism violations
are zero. The 228 query/mode rows and 684 timed samples are retained unchanged.

| Reserved mode | Evidence covered | Absence rejected | False rejection | Recall / nDCG | Validation |
|---|---:|---:|---:|---:|---|
| Production policy | 5/6 | 5/6 | 1/6 | 0.833333 / 0.833333 | FAIL |
| Ungated control | 6/6 | 0/6 | 0/6 | 1.000000 / 1.000000 | FAIL |

The production policy falsely rejects `reserved-answerable-05` and still
accepts `reserved-related-only-01`. Its aggregate ranking gates pass, but the
stricter rejection validation requires every positive span and every absence
case to succeed. That acceptance gate remains open. These are public reserved
validation cases, not a blind benchmark. Both modes have zero integrity and
determinism violations and retain three complete repeats per query.

Production ingest emits zero warnings. The diagnostic audit records 850 legacy
probes, 508 global top-50 misses, and positive row-specific MATCH for all 508
misses. There are zero legacy misses without a row match and zero legacy hits
without one. This audit checks sampled legacy terms, not full posting integrity;
its queries add ingest work. Historical warning evidence remains unchanged.

## Collection and independent review

Collection succeeded on its first attempt without retries or errors. Prebuilt,
installed and post-instrumentation local test APK hashes all equal
`30755daab8310307799ede117ca4e976a34d9efa1b5897f26a032b62623e0a60`.
The complete 3,186,147-byte JSON has host and device SHA-256
`2435b8b11d2456524da2b8e63a75a004bcdb2f6585e73fc01b8c66fb084f2caa`.
Source revision is host-declared clean-checkout provenance, not embedded APK
attestation. All retained range-read entries passed ZIP CRC and matched the
complete `gh run download` byte for byte.

The runner review checks exact fixture/query sets, complete deterministic result
arrays, metric/category aggregation, timing percentiles, zero integrity flags,
and report/APK linkage. The independent FTS review additionally checks all 324
reserved hit occurrences against the six source documents, titles, text, UTF-8
byte spans, BLAKE3 revisions, fingerprints, grades and metrics. It verifies all
228 development rows/684 samples against the preserved preliminary report and
frozen policy. Neither reviewer converts diagnostic FAIL into PASS.

Raw report, XML, runner summary, Gradle output and per-attempt collection files
are unchanged. The selected attempt's `report.stdout` equals `retrieval.json`
and is stored once in this bundle; there are no failed collection attempts.
The complete original artifact, including logcat and rendered test reports,
remains in the runner worktree under
`build/agent-logs/final-retrieval-36514924095/artifacts/`;
`retrieval/full-download-file-index.json` records every file's size and SHA-256.
Previous failed and preliminary evidence stays in its separate immutable bundles.

`SHA256SUMS` indexes all bundle files except itself. Verify with
`shasum -a 256 -c SHA256SUMS` from this directory. Raw trailing whitespace is
preserved intentionally.
