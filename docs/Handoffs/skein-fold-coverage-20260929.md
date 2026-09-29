# AL-10 / AL-15 / AL-16 coverage audit and Fold candidate

Source baseline: `a4ba4f4` (`codex/ux-integration-20260929`, integrated UX + retrieval baseline). Coordinator owns Beads and publishes the final integrated source SHA and evidence. This document does not close AL-10, AL-15, AL-16, retrieval, embedding, or physical-device acceptance.

## Production coverage added

`MainActivityDraftRetentionTest` launches the real `MainActivity`, vault gate, `NavShell`, Chat entry, controlled composer, session DraftStore and lock hooks. Only the key provider, repository, engine and other service dependencies use the existing `TestSkeinApplication` seams; `enableSessionChat` is explicitly enabled. It checks:

- An existing conversation's typed draft and caret survive a new Activity instance without appending a message.
- Lock removes NavDisplay and composer, persists draft and selection to the fake encrypted-row contract, and unlock restores them through the replacement session.
- Actual `onSaveInstanceState` Bundles stay below 64 KiB and their marshalled Parcel contains no draft, chat-title or message sentinel before/after recreation and before/after lock/unlock. The Parcel is read back with the app classloader.

This is not the complete D7 `BundlePrivacyTest`: rename, searches, editor, model/Space labels, attach picker, arbitrary Parcelable allowlists, epoch-range numbers and fresh-process restore remain unproved. The fake row proves wiring/order, not SQLCipher at-rest encryption.

## AL-10 acceptance audit

| Contract | Current production implementation | Remaining evidence or work |
|---|---|---|
| Draft text and caret | `DraftComposerState.value` reads text and both selection endpoints from the session snapshot; IME composition range is local and version-bound. `ChatRoute` wires it using `ChatDraftKey.Existing`. | New MainActivity tests cover recreation and lock/unlock. Live scene move with real IME and process-death row reload remain open. |
| Composer focus across scene changes | `ChatBottomBar` has no `FocusRequester`, retained focus flag or scene-identity restore hook. The prototype follow-up in adaptive spec §8.9 explicitly required these. | D1 across drawer/rail scene changes and D real-IME acceptance remain open. Do not infer focus retention from text retention or the existing same-scene short-height test. |
| User transcript anchor during appended rows | Reverse-layout `MessageList` keeps `rememberLazyListState`, but `LaunchedEffect(itemCount)` unconditionally calls `animateScrollToItem(0)`. | This can pull an older reading position to latest on append/turn-state item count changes. Following-only behavior and jump-to-latest are still required. |
| Entry view-state identity | `ChatScreen` constructs `ChatViewModel` with composition `remember` and disposes it on composition exit. The session turn controller is separate and already landed. | A2's exact same entry ViewModel identity, expanded citations/activity blocks and view state through destination exit are unproved. Do not rebuild or replace the landed turn controller/draft store to address UI retention. |
| Live resize without new messages | Existing `ChatEntriesTest` covers draft text across Compact/Expanded and same-scene short composer budget; `MainActivityConfigChangeTest` covers the manifest's same-Activity size handling. | Neither proves the full A–G bundle of focus, caret, transcript anchor, expanded activity, row count and entry identity in one production flow. |

No AL-10 implementation files were changed by this audit.

## Foldable emulator lane

`.github/workflows/foldable.yml` is coordinator-dispatched, separate from the ordinary instrumentation suite. `-Pskein.foldableTests=true` adds test-only source and manifest plus Espresso Device API 1.1.0; the default test APK and production APK gain no emulator networking permission. The API setup follows [Android's Espresso Device guide](https://developer.android.com/studio/test/espresso-api) (2026-09-16 revision).

The default dispatch requires the exact `pixel_9_pro_fold` catalog profile and fails if absent. The coordinator can explicitly select `pixel_fold` for compatibility coverage; it is labelled in the job and evidence and cannot satisfy the Pixel 9 Pro Fold profile acceptance gate. There is no silent fallback. The local command-line tools 13.0 catalog was inspected on 2026-09-29 and contains `pixel_fold` but no `pixel_9_pro_fold`; its original catalog is retained in the owning worktree at `build/agent-logs/local-device-profiles.txt`. It uses API 35 `google_apis` x86_64 and records profile catalog, emulator version/properties, source/submodule SHAs, XML, Gradle output and logcat. The helper refuses local execution outside the disposable CI host, rejects a non-emulator serial and verifies `ro.kernel.qemu=1` before further device operations.

`MainActivityFoldableGateTest` runs the real production Activity on a fresh unopened vault. It checks closed → flat → closed and outer portrait → landscape, actual cover/inner/short-window width and height thresholds (inner at least Medium and wider than the cover), Activity identity, a visible setup gate, no NavDisplay, and `FLAG_SECURE`. There is no assumption skip for missing fold support: a wrong profile fails. No data is imported, no setup is completed and no physical device is contacted.

`verify-foldable.py` requires both exact named testcases, rejects unexpected/duplicate/failing/errored/skipped cases (including empty UTP failures), requires the dispatched source SHA and hashes original XML plus both app/test APKs. The source SHA is the host-declared checkout; APK hashes identify build outputs, not independent installed-package attestation. Runtime window metrics and Activity identity are appended by the instrumentation test and collected separately. A successful workflow summary is insufficient: inspect `review.json`, actual XML and source/hash correspondence. Its host regression tests exercise false-success cases.

This first gate does **not** exercise unlocked A–G, real IME, streaming, app lock during generation, tabletop/book layout, or rooted `system_server` heap privacy. AL-16 remains open until those tests run with passing original evidence. API 35 does not exercise Android 17's local-network runtime permission behavior; the manifest declares that permission only in the opt-in test APK.

## Candidate and later physical Fold runbook

**Scoped authorization update (2026-09-29):** the user released Fold HOLD only for a verified, data-preserving update/demo; `/root/fts_verification` owns the sole physical runner. This coverage session still has no physical-device access. The candidate is not released until the coordinator completes integrated verification. The earlier blanket HOLD is retained in original evidence; this paragraph records the later authorization without expanding it. Any physical work beyond that update/demo needs further authorization. Do not use software posture overrides on the physical device.

The candidate is the coordinator's final integrated commit containing this change and the reviewed inference branch. Before releasing the authorized update/demo candidate, the coordinator fills an immutable candidate record under its own worktree's evidence directory with:

```json
{
  "source_sha": "<full final integrated commit>",
  "submodule_shas": "<git submodule status --recursive>",
  "apk_path": "<foss debug or approved fixture flavor APK>",
  "apk_sha256": "<sha256 of the exact APK>",
  "application_id": "<verified from this APK>",
  "jvm_xml": "<paths and hashes, exact failures/skips>",
  "screenshots": "<compare JSON and original artifact hashes>",
  "ordinary_instrumentation": "<run URL, source SHA, actual XML review>",
  "foldable_instrumentation": "<run URL, source SHA, actual XML review>",
  "retrieval_gate": "OPEN unless separately satisfied",
  "embedding_gate": "OPEN unless separately satisfied",
  "physical_fold": "scoped update/demo authorized; sole runner /root/fts_verification; candidate awaiting verification"
}
```

Use JDK 17 and the documented SDK, pinned submodules, `--max-workers=2`, explicit ktlint, affected compilation/tests, strict screenshot comparison without clearing goldens, native checks and actual ordinary/foldable instrumentation evidence. A failed or skipped gate stays in the record; preserve its first evidence. Builds on this Mac require the shared atomic `build.lock` owner lease. No test worker dispatches CI/emulators or removes another owner's lease.

Within the scoped authorization, agree with the sole physical runner on a data-preserving demo plan; a synthetic fixture vault or separate approved fixture application is required for destructive or sentinel-based security journeys beyond the demo. Record the installed source/APK hash and retain the prior installation/recovery state. The owner's daily vault must not become a debuggable test fixture. Allow screenshots only for the explicit fixture run; preserve the secure default and test Recents with both screenshot settings per D7.

The following full adaptive acceptance sequence exceeds the current update/demo scope. Obtain separate owner authorization before running it, especially repeated lock, performance, and privacy journeys. Then execute adaptive spec §9.2 in order, with physical folds by the owner and read-only observations: A/B chat + inspector/draft with caret 10 and older transcript anchor; C prefill, streaming, Stop and interrupted lock; D soft IME on both panels and outer landscape plus hardware-keyboard typing; E pending note edit and Connections; F selected graph node and transform; G drawer, model popup, search query and confirmation persistence. Record each journey's before/after geometry, visible state, Activity creation/configuration counts, lock reason and result. Measure outer-landscape transcript height with IME visible, ten physical fold-cycle frame statistics against the baseline, and twenty mid-stream dirty-draft locks with p95 below the current lock window and no FORCE_TIMEOUT.

Screenshots, hierarchy dumps and logs may contain fixture text. Keep original files with their hashes and timestamps in a new run directory, never overwrite a failed run. Record any skipped case as an open gate. Later physical results must distinguish true folds from screen-off/keyguard behavior and may not substitute emulator posture results for GrapheneOS behavior.

## Local validation handoff

The owning worktree is `/Users/andrewherrera/skein-worktrees/ux-fold-coverage-20260929`.
[The evidence record](skein-fold-coverage-20260929-evidence.json) pins original logs, actual XML and dependency-review hashes. `fold-check4` passed explicit `:app:ktlintCheck`, both devDebug unit/instrumentation Kotlin compiles, and `MainActivityDraftRetentionTest`: **2 tests, 0 failures, 0 errors, 0 skips**. Its original XML SHA256 is `489a7e994987e8deed5974fe5fd9795c3247e59a96efe5933269277c784fa5b4`. Five host parser/reviewer tests also pass; shell syntax and `git diff --check` pass.

The same gate resolved `devDebugAndroidTestRuntimeClasspath` and AGP 9.4.1's actual `unified-test-platform-gradle-work-action` host configuration without device access. AGP's UTP enum was inspected from the pinned local artifact: its host runtime uses `gradle-work-action:32.4.1` and `android-test-engine:1.0.1`, whose jar includes the emulator-control helper. The new metadata contains only 32 added Espresso/transitive artifact hashes; all pre-existing pins are unchanged. Downloaded bytes matched Gradle SHA256 pins and official published digests (26 SHA256, 6 SHA1 plus independent SHA256 of downloaded bytes). No new native classifier was added; the existing Linux aapt2 pin remains unchanged. Final integration still runs the strict combined-source checks.

Failed attempts remain original evidence: check1 exposed a Kotlin companion import mismatch in the Device API; check2 ran two unit cases with one failure after manually saving Activity state; check3 caught the resolution init script also visiting the included build-logic build. Check4 fixes those test/tool issues: the dirty draft is locked before any lifecycle-changing save, saved locked state completes stop/start before UI unlock, and the init script ignores included builds without `:app`. No product implementation was changed to make the tests pass. Instrumentation was compiled, never executed by this session.
