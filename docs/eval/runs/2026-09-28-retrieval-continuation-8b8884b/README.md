# Resumed retrieval validation at 8b8884b

Measured source: `8b8884b4b24a93f1ae72461840518ba2ebaaafb3`.
This bundle preserves the corrected ordinary run and the **single first runtime
execution** of the independently authored frozen validation set. The source was
held unchanged across those runs. The preceding ordinary failure at `bcd2b32`
remains in [its own unchanged bundle](../2026-09-28-retrieval-continuation-bcd2b32/README.md).
No new-set retrieval rerun or post-validation policy calibration was performed.
Dates use the session's 2026-09-28 Pacific date; final run completion was
2026-09-29 06:42:31 UTC.

## Actual workflow conclusions

| Lane | Run | Actual result |
|---|---|---|
| Ordinary emulator | [36530225153](https://github.com/HeyZeus100/skein/actions/runs/36530225153) | SUCCESS; 277/277 actual XML cases pass |
| Dedicated, `require_quality=true` | [36531672149](https://github.com/HeyZeus100/skein/actions/runs/36531672149) | FAILURE; complete valid measurements, production quality FAIL |
| CI | [36530199120](https://github.com/HeyZeus100/skein/actions/runs/36530199120) | SUCCESS; unit tests, lint, guards and Foss debug assembly |
| Screenshots | [36530199083](https://github.com/HeyZeus100/skein/actions/runs/36530199083) | SUCCESS; actual eight-module Roborazzi verification |
| Reproducibility | [36530199086](https://github.com/HeyZeus100/skein/actions/runs/36530199086) | SUCCESS; toolchain/self-tests, native cold-build comparison, two unsigned releases and comparison; SQLCipher source tag job SKIPPED |

Every retained workflow JSON identifies this same full source SHA. Actual job and
step conclusions and logs were inspected, rather than inferring outcomes from
the workflow name or overall badge. Screenshot tasks ran for designsystem, chat,
editor, graph, models, settings, shell and timeline. These source-specific runs
do not establish physical-device or generated-answer acceptance.

## Ordinary separation and Unicode controls

Original `ordinary/{app,vault,inference-service}.xml` contain 37, 204 and 36
cases respectively: **277 unique cases, zero failures/errors/skips**. The
retrieval diagnostic class is absent. `targeted-cases.json` identifies seven new
Unicode/tokenizer/ingest controls and six preserved graph/FTS regression targets.
All pass, including `unicodeTextBindingPreservesSupplementaryAndLegacyModifiedUtf8`.
The coordinator and Unicode agent independently reviewed the original XML.

`inventory-comparison.json` compares every testcase against failed ordinary run
36527953704: no additions or removals and exactly one failure-to-pass change.
The correction replaced a synthetic CESU-8 title-comparison assumption with
measured ART generic binding, an actual NUL-key negative control and independently
forced legacy body/JSON preservation. Production policy and retrieval fixture
were unaffected. The prior failure remains a failed run; it is not rewritten as
successful or used as fresh validation.

`ordinary/full-download-file-index.json` records all 351 downloaded artifact
files with byte lengths and hashes. Original XML, workflow logs, original
instrumentation review and independent review are directly retained here.

## Enforced dedicated result

The actual diagnostic XML contains one pass, no failure/error/skip, lasting
65.080 seconds. Gradle exited 0. Collection is `complete=true`, attempt 1,
without errors, timeout or recovery. The measurement step then failed with:

```
ValueError: retrieval quality gate FAIL; complete measured evidence retained
```

Artifact upload succeeded. Job and workflow conclusions are **failure**.
`quality_gate` has development ranking PASS, development absence PASS, original
reserved regression FAIL and independent validation FAIL. This is successful
measurement of failed quality, not a quality pass. Full hybrid is **INELIGIBLE**.

`retrieval/retrieval.json` is the original 4,685,276-byte report, SHA-256
`b51830389930206f991de92be217cc55bcd0371e9bebf34646bef570ab5d0745`, matching the
device file. Built, retained-installed and post-run local test APK digests all
match `aaa895db11c95fb989d2c682fc14db6d38e1818978055201717b3aa906c803fc`.
Source identity is **host-declared**, not embedded APK attestation.

`retrieval/complete-original-artifact.tar.gz` contains **all 29 original
downloaded files**, including raw collection command/stdout/stderr, the duplicate
report stdout, complete logcat, XML, protobufs, HTML reports and build logs.
Every member's uncompressed bytes were verified against
`retrieval/full-download-file-index.json`. Key review files are also directly
available under `retrieval/`; their contents were copied without rewriting.
All archived checksums are in `SHA256SUMS`.

## Frozen policy and measurements

Policy freeze: `564243494f4ba1de73a4df1457d5a0dde128f6b0`, as recorded in the
runner's original manifest metadata. Production remains
`lexical-query-coverage-v1` at 0.5. The separate
`lexical-fact-shape-v2-experimental` uses 0.5 query coverage and 0.25 value
coverage. Development counterexamples had already falsified that candidate;
it was not enabled in production and these measurements do not promote it.
Both policies retain the explicitly uncalibrated semantic-vector bypass.

New fixture SHA-256:
`4f79b2ddcf42dedd6b7f83c10402855f683fcebc3045d9e4668f6da2951759e8`.
It was authored and frozen independently before coordinator calibration and
contains 12 source notes and 24 queries. The original reserved, development and
gold data and all acceptance thresholds are unchanged. The new set has now been
executed once; any future replay is a regression run, not fresh validation.

| Set / configuration | Covered spans | Rejected absences | False rejections | Recall / nDCG | Strict gate |
|---|---:|---:|---:|---:|---|
| Reserved / production | 5/6 | 5/6 | 1/6 | 0.833333 / 0.833333 | FAIL |
| Reserved / ungated | 6/6 | 0/6 | 0/6 | 1 / 1 | FAIL |
| Reserved / experimental | 5/6 | 6/6 | 1/6 | 0.833333 / 0.833333 | FAIL |
| New / production | 9/12 | 6/12 | 3/12 | 0.75 / 0.75 | FAIL |
| New / ungated | 12/12 | 0/12 | 0/12 | 1 / 0.969244 | FAIL |
| New / experimental | 10/12 | 7/12 | 2/12 | 0.833333 / 0.833333 | FAIL |

Every configuration passes coarse ranking; none passes strict relevance. New
production false rejections are `fresh-20260928-03`, `05`, `08` (film packaging,
paper treatment duration and choir music location). Unsupported accepts are
`13`, `14`, `16`, `17`, `19`, `20` (manufacturer, date, capacity, humidity,
inspection finding, composer). Experimental still rejects `03`/`08` and accepts
`13`/`14`/`17`/`19`/`20`. `validation-failures.json` preserves query text, expected
answers and returned source excerpts as a derived review, without changing raw
results or labels. Original production failures remain
`reserved-answerable-05` and `reserved-related-only-01`.

| Development mode | Recall / nDCG | Rejected absence | False rejection | Ranking | p50 / p95 ms |
|---|---:|---:|---:|---|---:|
| Lexical only | 0.600000 / 0.601604 | 16/16 | 12/60 | FAIL | 11.745 / 16.095 |
| Graph only | 0.200000 / 0.141962 | 16/16 | 48/60 | FAIL | 0.838 / 3.816 |
| Default lexical + graph | 0.800000 / 0.717357 | 16/16 | 12/60 | PASS | 12.173 / 15.606 |

New production p50/p95 is 6.401/7.752 ms and experimental 6.607/8.771 ms. These
are synthetic emulator retrieval timings, not model, Fold or production latency.
All measured scope, provenance, anchor, duplicate and determinism violations are
zero. Recomputed aggregate/category metrics agree with raw query rows. Independent
source review checks **1,449 source occurrences** (414 reserved, 1,035 new)
against complete source bytes, titles, UTF-8 locators, BLAKE3 revision hashes,
fingerprints and gold grades. Both the sole runner's and coordinator's offline
reviews are retained. These checks do not turn related-but-unsupported sources
into evidence of the requested fact.

The diagnostic audit again finds 508 legacy top-50 misses with row matches from
850 probes; no missing sampled posting and no ingest warnings. This tests the
sampled terms, not complete index integrity. There are **0 vectors**, **850
pending documents** and **850 pending chunks**. The runtime still uses the
approximate chunk tokenizer, no configured production embedder and no artificial
vectors. Embedding/vector ablation and full-hybrid gates remain open.

## Local evidence and remaining limits

`local/art-correction-final-check.log` records final corrected-source vault
ktlint and both Dev/Foss AndroidTest compilations, 67 tasks, BUILD SUCCESSFUL.
`local/art-native-test.log` records the native tokenizer/posting contract pass.
`local/pre-correction-local-verification-bcd2b32.json` is explicitly earlier
local-source evidence and preserves known skipped tests; it is not presented as
final corrected-source runtime evidence.

Quality enforcement and relevant PR/nightly workflow wiring are implemented;
this run exercised the shared quality-enforced workflow manually. A distinct PR
or scheduled event was not executed during this session. The failed strict gates,
production embeddings, full hybrid, generation quality and physical-device
acceptance remain open. Fold HOLD is preserved: no physical access, SSH,
installation or generation. No regression or holdout was retuned into a pass.
