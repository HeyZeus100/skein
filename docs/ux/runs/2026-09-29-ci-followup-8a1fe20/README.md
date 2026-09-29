# Failed fold run and Activity observer repair

[Foldable run 36588307563](https://github.com/HeyZeus100/skein/actions/runs/36588307563) failed at source `8a1fe20bd451314596e2f5ea75766f3dc41a64ef`, with the explicit **7.6in Foldable** compatibility profile on API 35. This capsule preserves that failure and the subsequent source repair. It contains no passing emulator claim or candidate release approval.

## Actual failed evidence

The [publication review](foldable-failed-36588307563/publication-review.json) re-parses the uploaded XML and verifies the two expected test identities: **2 tests, 0 passed, 2 failures, 0 errors, 0 skips**, with no missing, unexpected or duplicate cases. Both failed while waiting for CLOSED window geometry before `MainActivity` was launched. The diagnostic `674 × 841 dp` came from `targetContext.resources.configuration`; no Activity viewport was sampled.

The [partial protocol diagnosis](foldable-failed-36588307563/partial-protocol-and-observer-diagnosis.json) and [raw console transcript](foldable-failed-36588307563/artifact/build/foldable-evidence/console-events.jsonl) establish four causal request → console → acknowledgment exchanges: `fold`, `unfold`, `fold`, `unfold`. Run ID, sequence, action and fresh nonce match; each console command returned `0` and `OK`, each acknowledgment was `ok`, and the controller stopped cleanly. Both cleanup unfolds succeeded. These four exchanges do **not** satisfy the required six-exchange test journeys.

There are **zero geometry records**. The preserved [geometry file](foldable-failed-36588307563/artifact/build/foldable-evidence/window-geometry.jsonl) is the original missing-file diagnostic, not valid geometry JSONL. Same-Activity and setup-gate journey assertions were not reached. In the unmodified [independent review](foldable-failed-36588307563/independent-evidence-review.json), the false full-protocol predicates mean the required six-exchange transcript was absent; the separate partial review verifies the four exchanges actually present.

Raw XML, per-test logcat, AVD configuration, state catalog, source marker, workflow review and Gradle log are retained under `foldable-failed-36588307563/artifact/`. The [whole-job log](foldable-failed-36588307563/logs/workflow-job.log.gz), [job steps](foldable-failed-36588307563/api-jobs.json) and [API run projection](foldable-failed-36588307563/api-run-projection.json) retain the failing execution context. The first artifact download timed out; its note and the successful second download's empty stderr remain included. No failed evidence was rewritten.

## Hash and source boundaries

The original artifact ZIP, ID `11042922802`, is **156,440,590 bytes** and SHA-256 `a899038a42f8bdef77687849e649f187886c50b9c35fb07435b48b95fc4e3d7c`. Its API digest and size match and ZIP integrity passed. The run-log ZIP is **106,148 bytes**, SHA-256 `9d97f16be8fbc255677dcff7adb003ee816a7e46918ccae3a9b0defcbc05aaa6`; ZIP integrity passed, but that endpoint supplied no independent API digest. See [archive records](foldable-failed-36588307563/archive-hashes.json) and [raw artifact API metadata](foldable-failed-36588307563/api-artifacts.json).

All six artifact-manifest files were rehashed. Both downloaded APKs and their native ZIP entries were independently checked again during publication:

| Downloaded build output | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-dev-debug.apk` | 171,611,846 | `5c01a4dd9a6980ac1183efd7d1aab9cac5b886daf0235c183aa4fff0ec722cc1` |
| `app-dev-debug-androidTest.apk` | 94,568,933 | `302e9c8d59fc4c02d90fa7e8b381218489b3b2e8b184882a06192f1151720620` |

These are downloaded **host build outputs**, not installed-package byte attestations. Source attribution combines the GitHub API `head_sha` and host-declared checkout marker. APKs, native libraries and original ZIP archives remain outside Git.

## Observer source repair

Commit `cc5ed8cba08d64ec6b2d6ea7e99c0b7372fd5167` launches the real `MainActivity` before the first closed request, retains the scenario through guarded cleanup, and samples its Configuration plus `WindowManager.currentWindowMetrics` on the Activity thread. Every observation checks the original Activity identity. Existing configuration, orientation, setup-gate and secure-window assertions remain; corresponding actual-window conditions are added. Five geometry records and six causal console exchanges are still required. See the [exact patch](activity-observer-cc5ed8cb/observer.patch.gz).

The [source proof](activity-observer-cc5ed8cb/source-proof/REVIEW.json) traces `Instrumentation.getTargetContext()` to the non-UI application context, while Activity context creation supplies Activity-specific resources and window metrics. Five full AOSP source files are retained losslessly with their [pinned URLs and hashes](activity-observer-cc5ed8cb/source-proof/SOURCE.json), at API 35 framework commit `cc8bb19595c661f5cf42f330457000e643c67d1b`. This is a published source-contract proof, **not** an exact CI framework-binary attestation. The distinction is also documented by Android's [visual-context guidance](https://developer.android.com/reference/kotlin/android/content/Context) and [WindowMetrics contract](https://developer.android.com/reference/android/view/WindowMetrics).

The [original red regression](activity-observer-cc5ed8cb/checks/observer-reviewer-red.log.gz) contains six false-acceptance failures before the verifier fix. The [green host log](activity-observer-cc5ed8cb/checks/observer-all-host-green.log.gz) records **38 tests passed, no failures or skips**. These are host verifier/protocol checks, not emulator tests. [Peer review](activity-observer-cc5ed8cb/peer-source-review.json) independently inspected the exact source, five pinned-source hashes and existing 38-pass log; it did not rerun tests or compile Kotlin.

All four repaired source files were byte-compared with root's cherry-pick `0401d15f190ca61f4637965dc9e33245043c8b1a`; they match. [Integration provenance](activity-observer-cc5ed8cb/source-integration-provenance.json) distinguishes that direct comparison from coordinator-reported checks. Root reported another 38-pass host run; its combined Gradle checks were still running when this capsule was assigned, so their outcome is not claimed here.

## Remaining acceptance and publication policy

A fresh root-dispatched run must demonstrate two passing cases, five actual Activity geometry records, six causal exchanges and guarded reset on the repaired source. The compatibility profile does not establish exact Pixel 9 Pro Fold behavior. Real IME, unlocked A–G journeys, physical sensor hinge-angle behavior and physical Fold acceptance remain separate. The observer reads current window bounds, not drawn-frame pixels. Its cooperative wait checks deadlines between synchronous main-thread calls; a hung main thread still depends on the outer workflow watchdog.

Original evidence is preserved outside this capsule. [Copy provenance](COPY_PROVENANCE.json) records raw/member hashes, published hashes and every transformation. Text is copied byte-for-byte or compressed with deterministic lossless gzip, except explicitly recorded local-path replacements in copied local logs/peer review. No APK/SO, broad emulator property dump, full-device logcat or private owner-device metadata is published. Public CI metadata, synthetic emulator IDs and protocol nonces are intentionally retained; GitHub's authorization header in the raw job log is already masked as `***`.

Verify the files from this directory with `shasum -a 256 -c SHA256SUMS`. [Publication checks](PUBLICATION-CHECK.json) record provenance/decompression, local-link, size and publication-boundary checks. The checksum manifest excludes itself.
