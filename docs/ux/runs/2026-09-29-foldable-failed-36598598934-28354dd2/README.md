# Failed Activity-observed foldable gate

[Run 36598598934](https://github.com/HeyZeus100/skein/actions/runs/36598598934) failed at exact source `28354dd25bb4995930ff46a71752f43f8a0d5df3`, using the explicitly selected **7.6in Foldable** generic compatibility profile on API 35. Independent inspection confirms **two expected test cases, two failures, zero errors or skips**, with no missing, unexpected or duplicate identities. AL-16 remains open; no retry, profile change or threshold relaxation is represented here.

## Actual failure and partial progress

Both cases fail at `FoldableDeviceControl.kt:97` while waiting for CLOSED **Activity** window geometry. The raw XML records Configuration `674 × 841 dp`, density `420 dpi`, and actual `WindowManager.currentWindowMetrics` bounds `Rect(0, 0 - 1768, 2208)`. Those bounds correspond to approximately **673.52 × 841.14 dp**. The distinct Activity identities were `169782737` and `78371802`, one per test. The compact condition requires both Configuration and actual-window width below 600 dp; neither passed.

This differs from the earlier `36588307563` failure: the current source launches `MainActivity` first and samples its window on the Activity thread, checking its original identity at each observation. The failure now establishes that the **live Activity** retained full-width bounds. It does not establish a successful fold journey. See the [failure diagnosis](partial-failure-diagnosis.json), [source hashes](source/SOURCE-FILES.json), and original XML under `artifact/app/build/outputs/androidTest-results/connected/`.

The [console transcript](artifact/build/foldable-evidence/console-events.jsonl) contains four causal exchanges: `fold`, `unfold`, `fold`, `unfold`, sequences 1–4. Each command returned `0` with `OK`, with matching run ID, sequence, fresh nonce, action and `ok` acknowledgment. The controller stopped cleanly. Both cleanup unfolds succeeded; the XML records one primary geometry failure per case and no separate cleanup failure. The source's `@After` runs flat reset, rotation unfreeze and secure setup-gate/original-Activity checks, but this is not independent visual/security attestation or complete journey acceptance.

There are **zero accepted geometry JSONL records**, and the required six-exchange journey transcript is absent. The [geometry file](artifact/build/foldable-evidence/window-geometry.jsonl) preserves the original missing-file diagnostic. The [full-protocol review](activity-protocol-review.json) correctly fails the six-exchange/five-record requirement; the partial diagnosis separately validates the four exchanges present. All original failure bytes remain unchanged.

## What the selected AVD declares

The [actual AVD config](artifact/build/foldable-evidence/avd-config.ini) declares:

- Inner display: `1768 × 2208 px`, `420 dpi`.
- Folded display region `0.1`: `884 × 2208 px`, offset `(0, 0)`, about `336.76 × 841.14 dp`.
- `hw.sensor.hinge.fold_to_displayRegion.0.1_at_posture=1`, with one hinge sensor.

The preserved runtime catalog names state 1 `CLOSED`, state 2 `HALF_OPENED`, and state 3 `OPENED`. Eight unmodified [LogicalDisplayMapper log lines](logs/display-transitions-excerpt.txt.gz) during the tests show transitions `3 → 1 → 3 → 1 → 3`, each applying the same single-display layout. Their original full-log hash and exact line numbers are recorded in the diagnosis. The full global log is retained outside Git.

A compact folded region is therefore **declared**, while effective Activity resizing is **not observed**. These bytes do not isolate which emulator, system-image or configuration policy caused that mismatch, establish a production app defect, or justify weakening acceptance. This generic profile does not prove exact Pixel 9 Pro Fold behavior or physical hinge-sensor behavior.

## Artifact and source verification

Artifact `11048452612` is **156,552,344 bytes**, SHA-256 `d4c8bbd02f2e93296a645f0b2ec823f0bc155983fbac31de631d71299c563eda`. The [API digest/size](api-artifacts.json) matches the downloaded ZIP and ZIP integrity passed. The run-log ZIP is **107,499 bytes**, SHA-256 `aec0ba08b2976ec890c94f5eb6df365dc9789c5e084111310582419946ce5a28`; integrity passed, with no separate API digest available. See [archive hashes](archive-hashes.json), [job execution](api-jobs.json) and the [whole-job log](logs/workflow-job.log.gz).

All six workflow-manifest entries were rehashed and sized. Both uploaded APKs and their native ZIP entries were independently checked:

| Host build output | Bytes | SHA-256 |
| --- | ---: | --- |
| App APK | 171,761,414 | `745ffa55c99e99a6c8f064cf28beac3f322143c8b24dfb2edd6b926d7128f267` |
| App test APK | 94,575,945 | `d9bf3e668a194dec44f834e2c8d4df351ef8ac2013116b3938d1d4720d9c2077` |

These are downloaded build-output bytes, **not installed-package byte attestations**. Source attribution combines GitHub run/job API SHA and the host-declared checkout. The [publication review](publication-review.json) and [full independent review](independent-evidence-review.json.gz) retain exact hashes and limits. APKs, native libraries and original ZIPs remain outside Git.

The ordinary 281-test pass at this SHA is separate evidence. Generic fold geometry, exact Pixel 9 profile acceptance, unlocked A–G, real IME/focus, physical Fold and retrieval-quality/embedding gates are not satisfied by this failed lane. Any physical demo authorization and owner unlock are outside this CI capsule.

[Copy provenance](COPY_PROVENANCE.json) records original/member hashes and lossless gzip transforms. No broad emulator property dump, full-device logcat or private owner-device metadata is included. Public CI metadata, synthetic emulator identifiers and protocol nonces are retained; GitHub authorization values in the raw job log were already masked. Run `shasum -a 256 -c SHA256SUMS` here; [publication checks](PUBLICATION-CHECK.json) also verify decompressed hashes, local links and publication boundaries.
