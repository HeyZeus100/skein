# Official Qwen 1.5B host qualification — 29 September 2026

**Host qualification passed; device and demo acceptance remain open.** This checkpoint covers an isolated public candidate downloaded and examined on the host, followed by a CPU vocabulary-only native probe at 18:15 UTC and independent review at 18:20 UTC. It does not select or import the model into the owner app. The existing 3B model and its evidence remain separate.

The candidate is the official [Qwen2.5-1.5B-Instruct Q4_K_M GGUF](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf), revision `91cad51170dc346986eccefdc2dd33a9da36ead9`.

| Identity | Verified value |
|---|---|
| File | `qwen2.5-1.5b-instruct-q4_k_m.gguf` |
| Bytes | 1,117,320,736 |
| SHA-256, matching official LFS | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| BLAKE3 | `2619da49b802f7bc7e92264edd12e4bf093dd97f42584535ae77dc587ab55362` |
| Native-emitted template bytes | 2,509 |
| Template SHA-256 | `d5495a1e5db0611132a97e46a65dbb64a642a499421228b9c8b93229097fa9a4` |

The pinned [GGUF license](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/91cad51170dc346986eccefdc2dd33a9da36ead9/LICENSE), [instruction-parent license](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/989aa7980e4cf806f80c7fef2b1adb7bc71aa306/LICENSE), and [base-model license](https://huggingface.co/Qwen/Qwen2.5-1.5B/blob/8faed761d45a263340a0528343f099c05c9a4323/LICENSE) declare Apache-2.0 and have identical verified bytes. This observation applies to this official 1.5B candidate, not the previous third-party 3B derivative.

[Identity metadata](identity.json) records GGUF v3, Qwen2, 28 layers, hidden width 1,536, 12 query/2 KV heads, context 32,768, and the `gpt2`/`qwen2` tokenizer with vocabulary 151,936. Separate stored embedding/output matrices account for 1,777,088,000 stored elements; excluding one embedding matrix gives 1,543,714,304, consistent with the card's rounded 1.54B count. The isolated `blake3` 1.0.8 implementation passed empty/`abc` known vectors. The independent reviewer repeated full-file SHA-256/BLAKE3 verification.

The native probe used llama.cpp `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` and tokenizer-overlay SHA-256 `71aba69a0fba8abc2df0a8f74f537182b79b29d60b13e74b7dc901e52dc36f29`. One small harness was compiled against existing verified static libraries under the build lease; 63 archive/header inputs were checked before and after, and the lease was released. [Provenance](provenance.json) retains the executable/source hashes and scope.

All five unchanged primary fixtures passed: `empty-system`, `role-word`, `unicode-whitespace`, `repeated-turns`, and `literal-control-isolation`. The first four arrays also match a fresh run of the existing four-case host probe on this candidate. Literal `<|im_start|>` content produced 14 safe versus 9 unsafe tokens, with control-token counts 2 versus 3; five additional partial/full boundary checks passed. Expected arrays came from this model's actual native outputs. See the exact [inputs](case-inputs.json), [native stdout](native-readiness.raw.json), and [reference arrays](native-reference.json).

Native EOG classification was true for `<|im_end|>` 151645, `<|endoftext|>` 151643, and `</s>` 128247. This classifies vocabulary entries; it does not observe generated EOS. Host scaffold ranges were checked against native ChatML rendering, without running Android JNI or Kotlin's placeholder boundary proof. The exact [template bytes](template.utf8) are retained. Full Jinja tool/default-system semantics are not qualified by this probe.

The [independent review](independent-review.json) verifies 34 capsule artifacts, 63 compile inputs, 16 prior provenance paths, 222 generated source files, ten official text blobs, and all five primary/five boundary cases. Its retained original is `conference-compact-workspace-20260929/build/agent-logs/official-model-five-independent-review-20260929/review.json`, SHA-256 `ce5c9cfc695b8f60b3e3e5c2a95ea16a0d1e952b8a03bac45942bbf144160f17`. Published review paths are sanitized; findings are unchanged. [SHA256SUMS](SHA256SUMS) covers this documentation bundle.

The [source-only switch review](switch-source-review.json), at app source `920e820eb23d2c5f4b70dfa95515899559cb2098`, records the eventual route: **Models → Import model → system file picker**. Normal unlocked import automatically selects the new default. With the original filename, the expected row is `qwen2.5-1.5b-instruct-q4_k_m.gguf` and **1.0 GB · UNKNOWN**; picked imports do not import upstream license metadata. The private filename is `models/qwen2.5-1.5b-instruct-q4-k-m-6a1a2eb6d156/model.gguf` relative to `filesDir`. Rollback is the preserved 3B row's **Set default** action. Row tap opens details, and a persona model override can take precedence over the global default. No switch occurred in this host qualification.

No CPU-setting improvement is established here. The app baseline remains four threads for generation and batches, prompt batch 512, mmap, GPU offload 0, and context cap 16,384. Live UI sampling permits 1,024 tokens at temperature 0.7; the separate frozen benchmark uses greedy sampling and 256 tokens. Fresh Fold native parity, tensor loading, generated stopping, answer/citation quality, speed, memory/thermal behavior, and live UI rehearsal remain open at this checkpoint. Old-model parity cannot be reused, and no thresholds, gold, production settings, or old-driver identity guards were changed.
