# Dependency verification (E1.I12)

Skein uses [Gradle's dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
to make sure every JAR/AAR the build downloads is byte-for-byte the artifact
we intended to depend on — not something a compromised repository, a
man-in-the-middle, or a typosquatted coordinate swapped in instead.

## What it does

`gradle/verification-metadata.xml` records a SHA-256 checksum for every
artifact (jar, aar, pom, module metadata) Gradle has ever resolved for this
project — one `<component>` entry per group:module:version, one `<artifact>`
child per file. On every subsequent build, before Gradle uses a downloaded
(or cache-hit) artifact, it hashes the file and compares it against the
recorded checksum:

- **Match** → the build proceeds normally.
- **Mismatch** → the build fails immediately, before any of that artifact's
  bytecode is compiled against or run, with a `Dependency verification
  failed` error naming the exact component and both hashes.
- **No entry for a resolved artifact** → the build also fails (missing
  checksums are treated as a verification failure, not silently skipped).

This is configured via `<verify-metadata>true</verify-metadata>` at the top
of the metadata file. No CI flag or Gradle property is required to turn
enforcement on — the file's presence *is* the enforcement. See "Why CI stays
at default" below.

### Why SHA-256 only (`<verify-signatures>false</verify-signatures>`)

Gradle also supports PGP signature verification per artifact. We deliberately
leave it off:

- Not every artifact this project depends on is PGP-signed (many AndroidX
  and third-party transitive dependencies simply have no `.asc` file
  published), so turning it on would mean either failing the build on
  unsigned-but-legitimate artifacts or peppering the metadata with `<trust>`
  exceptions that erode the guarantee.
- SHA-256 alone already defeats the threat this issue mitigates: a
  compromised repository or a MITM would have to reproduce the *exact*
  checksum we pinned, which requires serving the *exact* artifact we
  resolved originally. Swapping in malicious bytes changes the hash and the
  build fails.
- If a future issue wants stronger provenance (e.g., verifying Kotlin/
  AndroidX/kotlinx maintainer signatures specifically), that can be added
  incrementally as `<trusted-key>` entries scoped to those groups, without
  turning on blanket signature verification.

## How to regenerate the metadata (Dependabot / manual dep bumps)

**Every dependency version bump changes at least one checksum.** The
verification file must be regenerated in the *same PR* that bumps the
version — a bump that lands without a metadata update will fail every build
after it (the old checksum won't match the new artifact). This includes
Dependabot PRs.

1. Let the version bump land in `gradle/libs.versions.toml` (or wherever the
   coordinate is declared) first.
2. Regenerate the metadata from a clean, online resolve:

   ```bash
   rm -f gradle/verification-metadata.xml
   ./gradlew --write-verification-metadata sha256 check lint assembleFossDebug assembleDevDebug assembleFossRelease
   ```

   Running against `check`, `lint`, and both flavors' `assemble*` tasks (not
   just `help` or a single module) matters: Gradle only records checksums
   for artifacts actually *resolved* during that invocation, and different
   tasks resolve different configurations (`testImplementation`,
   `androidTestImplementation`, release vs. debug classpaths, lint's own
   tool classpath, etc.). Regenerating from a narrow task list silently
   produces an incomplete file that fails the next full build with "no
   checksum on record" for whatever configuration you didn't touch.
3. Re-add the header block if the regeneration tool ever emits a fresh file
   without it (it shouldn't, but verify):

   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <verification-metadata>
      <configuration>
         <verify-metadata>true</verify-metadata>
         <verify-signatures>false</verify-signatures>
      </configuration>
      <components>
         ...
      </components>
   </verification-metadata>
   ```

4. Diff the file before committing. For a routine single-dependency bump you
   should see one component's version (and its checksums) change, plus
   whatever new transitive versions came along with it — not a wholesale
   rewrite. A much larger diff than expected is a signal something else
   drifted (a plugin version, a transitive resolution change) and is worth a
   second look before committing.
5. Run `./gradlew --refresh-dependencies :app:assembleFossDebug` and
   `./gradlew :app:check` once more locally to confirm the regenerated file
   verifies cleanly, then commit `gradle/verification-metadata.xml` alongside
   the version bump.

**For Dependabot specifically:** Dependabot cannot run `--write-verification-
metadata` itself, so every Dependabot PR that bumps a Gradle coordinate will
fail CI on the stale-checksum error until a human (or an agent) checks out
the branch, regenerates the file per the steps above, and pushes the update
to the PR branch. Treat "Dependabot PR is red" as the expected first state
for a dependency bump, not a sign something else is wrong — check whether
it's *this* failure mode before investigating further.

## The trust story

What this buys us: if `mavenCentral()` or `google()` (or a CDN in front of
them) were compromised or MITM'd after this metadata was generated, or if a
dependency coordinate were silently repointed to different bytes, the build
fails loudly on the very next resolve instead of silently compiling and
shipping different code than what was reviewed and pinned.

What this does **not** buy us:
- It does not verify the artifact was built from the source it claims to be
  built from (that's what reproducible builds / Sigstore-style provenance
  cover — see the R9 risk entry in the project plan). It verifies the bytes
  we're pinned to today stay the bytes we get tomorrow.
- It does not protect against a version bump that legitimately introduces
  malicious code upstream — regenerating the metadata after a bump re-pins
  to whatever the bumped version actually is. Reviewing the diff of a
  version bump (changelog, release notes, unexpected new transitive deps)
  remains a human/reviewer responsibility; this file only pins what was
  already decided to be trusted.
- It is a supply-chain-integrity control, not a license or vulnerability
  scanner — those are separate gates (`licenseAudit`,
  `.github/workflows/dependency-review.yml`).

## Why CI stays at default (no `--verification-mode strict`)

`--dependency-verification=strict` on the command line does not add
anything CI doesn't already get for free: once `verify-metadata=true` is set
in the metadata file (as it is here), Gradle already fails the build on any
checksum mismatch or missing entry — that is the default enforcement
behavior, not an opt-in. The `--dependency-verification` flag exists to
*loosen* that (`lenient` logs warnings instead of failing; `off` disables
verification entirely), not to make default-mode stricter than it already
is.

Explicitly passing `strict` on every CI invocation was considered and
rejected: it adds a command-line flag that must be remembered on every
`ci.yml` step invoking Gradle, buys no additional enforcement over the
file-driven default, and increases friction on exactly the workflow this
file makes routine — Dependabot version-bump PRs — where a missing flag on
one step would silently under-verify rather than the visible, actionable
"stale checksum" failure the default already gives us. CI enforces
verification simply by resolving dependencies at all with the metadata file
present in the tree; no extra flag is needed or added.

## Negative-case verification (done once, for this issue)

To confirm the mechanism actually fails closed, a single checksum byte in
`gradle/verification-metadata.xml` was deliberately corrupted (one hex digit
flipped in a `sha256` `<artifact>` entry already exercised by
`:app:assembleFossDebug`), and `./gradlew --refresh-dependencies
:app:assembleFossDebug` was re-run. The build failed with Gradle's
`Dependency verification failed` error naming that exact component and
showing the expected vs. actual checksum, before any compilation ran. The
file was then reverted (`git checkout -- gradle/verification-metadata.xml`)
and the build re-verified green. See the issue close notes on `skein-9qb`
for the exact component used.
