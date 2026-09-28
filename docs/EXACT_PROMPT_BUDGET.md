# Exact prompt capacity (skein-gg11.33)

Production `ModelServices` supplies `LlamaCppEngine.measurePrompt` to `SendPipeline`.
The pure content counter remains a retrieval preselection heuristic, including
its existing timeout estimate; it never authorizes production generation.
`ExactPromptAssembler` measures the mandatory policy and question first, then
measures every accepted full candidate. It removes oldest complete user-led
history exchanges, then trailing evidence passages. Leading orphan assistant
messages are omitted. A question/policy/answer reservation that cannot fit raises
`ContextFull` before `engine.stream`. If fitting removes all Knowledge evidence,
the existing application missing-evidence reply is persisted without generation.

The service renders through `ChatTemplating` and tokenizes the proven role/content
segments using the same helper for measurement and generation. This includes BOS,
chat delimiters, any template EOS, and the assistant prefix, while untrusted
message content continues to use `parseSpecial=false`. No fallback format or
whole-prompt special-token parsing is introduced. Measurement never clears the
KV cache, creates a sampler, or decodes.

Capacity is the actual `llama_n_ctx` of the loaded native context, not catalog or
requested context metadata. Generation independently checks the exact count plus
`maxTokens` with wide arithmetic before clearing KV or prefilling. Equality fits;
one position beyond capacity refuses. The native decode boundary also refuses
before decoding any portion of an oversized batch.

`Prompt.expectedModelSha256` binds every fitted candidate and generation to the
pinned loaded model. Measurement checks epoch/identity at admission, on the worker,
and before returning. Generation checks on the worker before native access and
again before prefill. A swap fails with `ModelChanged`; a lock fails with the
existing `SessionLocked`. Request FD ownership, BUSY admission and cancelled
preparation are preserved. Normal diagnostics contain counts and fixed messages,
never prompt/template content.

Wire additions are appended: `GenerateRequest.expectedModelSha256`,
`PromptMeasurementParcel`, and the last AIDL method `measurePrompt`. Existing
transaction numbers and fields retain their order. The same per-message FD spill
transport is used for counting and streaming. Its existing 128 KiB per-message
read bound now refuses excess data rather than silently truncating it. Premature
EOF, size mismatch, and malformed UTF-8 refuse with a fixed error; valid multibyte
text at the bound is retained. The host closes its local FDs and the service closes
every received FD on success and refusal.

`AssembledPrompt.formattedTokens` and `contextLength` contain the exact accepted
measurement. `estimatedTokens` remains content-only. `droppedHistoryTurns` retains
its historical meaning of **messages**, although removal occurs in exchanges;
`droppedRetrievedItems` counts supplied sources omitted, and citations contain only
survivors. The original transcript is not deleted or rewritten by fitting.

JVM tests cover exact overhead, Unicode and empty system messages, equality and
overflow, model identity, lock/BUSY/refusal ownership, long history, all evidence
removed, cancellation and closed transport. Native capacity boundary tests compile
for both variants; running them with the pinned tiny model and the synthetic Qwen
isolated-service harness remains a dedicated runner task. Passing counts is not
proof of answer quality or cross-model token parity.
