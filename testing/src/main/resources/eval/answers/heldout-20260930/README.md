# Fresh answer evaluation, 30 September 2026

Status: frozen after independent AI gold review on 30 September 2026. No model output has been generated for this set. The manifest pins the approved case bytes and peer-review receipt; the wrapper refuses export or scoring without this recorded freeze.

The [80 cases](cases.json) were individually authored before any candidate output. They are separate from the unchanged 12-case development set and 80-case templated regression set. Their questions, answer structures and evidence relationships were written separately; the private authoring script only assigned deterministic IDs, calculated hashes and serialized those authored entries. Existing fixture generation, labels, scoring code and policy thresholds are preserved by hashes in the [manifest](manifest.json).

| Category | Cases | Main demand |
| --- | ---: | --- |
| Supported | 16 | Direct extraction, conditions, sequence, exact quotation and multiple-note synthesis |
| Missing | 8 | Missing fields, identity, units, unobserved facts and causal limits |
| Distractor | 8 | Wrong object, role, status, quantity or tempting nearby value |
| Conflict | 6 | Both competing sources, with no invented resolution |
| Follow-up | 8 | Referents, corrections, ellipsis, changed topic and ambiguity |
| Injection | 6 | Treat instruction-like note text as data |
| Spaces | 6 | Scope exclusion and no fallback into another Space |
| Reasoning | 8 | Set overlap, rates, ordering, logic, arithmetic and nonunique answers |
| General | 14 | Knowledge-off explanations and common conceptual mistakes |
| Total | 80 | Every case remains in the denominator |

Every case has required claims, forbidden conclusions and a rationale. Vault cases use synthetic notes and exact UTF-8 spans pinned to body hashes. A span may cover a whole short note; a reviewer must still check whether each answer claim follows from that note. Conflict cases require all competing evidence, including one case with two contradictory instructions in the same note. The general cases have primary reference URLs in their gold annotations, checked during authorship; these references are never supplied as answer hints to the model. No owner content or real financial, medical or personal records appear here.

The fixture is held out from subsequent candidate tuning, not a blind external benchmark. Authorship and independent peer review are by AI agents. This does not satisfy human grading of model answers. A correction after freezing requires a new version and an explanation, never rewriting a failed candidate's gold labels. Similar topic coverage across old and new sets is deliberate; a new UUID alone is not evidence of independent difficulty.

## Execution and reporting

Use [heldout_answer_eval.py](../../../../../../../tools/eval/heldout_answer_eval.py) to validate, export gold-free JSONL, or score recorded results:

```sh
python3 tools/eval/heldout_answer_eval.py validate
python3 tools/eval/heldout_answer_eval.py export > build/agent-logs/heldout-input.jsonl
python3 tools/eval/heldout_answer_eval.py score --manifest RUN.json --results RESULTS.jsonl --reviews REVIEWS.jsonl
```

Export preserves raw questions and user/assistant history; it supplies only current notes in the selected Space, or no notes with Knowledge off. It excludes labels, expected answers, category, rationale, gold spans and general reference URLs. This is **supplied-evidence answer evaluation**. It does not run production retrieval, context resolution, source authorization, revision replay or deletion. Successful Space/follow-up answers here cannot close those application gates.

The run manifest must contain the complete provenance required by the unchanged answer calculator: actual model/template/input/APK hashes, source and llama identities, device, sampling, context, run ID and explicit unique integer seeds. It also pins the frozen manifest SHA-256 and the full commit containing the frozen fixture. These are declared receipts; the script cannot attest an installed APK or authenticate a human reviewer.

Every expected case × declared seed remains in the report, including missing, timeout, out-of-memory and error results. Unreviewed output never counts as correct. Reviews must declare `reviewer_type` as `human` or `ai`, plus reviewer identity, evidence notes, behavior and claim/citation annotations. AI scores are provisional. Declared human coverage is reported separately and is not proof of reviewer identity. Inspect all substantive claims and prose quotations; a valid source ID does not prove support.

The wrapper reuses the existing ratio calculations and applies no new thresholds. `structural_report_complete` can be true for an all-timeout run with zero correctness; it is not a quality pass. `quality_gate_status` is always `NOT_DECIDED_BY_THIS_TOOL`. Existing latency, output-quality, citation, default-model and runtime gates remain open until their actual evidence is reviewed. Descriptive intervals over correlated cases/seeds are not independent benchmark confidence.

Start with the separately recorded bounded feasibility controls on the already installed application. Those controls, including any prior purchase-order failure, are not fresh80 cases and cannot be mixed into this denominator. Do not blindly run three seeds × 80 if bounded latency or runtime checks already fail. A partial run must retain all expected missing rows in its denominator, the original failure artifacts and the reason for stopping. Never retry until a concrete correction and new run identity are recorded.

This fixture/tool addition performs no model calls, installs, model imports, retrieval execution or physical-device operations. Its integrity tests are not model-quality results.

## Integrity verification

The serialized local queue ran the fixture validator and all 15 answer-evaluation integrity tests (eight new and seven existing): all passed, with no skips. The original first validator failure is retained privately: its rule incorrectly required separate documents for every conflict, although one authored case deliberately contains both conflicting instructions within one note. The validator now requires all competing supplied evidence; the case and gold bytes were unchanged. A regression still rejects dropping one source from a two-source conflict. These results validate the tooling and fixture structure, not any model answer.
