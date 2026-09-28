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

Score the first eight raw returned positions. An exact repeated chunk ID keeps
its position but earns zero additional gain or evidence coverage; do not compress
the ranking or promote a ninth result into the @8 window. For answering-evidence
recall, count each labelled answer span once, even if overlapping chunks both
cover it. For a span split across chunks, grade 3 and reciprocal-rank credit occur
at the first raw rank where their byte-range union completes that span.

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

## Real SQLite diagnostic harness

`core/vault/src/androidTest/kotlin/app/skein/core/vault/eval/RealRetrievalEvaluationTest.kt`
is an opt-in instrumented harness. Ordinary instrumentation runs skip it unless
the `skein.retrieval.eval=true` argument is present. It uses an isolated encrypted
`VaultLifecycle` with all production migrations, `VaultRepositoryImpl`,
`IndexStoreImpl`, and four real personas. Seeding creates documents only. The
same components as `app/IngestPipelines.forSession` perform ingestion:
`Chunker(ApproximateTokenizer)`, `IngestSteps`, `EdgeUpserter`, and
`DanglingResolver`. `RetrievalServiceImpl` performs every measured query.

The current app has no production embedder or entity extractor. The harness
therefore inserts no vectors or artificial entity/gold edges. It evaluates:

- `lexical_only`: lexical recall, no vector/graph recall; existing ranker settings
  `recallWeight=1`, `pprWeight=0`, `neighborHops=0` remove graph influence.
- `graph_only`: real graph recall and the production ranker defaults.
- `lexical_graph_default`: all default recall stages requested, with the existing
  explicit no-embedder degradation. Ranker weights remain unchanged.

`RecallStages` adds optional production stage switches with all stages enabled
by default. It does not inject results or replace production ranking. Automatic
Knowledge evidence excludes CHAT and AIOUT. The report records excluded document
and chunk counts; any returned generated source is a hard provenance failure.
Per-source recall still caps at 30 before eligibility filtering, so generated
documents can consume recall capacity even though they cannot become evidence.
The evaluator does not overfetch or tune this away.

Null query persona aliases and unassigned documents resolve to the actual default
Space. All current gold labels agree with this mapping. Each indexed row is
checked against real chunker output, including heading breadcrumbs, source byte
offsets and revision hashes. Only eligible NOTE/ATTACHMENT chunks enter the
citation oracle: CHAT revision snapshots are deliberately empty/bounded by the
repository contract. Eligible chunks must match their stored revision. A returned
row must also match its actual indexed text and anchor; an arbitrary text suffix
is not accepted as integrity proof.

The pure scorer lives in `testing/eval/RetrievalMetrics.kt`. Ideal DCG is computed
from positive chunks in the real indexed corpus, independently of retrieval
candidates, using an exact bounded subset search. The current short gold notes
have only one or two positive chunks per query. A future corpus exceeding 18
positive chunks per query fails explicitly rather than approximating the oracle.
Missing stored evidence or incomplete index coverage fails fixture validation.
Gold resources and thresholds remain unchanged.

Each query/mode has one untimed warm-up and three measured repetitions by
default (`skein.retrieval.repetitions`, allowed range 2–10). Determinism compares
ordered IDs, source kind, revision, locator, text, exact scores, and recall
provenance. The report contains per-query and per-category recall/nDCG/MRR,
numerators/denominators, raw rankings, scope/provenance/anchor violations, absence
rejection and false rejection, and nearest-rank p50/p95. Absence rejection is the
current production result-list behavior; no gold-aware or calibrated weak-source
rejector is introduced by the harness.

The report always identifies this implementation as `full_hybrid_gate=INELIGIBLE`
because production vectors are unavailable. Ranking targets are still evaluated
at 0.75/0.60 and reported as PASS, FAIL or INELIGIBLE. Diagnostic instrumentation
success proves integrity, scope and determinism; it does not override a failed
quality target or establish a full hybrid gate. `skein.retrieval.requireHybrid=true`
deliberately fails after writing the report in the current implementation.

## Running and collecting the report

Build the dedicated library test APK; no inference-service model download is
needed:

```sh
./gradlew --max-workers=2 :core:vault:assembleDevDebugAndroidTest
```

On an explicitly selected test emulator, install that APK and run only this
class. The dev APK is under `core/vault/build/outputs/apk/androidTest/dev/debug/`.
Use the package and runner in its generated AndroidManifest (`app.skein.core.vault.test`
and `androidx.test.runner.AndroidJUnitRunner` with the current configuration):

```sh
adb -s "$ANDROID_SERIAL" install -r "$RETRIEVAL_TEST_APK"
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class app.skein.core.vault.eval.RealRetrievalEvaluationTest \
  -e skein.retrieval.eval true \
  -e skein.retrieval.revision "$(git rev-parse HEAD)" \
  app.skein.core.vault.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p artifacts/eval
adb -s "$ANDROID_SERIAL" exec-out run-as app.skein.core.vault.test \
  cat files/artifacts/eval/retrieval.json > artifacts/eval/retrieval.json
```

The harness emits its actual package and relative artifact path through
instrumentation status. Use those values if the test application ID changes.
The temporary synthetic vault is closed and deleted afterward; the JSON remains
in the test application's files directory. Early failures write `HARNESS_FAILED`
with a fixed phase and exception class, not source text. Archive the JSON with the
instrumentation result and build SHA before another run replaces it. If using
Gradle connected tests instead of direct instrumentation, collect the report
before any runner uninstall/cleanup removes the test package.

This delivery is compile- and JVM-test-validated; it contains no claimed device
scores. `skein-9744` remains open for the first actual baseline, reviewed failures,
real production embedder/vector ablation, full-hybrid eligibility and enforced
quality gate, and manual/nightly artifact wiring. A diagnostic run cannot close
those remaining requirements.
