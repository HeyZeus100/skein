# Secondary CI-only follow-up review: 69dc5cf8

Reviewed source: `69dc5cf8d6ca87414cce1808e6d71bf0c43e3567` on main. Released app identity remains `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`; no replacement APK release is implied.

- CI: https://github.com/HeyZeus100/skein/actions/runs/36554757098
- Screenshots: https://github.com/HeyZeus100/skein/actions/runs/36554757109
- Reproducibility: https://github.com/HeyZeus100/skein/actions/runs/36554757065

All three completed successfully, with the underlying evidence checked below.

## Actual evidence

| Lane | Independently reviewed result |
|---|---|
| Unit XML | 494 files; 5,167 cases = 5,079 pass, 88 skipped, zero failures/errors. All manifest bytes/hashes and file coverage verified. Both LlamaNativeSurfaceTest flavor cases record pass. |
| Screenshots | 705/705 manifest records verified; 145 XML files, 1,321 cases = 1,241 pass, 80 skipped, zero failures/errors. All 552 individual Roborazzi results are unchanged; eight summaries exactly match individuals. Goldens-untouched check succeeded; baseline budget 35,660 KiB. |
| Native cold pair | Two real ELF64 little-endian AArch64 libraries, each 25,314,032 bytes, SHA256 `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`. Inner 6/6 and outer 9/9 manifest entries independently rehashed. Both retained build logs successful (3m11s / 2m), no FROM-CACHE tasks. |
| Release pair | Each unsigned APK is 100,673,247 bytes, SHA256 `14d0184d7bf029b219261440eed47eb954d0a5d0c907e67ddbbfe305e12cf2cd`. Independent local comparison and actual CI comparator agree: 826 names/order, entry metadata, all contents and whole-file bytes identical. Both embedded llama libraries match the cold pair. |
| Artifact transport | All six immutable artifact ZIPs match GitHub API sizes, digests and source SHA. Exact IDs, raw archive hashes and original paths are in `artifact-index.json`. |

Ordinary CI used `--max-workers=2 ktlintCheck lint check` and `--max-workers=2 assembleFossDebug`. Its check/assemble logs record success in 1m33s/3m02s. Native guards inspected 34 llama/JNI translation units at -O2/-O3, 16KiB PT_LOAD alignment, 27 llama exports and 25 SQLite JNI exports matching declarations; content logging and manifest audits pass. These native guards actually ran.

The unit task logs include **28 FROM-CACHE task lines**, including both app and inference-service flavors. The 5,079/88 counts and host-native guard cases are verified retained XML results; this review does not claim every unit case freshly executed on 69dc. The 88 skips remain 80 screenshot exclusions, four embedding cases, two vault-import contract cases, and two existing `skein-lds9` CI retry assumptions. The separate screenshot lane used `--max-workers=2 --no-build-cache verifyRoborazziDebug --continue --stacktrace`; its 80 skipped cases remain skipped.

Both release build commands explicitly used `clean :app:assembleFossRelease --no-build-cache --max-workers=2`, successful in 10m06s and 10m32s; no FROM-CACHE tasks. Source contexts show distinct checkout depths, the exact source SHA and SOURCE_DATE_EPOCH `1790677097`. Native context records the same source/epoch, clean status and pinned submodules. The retained exact cold-build script SHA matches source and its per-build Gradle invocation has `--max-workers=2`; default native Gradle logs do not independently echo argv. The tag-only SQLCipher job and conditional diff/classification/upload steps were skipped as recorded in `final-run-review.json`.

## Equality and source limits

`source-identity.json` verifies that the only changes from 5ae to 69dc are `.github/workflows/foldable.yml` and `tools/ci/test_foldable_workflow.py`. Application/native/configuration tree objects are unchanged. This establishes source equivalence for those trees, **not cross-source binary equality**.

The 69dc release APK is not byte-equal to the 5ae release APK (`d397160b48a73e9265b65d2ce4ea10ba1315bdd0bbd7f149d74dbaa97027f78f`). Cross-source inspection found identical 826 names/order and 825 uncompressed payloads; only `lib/arm64-v8a/libskein_sqlite.so` differs. Both SQLite members are 6,249,008 bytes: old SHA256 `9d0bad51b00f1600bb8817e10a08f0a08199d0bde86f408001ef149fed224a3d`, new SHA256 `c66dd65963887e491b357ba2e597cd77db7596e380d635a8203c020dd567bbac`. Their only printable string-set difference is `built on: Tue Sep 29 09:46:03 2026 UTC` versus `built on: Tue Sep 29 10:18:17 2026 UTC`, matching the respective source epochs. This observation does not establish that all machine-code differences are explained by that string. The contract evaluated here is A/B equality within exact source/epoch; that contract passed. No deeper source investigation was performed.

The ordinary CI debug APK is 125,280,302 bytes, SHA256 `3317d776207db71f3fcd924415f172461cb0e0a8c62ff24ba39271ff187d14ff`, distinct from the earlier debug artifact. It is not a replacement candidate.

These are host-declared checkout and artifact records, not installed APK attestation. This review does not close inference/retrieval/embedding runtime gates, establish three consecutive main reproducibility runs, convert skips into passes or review foldable run `36554797839` (separate owner). The earlier released-source CI/repro evidence remains under `docs/ux/runs/2026-09-29-integration-5ae91c2/remote-ci-repro-5ae91c2`; it is not duplicated here.

## Retention

Original evidence root: `/Users/andrewherrera/skein-worktrees/ux-rb-evidence-20260929/build/agent-logs/secondary-followup-69dc5cf8`.

The portable copy includes both original small raw evidence archives (unit XML/reports: 2,519,442 bytes; screenshot XML/JSON: 441,800 bytes), source contexts, exact metadata/API digests, final API snapshots, checks, review helpers and losslessly gzipped logs/large JSON. `COPY_PROVENANCE.json` maps copies to original absolute paths, sizes and hashes. `EXTERNAL_ARTIFACTS.json` records all retained large archives, APKs and native libraries outside Git. The auxiliary binary-difference JSON is retained losslessly gzipped. No APK/GGUF/.so or file over 10MB is included.

Copied inner/outer manifests describe complete original artifacts, including external binary members. The root `SHA256SUMS` covers the files actually included in the portable bundle; verify with `shasum -a 256 -c SHA256SUMS`. Read gzip files with `gzip -dc`. Originals were not altered. No builds, dispatches, source changes, device actions or Beads operations were performed for this review.
