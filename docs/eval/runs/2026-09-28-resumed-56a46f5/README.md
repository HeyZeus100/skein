# Resumed emulator evidence at 56a46f5

All three runtime workflows below ran the complete source revision
`56a46f5eedf217685528072bbf53a04f94e85aa5`. Later screenshot fixes and documentation
commits do not change the source scope of these results. The physical Fold was
not accessed, installed, or exercised; its verification hold remains in force.

| Lane | GitHub run | Actual evidence |
|---|---|---|
| Ordinary emulator | [36502596076](https://github.com/HeyZeus100/skein/actions/runs/36502596076) | App 37 pass; vault 191 pass and one opt-in assumption recorded as an XML failure; native 36 pass. |
| Tiny structural smoke | [36504291918](https://github.com/HeyZeus100/skein/actions/runs/36504291918) | 12 unique case/seed rows: 12 OK, zero timeout/OOM/error; all five native predicates pass. |
| Real retrieval diagnostic | [36505374600](https://github.com/HeyZeus100/skein/actions/runs/36505374600) | One executed instrumentation testcase passes; all three measured ranking gates fail; full hybrid is INELIGIBLE. |

## Ordinary emulator

The three files in `ordinary/*.xml` are unmodified actual reports, containing
265 unique cases: 264 passing cases, one `<failure>`, zero `<skipped>` elements.
The failure is
`app.skein.core.vault.eval.RealRetrievalEvaluationTest#evaluate_real_ingest_and_retrieval`,
with `AssumptionViolatedException: opt-in retrieval evaluation`. That diagnostic
was not executed in this lane. Gradle and the workflow nevertheless concluded
success; the XML failure is retained, not converted to a passing or skipped row.

`ordinary/targeted-cases.json` records 31 inspected cases covering migration 011,
wrong keys, native context overflow/cancellation/BUSY/lock, post-COMMIT
acknowledgment, drafts, citation revision retention, quiescence, kind lookup,
service process death/rebind, tokenizer merges and control isolation. Every
listed case passed. The full ImportService contract is a host test; this device
run does not establish the full import journey.

## Tiny structural smoke

`synthetic/` preserves the original answer rows, manifest, native ID arrays,
smoke summary, inputs, configuration, metadata and instrumentation output. The
separate `independent-review.json` records host checks against those raw files.
All 12 rows have `used_sources_status: finalized_prompt`; 11 rows generated
four tokens and one used the application's missing-evidence abstention.
No failed or missing row was dropped or retried.

Four benign native cases have exactly equal actual/reference arrays (16, 10,
30 and 23 tokens). The Unicode-whitespace case is now exactly 30 tokens. The
fifth case intentionally compares safe tokenization with an unsafe reference:
the safe array contains two ChatML start-control IDs, while the unsafe reference
contains three. This proves the literal user marker did not add a control token;
equality to the unsafe array would be a failure.

The app and app-test manifest digests match the built APK digests; the native
test's installed-byte readback digest also matches its built APK. Build, llama
and tokenizer-overlay source identities remain declared host provenance, not
independent attestation of source embedded in an APK.

The `tiny-structural-v2` profile is context 1024, two threads, four output tokens,
seed 17 and a 60-second case deadline. It differs from the retained earlier
4096-context/four-thread/64-output-token workload. These results establish no
comparable speed improvement, factuality score, Qwen capability, or hybrid
retrieval quality. Memory and thermal measurements remain unavailable.

## Real retrieval diagnostic

`retrieval/retrieval.json`, `runner-summary.json` and `instrumentation.xml` are
unmodified output. There are 76 unique queries per mode, three measured
repetitions per query after one warmup: 228 query/mode rows and 684 measured
repetitions. The original corpus and gold hashes are retained; all labels and
recall/nDCG thresholds remain unchanged. The installed test APK digest equals
the prebuilt digest. Host review checked completeness, repeated result arrays,
DCG and summary aggregation; the native evaluator supplies the anchor oracle.

| Mode | Recall@8 | nDCG@8 | Ranking gate | Absence queries rejected | Answerable queries falsely rejected |
|---|---:|---:|---|---:|---:|
| Lexical only | 0.616667 | 0.612120 | FAIL | 0/16 | 2/60 |
| Graph only | 0.000000 | 0.000000 | FAIL | 16/16 | 60/60 |
| Default lexical + graph | 0.600000 | 0.601604 | FAIL | 0/16 | 2/60 |

Recall must reach 0.75 and nDCG must reach 0.60. All three modes fail the joint
gate. Graph-only returns no results for any query despite 12 materialized gold
links. Its absence rejection rate is therefore not useful rejection accuracy.
Lexical/default return evidence for every absence query, exposing the remaining
weak-evidence rejection gap. Scope, provenance, anchor and nondeterminism
violation counts are all zero in this fixture.

The report retains 508 identical lexical-index probe warnings suggesting the
FTS triggers may be broken. Those warnings are observations, not a confirmed
root cause. The diagnostic retains 850 chunks, 800 eligible evidence chunks,
50 excluded CHAT chunks, and zero vectors. No embedder was installed, so the
full hybrid gate remains **INELIGIBLE**. A successful workflow here establishes
harness integrity and preservation of measured failures, not retrieval quality.

## Preservation

`SHA256SUMS` covers every file in this directory except itself. Raw reports were
copied byte-for-byte from the workflow artifacts; summaries and independent
reviews are explicitly separate. Full build/device logs remain in the linked
workflow artifacts and the runner worktree's `build/agent-logs/`.

To verify the bundle, run `shasum -a 256 -c SHA256SUMS` from this directory.
Earlier failed baseline artifacts are unchanged.
