# Contributing to Skein

Skein welcomes contributions. This document outlines how to participate effectively.

## Getting Started

**Before opening an issue or PR, skim these:**

- `docs/BD_TAXONOMY.md` — issue tracking taxonomy, tier hints, and dispatch workflow
- `docs/ROADMAP.md` — the intended direction and design principles
- `GOVERNANCE.md` — how decisions are made, who decides, and license stability

## Opening Issues

Use **bd** (beads) for all issue tracking — not GitHub's issue tracker. This keeps the source of truth in one place and allows precise dependency tracking across the project.

1. **Install bd:** Download from https://github.com/saulpw/beads/releases. Follow the setup instructions.
2. **Sync the tracker:** Run `python3 tools/bd_bootstrap.py docs/superpowers/plans/2026-09-19-skein-v1-plan.md --apply` to import the v1 plan into `bd` (one-time setup).
3. **Find work:** Run `bd ready` to see ready-to-work issues across all milestones, or `bd ready -l tier:sonnet -l milestone:M3` to filter by tier and milestone.
4. **View details:** Run `bd show skein-xxx` to see the full issue description, acceptance criteria, and metadata.

For new bugs or feature requests not yet in the plan:

1. **Run `bd ready` first** — your idea might already be tracked.
2. **Open a GitHub discussion or issue** with:
   - **Title** — clear, one-liner description
   - **Description** — what is the problem? Why is it important?
   - **Context** — OS, device model (if hardware-specific), app version if a bug, and related issues
3. **Tag the BDFL** (@HeyZeus100) for triage and prioritization. The BDFL will create or link a `bd` issue.

## Submitting Pull Requests

### Before You Code

1. Find or open a `bd` issue and claim it: `bd update skein-xxx --claim`
2. Create a new branch from `main`, naming it after the issue: `git checkout -b skein-xxx-short-description`
3. **Read the issue's acceptance criteria.** These define what done looks like.
4. **Read any referenced design docs** under `docs/design/` if the issue is architectural.

### Code Style

Follow the existing codebase conventions. For Kotlin:

- **Indentation:** 4 spaces
- **Naming:** `camelCase` for functions/variables, `PascalCase` for classes and type aliases
- **Imports:** Organize as (1) Android framework, (2) Kotlin stdlib, (3) third-party, (4) project packages
- **Comments:** No comment explaining *what* the code does (the code should be clear). Comments are for *why* — non-obvious intent, performance rationale, or references to external specs
- **No emojis** in code or commit messages unless already established in the project conventions
- **ktlint compliance:** Run `./gradlew ktlint` locally before pushing. CI will reject non-compliant code

### Testing and Documentation

- **Unit tests:** Write JVM tests in `src/test/kotlin/` for all business logic (no Android runtime needed). See `docs/TESTING.md` for the three test lanes and shared fake utilities.
- **Compose UI tests:** Use Robolectric-hosted tests in the Android module's `src/test/kotlin/` directory.
- **Instrumented tests:** Reserved for JNI, real `WorkManager`, or `DocumentsProvider` behavior. These run nightly, not on every PR.
- **Documentation:** Update `README.md`, design docs under `docs/design/`, or user-facing docs if your change affects how the system behaves.

### Developer Certificate of Origin (DCO)

All commits must be signed off using the Developer Certificate of Origin v1.1. This certifies that you have the right to contribute your code under the Apache 2.0 license.

**Sign your commits:**

```bash
git commit -s -m "Your commit message"
```

The `-s` flag appends `Signed-off-by: Your Name <your.email@example.com>` automatically. A required status check (`dco`) will block PRs without proper sign-offs.

**If you forgot to sign off:**

```bash
# Amend the last commit
git commit --amend --no-edit -s

# For multiple unsigned commits, rebase and sign all of them
git rebase -i main --exec 'git commit --amend --no-edit -s' 
git push --force-with-lease
```

See https://developercertificate.org/ for the full text.

### PR Checklist

Before marking your PR ready for review:

- [ ] All commits signed off with `-s`
- [ ] `bd` issue ID in PR title or description (e.g., `Fix for skein-xxx: …`)
- [ ] Spec or plan reference included if applicable (e.g., `E4.I1`, relating to the M1 plan)
- [ ] New code passes all three test lanes:
  - `./gradlew test` (JVM unit tests)
  - `./gradlew testDevDebugUnitTest` (Robolectric Android tests)
  - Instrumented tests if the change touches JNI, `WorkManager`, or cross-app behavior
- [ ] `./gradlew ktlint` passes; no linting errors
- [ ] `docs/` updated if the change affects build, setup, testing, or user-visible behavior
- [ ] **No new manifest permissions added** — especially no `INTERNET`, `QUERY_ALL_PACKAGES`, or other risk-sensitive permissions without explicit BDFL approval
- [ ] No telemetry, analytics, crash reporting, or other data collection added
- [ ] No new dependency on Google Play Services
- [ ] No new dependency with a restrictive license (only Apache 2.0, MIT, or equivalent permissive licenses in the `foss` flavor)

### What Makes a PR Mergeable

1. **All CI checks pass** — tests, ktlint, guards, reproducible-build verification
2. **Human review approved** — the BDFL or designated reviewer approves the change
3. **Acceptance criteria met** — your PR solves the issue as described
4. **DCO signed** — all commits have `Signed-off-by` lines

In year 1, the BDFL (Andrew Herrera) has final approval authority. See `GOVERNANCE.md` for the decision-making process.

## Non-Negotiable Design Principles

Skein's security and privacy model is load-bearing. Every PR must preserve these:

1. **No `INTERNET` permission** in the manifest, verifiable via GrapheneOS's per-app network isolation
2. **No Google Play Services dependency**
3. **No telemetry, crash reporting, or analytics** — not even opt-in
4. **No comments or code explaining what it does** — write self-documenting code

These are not style preferences — they are core to Skein's identity. Any PR violating them will be rejected, regardless of other merits. If you believe a temporary exception is justified, open an issue with the BDFL first.

## Security-Sensitive Contributions

Changes affecting Skein's threat model, encryption, key management, isolation, or network behavior require extra review.

1. **Before you code**, open a GitHub issue or discussion and tag the BDFL (@HeyZeus100) to discuss the approach.
2. **Add a comment** to your PR explaining the security impact: *Why is this change safe? What threat does it address or mitigate?*
3. **Expect a more thorough code review.** Security changes may require multiple rounds and third-party audit.

If you discover a security vulnerability, do **not** open a public issue. Email `andrew@aherrera.us` with details. See `SECURITY.md` for the full responsible-disclosure policy.

## Local Development Setup

### Prerequisites

- **Java Development Kit (JDK):** Version 17 or later (Java 21 works but is not required)
- **Android SDK:** API level 34 (Android 14 — the minimum build target)
- **Gradle:** The project uses the Gradle wrapper; you do not need to install Gradle separately
- **Git:** For cloning and pushing changes

### Setup Steps

1. **Clone the repository:**
   ```bash
   git clone https://github.com/HeyZeus100/skein.git
   cd skein
   ```

2. **Verify the JDK:**
   ```bash
   java -version
   # Should output Java 17+
   ```

3. **Build and run tests:**
   ```bash
   ./gradlew build          # Full build + all tests (JVM + Robolectric)
   ./gradlew test           # JVM unit tests only
   ./gradlew testDevDebugUnitTest  # Android + Robolectric tests
   ```

4. **Run ktlint:**
   ```bash
   ./gradlew ktlint
   ```

5. **Build the debug APK** (for testing on device):
   ```bash
   ./gradlew assembleDevDebug  # or assembleFossDebug for the FOSS flavor
   ```

6. **Install to a connected device:**
   ```bash
   adb install app/build/outputs/apk/dev/debug/app-dev-debug.apk
   ```

### Device Testing

Skein is designed for GrapheneOS on Pixel 9 Pro Fold. If you have access to the device, you can:

1. Install the debug APK (see above)
2. Run instrumented tests (reserved for hardware-specific behavior):
   ```bash
   ./gradlew connectedDevDebugAndroidTest
   ```

Instrumented tests are gated behind a nightly CI run (`.github/workflows/emulator.yml`) and are not run on every PR because device booting is slow. For reference, `docs/TESTING.md` has detailed test-lane documentation.

## Questions?

- **Setup or build issues:** Open a GitHub issue or discussion and tag @HeyZeus100.
- **Design questions:** See `docs/ROADMAP.md` and `docs/design/` for architectural context.
- **Issue tracking:** Read `docs/BD_TAXONOMY.md` for the tier model, priorities, and dispatch workflow.
- **Security or governance:** See `GOVERNANCE.md` and `SECURITY.md`.

Welcome aboard!
