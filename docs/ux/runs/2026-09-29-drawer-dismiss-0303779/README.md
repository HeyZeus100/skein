# Open drawer dismissal, 29 September 2026

Application source `0303779fcb14a3ed37b9355d07ad98934e2d12a5` repairs the owner-reported split-view floating drawer that could not be dismissed by tapping outside it. A narrow workspace could open the modal drawer while the outer window used a rail, but dismissal gestures were enabled only for an outer drawer layout. The pinned Material3 implementation gates both scrim taps and swipe dismissal on that flag. The fix enables gestures whenever the drawer is actually open. It keeps closed-edge opening disabled and retains the existing Back handler.

The predecessor app reproduced the defect physically: one tap on empty dimmed space left the drawer open, with byte-identical before/after screenshots. One separately authorized normal Back closed it and revealed the exact original nine-link synthetic draft, with no edits or Sends. Back key dispatch is not proof of predictive-swipe animation behavior. Private screenshots and original failures remain local.

Five added regressions cover real scrim taps, closing swipe from empty sheet space, closed-edge opening suppression, existing Back behavior, and a real split child-menu-to-scrim interaction with unchanged navigation/ownership. The initial swipe test that accidentally selected a drawer item was retained and corrected before judging the intended dismissal failure. Final focused tests have30 passes; full shell tests257 passes and14 existing skips; integrated shell/app checks829 passes and14 skips; explicit lint and both opt-in instrumentation compiles pass. Strict screenshots have552 unchanged images with1,303 passes and80 existing skips. No golden or threshold change was made.

All required remote checks passed for this exact application source. The actual XML, JSON, job steps and downloaded artifact bytes were reviewed:

| Check | Actual result |
|---|---|
| CI [36643196454](https://github.com/HeyZeus100/skein/actions/runs/36643196454) | 5,176 passes, 88 existing skips, zero failures/errors |
| Fresh UX [36643196416](https://github.com/HeyZeus100/skein/actions/runs/36643196416) | 1,303 passes, 80 existing skips, 552 unchanged screenshots |
| Ordinary emulator [36643196118](https://github.com/HeyZeus100/skein/actions/runs/36643196118) | 281 unique passes, zero failures/errors/skips; diagnostics absent |
| Reproducibility [36643196409](https://github.com/HeyZeus100/skein/actions/runs/36643196409) | Actual unsigned APK pair and cold native pair byte-equal; native payload matches the unsigned APK |

CI reused 12 unrelated unit-task caches. All five new touch regressions and seven prior overlay regressions ran freshly in both CI and UX; every prior case was retained. Tag-only SQLCipher regeneration was skipped. These automated checks do not establish physical Fold or answer-quality acceptance.

The signed app is 128,079,415 bytes, SHA-256 `c9b2ffd8ca4306c63c9e7bb3975de4b5e2b6a57a04385c39d4c49eb33f87c14e`. The release manifest is pinned by SHA-256 `5986c1c0bb65018d839c46b7bc14aee38b5a372dd5981f91df91914963e2ba19`. Its updater passed 46 independent host mocks and a dry run with no device access. Only the sole USB runner may execute the one app-only replacement.

The [machine-readable status](status.json) distinguishes candidate identity, automated runs, predecessor observations and installation/physical acceptance. [Review receipts](automated-review-receipts.json) pin the inspected local reviews. Native libraries, model selection, inference and draft storage are unchanged.

The app was installed once at 23:28:18 UTC. Root independently rehashed both preserved predecessor and copied-back replacement APKs. All six package-metadata continuity checks passed; UID was unavailable and is not claimed. Normal launch requested vault biometric unlock, so the dependent control/model rehearsal remains pending. No reimport or data reset occurred.
