# Retrieval repair execution

The continuation from `513d97a` repaired Unicode retrieval and passed all 277
ordinary instrumentation cases, but **relevance acceptance still fails**. On the
new independently frozen set, production covers 9/12 supported answers and
rejects only 6/12 absent-fact queries. The quality-enforced diagnostic correctly
failed while retaining complete evidence. Production remains v1; the
requested-value experiment was falsified on development data and stays disabled.

[The latest continuation](#continuation-from-513d97a-implementation-and-frozen-decision)
records exact source `8b8884b`, all runs, hashes and remaining work. Embedding
contract preparation and nightly/PR quality enforcement advanced; production
embeddings, full-hybrid, broader relevance and physical-device gates remain open.

The earlier repair measurements below are preserved historical evidence; their
source-specific results are not measurements of later code.

## Earlier repair measurements through 513d97a

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


## Continuation from 513d97a: implementation and frozen decision

The owner explicitly resumed the prior pause. Three isolated worktrees handled
production embedding preparation, Unicode lexical retrieval, and evaluation/sole
CI execution. The coordinator retained relevance policy, integration and all
Beads updates. Fold remains **HOLD**; no physical-device access, SSH, installation
or generation was performed. Owner `Untitled/` and `logos/`, the old stash and
all recovery worktrees are preserved.

The application/test source for the corrected runtime is
`8b8884b4b24a93f1ae72461840518ba2ebaaafb3`. Its predecessor
`bcd2b3264805b9559d01622af1d17abe3ae6fc9a` failed ordinary instrumentation;
that checkpoint remains separately preserved.

Unicode queries and ingest probes now use the actual linked SQLite `unicode61`
tokenizer through its FTS5 API. Literal quoting, final-token prefix behavior and
row-constrained MATCH remain. Native and query bounds are 128 terms, 512 UTF-8
bytes per term and 65,536 input bytes. Overlong tokens are skipped whole; empty
or overlong inputs remain unprobed. Duplicate terms consume the term cap. The
probe keeps the existing final-token prefix behavior, so a longer prefix match
can mask damage to an individual exact token. The real missing-row controls
remove postings while retaining the base chunk, including a damaged middle row
between healthy rows. This does not establish complete index integrity or
language-specific segmentation. The synchronous relevance gate's
normalization is separate from SQLite tokenization.

Independent native review found that SQLite UTF-16 binding strips a leading
BOM. The implementation therefore binds explicit standard UTF-8 bytes only for
derived chunk text and FTS query parameters, leaving generic repository bindings
unchanged. The reader accepts standard UTF-8 and CESU-8 without rewriting source
revision bytes. A host-JNI compatibility concern initially overgeneralized CESU-8
supplementary encoding to Android. Actual ordinary run 36527953704 exposed that
faulty test premise: its forced-CESU-8 title did not match an unchanged Android
JNI parameter. AOSP ART explicitly uses four-byte supplementary UTF-8 while
retaining modified-NUL behavior. The corrected control measures actual Android
JNI bytes, verifies exact/prefix lookup, and separately tests artificial CESU-8
read/byte preservation. It also tests the real generic-versus-explicit NUL
encoding boundary. This is a test-oracle correction, not a production-policy
change; the original failed XML is preserved.

The first run does **not** prove historical Android supplementary postings need
migration. **skein-5uu2** remains open to identify actual affected mixed-encoding
posting states before proposing bounded derived-row re-ingest. It must preserve
source revisions and distinguish artificial foreign-encoding fixtures from
reproduced production history. No automatic migration or global encoding change
is included.

The embedding lane advanced the existing `skein-lbw` → `skein-079` →
`skein-hwsa` chain with backend-neutral masked pooling, 256-dimensional
normalization/int8 preparation, complete document-batch validation, query-vector
validation and cancellation boundaries before index access. Malformed responses
retain pending status. Neither an approved `embedder_path` decision nor
`docs/MEASUREMENTS.md` exists; no ONNX/GGUF decision was invented. The service is
still a stub, both production factories lack a real embedder, and tokenizer
sharing, loaded lifecycle, query/document activation and re-indexing are pending.
The [readiness matrix](../design/EMBEDDING_READINESS.md) records exact interfaces
and dependencies. These three issues and `skein-5hr` remain open.

**Production relevance remains v1 at 0.5.** A requested-value prototype was
calibrated only on preserved ungated development data and explicitly authored
development controls. It preserved default recall/nDCG 0.800000/0.717357 and
16/16 absence rejection in replay, while allowing three typed-value paraphrases
in the new development controls at a 0.25 typed cutoff. A separate agent then
falsified the prototype with six development counterexamples: unrelated prices,
wrong value types, physical length, heading-separated evidence and alternative
duration wording. It was not promoted to production or patched to fit those
cases. The explicit experiment, all counterexamples and the rejected earlier
budget-unit replay are retained in the
[development decision bundle](../eval/runs/2026-09-28-retrieval-continuation-calibration/README.md).
Post-budget checks, cancellation, citations, original scores, provenance and
Space filtering retain their production behavior.

A new independently authored 12-document/24-query fixture was frozen in agent
commit `4423d1a` before calibration, with SHA-256
`4f79b2ddcf42dedd6b7f83c10402855f683fcebc3045d9e4668f6da2951759e8`.
The coordinator did not read it before the production/experimental decision was
frozen at `564243494f4ba1de73a4df1457d5a0dde128f6b0`. The run manifest pins
both policy source files, parameters and fixture hash. The author knew the
historical evidence but had not received this candidate or its calibration
result. This is synthetic independent validation, not a secret or statistically
representative benchmark. Original corpus, gold,
reserved fixture and 0.75/0.60 ranking thresholds remain byte-identical.
The three configurations—production, explicit experiment and ungated control—
are reported separately. Public reserved cases remain regression evidence;
future reruns of the newly measured fixture are also regressions.

Quality enforcement is separate from ordinary instrumentation and artifact
integrity. The opt-in lane now supports `--require-quality` and is called nightly
and on relevant PRs. It rejects unmet production development/rejection gates
after retaining complete XML/JSON and source-level verification. The
experimental candidate and control cannot substitute for production acceptance.
Full-hybrid enforcement remains separately ineligible without real embeddings.

Local verification passed 965 integrated Gradle tasks and, after the native
compatibility corrections, 901 affected vault/app tasks including explicit
ktlint, both app/vault instrumentation compilations and the opt-in test APK.
The host evaluation suite passed 84 tests. Reviewed actual local XML has no
failures/errors. Four pending real-embedder contracts, one vision-import case
per vault flavor and twelve chat screenshot cases remain skipped, not passed.
The final native syntax and exact-tokenizer ASan/UBSan checks passed. Emulator
and job-specific measurements are recorded separately below; local compilation
alone does not establish Android execution or retrieval quality.

The corrective test/comment change then passed both vault AndroidTest flavor
compilations and explicit ktlint in 67 local Gradle tasks. An earlier invocation
used a nonexistent `Play` flavor and failed task selection; the corrected
`Dev`/`Foss` invocation completed successfully. No production logic changed in
that correction. A separate read-only audit confirmed production v1 wiring,
manifest/source hashes and unchanged post-budget/cancellation/citation/Space
paths. The actual emulator outcome remains a separate acceptance requirement.

### Preserved failed ordinary checkpoint

[Ordinary 36527953704](https://github.com/HeyZeus100/skein/actions/runs/36527953704)
at `bcd2b32` executed 277 cases: 276 passed and the one ART-encoding fixture
failed at `IndexStoreImplAcceptanceTest.kt:327`. App 37/37 and inference-service
36/36 passed; vault passed 203/204. There were zero errors/skips and no diagnostic
testcase. No new validation measurement occurred at this checkpoint.
The [source-specific bundle](../eval/runs/2026-09-28-retrieval-continuation-bcd2b32/README.md)
retains all three original XML files, actual workflow logs/conclusions, independent
reviews and a hash index of all 351 downloaded artifact files. Its 17 retained
file hashes were independently verified. Actual automatic CI, screenshot and
reproducibility jobs passed at the same source; tag-only SQLCipher verification
was skipped. Those results do not override the ordinary runtime failure.

### Corrected-source automatic jobs

At exact source `8b8884b`, [CI 36530199120](https://github.com/HeyZeus100/skein/actions/runs/36530199120)
passed its actual lint/unit/guard and Foss assembly job.
[Screenshots 36530199083](https://github.com/HeyZeus100/skein/actions/runs/36530199083)
passed the actual screenshot job and all eight verification tasks.
[Reproducibility 36530199086](https://github.com/HeyZeus100/skein/actions/runs/36530199086)
passed toolchain/self-tests, native cold-build equality, both release builds and
the comparison of all 826 APK entries plus whole-file SHA-256. The tag-only
SQLCipher source-verification job was skipped, not passed. The coordinator
independently read the retained job JSON and logs, including exact head SHAs;
these results do not establish relevance quality or physical-device acceptance.

### Corrected ordinary runtime

[Ordinary 36530225153](https://github.com/HeyZeus100/skein/actions/runs/36530225153)
at exact `8b8884b` has **277 executed passing XML cases**: app 37, vault 204 and
inference-service 36, with zero failures, errors or skips. The coordinator
independently parsed all three original XML files. All seven new Unicode
contracts and the six preserved graph/FTS regressions executed; the opt-in
retrieval class is absent. A separate reviewer confirmed that the complete
277-case inventory matches
the failed predecessor exactly: no tests were removed. The corrected ART
byte/lookup/NUL negative controls passed without changing production logic.
The passing assertions establish their expected bytes; the XML does not
separately dump those hex values. **skein-3q32 is closed** against this scoped
acceptance evidence and its documented exclusions. The failed predecessor remains
separate evidence and is not relabeled.

### Frozen retrieval outcome and enforced failure

The sole [dedicated run 36531672149](https://github.com/HeyZeus100/skein/actions/runs/36531672149)
measured exact source `8b8884b` once with `require_quality=true`. Its original XML
contains one executed passing test, 65.080 seconds, zero failures/errors/skips;
Gradle exited zero. The workflow and actual retrieval job nevertheless **FAILED**
because the explicit relevance quality gate failed. The summary retains
`complete=true`, `quality_gate.status=FAIL` and the enforcement exception. This
is the intended distinction between successful execution/collection and quality.
No second measurement or calibration followed these outcomes.

The complete 4,685,276-byte JSON has SHA-256
`b51830389930206f991de92be217cc55bcd0371e9bebf34646bef570ab5d0745`,
matching the device report. Collection succeeded on its first attempt with no
errors. Prebuilt, installed and post-instrumentation APK digests all equal
`aaa895db11c95fb989d2c682fc14db6d38e1818978055201717b3aa906c803fc`.
The source revision remains host-declared, not independently embedded APK
attestation. Raw XML/JSON, all workflow conclusions and independent reviews are
retained in the [corrected source bundle](../eval/runs/2026-09-28-retrieval-continuation-8b8884b/README.md).

| Development mode | Recall@8 | nDCG@8 | Absence rejected | False rejection | Ranking gate | p50 / p95 ms |
|---|---:|---:|---:|---:|---|---:|
| Lexical only | 0.600000 | 0.601604 | 16/16 | 12/60 | FAIL | 11.745 / 16.095 |
| Graph only | 0.200000 | 0.141962 | 16/16 | 48/60 | FAIL | 0.838 / 3.816 |
| Default lexical + graph | 0.800000 | 0.717357 | 16/16 | 12/60 | PASS | 12.173 / 15.606 |

Development quality is unchanged. The FTS audit still finds healthy row matches
for all 508 reproduced legacy top-50 misses among 850 probes, with zero new
ingest warnings. There are zero vectors and 850 pending documents/chunks;
`ApproximateTokenizer` and `embedder=None` remain explicit. Full hybrid stays
**INELIGIBLE**. Timings describe this emulator run, not a paired speed experiment
or physical-device performance.

| Validation set / configuration | Answer spans covered | Absence rejected | False rejection | Recall@8 / nDCG@8 | Strict validation |
|---|---:|---:|---:|---:|---|
| Original reserved / production | 5/6 | 5/6 | 1/6 | 0.833333 / 0.833333 | **FAIL** |
| Original reserved / experiment | 5/6 | 6/6 | 1/6 | 0.833333 / 0.833333 | **FAIL** |
| Original reserved / ungated | 6/6 | 0/6 | 0/6 | 1.000000 / 1.000000 | **FAIL** |
| New frozen set / production | 9/12 | 6/12 | 3/12 | 0.750000 / 0.750000 | **FAIL** |
| New frozen set / experiment | 10/12 | 7/12 | 2/12 | 0.833333 / 0.833333 | **FAIL** |
| New frozen set / ungated | 12/12 | 0/12 | 0/12 | 1.000000 / 0.969244 | **FAIL** |

Every configuration passes the unchanged ranking thresholds, but none meets
the strict all-answers/all-absence validation requirement. Experimental/control
results do not substitute for production acceptance. The experiment remains
disabled as decided from development evidence before this measurement.

Production rejects new answerable paraphrases `03`, `05` and `08` (film
protective packaging, wet paper between absorbent layers, and choir sheet
music), although the ungated control covers their answers. It accepts related
sources for absent facts `13`, `14`, `16`, `17`, `19` and `20`: a manufacturer,
calendar date, boat capacity, humidity, inspection outcome and composer.
The experiment recovers `05` and rejects `16`, while still failing the other
listed cases. The original reserved paraphrase/cost failures also remain.
No labels, text, thresholds or policy were changed to fit these results.

The coordinator inspected the original XML, JSON and runner summary,
independently recounted the query aggregates and reran the retained report and
source checks. The source review recomputed titles, complete source bytes,
UTF-8 spans, BLAKE3 revisions, fingerprints and grades for all **1,449** returned
source occurrences: 414 original reserved and 1,035 newly frozen. Scope,
provenance, anchor, duplicate and determinism violations are zero. This verifies
artifact consistency and isolation, not fact-level relevance. The measured
quality gate correctly reports development ranking/absence PASS and both
production validation gates FAIL. Nightly/relevant-PR replays now enforce those
same unmet gates and must be described as regression runs. This manual dispatch
verified the shared enforced lane; a scheduled or PR event was not separately
executed in this session.

### Remaining acceptance and handoff

`skein-3q32` is closed for the measured Unicode query/probe repair.
`skein-5uu2` remains an investigation task: demonstrate actual affected
mixed-encoding postings before implementing any bounded derived-row repair.
The first runtime failure does not justify an assumed historical Android
supplementary-character migration.

`skein-gg11.32` remains in progress. The development-falsified value-shape
experiment is disabled in production; lexical overlap and nearby quantities do
not reliably establish the requested attribute. Supported paraphrases,
contradictory/revised facts, compact evidence selection and provenance-aware
follow-up resolution still require work. Future calibration must use development
data and another independently frozen validation set; the measured sets here
are now regression evidence.

`skein-lbw`, `skein-079`, `skein-hwsa` and `skein-5hr` remain open. No approved
embedding backend decision, real service, shared tokenizer activation,
production document/query embeddings or complete reindex path is established.
`skein-9744` now has separate ordinary/diagnostic execution, explicit quality
enforcement and nightly/relevant-PR wiring, but production vector ablation and
full-hybrid acceptance remain open. No model factuality or physical-device
performance claim follows from these synthetic retrieval results.

The measured application/test source remains `8b8884b`; later commits retain
raw evidence and documentation without extending runtime measurements to new
application behavior. Original evidence, gold labels and thresholds are
preserved. The coordinator independently verified all 105 SHA-256 entries in
the four earlier bundles, all 17 entries in the failed continuation bundle,
and all 34 entries in the corrected source bundle. All 29 uncompressed members
of the complete dedicated artifact archive also match their original byte
lengths and hashes. Owner directories, the existing stash and recovery worktrees remain intact.
**Fold HOLD remains unchanged:** no physical-device access, SSH, installation
or generation occurred. Beads was updated locally; its Dolt remote is still
unconfigured, so its attempted push could not sync. No remote was invented.
