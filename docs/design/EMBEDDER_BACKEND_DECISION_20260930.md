# Embedding backend decision packet — 2026-09-30

**Status: qualification and measurement proposal, not an approved `embedder_path` decision.**
`skein-5hr`, `skein-lbw`, `skein-079`, and `skein-hwsa` remain open. The production
service has no selected backend factory. The first packet used metadata only; a
subsequently authorized scratch qualification is recorded below. No model was
installed in Skein, reimported into owner storage, or executed, and no device
commands were issued.
Existing owner models, content, gold labels, thresholds, and original evidence
remain untouched. Source baseline is `0e56b433ff9db9323123c8afd9c33dc58fab5e8e`.

## What is established

The retained actual retrieval JSON at
`docs/eval/runs/2026-09-28-retrieval-repair-7601a20/retrieval/retrieval.json`
is 3,186,147 bytes with SHA-256
`2435b8b11d2456524da2b8e63a75a004bcdb2f6585e73fc01b8c66fb084f2caa`.
Its fields still say zero vectors, 850 pending embedding chunks, no embedder,
`ApproximateTokenizer`, and `full_hybrid_gate=INELIGIBLE`. Host boundary tests
cannot change that retained runtime result.

The pre-existing ONNX 1.27.0 dependency and the 151-byte identity-model fixture
prove neither Nomic execution nor a backend choice. The fixture SHA-256 is
`f817b8482786fce7b408ded1a5e2a2ee14ff357c8dc9799e2b845310c49f583d`.
`OnnxSession.runFloat` currently handles one float input; Nomic requires an
adapter that checks the actual model's input/output names, integer tensor types,
shapes, tokenizer, masks, and output semantics. GGUF would need an isolated
embedding adapter and its native dependency boundary reviewed separately.

The existing generic `Pooling` helper establishes masked mean pooling and
truncate/L2/int8 arithmetic only. The pinned official Nomic model card documents
full-vector layer normalization before Matryoshka slicing and L2 normalization,
along with distinct document/query prefixes. The future adapter must determine
which stages its exact exported model already includes, then verify each stage
against an independent reference. Blindly applying normalization twice is also
incorrect. See the pinned [Nomic model card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/blob/e9b6763023c676ca8431644204f50c2b100d9aab/README.md).

## Concrete artifacts to qualify

The following sizes and SHA-256 values are **upstream LFS declarations**, not
locally verified weight bytes and not benchmark results. Both model cards declare
Apache-2.0; preserve the pinned model-card/license attribution with any acquired
weights and record the tokenizer/config and export provenance separately.

| Candidate | Pinned revision | Declared bytes | Declared SHA-256 |
|---|---|---:|---|
| `nomic-ai/nomic-embed-text-v1.5`, `onnx/model_int8.onnx` | `e9b6763023c676ca8431644204f50c2b100d9aab` | 137,296,292 | `b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27` |
| Same repository, `onnx/model.onnx`, candidate reference export | Same revision | 547,310,275 | `147d5aa88c2101237358e17796cf3a227cead1ec304ec34b465bb08e9d952965` |
| `nomic-ai/nomic-embed-text-v1.5-GGUF`, `nomic-embed-text-v1.5.Q4_K_M.gguf` | `0188c9bf409793f810680a5a431e7b899c46104c` | 84,106,624 | `d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac` |
| Same GGUF repository, `nomic-embed-text-v1.5.f16.gguf`, candidate reference | Same revision | 274,290,560 | `f7af6f66802f4df86eda10fe9bbcfc75c39562bed48ef6ace719a251cf1c2fdb` |

Sources: pinned [ONNX tree API](https://huggingface.co/api/models/nomic-ai/nomic-embed-text-v1.5/tree/e9b6763023c676ca8431644204f50c2b100d9aab/onnx?expand=true),
[GGUF tree API](https://huggingface.co/api/models/nomic-ai/nomic-embed-text-v1.5-GGUF/tree/0188c9bf409793f810680a5a431e7b899c46104c?expand=true), and
[GGUF model card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/blob/0188c9bf409793f810680a5a431e7b899c46104c/README.md).
The GGUF card's historical compatibility and error table are upstream evidence;
they do not establish results for Skein's pinned llama.cpp, Android build, or
256-dimensional quantized output.

The checked-in Nomic WordPiece tokenizer is already tied to revision
`e9b6763023c676ca8431644204f50c2b100d9aab`. The rehashed raw bytes are 711,396
bytes, SHA-256 `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66`;
the gzip SHA-256 is `b825ebe614d1aa9db667ca1bbbefb125b872b5051ab0bac39b79b10e77b26cbb`.
These are test resources, not an installed production tokenizer. The immutable
Nomic golden JSON SHA-256 is
`829d328fd2351078360954aed77e360e2282386d8d608cc59bf1608380f8e372`.
Do not regenerate it to fit either candidate. Config/tokenizer companion
acquisition, full manifest coverage, and an approved module seam remain needed.

## Host feasibility, bounded inventory

This host is macOS arm64. Python, Java, CMake, Ninja, and uv are present. Neither
`llama-bench` nor `llama-embedding` is on PATH. The initialized llama.cpp source
pin is `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4`. Default Python has `onnx` and
`numpy`, but no `onnxruntime`, `torch`, or `tokenizers`; no packages were installed.
The immediate Hugging Face cache inventory contains no Nomic embedding repository.
This is a bounded cache check, not a search of owner storage or the Fold.

The existing Gradle cache contains ONNX Runtime 1.27.0 desktop JAR (43,364,304
bytes, SHA-256 `00546de2041215df2771c7665f318ba4b40f024feefa3903ceb2600983e159c5`)
with macOS arm64 native libraries, and Android AAR (44,532,227 bytes, SHA-256
`077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb`).
Thus a separate JVM experiment can use an already cached native runtime after
weight qualification; installing a Python runtime is not a prerequisite.
These package bytes are not model PSS or APK-size measurements.

`tools/m0-benchmark` currently drives generation-oriented llama-bench cells and
names embedding/GLiNER as a later harness stage. Its manifest still has placeholder
weight hashes/sizes. Its existing generation token rates cannot be reinterpreted
as embedding throughput. An embedding harness must record tokenization,
verification/load, model run, pooling, normalization, quantization, and IPC costs
separately. Any host comparison is a functional/performance experiment on macOS,
not a prediction of Fold latency, PSS, battery, or thermal behavior.

The reproducible receipt set is retained in the isolated embedding worktree at
`build/agent-logs/embedder-decision-20260930/host-feasibility-receipts.json`, SHA-256
`68b81a039445ba986d73582e7cde5182665db9cdd842a4dfcd2f6b960bf74d5e`.
It includes exact downloaded metadata/card hashes, local tokenizer/gold hashes,
cache package hashes, and tool/module inventory. Upstream source bytes are
retained beside it. The initially failed Python TLS fetch attempts remain recorded;
successful curl fetches used normal certificate verification.

## Next bounded measurement sequence

1. Acquire only the exact candidate and required companion bytes above into a new
   coordinator-approved scratch corpus/model directory. Verify size and SHA-256
   against pinned declarations before parsing or execution. Record license and
   model/export/runtime versions, all graph external-data references, and every
   opened companion. Never infer an approved model from a filename or dependency.
2. Freeze and hash a new synthetic **development** corpus before execution: 128
   texts spanning empty/whitespace, punctuation, Unicode, short factual passages,
   paraphrases, explicit contradictions, entity names, and 511/512/513 token
   boundaries. Use only synthetic text. Freeze its independent semantic labels,
   tokenizer expectations, and proposed numeric tolerances before seeing either
   backend's outputs. Existing retrieval gold and exposed validation remain intact.
3. First use the cached desktop runtime for a qualified ONNX functional experiment.
   Inspect actual tensor schema; verify tokenizer parity and prefix handling;
   compare full float output and each postprocessing stage with a separately
   qualified reference. Keep all raw vectors and failures. Confirm deterministic
   repeated 256-byte output and the existing `skein-079` int8 semantic margin of
   at least 0.15 on its stated cat/paraphrase/revenue example. That one example is
   a smoke gate, not general semantic relevance acceptance.
4. Add the GGUF candidate under the coordinator's single build queue, with the
   exact llama.cpp/build flags pinned. Use the same frozen inputs and report
   pooling/normalization differences, cosine drift, quantization drift, and task
   margins. Run batch sizes 1, 8, and 32 and thread counts 1, 2, and 4; use two
   warmups and ten measured repetitions per cell. Report all failures, p50/p95,
   peak process memory, cold load/hash time, output hashes, and cancellation latency.
   Those are proposed experiment parameters, not new production acceptance limits.
5. Only after functional parity, use the coordinator's sole Fold runner for the
   actual isolated Android path. Measure verification/load PSS and peak PSS,
   tokenization and end-to-end p50/p95, thermal state, cancellation and hard lock,
   shared-FD ownership, process death/rebind, and concurrent app memory pressure.
   Keep emulator, host, Termux CLI, and APK observations separately labeled.
6. Put actual cell artifacts under `artifacts/bench/`, including source, toolchain,
   binary and model hashes, exact command, corpus/label hashes, warmups/repetitions,
   timestamps, raw output, failures, and device identity where applicable. Compare
   the two candidates in `docs/MEASUREMENTS.md`. A human must explicitly approve
   the resulting decision table before the coordinator records `embedder_path`
   or enables production ingestion/query wiring. No winner is selected here.

## Implemented boundary and remaining acceptance

The new service slice authorizes only after an explicit two-way unlock push.
Positive, increasing request IDs are scoped to an epoch. Epochless count is
refused; epochless cancel/unload do not mutate live work. Spill transport uses
bounded seekable descriptors, strict UTF-8, explicit record lengths, and at most
32 texts. The 128 KiB spill bound is an explicit conservative first-service cap,
not a measured model limit or an inference-service transport change.

One worker owns an admitted request until action and reply handling finish.
Cancellation revokes publication immediately but cannot pretend an uncooperative
native call has exited. At hard lock, a still-running action triggers termination
of the isolated embedder process instead of concurrently freeing its native
handles. Kotlin immutable strings are not claimed to be zeroizable. Exact native
plaintext clearing and real Binder/ashmem behavior require Android acceptance.

Each received model and companion descriptor is pinned and checked by SHA-256
before and after mapping; originals close on success/refusal and retained pins
close with their backend. This verifies declared hashes, not an attestation
bundle or an immutable snapshot against later writes. The future adapter must
consume the same verified mappings/descriptors under the immutable model-store
contract. It may not discover sidecars by reopening owner paths.

Production activation, actual Nomic/tokenizer outputs, GLiNER spans, optional
reranker behavior, memory/thermal measurements, real Binder spill/lock tests,
backend module integration, and production reindexing remain unmet gates.
No arithmetic test, test-only injected backend, host timing, or passed IPC test
closes those gates or promotes full-hybrid retrieval.

## Host verification of this slice

The focused JDK 17 invocation used `--no-daemon --max-workers=2` and passed
`:core:ipc:testDebugUnitTest` (135 actual XML passes in six suites) and
`:embedder-service:testDebugUnitTest` (37 passes in seven suites), with zero
failures, errors, or skips. Both modules passed `ktlintCheck`; embedder passed
`checkIsolationGuards`, `checkNoRawLogging`, and `checkNoTestDoublesInMain`.
The first two invocations stopped at formatter findings before tests; those
reports remain preserved. The successful invocation executed both unit tasks.

Actual XML copies and exact task/command/log hashes are retained at
`build/agent-logs/embedder-boundary-20260930/final/validation-receipt.json`, SHA-256
`b619b108fec1e22653efc88be2ebea8685531c9fd269a042a9177bd390e836bd` in the isolated
embedding worktree. Tests use synthetic verified bytes and injected test-only
backends. They establish the host boundary, not native model acceptance.

Independent review subsequently found and corrected a future-epoch ordering bug:
a lock for epoch 6 while epoch 5 remained authorized must tombstone epoch 6 and
revoke all older work. The gate now records that watermark; endpoint cancellation,
model cleanup, and the hard-lock backstop cover obsolete epochs through the lock
value while preserving a newer authorized epoch. Three added regressions failed
against the original implementation (15 cases: 12 pass, three failures), and the
fixed full embedder suite passes all 40 cases with no failures/errors/skips.
The new negative-control source substitutions were restored in `finally` and
verified byte-for-byte. Lint and all three embedder guards passed again.

Both actual XML sets, commands, logs, and restoration hashes are retained at
`build/agent-logs/embedder-boundary-20260930/future-lock-fixed/review.json`, SHA-256
`11bb5208eddfcf372ec6b00388ed14676e24db5c197b883c303eef08a98b1ebd`.
The earlier passing XML remains immutable; it did not cover this ordering case.


## Authorized isolated artifact qualification

After the initial proposal, the coordinator authorized acquisition into the
embedding worktree's ignored `build/agent-logs/embedder-experiment-20260930/models`.
Before acquisition, 128 new synthetic development records and 48 independently
authored query labels were frozen, including support, unrelated text, revised
facts, absent attributes, follow-up context, Unicode and token-boundary probes.
This is development data, not new held-out validation. Freeze receipt SHA-256:
`f4f551b9ea7ad069de35b82efeb16bf82c809db4f5d4eabcbcb8f07089815af3`;
corpus `95f0a6b920f06a6f57dffaa6513ce8373dda67a6a75739828e9d1f07a63690dd`;
gold `8a12d110037fbf7ffdc4c0325f771969547479f229a734c474d60ae32ad7523a`.

The ONNX int8 candidate's actual downloaded 137,296,292 bytes match
`b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27`.
Tokenizer, config, special-token metadata, sentence-transformer module metadata,
and model card were verified against Git blob IDs from the pinned source tree.
The tokenizer also matches the existing independent raw artifact hash above.
The acquisition receipt is `model-acquisition-receipt.json` in that scratch
experiment directory, SHA-256
`f16dc90985481cb3436f19476c5da19d3211a1f3d2b895983cd2ea0ef9f89423`.

Read-only protobuf inspection found ONNX IR 7/opset 14, three INT64 tensors
(`input_ids`, `token_type_ids`, `attention_mask`) shaped `[batch, sequence]`, and
FLOAT `last_hidden_state` shaped `[batch, sequence, 768]`. There are 1,566 nodes,
260 top-level initializers and no external references in those initializers.
The separately verified pooling config selects mean-token pooling. Inspection
receipt `graph-inspection.json` SHA-256 is
`8ae888690527f977b5b05f365118b1a317f909aebcbb4f6bf22f8b024242058e`.
These are parsed schema facts. No ORT session, remote model code, inference,
latency/memory benchmark, or Android acceptance was executed. The reference
export, GGUF comparison, fresh held-out evaluation, and human decision remain open.
