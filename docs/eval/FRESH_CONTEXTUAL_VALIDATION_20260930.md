# Fresh contextual retrieval validation protocol

This additive protocol evaluates the frozen synthetic 2026-09-30 fixture without
changing the old metrics, policies, thresholds or fixtures. It is available only
inside the existing opt-in `retrievalEvaluation` source set. It does not enable a
production feature, provide a hybrid baseline, or establish answer quality.

The fixture remains SHA-256
`653b79ce9ab1c36776b5876d1b5d687a1d335f811f388b832fd0d9d258952674`:
22 documents, 40 queries, 22 answerable queries, 18 absences and 24 required spans.
Both competing sources are required for each of the two conflict cases. The
candidate author remained blind to this fixture; the adapter author reviewed the
gold before any candidate execution. This is AI-reviewed synthetic validation,
not human grading or a generalization guarantee.

## Explicit execution and source identity

The existing `RealRetrievalEvaluationTest` adds `fresh_contextual_validation` to
its report only when `skein.retrieval.fresh40=true`. It also requires full source
IDs in `skein.retrieval.fresh40CandidateFreeze`,
`skein.retrieval.fresh40EvaluatorFreeze`, and the existing
`skein.retrieval.revision`. The host runner verifies clean source, ancestry and
frozen file bytes. The IDs in the Android report are host-supplied attribution,
not APK-embedded attestation. No fresh candidate evaluation is authorized until
the final candidate and evaluator are frozen and the coordinator dispatches the
dedicated lane. Default legacy runs and their report fields remain unchanged.

## Actual corpus and provenance

The helper creates a separate disposable encrypted vault through the production
repository and ingest pipeline. It creates historical revisions, ingests them,
calls `updateBody` on the same document IDs and ingests the current revisions.
It reads back retained historical revisions and verifies current chunk revision
hashes. The four revision-replay cases can therefore execute in the primary
component evaluation. This does not demonstrate an app editor/lock lifecycle.

Before retrieval, the helper enumerates the whole actual index and verifies its
row count against SQLite. Every chunk must match the actual `Chunker` output,
including exact embedding breadcrumb, tokenizer count, raw UTF-8 offsets,
canonical offset conversion, revision and pending embedder metadata. It records
raw and canonical document bodies, frontmatter, revision hashes, full indexed
rows, source pins and history receipts. It recomputes revision hashes from both
body and frontmatter. Arbitrary text ending in a source suffix is not sufficient.

Both modes call the real production service with the raw current query. The
baseline uses the unchanged String API. The explicit candidate uses the
contextual API with only the prior USER query and verified historical source
pins. Assistant prose, expected resolution strings, answer labels and gold spans
are never supplied as retrieval inputs. The baseline's returned indexed chunks
are explicitly distinguished from expanded units; no expanded member map is
invented for it.

Expanded evidence must equal current immutable source bytes at valid UTF-8
boundaries, belong to the requested Space and a NOTE, and exclude forbidden
sources. Every nonempty member list must match the full actual indexed identity
and the original query candidate including unchanged scores. Full-index
enumeration scores establish no ranking claim. Representatives must be the
earliest original member, with original signals. Unknown, duplicated, reused,
cross-source, out-of-range or detached members fail provenance. Expanded units
cannot overlap. Invalid/duplicate results consume their original rank; result 9
never backfills a failed rank within the first 8.

## Denominators and runtime limitations

Each mode always reports the fixed 40 query IDs with `EXECUTED`, `ERROR`,
`TIMEOUT`, or `UNEXECUTED`. The six conversation-context cases remain primary
`UNEXECUTED`, with no score or primary credit, because the app SendPipeline has
not exercised this context seam. A separately named `component_diagnostic` can
retain fixture-fed API calls for them. Those diagnostics cannot change the
primary 40/22/18/24 denominators or the app's `executed=0,total=6` status.

One warmup and the configured repetitions (normally three) are retained. API
exceptions, timeouts and recall-stage failures remain explicit failed samples;
the production service's empty-on-stage-error fallback never counts as correct
absence rejection. Only its exact expected missing-embedder diagnostic is
allowed. Every executed scored row has all successful repetitions. Coverage is
the minimum valid-span count across repetitions; any nonempty repetition counts
as an absence admission. Strict and all-span success additionally require
identical actual results and no provenance violations. Errors and unexecuted
rows get no success credit.

The report records micro coverage over all 24 required spans, macro coverage
over all 22 answerable queries, all-span query success, both conflict members,
absence rejection/admission over all 18 absences, and strict success over 40.
Coverage is diagnostic even when a different returned item violates provenance;
the provenance failure remains a hard failure. Expanded nDCG and legacy ranking
gates are `INELIGIBLE`: the old indexed-chunk ideal universe cannot score expanded
or merged units. No new numeric acceptance threshold is invented.

This protocol does not implement embeddings, semantic entailment, app context
restoration, answer generation, citation presentation, or physical acceptance.
Original validation evidence, gold labels and thresholds remain unchanged.
