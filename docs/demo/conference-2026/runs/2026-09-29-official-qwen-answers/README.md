# Official Qwen 1.5B supplied-evidence answers — 29 September 2026

**Four completed, factually supported answers; citation gate unmet.** Run `qwen15b-answers-20260929T190424Z` exercised the isolated APK with the official Qwen 2.5 1.5B Instruct Q4_K_M model after [fresh native parity passed](../2026-09-29-official-qwen-native/README.md). This is a supplied-evidence test: its fixed fictional excerpts bypass live retrieval. It does not establish owner-app model selection, source navigation, live UI behavior, or full demo acceptance.

The [actual answers](answers.jsonl) correctly identify RFI-017 awaiting finish-selection approval and Nora Elm coordinating delivery, state that the purchase order number is absent, identify the missing propeller log, and report the **synthetic** 21.1 g observation for 22 September 2026. The [independent review](independent-review.json) found no unsupported factual additions. **All four raw answers and citation arrays contain zero citations**, including the synthesis answer that requires both supplied sources. No complete answer-quality approval or benchmark score is assigned.

| Case | Prior 3B v5 TTFT (s) | Official 1.5B TTFT (s) | Prior total (s) | Official total (s) |
|---|---:|---:|---:|---:|
| Construction synthesis | 53.171 | 21.708 | 90.844 | 33.592 |
| Missing purchase order | 37.823 | 15.464 | 111.525 | 18.573 |
| Aviation | 34.455 | 14.111 | 37.824 | 47.866 |
| Synthetic mycology | 45.169 | 25.038 | 52.491 | 74.227 |

All four measured times to first token (TTFT) were lower. Completion was faster for construction and slower for aviation and mycology. These are single sequential runs with different model/APK identities and answer lengths, so they do not establish a general speedup or a thermal cause. [Timing comparison](timing-comparison.json) binds the original v5 evidence and both identities; fixture, supplied source identities, greedy sampling, seed 17, context 4096, and four threads matched. The 1.5B rows generated 34/14/15/29 tokens and reported `EOS`; this establishes those runtime stop reports, not every stopping path or an emitted EOS token spelling.

The exact [instrumentation log](instrumentation.log) reports **one JUnit test passed in 176.463 seconds**; host instrumentation elapsed was **180.573 seconds**. The [36 setup events](setup-progress.jsonl) have no reported errors. Engine load starts at setup elapsed 1.809 s and completes at 5.615 s, a **3.806 s span**. Exact-target teardown passed two consecutive absent-PID checks for the app, its inference service, and the app test package; no active instrumentation remained.

[Run manifest](run_manifest.json) and [public status](status.json) bind source `920e820eb23d2c5f4b70dfa95515899559cb2098`, the verified installed app/test APKs, unchanged fixtures, native pins, sampling, and model SHA-256 `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`. Model BLAKE3 is explicitly host-declared and bound to the device-verified SHA-256; it was not measured on-device. The [host qualification](../2026-09-29-official-qwen-host/README.md) records the official artifact and license.

Answers, setup events, and instrumentation log are byte-identical public synthetic outputs. The manifest omits device fingerprint/security-patch metadata; the review omits absolute paths while binding its original SHA-256 `212c6db754754488b166738390d11d1388519426a6fe08974e720114445e35d1`. [SHA256SUMS](SHA256SUMS) covers this publication. Device serials, raw commands, owner content, and private process metadata are excluded. Original v5 and failed v6 evidence remain preserved; no fixture, gold, or threshold was changed.
