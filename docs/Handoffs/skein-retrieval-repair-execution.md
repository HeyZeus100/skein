# Retrieval repair execution

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

## Local verification

Integrated checks covered model, RAG, vault, shared testing/fakes, chat and app,
including each module's `check` and explicit `ktlintCheck`, plus both app and
vault AndroidTest Kotlin compilations (947 Gradle tasks, successful). Actual unit
XML contained no failures or errors. Existing exclusions remain visible: four
embedding contracts, one vision-import contract per vault flavor and twelve
chat screenshot matrix cases. They are not counted as passes. New post-budget
and cancellation regressions executed successfully. The collector's 58 host
tests also passed and received independent review.

Gold labels and the recall@8 ≥ 0.75 / nDCG@8 ≥ 0.60 gates are unchanged.
Production embeddings are still unavailable, so full hybrid remains
**INELIGIBLE**. Development retrieval and public reserved regressions do not
establish model factuality, physical-device performance or a blind benchmark.
