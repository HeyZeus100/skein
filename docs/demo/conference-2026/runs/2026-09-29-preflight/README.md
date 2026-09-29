# Fold demo preflight: 29 September 2026

The owner explicitly released the prior Fold HOLD for a verified update and demo checks, with existing app data preserved. The requested ready time is 17:00 America/Los_Angeles on 29 September; 16:00 is the planning code-freeze target. This checkpoint records preflight and artifact identity only. **No candidate update, model generation or demo-note import has occurred.**

One designated runner observed a single authorized Pixel 9 Pro Fold, Android 17/API 37, battery 100%, thermal status 0 and approximately 215 GB available storage. The existing `app.skein` APK was preserved locally and its host/device hashes matched:

- Package version: `0.1.0`, version code `1`.
- APK: 123,290,168 bytes; SHA-256 `b610ca509e069d5716ff02c5019e6b8448b76886c06a18d20725038bd54d70aa`.
- Single Android Debug signing certificate SHA-256: `75bcae7118a4635dd0fb153438e5bdbfd7aab5d467b3a0c2bb210eefde5becf9`.

These identify the **existing installation**, not a new candidate. The runner must compare the candidate package/signature and bind its APK hash to the reviewed source and checks before a data-preserving update. Version code/name alone cannot distinguish these builds.

The existing app launched successfully. Minimal accessibility probes did not yield a usable hierarchy, so the vault unlock state and selected/registered app model remain unverified. No authentication, screen-lock or screenshot-protection settings were changed.

## Public Qwen artifact qualification

The runner copied only the approved shared-storage Qwen Q3 smoke artifact to an ignored host cache. Device and host full hashes match `2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12`, size 1,590,475,744 bytes. This does not identify the app-private registered/selected model.

The host qualifier from commit `b63ac7d1cf4271594d186f3daae046143bb10c9e` completed with exit 0. The original [report](report.json), [invocation/source manifest](manifest.json), empty [stderr](stderr.txt) and [hash index](SHA256SUMS) are preserved here unchanged.

Observed metadata:

- Architecture `qwen2`, tokenizer `gpt2` with `qwen2` pre-tokenizer; vocabulary size 151,936.
- Declared EOS is token 151645, `<|im_end|>`, type 3. `<|endoftext|>` is token 151643, type 3. The distinct `</s>` token 128247 is type 1.
- Embedded template: 2,507 bytes, SHA-256 `cd8e9439f0570856fd70470bf8889ebd8b5d1107207f67a5efb46e342330527f`.
- Raw tokenizer-metadata SHA-256: `7f463778c3b814b7632f97530888990620a2119bffc1d67a8f5161ed7a4e5fdf`; the report defines the exact byte coverage.

This establishes file and metadata identity. Native loading, rendered-template parity, native EOG classification, generation stopping, answer quality and generation memory/thermal measurements remain **unmeasured**. The phone's idle thermal reading is not an inference thermal result. Upstream source revision and artifact-specific licensing still require separate qualification.

## Retained local evidence and failures

Raw device preflight, the preserved installed APK and the public model cache remain under the conference-demo worktree's ignored `build/agent-logs/fold/20260929T081355Z-preflight-89bcc0/`. Device identifiers and broad logs are not published in this documentation directory.

The first remote `stat` command used a spaced format that the ADB shell split; its exit-1 output is retained beside the corrected successful size read. The attempted `is-user-unlocked` query was unsupported and is retained as such. Two bounded accessibility probes returned no parseable hierarchy; they are not evidence that the vault was unlocked. No failed observation has been converted into a pass or silently removed.

The coordinator still owns Beads, main integration and CI/emulator dispatch. This preflight does not close `skein-830f`, real-Qwen `.28/.30`, model qualification, UX or retrieval acceptance gates.

## Follow-up: foreground observation and public source identity

At 08:27 UTC, the designated runner confirmed the existing app was foreground and captured one authorized screen without changing screenshot protection. The old shell was unlocked, with a Space chooser, new-note/new-chat controls and a truncated Qwen model chip visible. This supersedes the earlier unknown unlock observation only; it does not establish full selected-file identity or working inference. The screenshot contains personal note previews and remains local and ignored. No notes were imported and no generation or installation occurred.

The [distributor file](https://huggingface.co/tensorblock/Qwen2.5-3B-Instruct-abliterated-GGUF/blob/574cf57acf9ca2a56ec7e62e3bd974a413881745/Qwen2.5-3B-Instruct-abliterated-Q3_K_M.gguf) at immutable repository revision `574cf57acf9ca2a56ec7e62e3bd974a413881745` has exactly the observed LFS SHA-256 and size. The selected API fields are retained in [upstream-identity.json](upstream-identity.json), with a digest of the full response retained locally. This establishes a matching published artifact; it does not prove how the device copy was obtained.

The distributor card labels its derivative `apache-2.0`; the [base Qwen2.5-3B-Instruct licence](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct/blob/main/LICENSE) is the Qwen Research License. Record both observations. No blanket Apache licence or complete licence qualification is asserted. The upstream identity match does not establish any of the still-unmeasured native/runtime gates above.
