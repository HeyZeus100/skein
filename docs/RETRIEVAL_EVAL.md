# Retrieval evaluation

`skein-jgs` supplies development data; `skein-9744` runs the real retrieval
pipeline and enforces its metrics. This fixture is not evidence that a model
answers correctly. Generation quality and held-out cases belong to
`skein-gg11.30`.

## Corpus and seeding

The version-1 resources are `testing/src/main/resources/eval/corpus.json` and
`gold.json`. Reproduce them with `python3 tools/eval/generate_retrieval_fixture.py`.
All names and facts are synthetic. No private vault or external model is used.

`RetrievalEvaluationVault.seed(repository, personaIds)` seeds the existing
`SyntheticVault` at seed 42/LARGE, then replaces 72 filler notes with coherent
source notes. The result remains 1,000 documents: 800 notes, 50 chats and 150
attachments. The existing random-word fixture alone cannot supply meaningful
semantic answers, so the explicit overlay is part of the evaluation definition.
It leaves some original filler links dangling, as the base fixture already does.

Create four personas in an empty test vault first and pass their real IDs keyed
by `default`, `work`, `research`, `personal`. These strings are fixture aliases,
not production persona IDs. The helper resolves aliases before repository writes
so real foreign keys can be enforced. The harness must also resolve each query's
optional `persona_id` alias before retrieval. Corpus document IDs are stable;
attachment IDs from the base generator are not gold targets.

Run actual ingest after seeding. Never materialize gold edges or inject gold
vectors as a replacement for the production index path. Record chunker,
tokenizer, embedder, reranker and build revisions with each run. The JVM fixture
tests prove source/span/scope integrity, not SQLite, indexing or retrieval.

## Cases and labels

There are 60 answerable queries: 12 each for lexical markers, semantic
paraphrases, graph expansion, persona filtering, and adversarial input. Another
16 cases exercise no-match and weak-only evidence (8 each). Category names state
the intended stressor; only ablation results can prove a retrieval channel's
exclusive contribution. In particular, semantic and graph queries may retain
incidental lexical overlap.

Each query has an ID, category, query text, nullable persona alias, answer and
`relevant` entries. An entry contains a canonical `doc_id`, grade and exact
`evidence` substring. Grade 3 means the retrieved chunk includes the answering
span, grade 2 is another chunk in that answering document, grade 1 is a listed
linked seed, and grade 0 is everything else. The fixtures pin documents and
evidence spans rather than unstable chunk ordinals. The harness assigns grade 3
only when the result covers the evidence, not merely when its document matches.
For cross-chunk spans, use revision byte-locator coverage, not exact chunk text
equality. Do not silently relabel a missed answering span as a correct answer.

Persona pairs have identical topics and contradictory values. `forbidden_doc_ids`
names the opposite Space's source. Any returned forbidden source is a hard scope
failure regardless of average recall. Adversarial cases include control markers
and misleading instructions in both the question and source body; these are data
for retrieval and cannot change the requested scope.

`no_match` and `weak_only` cases have no relevant answer and `answer = null`.
The latter deliberately share an exhibit topic with existing sources while
asking for an absent insurance premium. Zero relevant results is correct; a
related topic is not sufficient evidence to answer. These cases measure the
relevance-rejection policy in `skein-gg11.32` separately from ranking.

## Metrics and initial gates

Score the first eight distinct returned chunks, preserving order; deduplicate
exact repeated chunk IDs. For answering-evidence recall, count each labelled
answer span once, even if overlapping chunks both cover it.

- **Recall@8:** fraction of labelled grade-3 spans covered by the top eight.
  Macro-average over answerable queries; initial gate **≥ 0.75**.
- **nDCG@8:** DCG uses `(2^grade - 1) / log2(rank + 1)`, ranks starting at 1;
  divide by ideal DCG from the same indexed corpus and query labels. Cap a
  duplicated evidence span to one grade-3 gain; other chunks in that document
  receive grade 2. Initial macro-average gate **≥ 0.60**.
- **MRR@8:** reciprocal rank of the first result covering grade-3 evidence,
  or zero on a miss. Report overall and per category; no initial numeric gate.
- **No-evidence rejection:** fraction of absence queries for which the final
  evidence-selection policy supplies no sources. Report separately for no-match
  and weak-only queries, alongside false rejection on answerable queries.
- **Scope violations:** count results outside the requested persona semantics
  and explicitly forbidden source IDs. **Zero** is required.

No-match cases do not enter recall/nDCG/MRR denominators. Do not map undefined
recall to 1 or hide a missed category inside an overall mean. Report numerators,
denominators, per-category results and p50/p95 retrieval duration. The 0.75/0.60
thresholds are the existing initial project targets, not measured performance;
their purpose is to catch broad regressions while the hybrid index is completed.
Raise them after a measured baseline, without changing the gold labels to fit.

## Harness and review protocol

`skein-9744` must ingest into real SQLite, invoke production retrieval, write
`artifacts/eval/retrieval.json`, and include lexical/vector/graph ablations.
Pending/missing embeddings must be explicit in the report; do not report a
lexical-only run as passing a complete hybrid gate. Run deterministic repeated
queries and assert scope per result before computing aggregate metrics.

The real harness validates every evidence span against the stored revision,
checks actual chunk coverage and materialized graph edges, and reports failures
without substituting fake results. JVM source-integrity tests are an early guard
against invalid labels. No retrieval scores or real-model accuracy claims have
been established by merely generating these files.

Use this development corpus for relevance-policy iteration. Keep independently
authored generation holdouts separate; never tune a prompt or threshold against
them and still call them held-out. Test output content is allowed only in
explicit synthetic evaluation artifacts, not normal application logs.
