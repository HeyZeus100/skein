# Confirmed chat and note deletion, 29 September 2026

Application source `e62f94785ef8e696d8ee59745fd299fe1f83cc4a` is pushed to main and installed on the Fold. The one data-preserving update completed at 01:34:10 UTC on 30 September (6:34pm PDT on 29 September). The copied-back APK is 128,085,735 bytes, SHA-256 `4052c0a8ba6363baa1f7387fcce0703ad0ca73e1355a7c7b22512583bca4d4a4`. All six available package-continuity checks passed. UID was unavailable, not inferred. No data clear, uninstall, model import or default change occurred.

The owner requested a discoverable way to delete chats and notes. Chat history rows and saved-chat headers now offer **⋮ → Delete…**. Independent note rows and open-note headers offer the same action; the existing note share/export actions remain. A target-specific confirmation explains permanent deletion and the effect on links and retained quotes. Cancel leaves the item intact. Queued or active answers must be stopped before their chat can be deleted. Files and extracted/source-backed notes are excluded pending `skein-xtov.27.2` (LC-09).

One session coordinator serves both workspaces. Confirmation rechecks the target and eligibility inside the writer transaction, reserves chats against new sends, and pauses/drains note writers. Commit clears the deleted object's session state and matching routes across both workspaces; rollback restores writers. Late loads, streaming events and editor saves cannot recreate the deleted object. Other workspace selections and drafts remain intact. A saved-note route is pruned without mistaking a same-ID New chat draft for the deleted note. The original nine-link root draft remains protected.

The [evidence capsule](../ux/runs/2026-09-29-confirmed-delete-e62f947/README.md) records the exact automated and installation results. Local `ktlintCheck lint check` passed with 5,234 actual XML passes, 86 skips and no failures/errors. Those reports include cached/up-to-date results. Focused regressions had 112 passes. Strict no-build-cache screenshots had 552 unchanged images, 1,333 passes and 80 skips.

| Exact-source remote check | Actual reviewed result |
|---|---|
| [CI 36654289372](https://github.com/HeyZeus100/skein/actions/runs/36654289372) | 5,232 passes, 88 unchanged skips, zero failures/errors; 514 XML files |
| [UX 36654289286](https://github.com/HeyZeus100/skein/actions/runs/36654289286) | 1,333 passes, 80 unchanged skips; 552 unchanged images; fresh execution |
| [Ordinary 36654289463](https://github.com/HeyZeus100/skein/actions/runs/36654289463) | 281 unique passes, no skips/failures/errors; opt-in diagnostic classes absent |
| [Reproducibility 36654289302](https://github.com/HeyZeus100/skein/actions/runs/36654289302) | Downloaded unsigned APK pair and cold native pair byte-equal; native payload agrees with unsigned APK |

CI reused ten unrelated unit-task caches; it is not an all-fresh execution claim. The active-delete test was deliberately replaced with the new active-answer refusal contract. No skip identities were added or removed. Required job steps, actual XML/JSON and artifact hashes were inspected independently; top-level workflow success alone was not used. Tag-only SQLCipher regeneration and the shader negative control were not run. The unsigned release pair is separate from the signed debug APK installed on the Fold.

All seven native entries match the previously installed source030 APK. The actual new APK has no INTERNET permission. Retrieval thresholds, gold labels and screenshot baselines are unchanged. Original style, compile and regression-test failures remain in the owning worktrees, including the failed same-ID draft test that led to the route-pruning fix.

Physical Cancel/Delete verification is pending the owner's normal vault unlock. `skein-57m1` remains in progress until the disposable-only checks are reviewed. No existing owner or conference-demo item may be used for destructive verification. The prior source030 construction answer, model-header and compact-control evidence remains attributed to source030; it is not a new-source repetition.

The [new orchestrator handoff](skein-orchestrator-resume-20260929.md) gives the current ownership, remaining Beads and preservation rules. Full Fold/M0, retrieval/embedding, rendered citations, broad model quality and the larger UX lifecycle waves remain open.
