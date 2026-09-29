# Corrected preliminary retrieval evidence at 890ad71

[Run 36512873399](https://github.com/HeyZeus100/skein/actions/runs/36512873399)
ran source `890ad71298fcf53488ed6b4860ecdf3a81df5eb1`. This release contains
graph discovery, row-specific FTS checks, opt-in lane separation, raw relevance
signals and hardened collection. It has **no evidence-rejection gate**. The
reserved validation helper is dormant and was not executed. Fold remains HOLD.

The actual XML has one executed passing testcase, zero failures/errors/skips.
Collection succeeded on the first attempt without retries. The prebuilt,
installed and post-instrumentation local APK digests all equal
`14542cc27c621c2065503ea9ebfb547868a82aef065c4520f0dfc8b2ced76b85`.
The complete 3,972,722-byte report's host and device SHA-256 both equal
`23985b1f67125be83eda0ca2fe0be77d1c34d75a2b9033e175848c0199403d0c`.
Source revision remains host-declared rather than embedded APK attestation.

| Mode | Recall@8 | nDCG@8 | Ranking gate | Absence rejected | False rejection |
|---|---:|---:|---|---:|---:|
| Lexical only | 0.616667 | 0.612120 | FAIL | 0/16 | 2/60 |
| Graph only | 0.200000 | 0.141962 | FAIL | 16/16 | 48/60 |
| Default lexical + graph | 0.800000 | 0.717357 | PASS | 0/16 | 2/60 |

Graph-only now covers all 12 graph-category answer spans, with category nDCG
0.709810. Its overall recall reflects the other 48 answerable queries. The
unchanged overall ranking gates are recall >= 0.75 and nDCG >= 0.60. Default
passes those initial targets, while absence rejection remains unimplemented at
this source. All 228 query/mode rows and 684 timed repetitions are retained;
independent review checked exact query/category sets, complete repeated result
arrays, DCG, overall/category aggregation, rejection counts and latency
percentiles. Scope, provenance, anchor and determinism violations are zero.
Full hybrid remains **INELIGIBLE** without a production embedder.

Production ingest reports zero warnings. The diagnostic audit executes at the
old probe's observation point and records 850 probes: 508 global top-50 misses,
all 508 with a positive row-specific MATCH, zero misses without a row match and
zero ranked hits without a row match. This reproduces rank crowding in the
current fixture; it checks sampled legacy terms, not full posting integrity.
The original 508 warnings remain unchanged in the historical baseline. Audit
queries add ingest work, so ingest latency is not comparable to an unaudited run.

`calibration.json` is the coordinator's unchanged development-only sweep over
this complete report. It selected the lowest tested coarse coverage cutoff,
0.5, rejecting all 16 absence queries without losing default recall/nDCG in
that sweep. It projects 12/60 false rejections, all semantic cases. This is
calibration, not an executed production-gate result. The cutoff and algorithm
were frozen before reserved validation; later production and reserved results
must be reported separately without retuning against validation outcomes.

Raw JSON, XML, runner summary, Gradle output and command collection metadata were
copied unchanged from CRC-verified ZIP entry reads. The successful attempt's
`report.stdout` duplicates `retrieval.json` and is stored only once here; no
failed attempt exists in this run. Full artifact downloads/logs remain in the
runner worktree under `build/agent-logs/corrected-preliminary-retrieval-36512873399/`.
The earlier failed run remains in its separate 5cdd2f1 bundle.

`SHA256SUMS` indexes every file except itself. Original raw whitespace is retained.
Verify with `shasum -a 256 -c SHA256SUMS` from this directory.
