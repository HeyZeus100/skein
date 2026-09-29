# skein-gg11.37: bounded synthetic answer timeout source triage

Exact source: `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`. Twelve relevant files were copied with `git show`; their hashes are in `source-manifest.json`, and each was identical to the coordinator tree at review time. No source edits, builds, device calls, dispatches or Beads operations occurred.

Only these approved runtime inputs were read (hashes/absolute paths in `approved-inputs.json`): `independent-answer-review-minimal.json` and `answers/instrumentation.log`. No process dump, embedding-containing native review, owner model registry, owner conversation or vault data was opened.

## Observed boundary

The approved minimal review records a host watchdog at 901.58 seconds (exit124), no remotely present run_manifest.json or answers.jsonl, zero valid answer rows, and verified remote stop. The instrumentation log records test start and a later `Process crashed.` result. The latter follows timeout cleanup; it is not evidence of a spontaneous native crash. Native parity5/5 and installed-app hash agreement are coordinator-supplied facts, not independently re-read from restricted raw native evidence here. Answer quality/citations/skipping/stop reasons remain unmeasured.

## Strong source localization

All line references below are to the exact saved5ae source, with their normal repository paths.

`app/src/syntheticBenchmark/kotlin/app/skein/benchmark/SyntheticAnswerBenchmarkTest.kt`:

- Lines69-70 enter `runBlocking(Dispatchers.Default)`; no JUnit timeout or whole-test coroutine timeout is declared.
- Lines73-104 validate opt-in/config/fixture/seed metadata. Config is capped64KiB and fixture8MiB; `SyntheticFixtures.kt:32-38` also caps cases at500. Path canonicalization, reads, parsing and hashing are synchronous.
- Line105 hashes the installed app APK (SHA256).
- Lines107-117 validate the dedicated public-model path/size and hash the entire model **SHA256 plus pure Kotlin BLAKE3**. The full model pass completes before any output directory or manifest is created.
- Lines127-130 create the unique output directory. Lines142-157 seal/register the dedicated model and parse its small generated manifest.
- Lines158-165 construct a fresh engine. This creates local state/scopes; it does not bind/load. `ImmutableModelStore.kt:181-190` is a new private map/monitor with a simple register assignment, not an existing model-service lock.
- Lines166-208 construct provenance, including another complete SHA256 pass over the test APK at180.
- **Lines209-210 write the first run_manifest.json.** Only afterward does line211 call `onSessionUnlocked`, and only line222 starts the first case timeout; model load is at224.
- Fresh `LlamaCppEngine.onSessionUnlocked` (`core/inference/.../LlamaCppEngine.kt:686-694`) returns when `connection` is null. Binding occurs lazily in load -> `connect()` (`:273-300`, `:773-829`). No service request precedes the first manifest.

Therefore the missing remotely present manifest makes **pre-manifest setup the first localization target**. It argues against blaming generation, model load, token-count deadlock, or final unload first. This remains an inference: approved evidence lacks phase markers, and an incorrect collection run identity, failed write or external data removal would also need exclusion. The harness itself never deletes the output directory or manifest; it refuses an existing run directory.

## Leading hypothesis, not proven diagnosis

The full model SHA256+BLAKE3 loop (`SyntheticAnswerBenchmarkTest.kt:450-465`, invoked at116) has no progress, deadline or cancellation check. It reads1MiB chunks and performs both hash updates synchronously. `core/model/.../Blake3.kt:147-170` allocates a state array and six permutation arrays per compression; `:178-195` constructs word arrays; `:264-285` processes64-byte blocks. The loops make progress in source; no infinite-loop path was identified. Their allocation/CPU cost on a large public model under this Android test build is unmeasured and could be substantial. Do not claim a measured throughput or explain901s solely from this inspection.

The direct native parity test is materially different: `inference-service/src/syntheticBenchmark/.../SyntheticTemplateParityTest.kt:31-40` computes **SHA256 only**, then loads natively at47-50. It does not execute the app's BLAKE3 preflight, app-side Binder model lifecycle, answer pipeline or generated-answer evaluation. Its five passing parity cases do not exclude this setup hypothesis.

Other pre-manifest candidates remain config/fixture parsing, app/testAPK hashing, filesystem access, chmod or output write. Phase evidence is required to distinguish them. A ordinary thrown preflight check would normally produce a test failure rather than continue to the host watchdog; the current log has no such pre-cleanup failure.

## Timeout and blocking boundaries

- **Physical outer watchdog:**900s is established by the approved review, not by a timeout in this test. It covers startup, preflight, all cases and cleanup. The separate repository smoke driver uses a different host timeout and was not treated as the physical driver.
- **Per-case timeout:** line122 reads `case_timeout_ms`, line123 allows1,000..600,000, line222 encloses load, initial context probe and answer pipeline. The actual run's configured value is not available in the approved minimal inputs and was not guessed. Preflight and provenance hashing are outside it.
- `hashFile` is ordinary blocking/CPU code and does not check cancellation. Merely moving it into `withTimeout` without cooperative checks would not make it a hard deadline.
- The synchronous prompt-assembler bridge at276 is `runBlocking { budget.countTokens(it) }` with no parent Job supplied. It can keep blocking its calling thread after the surrounding case is cancelled. `ContextBudget.kt:79-91` holds its mutex over the token Binder call; no recursive acquisition was found in the inspected sequential path (`computeBudget` calls countTokens then returns). This is a later cancellation risk, not proof of self-deadlock or of this missing-manifest failure.
- `LlamaCppEngine.kt:559-584`/`:593-595` run measure/tokenCount through synchronous Binder inside `withContext(IO)`. Coroutine timeout does not itself interrupt a synchronous Binder transaction. `ServiceConnector.kt:97-173` awaits binding without an internal duration, but its Deferred wait is cancellable. Load uses a separate cancellable `ModelTransactions.call` (`ModelTransactions.kt:46-87`) so caller cancellation can stop waiting without claiming to stop remote native work.
- Timeout is caught at289-291; answers.jsonl is appended at345 **before** error cleanup at347-349. Final cleanup at353-356 is also outside the case timeout. `LlamaCppEngine.unload` (`:539-547`) synchronously waits for service.unload onIO. `InferenceWorker.kt:116-129` waits for FutureTask completion and deliberately does not abandon native ownership on interruption; those waits have no duration. They can extend teardown but would ordinarily leave the already-written manifest (and on a completed timeout catch, a row).
- The known worker self-post deadlock is guarded in this source: `InferenceWorker.kt:64-72` executes inline when already on its HandlerThread. No new deterministic pre-manifest self-deadlock was identified.

The official Kotlin API documentation confirms cooperative `withTimeout` cancellation and that `runBlocking` uses a supplied context/Job rather than inheriting an arbitrary surrounding suspend context: [withTimeout](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html), [runBlocking](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html). These references support semantics; they are not runtime evidence of the stopped physical process.

## Narrow privacy-safe next measurement proposal

The demo owner alone controls any next physical run. First add only opt-in synthetic-test diagnostics; do not change inference implementation, verification algorithms, thresholds, labels or budgets based on this hypothesis.

1. Emit an allowlisted instrumentation status event immediately before entering `runBlocking`, then on entry to its Default body. This distinguishes scheduler entry from later phases. No process dump or raw logcat is needed.
2. Emit start/end markers for config validation, fixture parse/hash, appAPK hash, model hash, output-directory creation, local engine construction, testAPK hash and manifest write. Within the existing model-hash loop, report bytes completed plus cumulative elapsed read/SHA256/BLAKE3 time at bounded intervals (for example every64MiB or2s); keep the same bytes and algorithms. The diagnostic schema should allow only phase enum, monotonic sequence, elapsed/cumulative timing, byte counts, boolean completion and validated synthetic run ID. Do not include file paths, model names, prompt/answer/source text, exception messages, device fingerprint, registry data or process state.
3. After manifest success, emit only high-level case/seed ordinal and phase markers around load, initial measurement, pipeline, row write and cleanup. Observe engine-state enum changes if useful, without model IDs or strings. Only if these markers locate a later service boundary should a separate bounded inference measurement be considered.
4. Keep diagnostics in a distinct phase artifact/status stream. A preflight marker is not a verified run manifest or answer row. The host collector should preserve those allowlisted events on timeout, retain the original stop/cleanup proof, and continue to reject missing/invalid result files. Verify that the configured safe run ID and collector run ID match via metadata before interpreting absence.
5. If a setup deadline is later added, use explicit monotonic checks between hash chunks (and cancellation checks in coroutine-aware code). Keep an external watchdog for non-interruptible I/O/Binder stalls; never assume coroutine cancellation killed remote work. Record the measured failed phase rather than manufacturing rows or increasing the outer watchdog blindly.

A diagnostics-only testAPK changes its own artifact hash/source provenance and must be recorded as such; the released app candidate remains the coordinator's exact5ae binary unless the coordinator explicitly selects another candidate. This review authorizes no retry or device action.
