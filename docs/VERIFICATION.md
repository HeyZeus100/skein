# Verification

Two independent guarantees, both answering "are these the bytes we think
they are", from opposite ends of the build:

1. **[Dependency verification](#dependency-verification-e1i12)** — every
   JAR/AAR the build *consumes* is byte-for-byte the artifact we pinned.
2. **[Build reproducibility](#build-reproducibility)** — the APK the build
   *produces* is a deterministic function of the source, so anyone can
   rebuild a tag and get the same bytes.

Neither subsumes the other: pinned inputs can still feed a build that
embeds a timestamp, and a perfectly reproducible build can faithfully
reproduce a compromised dependency.

## Dependency verification (E1.I12)

Skein uses [Gradle's dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
to make sure every JAR/AAR the build downloads is byte-for-byte the artifact
we intended to depend on — not something a compromised repository, a
man-in-the-middle, or a typosquatted coordinate swapped in instead.

### What it does

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

#### Why SHA-256 only (`<verify-signatures>false</verify-signatures>`)

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

### How to regenerate the metadata (Dependabot / manual dep bumps)

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

### The trust story

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

### Why CI stays at default (no `--verification-mode strict`)

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

### Negative-case verification (done once, for this issue)

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

## Build reproducibility

A tagged Skein release must be a deterministic function of its source: two
people building the same commit with the same pinned toolchain must get a
byte-identical APK. That is what makes "the APK on the release page is
built from the source in this repo" a checkable claim rather than a promise
— see `docs/SIGNING.md` for how signing sits on top of it, and
`docs/PRIVACY.md` for why it matters to a user who cannot audit the binary.

> This section is the current home for the rebuild recipe. `E8.I7` plans a
> standalone `docs/REPRODUCIBLE_BUILDS.md` plus a containerised
> `tools/rb/rebuild-in-docker.sh` for third-party rebuilders (F-Droid,
> IzzyOnDroid); when that lands it should *move* this content rather than
> restate it.

The unit of comparison is the **unsigned** APK,
`app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk`.
Signatures are deliberately out of scope: signing is not reproducible by a
third party (they do not have the key) and does not need to be — a verifier
rebuilds the unsigned APK, compares it against the released APK's contents,
and separately checks the signature against the published certificate.

### The recipe

```bash
git clone https://github.com/<org>/skein && cd skein
git checkout v<version>

export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew clean :app:assembleFossRelease --no-build-cache
```

`--no-build-cache` is not optional when you are checking reproducibility.
Gradle's build cache is keyed on task inputs, so a second build in a tree
that shares a cache with the first will *restore* `:app:minifyFossReleaseWithR8`
and the dex tasks rather than re-run them. The APKs then match because the
outputs were copied, not because they were recomputed — which tells you
nothing about determinism. `.github/workflows/reproducible-build.yml` passes
this flag for the same reason.

### Comparing two APKs

`sha256sum` on the whole file is the contract, but when it fails it tells
you nothing actionable. Use the comparator instead:

```bash
tools/ci/compare-apk-entries.sh path/to/apk-a path/to/apk-b "label"
```

It checks four things, in order, and reports all of them even after one
fails:

| # | Check | Catches |
|---|-------|---------|
| 1 | Entry names in **stored order** | added/removed entries; central-directory reordering (which changes the bytes even when every entry matches) |
| 2 | Per-entry zip metadata | a real wall-clock timestamp leaking in; a compression-level or method change |
| 3 | Per-entry content sha256 | the actual payload diff, named by entry |
| 4 | Whole-file sha256 | the contract itself |

Entries are streamed with `unzip -p` rather than extracted to disk, so two
entries whose names differ only in case are not silently merged on a
case-insensitive filesystem (macOS).

`tools/ci/compare-apk-entries.sh --self-test` exercises the comparator
against archives that differ in content, in membership, and in mtime only,
and asserts it reports each one. CI runs this *before* the comparison, so a
green job cannot be a silently no-op comparison. `diffoscope`, if you have
it, is a strictly better second step once this has named the entries to
look at; it is not required and is not installed on the runner.

### Must-match toolchain

Reproducibility here is *conditional on an identical toolchain*, which is
the normal contract for Android builds — AGP embeds its own version in the
APK, and NDK/JDK codegen is not stable across versions. A verifier must
match:

| Input | Pinned at | Why it matters |
|-------|-----------|----------------|
| **JDK 17** (Temurin in CI) | `.github/workflows/*.yml`, `compileOptions` in `app/build.gradle.kts` | class-file layout, `kotlin_module` contents |
| **Android Gradle Plugin** | `gradle/libs.versions.toml` | literally written into `META-INF/com/android/build/gradle/app-metadata.properties` as `androidGradlePluginVersion` |
| **R8** | ships with AGP | dex layout; see the R8 note below |
| **NDK r27c** (`27.3.13750724`) | `native/sqlite/README.md`, module `ndkVersion` | native codegen for `libskein_sqlite.so` |
| **compileSdk / build-tools** | `app/build.gradle.kts`, CI `sdkmanager` step | `aapt2` resource-table layout in `resources.arsc` |
| Gradle distribution | `gradle/wrapper/gradle-wrapper.properties` | task behavior, zip packaging |

`app-metadata.properties` is the clearest case: it contains
`androidGradlePluginVersion=<version>` and nothing else build-specific. It
is deliberately **not** patched out. It is a toolchain-embedded value, not a
nondeterminism — it is identical for everyone on the pinned AGP and
different for anyone who is not, which is exactly the signal a verifier
wants. Treat a diff in that entry as "you are on the wrong AGP", not as a
reproducibility bug.

### What was fixed, and why (skein-8jtj)

Measured with two clean builds in one checkout plus a third from a separate
clone of the same commit at a different path:

| Result | Finding |
|--------|---------|
| Same path, two clean uncached builds | **716/716 entries identical** with no changes needed |
| Different path, before fixes | exactly **one** entry differed: `META-INF/version-control-info.textproto` |
| Different path, after fixes | **716/716 entries identical** |

Two settings in `app/build.gradle.kts` carry that result:

- **`vcsInfo { include = false }`** (release build type). AGP otherwise
  packages `META-INF/version-control-info.textproto` describing the git
  checkout the build ran in. A git *worktree* yields
  `generate_error_reason: NO_VALID_GIT_FOUND` (42 bytes); a normal clone
  embeds `revision: "<sha>"` (~120 bytes). That makes the APK's hash depend
  on how the tree was obtained rather than on its contents, which defeats
  the entire check. It also changes on every commit and leaks repo metadata
  into a FOSS build.
- **`dependenciesInfo { includeInApk = false; includeInBundle = false }`**.
  AGP writes Play's dependency-metadata blob into the APK Signing Block,
  compressed and encrypted to a Google public key, so it is nondeterministic
  by construction. It is absent from today's APK only because the `release`
  build type has no signing config yet (an unsigned APK has no Signing Block
  at all) — it is disabled pre-emptively so E1.I8's signed pipeline cannot
  silently reintroduce it, and because a FOSS/F-Droid build ships no Play
  metadata regardless.

Everything else already reproduced, including the three points this issue
was opened to re-check:

- **R8 is deterministic.** E1.I11 (skein-4je) turned on `isMinifyEnabled`
  for the release build type, and the concern was that R8 would introduce
  ordering or naming nondeterminism into the dex. It does not: all three
  `classes*.dex` were byte-identical across every pair compared. The scope
  stays stripping-only (`-dontobfuscate`, blanket `-keep`,
  `-assumenosideeffects` on `SkeinLog.d`/`SkeinLog.i`); re-enabling
  obfuscation and real shrinking remains E1.I8's job, together with the
  keep-rule audit that must precede it.
- **R8's side outputs stay out of the APK.** `-printmapping`, `-printusage`
  and `-printconfiguration` write under `app/build/outputs/mapping/` and are
  not packaged. Verified by entry listing, not by assumption.
- **Native libraries are path-independent**, per skein-ej1b:
  `lib/arm64-v8a/*.so` matched across two different absolute build paths.

**DWARF caveat (skein-ej1b).** The `.so`s that ship in the APK are stripped,
and it is the *stripped* libraries this reproducibility claim covers.
Unstripped intermediates under `core/vault/build/intermediates/cxx/` still
embed absolute compilation paths in their DWARF debug info and will *not*
match across build directories. If you are diffing native artifacts by hand,
diff the ones inside the APK (or run `llvm-strip` first); see
`native/sqlite/README.md` § Reproducibility.

### No pinned hash in this document

This section deliberately records **no** expected APK sha256. Such a hash
would be correct for exactly one commit and would rot silently into a value
nobody can reproduce or refute — the worst possible state for a security
document. The reproducibility claim is verified two ways instead, both of
which travel with the code:

- `.github/workflows/reproducible-build.yml` double-builds every `v*` tag
  and fails on any entry-level diff, so the claim is re-tested per release.
- Anyone can run the recipe above against a released tag and compare
  against the published artifact.

A release's actual hashes belong with that release (release notes /
`SHA256SUMS`), not in a checked-in document.
