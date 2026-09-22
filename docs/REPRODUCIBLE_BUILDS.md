# Reproducible builds

> **This document is a stub with one complete section.**
>
> The full third-party rebuild guide — the containerised
> `tools/rb/rebuild-in-docker.sh`, the pinned Dockerfile, the narrative
> "what we had to fix" history, and the migration of
> `docs/VERIFICATION.md` § "Build reproducibility" into this file — is
> **`E8.I7` (bd `skein-6x6`)** and has not been written yet.
>
> What is here now is the section `E1.I8` (bd `skein-ddp`) owes: how to use
> `tools/rb/verify.sh`. Everything else below is a pointer to where that
> information currently lives. Do not treat the absence of a section here as
> the absence of the information.

| You want | Read |
|----------|------|
| How to verify a published release yourself | **[Verifying a release](#verifying-a-release)**, below |
| Why the unsigned APK is the unit of comparison, the must-match toolchain table, the R8/native/DWARF notes, the list of nondeterminism sources already fixed | `docs/VERIFICATION.md` § "Build reproducibility" |
| The exact pinned toolchain, as machine-readable data | `reproducible-builds.yml` (repo root) |
| How signing sits on top of reproducibility, and the release certificate | `docs/SIGNING.md` |
| What CI checks, and when | `.github/workflows/reproducible-build.yml` |
| How to report a mismatch | `SECURITY.md` |

---

## Verifying a release

The claim Skein makes is narrow and checkable: **the unsigned APK is a
deterministic function of the source.** Two people who check out the same tag
with the same pinned toolchain get byte-identical
`app-foss-release-unsigned.apk`. Signing is deliberately *not* part of that
claim — you cannot reproduce a signature without the key, and you do not need
to. You rebuild the unsigned APK, check it matches the published APK's
payload, and check the *signature* separately against the certificate in
`docs/SIGNING.md`.

`tools/rb/verify.sh` does both halves and keeps them clearly apart.

### One command

```bash
git clone --recursive https://github.com/<org>/skein && cd skein

export JAVA_HOME=/path/to/temurin-17.0.20+8
export ANDROID_HOME=/path/to/android-sdk

tools/rb/verify.sh ~/Downloads/skein-v1.0.0.apk v1.0.0
```

It will:

1. **Check your toolchain** against `reproducible-builds.yml` — JDK vendor
   and version, NDK, and that the manifest itself has not drifted from the
   tree. This runs *first*, because a toolchain mismatch explains almost
   every "it didn't reproduce" report and the build takes the better part of
   an hour.
2. **Print the published APK's certificates** (`apksigner verify
   --print-certs`). Compare these fingerprints against `docs/SIGNING.md`
   yourself. The script reports them; it never folds them into its verdict,
   because "this was built from the source" and "this was signed by the key
   you expect" are two different claims and conflating them is how people get
   fooled.
3. **Clone the repo fresh**, at that tag, with submodules, into a temporary
   directory — *not* into your working copy. Building in place would hide a
   path-dependent difference, which is exactly the failure mode that bit this
   project once already.
4. **Derive `SOURCE_DATE_EPOCH`** from the tag's committer date
   (`tools/rb/source-date-epoch.sh`), so your build and CI's agree.
5. **Build** `clean :app:assembleFossRelease --no-build-cache`.
6. **Strip the APK Signing Block** from the published APK
   (`tools/rb/strip-signature.py`) so a signed release can be compared with a
   locally built unsigned one.
7. **Compare** — see below for what "match" means.

### What counts as a match

It depends on whether the APK you downloaded was signed, and the difference
is not a technicality.

**A published, signed release is compared entry by entry.** `apksigner` does
not merely append its signing block: it rewrites the archive. Measured with
build-tools 37.0.0 and this project's own signing options
(`tools/release/sign.sh`: v1 off, v2+v3 on), signing a 98 056 573-byte
unsigned APK and stripping the block off again gives **98 058 597** bytes —
2 024 bytes of alignment padding in local header extra fields, diverging from
byte 167 — while **all 730 entries keep their order, compression method,
sizes, mtimes and content hashes**. `--alignment-preserved true` does not
avoid it. So:

```
strip(sign(unsigned)) != unsigned     byte for byte
strip(sign(unsigned)) == unsigned     entry for entry
```

`verify.sh` therefore takes its verdict from the entry table (name, method,
CRC, uncompressed size, compressed size, content sha256, per entry) plus the
stored order, and *reports* the whole-file sha256 with that explanation. No
entry can be substituted, recompressed, added, removed or reordered without
failing. A verdict that insisted on the whole-file hash would fail on every
genuine release, which is worse than useless — people would learn to ignore
it.

**An unsigned APK is still held to the whole-file sha256.** Nothing has
touched the archive, so anything weaker would be weaker than the check CI
already runs between its two runners. `verify.sh --self-test` asserts both
halves of this, including that the tolerance for a re-laid-out archive does
not hide a tampered entry.

### Reading the result

```
RESULT: REPRODUCED -- ~/Downloads/skein-v1.0.0.apk matches a fresh build of v1.0.0
```

Exit status is the machine-readable version: **0** reproduced, **1** did not
reproduce, **2** could not run the check at all. "Could not check" is never
reported as "reproduced".

A `REPRODUCED` result can still list **toolchain deviations**:

```
Toolchain deviations (each of these can by itself explain a mismatch):
  - JDK vendor is not Temurin (release builds are pinned to it)
```

These are printed, not swallowed. Reproducibility is *conditional on an
identical toolchain* — that is the normal contract for an Android build, not
a defect: AGP writes its own version into the APK and JDK/NDK/aapt2 codegen
is not stable across versions. Pass `--strict` to make any deviation a
failure; CI does. A developer on their distro's OpenJDK 17 usually should
not, and the Gradle build itself never refuses a non-Temurin JDK.

### Options

| Option | Effect |
|--------|--------|
| `--repo <url\|path>` | clone from somewhere other than this checkout's `origin` (a local path works, and is how you verify an unpushed branch) |
| `--workdir <dir>` | put the clean clone somewhere you choose instead of a temp dir |
| `--keep` | do not delete the workdir, so you can inspect the build and its log |
| `--strict` | treat a toolchain deviation as a failure |
| `--self-test` | run the script's own negative cases — no clone, no build |

### The `.cxx` trap (read this before rebuilding by hand)

`verify.sh` always builds in a brand-new clone, and every CI runner is fresh,
so neither is affected. **A rebuild in a tree you have already built is a
different story**, and it is the likeliest way to get a wrong answer here.

AGP keeps the CMake/ninja build *outside* `build/`, in `<module>/.cxx/`.
`./gradlew clean` does not remove it, and ninja only rebuilds on file-content
changes — so changing an **environment variable**, `SOURCE_DATE_EPOCH` above
all, does not invalidate anything. The "rebuild" relinks the old objects and
ships a stale `.so`.

Measured on 2026-09-21 while verifying `E1.I8`, same commit throughout:

| Build | Path | `SOURCE_DATE_EPOCH` | `.cxx` | `libskein_sqlite.so` |
|-------|------|---------------------|--------|----------------------|
| 0 | A | 1790007384 | cold | `1f1f1ecc…` |
| A | A | 1790008784 | **reused** | `1f1f1ecc…` — *not recompiled at all* |
| B | B | 1790008784 | cold | `a2c3cab6…` |

`libskein_llama.so` showed the same shape from the other direction: builds 0
and B (both cold, *different paths*) were byte-identical, while build A's
incremental relink shifted two bytes. So the native libraries are
path-independent and reproducible — **when they are actually rebuilt**.

And `SOURCE_DATE_EPOCH` really does change the bytes, so getting its value
right is not bookkeeping. Two cold builds at the *same* path differing only
in the epoch produced different `libskein_sqlite.so` (`1f1f1ecc…` at
1790007384 vs `a2c3cab6…` at 1790008784) — it reaches OpenSSL through
`native/sqlite/CMakeLists.txt`. `libskein_llama.so` was unaffected (that
CMakeLists reads the variable but does not use it). This is why the workflow
no longer derives the epoch from `github.event.repository.pushed_at`: that is
the time of the *push*, so it changed on every re-run and no local rebuild
could ever have matched a release.

So when rebuilding in place, remove the native intermediates too
(`reproducible-builds.yml` lists them under
`artifact.clean_native_intermediates`):

```bash
rm -rf core/vault/.cxx inference-service/.cxx
./gradlew clean :app:assembleFossRelease --no-build-cache
```

Better: do not rebuild in place. Let `tools/rb/verify.sh` clone.

### Closed defect: `libskein_llama.so` (bd `skein-ylux`)

**There are no known open irreproducibility defects.**
`known_irreproducible` in `reproducible-builds.yml` is empty, and
`tools/rb/manifest-check.py` will not let an entry sit there describing a
build that has been fixed. A differing entry is a real differing entry.

There *was* one, and it is worth knowing about because it is the shape of
defect this whole document exists to catch. Between 2026-09-21 and its fix,
`libskein_llama.so` differed between cold builds of the same commit, at the
same absolute path, with the same `SOURCE_DATE_EPOCH` — about one build in
seven on a CI runner, and four distinct hashes across eight builds on a
16-core laptop. The entry kept its size, compression method and mtime; only
the ELF bytes and the CRC changed.

The cause was not the build system. `cmp -l` on two differing libraries found
**four** differing bytes, inside the SPIR-V for one embedded Vulkan shader.
NDK r27c's `glslc` (`shaderc v2022.3`) mis-folds the `int -> float ->
float16_t` conversion in `D_TYPE(0)` in ggml-vulkan's `tri.comp` and
`diag.comp`, emitting an `OpConstant %float` whose literal is uninitialised
memory — observed as `0`, `-8`, `+inf` and `-nan` for one unchanged command
line. (It was also a miscompile: the shader stored `+inf` where it meant
zero.) The fix writes the literal as `0.0`, which glslang folds correctly and
deterministically; it is a sha256-pinned patch applied to a **copy** of the
shader sources in the build tree, so `third_party/` stays pristine. Full
write-up: `native/llama/README.md` §4.

Two things now guard it:

| Guard | Cost | Fails when |
|---|---|---|
| sha256 pins in `native/llama/patches/PINS.txt`, checked at CMake configure | milliseconds, every build | the patch, the pristine shader or the patched result is not what was measured — e.g. a submodule pin bump touched a patched file |
| `native-determinism` job in `.github/workflows/reproducible-build.yml` | two native builds, one runner | the library stops being a function of its sources, for any reason |

Run the second one yourself with `tools/rb/so-determinism.sh` (see
`--help`); `--negative-control` puts the defect back and proves the check
still catches it.

### When it does not reproduce

Work outward from the cheapest explanation.

1. **Re-read the deviations list.** A different JDK, NDK or AGP is the answer
   far more often than a real bug.
2. **Classify the difference.** `verify.sh` prints the exact commands; the
   first one tells you whether the diff is timestamp-only, order-only,
   compression-only, or a genuine payload change:
   ```bash
   python3 tools/rb/normalize-apk.py <released-stripped.apk> <rebuilt.apk>
   ```
   That script *explains*; it never rewrites an APK. Normalising entry order
   or timestamps to force a match would hide precisely the nondeterminism
   this whole apparatus exists to catch, which is why nothing in the build or
   in CI's verdict calls it.
3. **Go deep** on the entries it named:
   ```bash
   diffoscope --html apk-diff.html <released-stripped.apk> <rebuilt.apk>
   ```
   CI produces this automatically as the `apk-diff` artifact whenever its two
   runners disagree.
4. **Special case:** if only
   `META-INF/com/android/build/gradle/app-metadata.properties` differs, you
   are on the wrong AGP. That file contains
   `androidGradlePluginVersion=<version>` and is deliberately not patched
   out — it is the signal a verifier wants, not a bug.
5. **Report it** per `SECURITY.md` if you have ruled the above out. A
   published APK that does not rebuild is a security finding, not a build
   annoyance.

### Verifying the verifier

Every tool here ships its negative case, and CI runs them *before* it trusts
a green result — otherwise "the check passed" and "the check cannot fail"
look identical in a log.

```bash
tools/rb/verify.sh            --self-test   # a tampered release must be caught
python3 tools/rb/strip-signature.py --self-test
python3 tools/rb/normalize-apk.py   --self-test
python3 tools/rb/manifest-check.py  --self-test   # needs PyYAML
tools/rb/source-date-epoch.sh --self-test
tools/ci/compare-apk-entries.sh --self-test
```

`tools/rb/manifest-check.py` (no `--self-test`) is the one to run after
bumping any toolchain version: it fails if `reproducible-builds.yml` has
drifted from `.tool-versions`, `gradle/libs.versions.toml`, the Gradle
wrapper JAR's sha256, `native/llama/PINNED_COMMIT`, the
`native/sqlite/*.sha256` provenance files, or the determinism settings in
`app/build.gradle.kts`. Fix the source of truth, then the manifest — never
the manifest alone.
