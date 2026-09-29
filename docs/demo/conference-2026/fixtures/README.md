# Conference demo supplied-evidence input

**Model execution: NOT RUN. Android loader execution: NOT RUN.** This is a four-case demo input for the existing opt-in `SyntheticAnswerBenchmarkTest`, using the fictional notes committed at `2ed08b645`. It supplies evidence directly to the real answer pipeline and isolated inference service. It does not test live retrieval, indexing, note import, the UI, unlocking or the owner's vault. Do not replace existing evaluation fixtures, labels or thresholds with this pack or describe its results as a benchmark.

## Files and host validation

- `demo-source.json`: standard answer-fixture schema, including host-only scenario expectations under the existing `gold` key. These are demo expectations, not changes to benchmark gold.
- `manifest.json`: standard fixture manifest with the separate `demo` split and exact case-set hash.
- `source-notes.sha256`: hashes of the four exact source note files used; relative paths resolve from this directory. Source bodies retain the full file text, including fictional/synthetic labels. Document IDs are deterministic UUIDs for these demo paths, not owner-vault IDs.
- `input.jsonl`: gold-free standard export. This is the only fixture file to stage for the app. Do not transfer `demo-source.json` or host expectations.
- `export_demo.py`: calls the unmodified `answer_eval.fixture("demo")` and `export_case` functions after pointing their fixture directory here. The ordinary `answer_eval.py` CLI only selects development/reserved and has no custom-directory option; this adapter does not change that CLI, its validators or its frozen data.

From the repository root:

```sh
python3 docs/demo/conference-2026/fixtures/export_demo.py validate
python3 docs/demo/conference-2026/fixtures/export_demo.py export
```

`validate` checks the existing case validator, source-file hashes and byte-for-byte export equality. `export` prints the exact JSONL to stdout without writing a file. It uses the existing exporter; no extra fields or persona prompt are sent to the Android loader. Android acceptance remains unmeasured until the released candidate actually loads this input.

## Scenarios and expectations — all NOT RUN

| Case | Question | Supplied notes and host expectation |
|---|---|---|
| `demo-construction-synthesis` | What blocks the Cedar cabinet order, and who coordinates delivery? | Cabinet Order + Delivery Coordination: RFI-017 awaits finish-selection approval; Nora Elm coordinates delivery. Both facts need their respective sources. |
| `demo-construction-absent` | What is the Cedar cabinet purchase order number? | Cabinet Order: number not recorded; do not substitute RFI-017. Evidence is nonempty, so this exercises handling an explicit fact gap, not an empty-retrieval shortcut. |
| `demo-aviation-direct` | Which log is missing from DEMO-A? | Records Packet: propeller log; airframe and engine logs are present. |
| `demo-mycology-direct` | What mass was recorded for OYS-014 on 22 September? | Observation Log: 21.1 g, explicitly synthetic, not a real experimental result. |

Inspect actual answers and source identities; a passing instrumentation test does not establish factual correctness. Preserve failed, partial, timeout and error rows. Citation presence does not prove source support. No automatic grade, percentage or quality claim is supplied here.

## Bind a run only after candidate and model qualification

The existing host helper is `tools/eval/prepare_android_benchmark.py`. The following is a command template, not a recorded invocation. The coordinator/runner supplies verified values, selects and records the context/sampling/thread/deadline configuration, and uses a fresh output directory:

```sh
python3 tools/eval/prepare_android_benchmark.py \
  --model /absolute/qualified-public-model.gguf \
  --model-sha256 FULL_VERIFIED_MODEL_SHA256 \
  --model-license VERIFIED_ARTIFACT_LICENSE \
  --apk /absolute/released-app-foss-debug.apk \
  --fixture docs/demo/conference-2026/fixtures/input.jsonl \
  --case-set-sha256 CASE_SET_SHA256_FROM_VALIDATE \
  --build-sha FULL_REVIEWED_SOURCE_SHA \
  --llama-sha FULL_PINNED_LLAMA_SHA \
  --tokenizer-overlay-sha256 SHA256_OF_REVIEWED_TOKENIZER_PINS \
  --run-id conference-demo-supplied-01 \
  --context-length APPROVED_CONTEXT_LENGTH --threads APPROVED_THREADS \
  --case-timeout-ms APPROVED_DEADLINE_MS --seeds 17 \
  --sampling /absolute/reviewed-sampling.json \
  --output-dir /absolute/fresh-host-run-directory
```

The existing sampling schema is `temperature`, `top_k`, `top_p`, `min_p`, `repeat_penalty`, `max_tokens`, `stop`. The helper verifies the full model digest and extracts the raw GGUF template digest; it does not independently verify licence/source provenance. The retained [preflight](../runs/2026-09-29-preflight/README.md) identifies Qwen Q3 bytes with SHA256 `2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12`; its follow-up records a matching immutable distributor revision and a licence-label discrepancy. Record that discrepancy accurately rather than substituting a permissive label. This smoke artifact is not the formal Q4 M0 artifact.

A normal APK/test APK does not contain the benchmark class. The released source head requires the existing `-Pskein.syntheticBenchmark=true` opt-in app and test APKs. Use the coordinator's released flavor and matching source, signatures and test APKs; the example above reflects the planned Foss Debug candidate. Builds and dispatches remain in the coordinator's serialized queue; only the designated device runner installs them. See [ANDROID_SYNTHETIC.md](../../../../tools/eval/ANDROID_SYNTHETIC.md) for exact build, dedicated-storage and instrumentation commands; use the observed instrumentation component and `synthetic_enabled=true`, not a guessed package or a whole-suite Qwen run. Read ordinary emulator evidence for that source head first.

The harness uses an in-memory repository and does not require the owner's vault unlock, but instrumentation restarts the target process and still needs an approved idle window. Stage the public model only in dedicated benchmark storage; never point it at owner model or vault paths. Keep config/APK/full-model/test-APK hashes, run manifest, answers and any failures. Owner UI import, real retrieval and physical fold rehearsal remain separate, unmeasured gates.
