# Production embedding readiness

Inspected from retrieval resume source `513d97a` on 2026-09-28. The existing
dependency order remains `skein-lbw` → `skein-079` → `skein-hwsa`.
This record does not select an embedding backend or close any of those issues.

`skein-5hr` still requires an artifact-cited, human-approved `embedder_path`
decision. Neither `docs/MEASUREMENTS` nor `docs/MEASUREMENTS.md` exists at this
source. The coordinator checked the issue and recorded M0 decision memories:
the recorded inference/Vulkan, native budget and model decisions do not choose
ONNX versus GGUF embeddings. The existing ONNX dependency and identity-model
smoke test also do not constitute that choice. Fold remains HOLD; no physical
device access, SSH, installation or generation was performed in this work.

## Dependency boundaries

| Issue | Progress that does not select a backend | Remaining acceptance boundary |
|---|---|---|
| `skein-lbw` | Pure numeric `Pooling` implements attention-mask mean pooling and 256-dimensional truncation before L2 normalization and the existing int8 rule. JVM tests cover padding, shapes, nonfinite/zero outputs and numeric extremes. | The Android service still has `onBind = null`. Real model verification, binding, sessions, serialized execution, transport ownership, per-request cancellation, actual model outputs, lock tests and measured memory remain unimplemented or unmeasured. |
| `skein-079` | Existing document/query consumers now reject malformed vectors and short/surplus document responses, with cancellation checks before index access. These are defensive contract checks, not a production embedder wrapper. | Blocked by `skein-lbw` and the approved `skein-5hr` choice. No real `EmbedderServiceImpl`, verified model/tokenizer load, Nomic document/query prefixes or real-model semantic margin is established. The pending contract suite stays pending. |
| `skein-hwsa` | Existing lexical ingest continues without an embedder, and malformed vector batches are reported pending instead of silently completing via truncated `zip`. | Blocked by `skein-079`. Both production document and query factories still pass no embedder; the chunker still uses `ApproximateTokenizer`. Loaded-service/tokenizer wiring and reindex activation remain open. |

The numeric helper is currently preparation for the service; it is not invoked
by the stub. Its tests establish arithmetic only. A future ONNX pipeline can
mean-pool token outputs before conversion, while an approved already-pooled
backend can enter the same conversion directly. Neither is running today.

## Interfaces requiring coordinated implementation

Before implementing `skein-lbw`, reconcile these existing IPC gaps with the
lock-policy contract. `IEmbedderService` has locking/locked pushes but no explicit
unlock authorization method. Its `tokenCount(String)` has no request epoch,
and `cancel(requestId)` has no corresponding request ID in `EmbedRequest`,
`ExtractEntitiesRequest` or `RerankRequest`. Do not authorize a cold service
merely because a load call supplied an epoch. This repair changes no AIDL.

`TokenizerFactory` and the tokenizer implementations currently reside in
`:core:rag`; the isolated service is not allowed to depend on that module.
Sharing tokenizer code needs an explicit module-boundary change while preserving
the service isolation guard. Loading a convenient unverified app-side tokenizer,
or silently retaining approximate counts for real embeddings, would not satisfy
the tokenizer wiring acceptance criteria.

Document ingestion is composed in `app/ingest/IngestPipelines.kt` and invoked
without an embedder by `VaultServices`. Query retrieval is composed in
`app/models/ModelServices.kt` with `embedder = null`. Wire both to the same
verified loaded model and tokenizer only after `skein-079` is ready. Preserve
session lock cancellation, Space filtering and generated-source exclusions.
Vector recall currently bypasses the lexical evidence gate; it still needs
independent semantic relevance calibration before any hybrid acceptance claim.

`VaultRepository.enqueueReembedAll()` exists but has no production caller. Its
current SQL requeues non-attachment documents through the ordinary ingest path;
that path replaces chunks and therefore can rechunk with a new tokenizer.
Activation must cover pending rows and version changes, respect the queue's
revision/cancellation behavior, and explicitly resolve attachment coverage.
Failed vector batches can leave previously committed batches present; the
pipeline reports pending, and this change does not add a durable completion
marker or a reindex scheduler. Chunk embedder ID/version alone is not proof
that every vector write completed.

## Existing runtime evidence

The preserved actual `7601a20` retrieval JSON reports `vector_count = 0`,
`pending_embedding_chunks = 850`, `vectors_pending_documents = 850`,
`embedder = None`, `tokenizer = ApproximateTokenizer`, and
`full_hybrid_gate = INELIGIBLE`. Its actual diagnostic XML has one executed pass;
ordinary XML has app 37, vault 197 and inference-service 36 passes, with no
failures, errors or skips. These execution results do not establish semantic
retrieval or an embedding backend. Original evidence, gold labels, reserved
failures and acceptance thresholds remain unchanged.

Evidence: `docs/eval/runs/2026-09-28-retrieval-repair-7601a20/retrieval/retrieval.json`,
the adjacent `instrumentation.xml`, and that bundle's `ordinary/*.xml`.

## Local verification of this preparation

JDK 17, no emulator or device: `:embedder-service:testDebugUnitTest` has 9 actual
XML passes (8 new arithmetic cases and the existing ONNX identity-model smoke),
zero failures/errors/skips. `:core:rag:testDebugUnitTest` has 348 XML cases:
344 executed passes and the 4 existing skipped `EmbedderServiceImplTest`
placeholder cases, with zero failures/errors. Those skips remain an explicit
unmet real-service contract, not acceptance. The new cases cover batch cardinality,
every vector's validity before writes, stable positional pairing, document/query
cancellation and a malformed response retaining the pipeline's pending outcome.

Both modules passed `ktlintCheck`, `checkNoRawLogging` and
`checkNoTestDoublesInMain`; the embedder also passed `checkIsolationGuards`.
The command used `./gradlew --no-daemon --max-workers=2` with those explicit
tasks. These host-side checks do not measure model quality, real Binder transport,
full-hybrid retrieval or physical-device performance.
