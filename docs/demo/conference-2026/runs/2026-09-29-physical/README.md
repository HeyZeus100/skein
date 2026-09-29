# Fold physical checkpoint: 29 September 2026

**Candidate installed; demo not ready.** The answer-attempt review was recorded at 10:43:59 UTC. The designated runner completed a replace-install and a native test replay. The synthetic answer attempt timed out. Post-update UI use and the presenter script have not been rehearsed.

The [minimized evidence record](physical-evidence.json) contains exact identities, outcomes and SHA-256 references to retained local evidence. It publishes no device identifier, private path, owner content, screenshot or raw process dump. The [earlier preflight](../2026-09-29-preflight/README.md) remains a historical checkpoint; its original observations and failures are unchanged.

## Installed update

The `app.skein` Foss debug APK is bound by reviewed host build provenance to source **`5ae91c238612bdd9b1511b3716d34b4fa5960dd2`**. Its installed SHA-256 is **`1f83b71c5fe60a6dc26fcac7a4c75b49fbf009b6e8c9d972b2176c18081c1323`**, size 125,280,302 bytes. The ordinary and opt-in app APKs have identical bytes. The signing certificate matches the preserved prior installation; `install -r` returned success. The answer-run review independently records the installed app and test APK hashes.

This establishes the update's identity and replace-install result. Source provenance comes from the reviewed host build, not an embedded source attestation. It does not establish vault-content equality, successful owner unlock or working post-update UI.

## Native public-model result

Both attempts used the public model with SHA-256 `2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12` and embedded-template SHA-256 `cd8e9439f0570856fd70470bf8889ebd8b5d1107207f67a5efb46e342330527f`. This is not verification of the owner-selected model. The preflight's distributor/base-model licensing discrepancy remains unresolved.

One exact JUnit method contains **five native cases**. Both native JSON artifacts report matching render/token results for empty-system, role-word, Unicode/whitespace and repeated-turn cases, plus successful literal-control isolation. The latter has two scaffold control tokens versus three in the unsafe reference.

Native EOG classification is true for `<|im_end|>` (151645), `<|endoftext|>` (151643), and the distinct `</s>` token (128247). **This is classification only; generated stopping and answer quality remain unmeasured.**

The first attempt's formatted output said `OK (1 test)`, but its host runner lacked `-r` and rejected that format. An unsupported process-status command also prevented its stop proof. Its original `complete: false` result remains preserved. The fresh v2 replay passed raw JUnit parsing, all five native cases and remote-stop verification; its `complete: true` result does not rewrite the first attempt.

## Answer attempt and open gates

The supplied-evidence answer run reached the unchanged **900-second host watchdog** (recorded elapsed time 901.58 seconds), had a host-recorded instrumentation-command exit of 124 and driver exit 1, and remained incomplete. Remote stop was verified. Neither the remote `run_manifest.json` nor `answers.jsonl` existed; collector placeholder/error files are not valid benchmark outputs. There are **zero valid answer rows**, so facts, citations, skipped-generation behavior and stop reasons cannot be assessed.

The final `Process crashed.` message followed timeout cleanup and force-stop. It does not establish a spontaneous app or native crash. The delay's cause and generation latency are unestablished. The original attempt and partial observation are retained; no automatic retry was performed.

A subsequent normal launch returned success in 2.48 seconds, but Android keyguard was showing and restricted input. The runner stopped without screenshots, key events or security changes. The owner must unlock normally before the on-screen rehearsal. App-vault state and the new shell remain unobserved.

Owner-vault UI, selected-model identity, note import, indexing/retrieval, citation navigation, real folds/IME, lock recovery and three full rehearsals remain open. This checkpoint does not close `skein-830f`, real-Qwen, UX, inference-lifecycle or retrieval acceptance gates. A successful installation and native classification test are insufficient to claim a ready demo.
