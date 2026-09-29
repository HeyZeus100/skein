# Secondary verification at 2e1dcbafd

Reviewed source: `2e1dcbafd645d4bb9348340905949bc5cae90acf`; source epoch `1790679589`.
The released physical candidate remains `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`.
This review does not release a replacement candidate or claim a new ordinary Android run.

| Lane | Verified actual evidence |
|---|---|
| [CI 36559029276](https://github.com/HeyZeus100/skein/actions/runs/36559029276) | 494 XML files, all source-manifest hashes/sizes verified. 5,167 cases: **5,079 passed, 88 skipped, zero failures/errors**. Exact identities and skip identities match prior 69dc; no missing, unexpected or duplicate cases. Both JNI managed-helper regression cases record pass. |
| [Screenshots 36559029446](https://github.com/HeyZeus100/skein/actions/runs/36559029446) | 145 XML files: **1,241 passed, 80 skipped, zero failures/errors**. All **552** individual results are unchanged; all eight summaries match exactly. **705/705** source-manifest hashes/sizes verify. |
| [Reproducibility 36559029462](https://github.com/HeyZeus100/skein/actions/runs/36559029462) | Both actual unsigned release APKs: **100,673,247 bytes**, SHA256 `e8d47da32a79e5ba48593431822e7391cf33bfb12183a54339d31fb60bac7317`. Whole bytes and all **826** ZIP names/order/metadata/payloads are identical. Both embedded llama libraries match the retained cold native pair. |
| Cold native pair | Two actual ELF64 little-endian AArch64 libraries, each **25,314,032 bytes**, SHA256 `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`. Outer **9/9**, inner **6/6** manifest entries independently rehashed. |
| Artifact transport | All six original ZIPs match GitHub API digest, size and source SHA. `artifact-index.json` records exact IDs/digests/paths. |

## Execution and cache attribution

All actual jobs and steps were reviewed; no failures are hidden by workflow success or continue-on-error.
The tag-only SQLCipher regeneration job is skipped. Screenshot failure-summary/diff uploads and
release difference-classification/diffoscope uploads are conditionally skipped. These are not passes.

CI ran `--max-workers=2 ktlintCheck lint check` and `--max-workers=2 assembleFossDebug`, with
successful logs of 1m23s and 2m51s. **28 unit-test tasks were FROM-CACHE** (756 task lines overall,
including compilation/resources/assembly). Retained XML is verified source-bound evidence;
this review does not claim every unit case freshly executed. The 88 skips are the existing
80 screenshot exclusions, four embedding cases, two import contracts, and two `skein-lds9`
CI assumptions. Native guards actually ran: 34 translation units at -O2/-O3, 16KiB alignment,
27 llama and 25 SQLite JNI exports matching declarations, content logging and manifest audit.
The debug artifact is 125,280,302 bytes, SHA256
`38835c37e41e2beaa52e4d018b4ab284c43cea1387923f15ff748ea0542e25a9`; it is not a replacement candidate.

Screenshot verification used `--max-workers=2 --no-build-cache verifyRoborazziDebug --continue
--stacktrace`, succeeded in 5m42s, and has zero FROM-CACHE task lines. The actual goldens-untouched
step passed; baseline size is 35,660KiB.

Native retained logs show real configure/build CMake tasks, successful in 3m42s and 2m36s,
with zero cached tasks. Context records a clean checkout, pinned submodules, source SHA/epoch,
and `max_workers=2`. The retained script hash matches exact source; its Gradle command is
`clean :inference-service:assembleFossRelease --no-build-cache --max-workers=2`.
Default inner logs do not independently echo argv.

Release A/B commands explicitly used `clean :app:assembleFossRelease --no-build-cache
--max-workers=2`; logs succeeded in 10m26s and 7m55s, zero cached task lines. Contexts record
exact source/epoch and different checkout depths. The actual CI comparator agrees with the
independent local comparison.

## Scope

`source-scope-review.json` limits the seven non-documentation changes from released 5ae to
fold workflow/test control, the foldable-only emulatorControl DSL removal, and fold CI helpers.
Production app, feature, core and inference implementation trees are unchanged. The named
source trees and exact diffs are retained. This does not establish cross-source binary equality;
SOURCE_DATE_EPOCH differs, and the new APK hashes differ from earlier-source artifacts.

These are host checkout and build-artifact records, not installed-package attestations. This
review does not supply fresh 281-case ordinary instrumentation, fold emulator/runtime acceptance,
hinge-sensor actuation, physical Fold acceptance, retrieval/embedding readiness, answer quality,
or three consecutive qualifying main reproducibility commits. Those require their own evidence.
No source fix was indicated by these three runs.

## Retention

Original evidence is in the owning `ux-hinge-windows-20260929` worktree under
`build/agent-logs/secondary-followup-2e1dcbaf`.
All API snapshots, original ZIPs, extracted artifacts, full logs, source contexts and review
helpers remain there. `retained-file-hashes.json` inventories original retained files.

The `portable` subdirectory contains raw unit/screenshot archives, final snapshots, review
JSON/scripts, source records and losslessly compressed logs. `COPY_PROVENANCE.json` verifies
each copied/decompressed byte stream against its original. `EXTERNAL_ARTIFACTS.json` records
large ZIP/APK/native members retained outside the capsule. Inner/outer upstream manifests
still describe complete original artifacts; capsule `SHA256SUMS` covers included files only.
Review scripts that require omitted binaries must be run against the original evidence root.
No APK/GGUF/.so or file over 10MB is copied into the capsule. Originals were not changed.
