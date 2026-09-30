# Local verification and hosted-run cost policy

The owner selected local checks by default on 30 September 2026 after exhausting
GitHub Actions minutes. All hosted workflow entry points are manual. Pushes,
pull requests, release tags and nightly schedules must not start hosted jobs.
The reusable retrieval workflow is called only by the manual quality workflow.
Agents must obtain an explicit owner request before dispatching a hosted run.
This changes where checks run; it does not turn failures, skips or unrun gates
into passes.

Use the coordinator's serialized build lease, JDK 17, the pinned submodules and
Android SDK, and `--max-workers=2`. Run commands in an isolated worktree. Preserve
owner content, models, drafts, stashes, old worktrees and original evidence.
No self-hosted runner or additional paid service is configured by this change.

## Routine local checks

For a bounded change, run the affected modules' explicit `ktlintCheck`, Kotlin
compilation and meaningful unit tests. Run the applicable tooling suites when
their code or workflow contracts change:

```sh
python3 -m unittest discover -s tools/eval -p 'test_*.py' -v
python3 -m unittest discover -s tools/ci -p 'test_*.py' -v
```

For an integrated application checkpoint, use the broad gate already exercised
locally in this repository:

```sh
./gradlew --no-daemon --max-workers=2 ktlintCheck lint check \
  :app:assembleFossDebug \
  :app:compileDevDebugAndroidTestKotlin \
  :app:compileFossDebugAndroidTestKotlin \
  :core:vault:compileDevDebugAndroidTestKotlin \
  :core:vault:compileFossDebugAndroidTestKotlin \
  :feature:shell:compileDebugAndroidTestKotlin --stacktrace
```

For UI changes or a checkpoint requiring a fresh screenshot comparison:

```sh
./gradlew --no-daemon --max-workers=2 --no-build-cache \
  verifyRoborazziDebug --continue --stacktrace
```

Do not record new goldens to make a verification failure disappear. Never run
`clearRoborazziDebug`: its output directory contains committed goldens. Relevant
opt-in harnesses still require their existing explicit Gradle property and
instrumentation compilation. A compiled test is not an executed Android test.

Retain each command, exit status and original log in a new evidence directory;
do not overwrite an earlier attempt. Inspect actual XML and screenshot JSON,
including failures and skip identities/reasons. Use
`tools/ci/verification-manifest.py` to hash existing raw records and attribute
them to the checkout. Record dirty source separately and distinguish fresh,
cached and up-to-date tasks. A manifest's checkout label is not installed-APK
attestation. Preserve actual APK hashes when a build is part of the gate.

## Runtime and platform boundaries

Local Android emulators can provide encrypted/native runtime evidence without
GitHub-hosted minutes. Select an explicit disposable emulator and use the
existing strict XML reviewers. Do not let an unqualified `adb` command select
the owner's physical Fold. Keep ABI, API level, emulator image, profile and
actual Activity geometry in the receipt: a local arm64 emulator does not prove
the earlier Linux/KVM x86_64 configuration or an exact Fold profile.

Linux dependency resolution, cross-host screenshot parity and independent
reproducibility are separate checks. A cached local build cannot stand in for
the two independent build roots/runners required by the reproducibility gate.
Leave those gates open until measured locally on suitable independent machines
or in an explicitly requested hosted run. Physical installation and acceptance
still require the sole Fold runner, current connection and normal owner unlock.

Local checksum, isolation and license guards remain enabled. They do not claim
to replace GitHub's dependency vulnerability comparison. Commit sign-offs remain
required; the existing DCO checker is available as a manual workflow.

## Explicit hosted checks

All existing check commands, artifact retention, comparison tolerances and
failure criteria remain in their workflows. To request a hosted check, identify
the workflow and exact source/ref and account for its runner time first. DCO
requires a pull-request number; dependency review requires explicit base and
head refs. Reproducibility on a manually selected `v*` release tag also retains its
tag-only SQLCipher regeneration step. No automatic tag build occurs.

The `Fuzz harness (manual placeholder)` workflow still does not run a fuzz
harness and earns no fuzz acceptance. Fresh40 remains consumed regression
evidence; moving its execution environment never makes reused cases blind.

Existing Actions artifacts are preserved. Local evidence avoids new artifact
uploads; this change does not delete historical evidence or claim to reverse
already accrued storage charges. GitHub currently documents hosted minutes and
artifact storage separately, and self-hosted runners as free of Actions-minute
charges: [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).
