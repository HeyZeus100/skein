# Official Qwen 1.5B fresh native parity — 29 September 2026

**PASS: fresh native parity only.** Run `qwen15b-native-b-20260929T190035Z` used the dedicated Android native library test app at source `920e820eb23d2c5f4b70dfa95515899559cb2098`. Its five cases match the separately qualified [official 1.5B host reference](../2026-09-29-official-qwen-host/README.md), including production `ChatTemplating` and JNI. This does not establish the owner app's selected model or a successful live answer.

| Verified identity | SHA-256 |
|---|---|
| Copied installed app, 127,100,043 bytes | `b3fab0e408d79a5566fdf2334000e6c0095d710fcd50b77774038b35fc6ad46b` |
| Copied installed native test APK, 130,304,344 bytes | `c1186bc554ae89d89a35983f883354e436cb34f2cebb790cc06a3a896409fb2d` |
| Full public model, 1,117,320,736 bytes | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Native template | `d5495a1e5db0611132a97e46a65dbb64a642a499421228b9c8b93229097fa9a4` |

The staged model's full hash and byte count were verified, and the native test independently checked the full-file SHA-256 before loading. The production app was verified as already installed; this phase installed the native test APK, not a replacement production app.

| Native case | Actual tokens | Result |
|---|---:|---|
| `empty-system` | 15 | PASS |
| `role-word` | 9 | PASS |
| `unicode-whitespace` | 25 | PASS |
| `repeated-turns` | 21 | PASS |
| `literal-control-isolation` | 14 | PASS |

All actual token arrays match the fresh host reference. The literal-control case retains two authorized `<|im_start|>` tokens versus three under unsafe all-special tokenization. Native EOG classification matches for `<|im_end|>` 151645, `<|endoftext|>` 151643, and `</s>` 128247. **These classifications do not prove generated stopping.** The [native report](native-parity.json) is retained byte-for-byte.

The exact [instrumentation log](instrumentation.log) reports **one JUnit test passed**, with JUnit time **1.614 seconds**. Host instrumentation elapsed time was **4.856 seconds**. Neither measures answer generation or prefill speed. Exact-target teardown passed two consecutive absent-PID checks for `app.skein.inference.service.test`, and no active instrumentation remained.

The original v6 attempt was rejected during a read-only storage check: its mount parser did not accept the returned data-directory alias. It performed no APK install, model transfer, or instrumentation. Original failure evidence remains retained. The v6b successor changed only that strict parser; the remaining driver AST and storage threshold were unchanged, and **44 host mock tests passed** before this fresh run.

[Public status and review bindings](status.json) retain the exact source, release, model, APK, host-reference, root-review, and failure/successor identities. [SHA256SUMS](SHA256SUMS) covers this bundle. Device identifiers, raw commands, and owner/private metadata are excluded.

The coordinator approved a separate answer phase after this pass. **Answer/citation quality, generated stopping, answer/prefill speed, and live UI rehearsal remain unmeasured by this record.** No result from the concurrent answer trial is included here.
