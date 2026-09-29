# Failed generic foldable run 36554797839

Run: https://github.com/HeyZeus100/skein/actions/runs/36554797839
Source: `69dc5cf8d6ca87414cce1808e6d71bf0c43e3567`.
Requested profile: generic `7.6in Foldable`, compatibility coverage only.

Actual retained XML contains **2 tests, 0 passes, 2 failures, 0 errors, 0 skips**. Both fail at the first Espresso `onDevice()` call with gRPC discovery failure before MainActivity launches. Emulator boot and APK packaging succeeded; no layout acceptance or geometry observation passed.

The unmodified `window-geometry.jsonl.gz` decompresses to `run-as: unknown package: app.skein`, not valid geometry JSON. There are **zero observed geometry records**. The original reviewer failure, malformed geometry file, raw XML, protobufs, emulator configuration/properties, Gradle log, per-test and full logcat, run logs and API snapshots are preserved losslessly. These are disposable emulator records, not physical-device metadata.

`evidence-review-v2.json` is the independent reviewer correction for whitespace in configuration keys. Its original SHA256 is `6bf0f12e1bec14660d6d3a2dcbf6ecc8e89e6e2d58c31da3a52dd062c3974bf5`. The first independent review and its original hash manifest remain present; neither the failed evidence nor original review was rewritten. Artifact bytes and the raw two-case XML were independently rechecked during this copy.

The full immutable artifact `11028107427` remains outside Git: 156,483,165 bytes, SHA256 `a46232cae68190f6a839e47d8ef7a521440836d605d6f9ac6a3a250bcad701c0`, matching GitHub's API digest. Its two APKs are also external. `EXTERNAL_ARTIFACTS.json` contains their exact original paths/sizes/hashes, complete artifact metadata, source/run identity and pinned-source archive references. No APK or JAR and no file above 10MB is included.

Original evidence is unchanged at `/Users/andrewherrera/skein-worktrees/ux-fold-hostdeps-20260929/build/agent-logs/remote-foldable36554797839/36554797839`. `COPY_PROVENANCE.json` maps every copy to original absolute path, size and hash. All raw text/logs/XML/config/source are losslessly gzipped; use `gzip -dc`. The root `SHA256SUMS` covers actual capsule files; verify with `shasum -a 256 -c SHA256SUMS`. Copied original hash manifests refer to original filenames before gzip encoding and may reference the external binaries.

## Proposed geometry transport repair

`geometry-transport-source-review/REVIEW.md` records the exact AGP 9.4.1 / Android test engine 1.0.1 source chain supporting the stable Gradle flag `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. This narrowly keeps target/test APKs installed on the disposable emulator after instrumentation, so the existing post-Gradle private JSONL pull can retain actual observations. It changes neither the five geometry observations nor acceptance thresholds. It does not solve the separate gRPC discovery failure or imply runtime verification of the repair. Source implementation belongs to the fold owner; this capsule made no source edits, builds, dispatches, device calls or Beads changes.

The released physical candidate remains exact source `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`. This failed compatibility run does not close exact Pixel 9 Pro Fold, unlocked A-G, real IME/focus, system_server privacy, or physical Fold acceptance.
