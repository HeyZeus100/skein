# Answer-quality evaluation

`skein-gg11.30` separates model answer quality from the retrieval development set
and from fake-engine UI tests. This commit provides fixtures and reporting only:
**no real-model factuality scores have been measured by this tooling yet.**

The checked-in `testing/src/main/resources/eval/answers` contains 12 development
cases and 80 reserved cases. Both are synthetic. The reserved split covers direct
facts (8), synthesis (8), follow-ups (6), absent evidence (8), weak-only matches (6),
conflicts (6), revised sources (6), deleted sources (6), prompt injection (6),
contradictory Spaces (6), general knowledge with Knowledge off (6), and calculation
(8). Stable UUIDs and exact UTF-8 revision hashes identify every source. Answers,
expected behavior, forbidden sources, and a human grading rubric remain host-side.

This is a small, public, templated regression suite, **not a blind external
benchmark**. Use development cases for implementation and prompt tuning. Freeze
the reserved split before the first comparative run; change its manifest/version
explicitly if labels prove defective, report the change, and rerun comparisons.
Do not repeatedly tune against failed reserved examples and continue calling the
result held out. Reproduce with `python3 tools/eval/generate_answer_fixture.py`.
Normal CI checks reproducibility, label/source integrity, and scoring failure paths.

## Export and run

```sh
python3 tools/eval/answer_eval.py validate --split reserved
python3 tools/eval/answer_eval.py export --split development > development-input.jsonl
```

The export strips gold judgments and produces the opt-in Android benchmark input:
`schema_version`, `case_id`, `scope`, `query`, `history`, and `sources`. Each source
has `chunk_id`, `doc_id`, `revision_hash`, `title`, `text`, `score`, `source_kind`,
and UTF-8 `byte_start`/`byte_end`. History contains only role/content.

This lane deliberately supplies all current synthetic documents in the requested
Space when Knowledge is on; Knowledge off supplies none. Weak and conflicting
sources and distractors remain. It tests the production answer pipeline against
known supplied evidence through the isolated APK engine. It **does not test real
retrieval, index updates, deletion, or Space filtering**. The fixture's lifecycle
and forbidden-source labels also define the separate full retrieval journey to
be implemented under `skein-9744`. Excluding a deleted source in this export is not
proof the production index removed it.

A run manifest must contain `engine_path: "isolated-apk"`,
`mode: "supplied_evidence"`, `run_id`, `case_set_sha256` from validation,
`fixture_sha256` of the exact exported JSONL, full `model_sha256`, `template_sha256`,
`apk_sha256`, `build_sha`, `llama_sha`, device identity, sampling configuration,
context configuration, and integer `seeds`. Start with development seed 17 for
harness validation; record reserved comparisons at fixed seeds 17, 41, and 73.
The benchmark must distinguish declared host metadata from values measured or
verified on device. A header hash does not substitute for a full GGUF hash.

Keep one output row for every attempted case/seed, including timeouts, OOMs,
service errors, and empty answers. Required fields are `run_id`, `case_id`, `seed`,
`status` (`ok`, `timeout`, `oom`, `error`), `answer`, `used_sources`, and `citations`.
Source entries identify `doc_id` and `revision_hash`. Optional `exact_quotes`
entries additionally carry literal `text`; omit that field if the runtime does
not expose quote metadata. Retain measured timing/token counts and runtime error
codes in the original run artifacts. Do not invent memory, throughput, or quality
measurements for fields unavailable from the harness.

## Grade and report

Read each synthetic answer alongside all gold documents, prior turns and lifecycle
labels. Judge the meaning, relevant omissions, unsupported additions, source
support, exact quotation and handling of uncertainty. A number or keyword match
is not sufficient. A citation pointing to a real source does not establish that
the source supports the claim. An LLM judge alone is not the grading authority.

Record a separate review JSONL row keyed by `case_id` and `seed`, with:

- `reviewer` and `notes` identifying the review and supporting evidence;
- `correct` (boolean), and observed `behavior` (`answer`, `abstain`, `explain_conflict`);
- `cited_claims` and `supported_cited_claims`;
- `claims_requiring_citations` and `claims_with_citations`.

The two claim pairs count only factual claims requiring evidence, not decorative
citations. Check all quoted prose manually even when `exact_quotes` is absent.
Mark invented quotes or sources incorrect and explain the failure. General-knowledge
cases do not require vault citations.

```sh
python3 tools/eval/answer_eval.py score --split reserved \
  --manifest run_manifest.json --results answers.jsonl --reviews reviews.jsonl
```

The reporter rejects duplicate case/seed rows, mismatched fixtures, unknown cases,
wrong run IDs and fake-engine provenance. Missing outputs and runtime failures
remain in the intended denominator. Unreviewed answers never count as correct.
It reports verified correctness lower bounds, answerable-only correctness,
appropriate abstentions, false abstentions on reviewed answerable cases, claim
support, citation coverage, per-category counts and source/quote violations.
`quality_gate_eligible` means the run has complete outputs and reviews of successful
answers with no structural violations; it does not mean a quality threshold passed.

Wilson intervals describe the observed binary rates. Cases from one template and
repeated seeds are correlated, so these intervals are not confidence in general
model intelligence. Report numerator/denominator and review completeness with every
score. Do not infer Astra/ChatGPT parity from this suite. Proposed quality targets
remain in the continuation handoff; no target is an achieved measurement.
