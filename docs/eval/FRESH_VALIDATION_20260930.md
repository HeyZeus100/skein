# Independent retrieval validation, 30 September 2026

Status: independently authored and reviewed before candidate outputs; approved for signed freeze after five passing fixture-integrity tests. No retrieval has been run on this fixture, and this document reports no quality result.

The new [fixture](../../testing/src/main/resources/eval/rejection-validation-20260930.json) contains 22 synthetic notes and 40 questions: 22 answerable and 18 without a supported answer. There are 24 labelled evidence spans because two unresolved-conflict questions each require both competing sources. The author inspected the existing harness and schema, but no new candidate implementation or candidate output. Earlier measured results, fixture bytes, gold labels and thresholds are unchanged.

Frozen fixture SHA-256: `653b79ce9ab1c36776b5876d1b5d687a1d335f811f388b832fd0d9d258952674`.

Independent review checked all sources and gold labels, both conflict spans, current versus historical facts, Space restrictions, raw follow-up wording and source-pin hashes. Before freeze, one ambiguous plural pronoun in case 36 was replaced by an explicit reference to the purchase-order issuer; source facts, gold labels and expected resolution were unchanged. The worker retained the original draft and correction receipt. No candidate output informed that correction.

The source baseline for authorship is `b2beaf6607ce5ccd96d3862e92892a7ed033800b`. This is a separately authored diagnostic set. It is not a claim of general retrieval quality, an answer-generation benchmark, a calibrated production rejector, or a license to tune thresholds against these labels.

| Cases | Coverage | Answerable / absent |
| --- | --- | --- |
| 01–08 | Literal and paraphrased facts, units and conditions, a table, negation, Unicode | 8 / 0 |
| 09–16 | Related but missing slots, wrong quantity or object type, no match | 0 / 8 |
| 17–20 | Two unresolved conflicts, explicit draft supersession, absent reconciliation | 3 / 1 |
| 21–24 | Current revisions and facts absent from the current revision | 2 / 2 |
| 25–30 | Duplicate titles with different Space facts; absence in the default Space | 4 / 2 |
| 31–36 | Raw contextual follow-ups, unsupported prior assistant claim, Space change | 3 / 3 |
| 37–40 | Purchase-order/invoice roles, conditional access, unsupported monetary/time fields | 2 / 2 |
| Total | All cases remain in the denominator | 22 / 18 |

## Execution contract

The base document/query fields retain the existing JSON schema shape. `fixture_protocol_version: 2` adds explicit capability requirements; it is not a change to production selection thresholds. An adapter must acknowledge this protocol before execution. The legacy fixture enum has not been amended here.

Each query retains its literal `query`, requested `persona_id`, nullable `answer`, exact grade-3 `relevant` spans and `forbidden_doc_ids`. Gold labels are scoring inputs only. Every gold span must occur uniquely in a current note belonging to the requested Space. Answers are explanatory annotations, never prompts or candidate evidence. `all_gold_spans_required` means every expected span counts: retrieving only one side of a conflict fails that case. Existing revision/UTF-8 anchor, scope, provenance, duplication and determinism checks still apply.

An absence case has no supported answer to the requested fact. Nearby names, numbers, related objects and unsupported assistant assertions cannot supply one. This fixture uses the existing rejection metric: nonempty selected evidence does not pass an absence case. A source that explicitly says a fact is unrecorded may support an abstaining generated answer, but answer generation is outside this retrieval-only metric. Report that limitation rather than interpreting every rejection failure as a hallucinated answer.

The four revision cases require real revision replay: create the listed `previous_revisions` under the document ID, update that same document to `body_md`, then ingest and verify current revision anchors. Historical revisions must remain available to inspect. Loading only the final text does not execute the revision gate. Current-price absence must not be answered with a superseded price.

The six follow-ups retain raw ambiguous wording and separate `conversation.turns`. Grounded earlier assistant turns carry source pins with the exact current-body SHA-256, document ID and Space alias. The adapter must resolve these through actual stored sources and current authorization. An unsupported assistant turn carries no source pin and is not evidence. A pin from an earlier Space does not authorize retrieval in that Space after the user changes Space.

`expected_resolution.query` is an independently authored oracle for reviewing context resolution. **Never feed it to the candidate and count the result as production success.** A separately labelled oracle-control experiment may diagnose retrieval after correct resolution; its results must remain outside the candidate success denominator. The raw query/history must pass through the actual application context-resolution path for a follow-up case to execute.

The existing harness calls `RetrievalService` with a single query string and assumes one gold span in its seed-coverage assertion. It does not currently implement this protocol's revision replay or conversation execution. An adapter that lacks a required capability must emit an `UNEXECUTED` row naming the case and capability. Keep all 40 cases in the report and distinguish executed passes, executed failures and unexecuted gates. Do not silently drop the ten context-dependent cases, count them as passing, flatten multi-span gold, or substitute oracle queries.

## Integrity and preservation

[FreshRejectionValidationFixtureTest](../../testing/src/test/kotlin/app/skein/testing/eval/FreshRejectionValidationFixtureTest.kt) checks the frozen-byte receipt, counts, source spans, Space ownership, competing-source labels, distinct revision history, follow-up/oracle separation and source-pin hashes. It never invokes a retriever, backend or model. All five integrity tests passed with no skips; `:testing:ktlintCheck` also passed. Actual XML and command logs are retained in the worker’s ignored `build/agent-logs/fresh-validation-20260930/` directory. These checks establish fixture integrity only; no retrieval or answer quality was measured.

Before authoring, the worker recorded hashes of `corpus.json`, `gold.json`, both previous rejection fixtures and `RetrievalMetrics.kt` under its ignored `build/agent-logs/fresh-validation-20260930/original-evidence-hashes.json`. The integration coordinator must verify those bytes remain unchanged. No original evidence, labels, ranking gates or rejection thresholds are replaced by this fixture.

The reviewed fixture must be committed before candidate outputs are produced. After that freeze, retain failed results verbatim. A necessary label correction requires a separately versioned fixture and explanation; do not rewrite these gold labels in response to candidate failures.
