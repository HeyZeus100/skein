# Retrieval repair execution

Graph discovery now covers the twelve development graph cases, and all 508
reproduced legacy FTS probe misses have healthy row postings. The default
retriever reaches recall@8 **0.80** and rejects all sixteen development absence
queries. **Reserved rejection validation still fails**: the frozen policy loses
one answerable paraphrase and accepts one related source without the requested
fact. `skein-gg11.32` remains open; no threshold was retuned after validation.

The owner resumed the pause documented at `f8d7c81`. The coordinator read the
handoff and referenced execution evidence, ran `bd prime`, and assigned isolated
graph, FTS and instrumentation/sole-runner worktrees. This report records the new
batch; the inactive pause checkpoint and original failed evidence remain intact.
**Fold remains HOLD.** No physical-device read, SSH, install or generation was
performed. Owner files, old stashes and recovery worktrees were preserved.

## Implemented repairs

- Graph discovery uses bounded multiword title-prefix searches with word
  boundaries, retains ambiguity within its cap and rejects overflow instead of
  choosing arbitrary seeds. Exact single-word behavior, bounded traversal,
  eligibility and Space filtering remain. The real-vault regression covers an
  ordinary query phrase, a longer source title and its linked answer.
- Ingest checks each newly written row with a row-specific FTS `MATCH`, independent
  of its global BM25 rank. Real SQLite controls cover a healthy posting below the
  top 50, an actually missing insert trigger, a missing later posting and Unicode
  token boundaries. Unicode-only text has no usable ASCII probe and remains
  unverified by this check; it does not validate every tokenizer or the entire
  index. `skein-3q32` tracks Unicode query/probe alignment with the real tokenizer.
- Ordinary APKs exclude the retrieval diagnostic at build time. The dedicated
  source set requires explicit build and runtime opt-in. Ordinary XML review
  rejects failures, errors, skips, duplicate cases, missing modules and an
  unexpected diagnostic class without rewriting the XML.
- Retrieval retains original per-source scores through normalization and ranking.
  The synthetic report records these scores and source text for development
  analysis; production logging does not expose query or source content.
- Collection performs at most three recorded read-only attempts after one
  instrumentation run. It requires complete JSON, matching host/device report
  hashes and equal prebuilt, installed and post-run APK hashes. Every failed
  attempt survives; an observed APK mismatch is terminal.

## First repair runtime evidence

The [source `5cdd2f1` bundle](../eval/runs/2026-09-28-retrieval-repair-5cdd2f1/README.md)
retains original artifacts and an independently verified SHA-256 index.

Ordinary run `36510513750` has **270 passing cases** in actual XML: app 37,
vault 197 and inference-service 36, with zero failures, errors or skips. The
diagnostic class is absent. All six targeted graph/FTS contracts passed. CI,
the actual screenshot job and reproducibility jobs also passed at this source;
the conditional SQLCipher source-verification job was skipped.

Preliminary retrieval run `36510517798` failed artifact acceptance despite one
executed passing XML testcase and Gradle exit zero. Its uploaded report is
truncated at 30,208 bytes, and installed-APK hash collection failed during an
observed adb disconnect. No actual hash mismatch was established. The original
invalid JSON, XML, summary and logs are retained unchanged. This failed report
supplies no usable retrieval metrics or calibration evidence.

The corrected ungated run `36512873399` at exact `890ad71` has one actual XML
pass and a complete JSON report matching the device hash. Collection succeeded
on its first attempt, and all three APK hashes match. Its results are:

| Mode | Recall@8 | nDCG@8 | Absence rejected | False rejection | p50 / p95 ms |
|---|---:|---:|---:|---:|---:|
| Lexical only | 0.616667 | 0.612120 | 0/16 | 2/60 | 4.154 / 11.195 |
| Graph only | 0.200000 | 0.141962 | 16/16 | 48/60 | 0.369 / 1.524 |
| Default lexical + graph | 0.800000 | 0.717357 | 0/16 | 2/60 | 4.770 / 14.963 |

Default ranking passes the unchanged development targets. Graph-only covers all
12 graph spans; its other 48 answerable cases still fail. Scope, provenance,
anchor and determinism violations are zero. Lexical/default still accept all
absence queries before the relevance gate.

The audit reproduces 508 old top-50 misses and finds a healthy row posting for
all 508, across 850 probes. No production ingest warnings remain. These sampled
terms establish rank-cap false warnings in this rerun; they do not prove every
posting, tokenizer or historical index state. Audit queries add ingest overhead.

## Frozen policy decision

The coordinator selected `lexical-query-coverage-v1` at **0.5** using only the
complete development report and froze it before viewing or executing reserved
cases. This is the lowest tested cutoff rejecting all 16 absence cases without
reducing default recall (48/60) or nDCG (0.717357). It increases answerable false
rejection from 2/60 to 12/60, all semantic cases already missed by the default.
Lexical-only loses one incidentally retrieved semantic answer (37 to 36/60).
The full sweep and raw source scores retain that tradeoff. Raw BM25 cutoff 20
would reject all absence cases but lose one default answer (48 to 47/60).

The rule preserves rankings and full chunks when any chunk covers half of the
distinct content terms; it rejects the whole list otherwise. It also rechecks
the actual post-budget sources. This is lexical support, not fact verification.
Vector recall bypasses it pending real semantic calibration. The broader
relevance, follow-up, compact-selection and embedding acceptance gates stay open.

## Final measured policy and reserved validation

Ordinary run [36514921869](https://github.com/HeyZeus100/skein/actions/runs/36514921869)
at the same final source has **270 actual XML passes**: app 37, vault 197 and
inference-service 36, with zero failures/errors/skips. The opt-in diagnostic
class is absent. Its complete testcase inventory matches the earlier 5cdd2f1
repair run, including all six targeted graph/FTS contracts.

Dedicated run [36514924095](https://github.com/HeyZeus100/skein/actions/runs/36514924095)
executed exact source `7601a20f7fe8a63109c21d1a69c0bbbc2630bb14`. Actual XML
contains one executed passing testcase (66.154 seconds), no failures/errors/skips.
The complete 3,186,147-byte report has SHA-256
`2435b8b11d2456524da2b8e63a75a004bcdb2f6585e73fc01b8c66fb084f2caa`, matching
the device file. Collection succeeded on its first attempt without errors.
Prebuilt, installed and post-run test APK digests all equal
`30755daab8310307799ede117ca4e976a34d9efa1b5897f26a032b62623e0a60`.
Source revision remains host-declared, not independently embedded APK attestation.

| Final development mode | Recall@8 | nDCG@8 | Ranking gate | Absence rejected | False rejection | p50 / p95 ms |
|---|---:|---:|---|---:|---:|---:|
| Lexical only | 0.600000 | 0.601604 | FAIL | 16/16 | 12/60 | 8.049 / 11.979 |
| Graph only | 0.200000 | 0.141962 | FAIL | 16/16 | 48/60 | 0.823 / 3.535 |
| Default lexical + graph | 0.800000 | 0.717357 | PASS | 16/16 | 12/60 | 8.348 / 12.151 |

All 228 development query/mode rows and 684 timed samples match the frozen
calibration: original ordered results or a whole-list rejection, with no reranking.
The final FTS audit again reports 508/508 legacy misses with row matches and zero
new ingest warnings. Scope, generated-source provenance, anchor and determinism
violations are zero. Timings describe these emulator runs; they are not a paired
performance experiment or Fold measurements.

The separate six-document, twelve-query reserved fixture remained byte-identical
to its pre-calibration freeze. Both configurations have three measured repetitions
per query and a warm-up, real encrypted ingest, no vectors and the same default
ranking and source/Space filters. Only the evidence gate differs.

| Reserved configuration | Answer spans covered | Absence rejected | False rejection | Ranking gate | Validation gate | p50 / p95 ms |
|---|---:|---:|---:|---|---|---:|
| Frozen production policy | 5/6 | 5/6 | 1/6 | PASS | **FAIL** | 4.297 / 5.883 |
| Ungated control | 6/6 | 0/6 | 0/6 | PASS | **FAIL** | 3.473 / 6.049 |

`reserved-answerable-05` asks where telescope eyepieces are kept for transport.
The control retrieves the answering source, but its maximum query coverage is
0.4 and the policy rejects it. `reserved-related-only-01` asks for kiln electricity
cost per firing. The related source reaches coverage 0.5 but contains no cost,
so the policy wrongly accepts it. No labels, source text, stopwords or threshold
were changed after observing these failures. The public reserved set cannot now
be used for tuning and still be described as fresh validation.

The separate [source-level review](../eval/runs/2026-09-28-retrieval-repair-7601a20/retrieval/final-retrieval-review-7601a20.json)
and retained [review script](../eval/runs/2026-09-28-retrieval-repair-7601a20/retrieval/review_final_retrieval.py)
recomputed all 324 reserved returned-source occurrences against
the frozen documents: titles, complete text, UTF-8 byte spans, BLAKE3 revision
hashes, fingerprints, grades, DCG/nDCG and aggregates. Both actual configurations
match the frozen policy/control behavior. This establishes artifact consistency,
not successful rejection quality or model factuality. Both `FAIL` statuses remain
in the raw report and runner summary despite successful instrumentation.

At the same source, [CI 36514897657](https://github.com/HeyZeus100/skein/actions/runs/36514897657)
passed its actual lint/unit/guard and Foss assembly job. The actual
[screenshot job 36514897715](https://github.com/HeyZeus100/skein/actions/runs/36514897715)
passed all eight screenshot-verification tasks. [Reproducibility 36514897631](https://github.com/HeyZeus100/skein/actions/runs/36514897631)
passed both release builds, their comparison, native cold-build equality and
toolchain/self-tests. Its explicitly tag-only SQLCipher source-verification job
was skipped on this main-branch push; it is not counted as a pass.

The [final raw bundle](../eval/runs/2026-09-28-retrieval-repair-7601a20/README.md)
retains original XML/JSON, collection/hash metadata and the separate reviews.
The [ungated preliminary and calibration bundle](../eval/runs/2026-09-28-retrieval-repair-890ad71/README.md)
and the failed collection bundle remain distinct, preserving each result's
source revision and acceptance boundary.

## Local verification

Integrated checks covered model, RAG, vault, shared testing/fakes, chat and app,
including each module's `check` and explicit `ktlintCheck`, plus both app and
vault AndroidTest Kotlin compilations (947 Gradle tasks, successful). Actual unit
XML contained no failures or errors. Existing exclusions remain visible: four
embedding contracts, one vision-import contract per vault flavor and twelve
chat screenshot matrix cases. They are not counted as passes. New post-budget
and cancellation regressions executed successfully. The host evaluation suite
passed 58 tests at the collection-hardening checkpoint and received independent
review. Final nested-report and fixed-ideal validation expanded that suite to
**68 passing tests**.
The final opt-in vault check, explicit ktlint, both AndroidTest compilations and
test APK assembly passed (214 Gradle tasks). The title-sensitive determinism
regression passed the shared testing module's check and explicit ktlint.

Gold labels and the recall@8 ≥ 0.75 / nDCG@8 ≥ 0.60 gates are unchanged.
Production embeddings are still unavailable, so full hybrid remains
**INELIGIBLE**. Development retrieval and public reserved regressions do not
establish model factuality, physical-device performance or a blind benchmark.

## Acceptance status and preservation

`skein-rw52` (graph discovery), `skein-vj5r` (row-specific FTS verification) and
`skein-u66y` (explicit instrumentation separation) are closed against the actual
regressions and runtime artifacts above.

`skein-gg11.32` remains in progress: reserved rejection fails, and semantic
retrieval, compact source selection, contradictory facts and follow-up resolution
are unfinished. `skein-9744` remains in progress for the production embedding
ablation/full-hybrid baseline and quality enforcement, plus its nightly/PR
integration requirements. The existing embedder chain is still
`skein-lbw` → `skein-079` → `skein-hwsa`; it was not duplicated. `skein-3q32`
tracks the remaining Unicode lexical-query/probe limitation. Qwen, broader answer
quality, lifecycle and physical-device gates remain open.

The runtime source is `7601a20`; later evidence/documentation commits do not
extend that measurement to different application code. The original baseline,
failed collection, gold labels, thresholds, owner directories, old stash and
recovery worktrees are preserved. No broad cleanup was performed. Beads updates
are local: no Dolt remote is configured, as recorded in the resume handoff.
