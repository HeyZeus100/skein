# Ordinary instrumentation evidence at 28354dd2

[Run 36592802616](https://github.com/HeyZeus100/skein/actions/runs/36592802616) completed successfully at source `28354dd25bb4995930ff46a71752f43f8a0d5df3`. Independent parsing of the three uploaded XML files confirms **281 passed, zero failures, errors or skips**. All 281 expected `(module, class, test)` identities match the prior passing reference, with no missing, unexpected or duplicate cases.

| Module | Actual XML passes |
| --- | ---: |
| App | 38 |
| Core vault | 204 |
| Inference service | 39 |

The [publication review](ordinary-passed-36592802616/publication-review.json) checks all 19 previously failing JNI cases and all seven critical cases, including `tokenizeReturnsAtLeastTwoIds`; every case passed. The seven critical cases overlap the prior-19 group and are not additional tests beyond 281. Exact identities, original XML, complete [independent review](ordinary-passed-36592802616/independent-evidence-review.json.gz), and prior-run identity references are included.

The verification manifest contains exactly **four current files**: the three XML reports and `build/instrumentation-review.json`. All four sizes and SHA-256 hashes were independently matched. There are no historical `docs/` XML paths, missing files or extra manifest entries. The [raw manifest](ordinary-passed-36592802616/artifact/build/instrumentation-verification.json) also preserves its source-attribution boundary.

The [job API](ordinary-passed-36592802616/api-jobs.json) and [whole-job log](ordinary-passed-36592802616/logs/workflow-job.log.gz) show a successful ordinary API 35 x86_64 job, including XML review and manifest collection. The executed Gradle command used `--max-workers=2 connectedDevDebugAndroidTest --continue --stacktrace`. This capsule does not equate the workflow conclusion with acceptance; the actual XML and current manifest were reviewed separately.

## Archive and APK boundaries

Artifact `11045810586`, `connected-test-reports`, is **1,089,891 bytes** with SHA-256 `b75723b16fc310ae9b9c7b78c01ff2780876905834069cce78ff506f5d3830a3`. Its [API digest and size](ordinary-passed-36592802616/api-artifacts.json) match the downloaded ZIP, and ZIP integrity passed. The raw run-log ZIP is **85,960 bytes**, SHA-256 `d06fd32bb07497746d1c7ff3fd0c8042bf250ce08bfddcd301f07689c1cb37fc`; integrity passed, but that endpoint supplied no independent API digest. Original ZIP archives remain outside Git.

The archive contains **no APK bytes**. These four values are host build declarations only, without an independent APK rehash or installed-package attestation:

| Declared build output | Declared SHA-256 |
| --- | --- |
| App test APK | `c238824900ee4e5da42f36db4af784f2c5f0c701f90ff88aee65faa75f6db6b1` |
| App APK | `76d0f6938bc597718f561232c7f40745b2157f62a693978726b3d672d3907197` |
| Vault test APK | `27ae586bbb81be99b4fc41b811a1447df9f8236f79f87c3814ba9a42908a8362` |
| Inference service test APK | `792aa79711d904fced350880041add7ac1bbb0cffd74eacd332c91e6569c7d1d` |

Source attribution is the GitHub run/job API SHA plus the host-declared checkout in the verification manifest. The exact workflow, reviewer and manifest collector source are preserved with [source hashes](ordinary-passed-36592802616/source/SOURCE-FILES.json).

## Coordinator host checks

The coordinator's [complete host log](root-host-checks/fold-observer-root-38-complete-host.log.gz) contains **38 named passes** and `Ran 38 tests ... OK`, including six verification-manifest tests. Its original 6,853 bytes have SHA-256 `c82916c72723e586ef025380ea0e42bcfa54d4f16c995c5c42af1feb40e9758a`.

All earlier partial logs remain preserved: nine tests in the first summary-only log, 28 named tests in `root-all-host`, and 32 named tests in the misleadingly named `root-38-host` file. Only `root-38-complete-host` records the full 38. See the [host-log review](root-host-checks/review.json). These local logs contain no embedded checkout SHA: their integration source attribution comes from the coordinator, while their bytes and actual pass lines were independently inspected here. They were not rerun by the capsule author and are distinct from the 281 Android instrumentation tests.

## Scope and verification

This is ordinary instrumentation evidence. Opt-in retrieval and foldable cases are excluded. It does not satisfy generic-fold or exact Pixel 9 Pro Fold geometry, real IME, unlocked A–G journeys, physical Fold, retrieval-quality/embedding, or candidate release gates.

[Copy provenance](COPY_PROVENANCE.json) records original bytes, published hashes and lossless compression. Original raw evidence was preserved unchanged. No APK/native-library binaries, full-device logcat, broad emulator properties or private owner-device metadata are included. Public CI metadata and synthetic test identities are retained; the raw workflow's GitHub authorization values were already masked as `***`.

Run `shasum -a 256 -c SHA256SUMS` from this directory. [Publication checks](PUBLICATION-CHECK.json) cover copied/decompressed hashes, local links, size and publication boundaries. The manifest excludes itself.
