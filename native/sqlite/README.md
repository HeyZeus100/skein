# native/sqlite — SQLCipher + sqlite-vec + FTS5 in one library

This directory produces **`libskein_sqlite.so`**, a single Android shared
object that bundles:

- SQLCipher 4.17.0 (SQLite 3.53.3 core + page-level encryption codec)
- OpenSSL 3.5.4 libcrypto (Apache-2.0) — built from a pinned source
  tarball (sha256 in `openssl.sha256`) as `no-shared no-dso no-engine
  no-legacy no-tests` and statically linked
- sqlite-vec v0.1.9 (`vec0` vtable + KNN) — sources vendored in-tree at
  `third_party/sqlite-vec-0.1.9/`, provenance recorded in
  `sqlite-vec.sha256`, statically linked and auto-registered via
  `sqlite3_auto_extension` at `sqlite3_initialize` time
- FTS5 (`-DSQLITE_ENABLE_FTS5`) and JSON1 (default in modern SQLite)

No runtime extension loading (`SQLITE_OMIT_LOAD_EXTENSION` on), secure
delete on, and `SQLITE_DQS=0` (no double-quoted string literals).

Owned Gradle module: **`:core:vault`**. Its
`android { externalNativeBuild { cmake { path = "…/native/sqlite/CMakeLists.txt" } } }`
consumes this build; the resulting `.so` is packaged into `:app`'s APK
under `lib/<abi>/libskein_sqlite.so`. The Kotlin JNI driver that surfaces
it as `SkeinSQLiteDriver` is tracked separately as `skein-e2ki`.

The spike that proved the recipe was `E0.I7 / skein-1ld` — see
`docs/SPIKE_E0_I7_RESULTS.md`.

**Why the amalgamation is checked in, not regenerated per build:** see
`docs/design/AMALGAMATION_POLICY.md` (decided in `skein-2lq9`). Short
version — reproducibility-by-default beats a smaller repo; the amalgamation
only changes on a deliberate SQLCipher bump. Integrity is enforced two ways:
`CMakeLists.txt` §0 hashes the three files against
`amalgamation/SHA256SUMS.txt` on every build, and the release-tag CI job
(`.github/workflows/reproducible-build.yml`, job `amalgamation-integrity`)
regenerates from the pinned SQLCipher source and diffs byte-for-byte. Both
are backed by `verify_amalgamation.sh` (`--verify` / `--regenerate` /
`--write-hashes`).

## Layout

```
native/sqlite/
├── CMakeLists.txt            # Consumed by AGP externalNativeBuild for
│                             # `:core:vault`. Fetches OpenSSL at configure
│                             # time (sha256-pinned), builds libcrypto.a per
│                             # ABI, then links libskein_sqlite.so.
├── cmake/
│   ├── extract_openssl.cmake     # Per-ABI pristine OpenSSL tree from the
│   │                             # pinned tarball (skein-doq0)
│   └── verify_openssl_abi.cmake  # Post-install libcrypto.a ELF Machine guard
├── skein_extra_init.c        # Chains sqlcipher_extra_init + sqlite-vec auto-ext
├── host_harness.c            # macOS host smoke test (kept for local dev)
├── openssl.sha256            # Pinned OpenSSL tarball hash
├── sqlite-vec.sha256         # Vendored sqlite-vec release provenance
├── sqlcipher.sha256          # Vendored SQLCipher amalgamation provenance
├── verify_amalgamation.sh    # Fast hash check + slow regenerate-and-diff
│                             # (see docs/design/AMALGAMATION_POLICY.md)
├── amalgamation/             # SQLCipher 4.17.0 amalgamation (checked in)
│   ├── sqlite3.c             # 9.3 MB, has SQLCipher + FTS5 + JSON1
│   ├── sqlite3.h
│   ├── sqlite3ext.h
│   └── SHA256SUMS.txt        # Hashes of the three files above
├── third_party/
│   ├── sqlite-vec-0.1.9/     # Vendored: sqlite-vec.c + generated .h + licenses
│   ├── openssl-3.5.4.tar.gz  # Fetched by CMake, git-ignored
│   └── openssl-3.5.4/        # Extracted by CMake, git-ignored
```

Everything under `build/`, `host-build/`, `prebuilt/`, and the OpenSSL
extraction is regenerated on demand — not checked in.

## Production build (Gradle)

Building the app also builds `libskein_sqlite.so`:

```
./gradlew :app:assembleFossDebug     # arm64-v8a only
./gradlew :app:assembleDevDebug      # + x86_64 for the emulator
./gradlew :core:vault:assembleFossRelease  # module-only stripped .so
```

The pipeline is:

1. AGP invokes `native/sqlite/CMakeLists.txt` per ABI selected by
   `:core:vault`'s `flavorDimensions("distribution")` × `productFlavors {
   foss, dev }`.
2. CMake fetches `openssl-3.5.4.tar.gz` (sha256 verified against
   `openssl.sha256`), extracts it, and — via `ExternalProject_Add` — runs
   `./Configure android-<abi> -D__ANDROID_API__=26 no-shared no-tests
   no-dso no-engine no-legacy --prefix=/skein-openssl
   --openssldir=/skein-openssl/ssl` followed by `make build_libs` and
   `make DESTDIR=<cxx build dir>/openssl-3.5.4-<abi>-install install_dev`.
   The prefix is synthetic on purpose — see [Reproducibility](#reproducibility).
3. `libcrypto.a` is statically linked into `libskein_sqlite.so` together
   with the SQLCipher amalgamation, `sqlite-vec.c`, and `skein_extra_init.c`.
4. AGP strips the release `.so`, packages it under `lib/<abi>/`, and hands
   it to `:app` via the standard AAR jniLibs mechanism.

### Cross-ABI / host-harness pollution

OpenSSL only supports in-tree builds, so step 2 needs a *writable source tree
per ABI*. It creates one by extracting `openssl-3.5.4.tar.gz` straight into
`<cxx build dir>/openssl-3.5.4-<abi>/`. It deliberately does **not** copy
`third_party/openssl-3.5.4/`.

That matters because `third_party/openssl-3.5.4/` is a working directory: the
manual recipe in §4 below and the host harness both invite you to run
`./Configure` and `make` in it. Until `skein-doq0` the CMake build copied that
directory into the per-ABI build tree, so any `.o`/`.a`/`Makefile`/
`configdata.pm` left behind came with it — `make` treated them as up to date
and an arm64 build installed a `libcrypto.a` full of x86_64 objects. The
symptom was an opaque link failure minutes later:

```
ld.lld: error: openssl-3.5.4-arm64-v8a-install/lib/libcrypto.a(libdefault-lib-pbkdf2_fips.o) is incompatible with aarch64linux
```

Two things keep that from recurring:

* **Per-ABI extraction** (`cmake/extract_openssl.cmake`) — every ABI's source
  is the sha256-verified tarball's bytes and nothing else, so whatever state
  `third_party/openssl-3.5.4/` is in cannot reach a `.so`. This is also what
  keeps rebuilds byte-identical: the compile inputs no longer depend on the
  checkout's history.
* **A post-install ABI guard** (`cmake/verify_openssl_abi.cmake`) — after
  `make install_dev`, `llvm-readelf -h` reads the first member of the
  installed `libcrypto.a` and fails the build immediately unless its ELF
  `Machine` matches `ANDROID_ABI` (`AArch64` for `arm64-v8a`,
  `Advanced Micro Devices X86-64` for `x86_64`).

So `./gradlew :app:assembleDevDebug` followed by `:app:assembleFossDebug` in
one checkout is safe, as is running §4's recipe by hand. If you ever do see
the guard fire, wipe `core/vault/.cxx` and `core/vault/build/intermediates/cxx`
and rebuild.

### Reproducibility

`lib/arm64-v8a/libskein_sqlite.so` is byte-identical across rebuilds **and
across checkouts at different absolute paths** — that second half is what
`E1.I8`'s cross-machine hash CI compares, and what `skein-ej1b` fixed.

Canonical `sha256` of the packaged (stripped) arm64-v8a `.so`
(OpenSSL 3.5.4 / SQLCipher 4.17.0 / sqlite-vec 0.1.9 / NDK r27c):

```
5e39a3b3d594d5339b1bbf3e44fccf27d24bf2234d2ff72971f67175c201e84e
```

(the unstripped `.so` is `7a3db743214cd53240310beec39d56395176dcb61800b5ad21b8c65a2fa7226a`,
but only the stripped one is a cross-machine contract — see the NDK caveat
below.)

**What makes it constant**

* **A synthetic OpenSSL prefix.** OpenSSL compiles `--prefix`-derived paths
  into `crypto/info.c` as the `OPENSSLDIR` / `ENGINESDIR` / `MODULESDIR`
  string literals, and those objects end up in `libcrypto.a` and then in the
  `.so`. Pointing `--prefix` at the real per-ABI install dir therefore baked
  `…/core/vault/.cxx/Debug/<agp-hash>/arm64-v8a/…` into the shipped library
  and made its hash depend on where the repo happened to be checked out.
  We configure with a fixed `--prefix=/skein-openssl` and an explicit
  `--openssldir=/skein-openssl/ssl` instead, and relocate the actual install
  with `make DESTDIR=…` — a *make command-line* variable, because OpenSSL's
  generated Makefile has its own `DESTDIR=` assignment that beats the
  environment. The three directories are inert regardless: `no-dso`,
  `no-engine` and `no-legacy` mean nothing is ever loaded from them, and
  SQLCipher never reads an OpenSSL config file.
* **`-ffile-prefix-map` on the OpenSSL compile**, passed as a make-time
  `CPPFLAGS` override rather than as a Configure argument. Flags given to
  Configure land in the Makefile's `CFLAGS`, and `crypto/buildinf.h` is
  generated from `$(CC) $(LIB_CFLAGS) $(CPPFLAGS_Q)` — so the flag text,
  absolute paths and all, would be compiled straight back into `info.c`'s
  `compiler: …` string, trading one path leak for another. `CPPFLAGS` reaches
  every compile via `LIB_CPPFLAGS` but is absent from the `buildinf.h` recipe.
* **`-ffile-prefix-map` on our own translation units** (`sqlite3.c`,
  `sqlite-vec.c`, `skein_extra_init.c`, `skein_jni.c`), mapping the source
  tree to `/skein/native/sqlite` and the CMake binary dir to `/skein/build`.
* `-Wl,--build-id=none`, `-fno-ident`, `-Wdate-time`, a sorted/deduped
  version script, and `SOURCE_DATE_EPOCH` (see `E1.I4`).

**How to verify**

```bash
# 1. No checkout path survives in the packaged .so.
strings -a app/build/intermediates/stripped_native_libs/fossDebug/\
stripFossDebugDebugSymbols/out/lib/arm64-v8a/libskein_sqlite.so \
  | grep -E '/Users/|/home/|/private/|\.cxx'      # must print nothing

# 2. The baked OpenSSL dirs are the synthetic ones.
strings -a .../libskein_sqlite.so | grep -E 'OPENSSLDIR|ENGINESDIR|MODULESDIR'
#   OPENSSLDIR: "/skein-openssl/ssl"
#   ENGINESDIR: "/skein-openssl/lib/engines-3"
#   MODULESDIR: "/skein-openssl/lib/ossl-modules"

# 3. Same bytes from a checkout at a different path.
git worktree add /tmp/skein-repro HEAD
(cd /tmp/skein-repro && ./gradlew :app:assembleFossDebug)
shasum -a 256 */app/build/intermediates/stripped_native_libs/fossDebug/\
stripFossDebugDebugSymbols/out/lib/arm64-v8a/libskein_sqlite.so
git worktree remove /tmp/skein-repro
```

**Caveat: the unstripped `.so` is not path-independent.** Its DWARF still
records the NDK's own sysroot (`…/android-sdk/ndk/27.3.13750724/…`), which
depends on where the NDK is installed rather than on the checkout. AGP strips
that before packaging, so it does not affect the shipped artifact; compare
stripped `.so`s (or APK entries) when checking reproducibility across
machines, never the `merged_native_libs` copy.

Changing any of the above — the prefix, the flags, the pinned versions —
changes the canonical hash, so it has to be re-recorded here in the same
commit.

Expected artifact sizes (measured on the spike, arm64-v8a):

| Stage | Size |
|-------|------|
| Unstripped `libskein_sqlite.so` | ~14 MB |
| Stripped `libskein_sqlite.so`   | ~6.6 MB |
| Contribution to APK             | ~4–5 MB (post `zip` compression) |

## Manual / spike build recipe (reproducible)

Prerequisites (installed during this spike):

| Tool | Version | Path |
|------|---------|------|
| Android NDK | r27c (`27.3.13750724`) | `~/android-sdk/ndk/27.3.13750724` |
| cmake | 4.4.3 | `brew install cmake` |
| ninja | 1.13.2 | `brew install ninja` |
| OpenSSL 3.x headers/libs (host build) | 3.x | `brew install openssl@3` |
| JDK | Temurin 17.0.20 | `brew install openjdk@17` |

### 1. Fetch pinned sources

```
cd native/sqlite/third_party
curl -sLO https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v4.17.0.tar.gz \
     -o sqlcipher-4.17.0.tar.gz
curl -sLO https://github.com/asg017/sqlite-vec/archive/refs/tags/v0.1.9.tar.gz \
     -o sqlite-vec-0.1.9.tar.gz
curl -sLO https://github.com/openssl/openssl/releases/download/openssl-3.5.4/openssl-3.5.4.tar.gz
tar xzf sqlcipher-4.17.0.tar.gz
tar xzf sqlite-vec-0.1.9.tar.gz
tar xzf openssl-3.5.4.tar.gz
```

### 2. Fill in the sqlite-vec.h template

sqlite-vec ships a `sqlite-vec.h.tmpl` that must be substituted:

```
cd sqlite-vec-0.1.9
sed -e "s/\${VERSION}/0.1.9/g" \
    -e "s/\${VERSION_MAJOR}/0/g" \
    -e "s/\${VERSION_MINOR}/1/g" \
    -e "s/\${VERSION_PATCH}/9/g" \
    -e "s/\${DATE}/2026-09-19T00:00:00Z/g" \
    -e "s/\${COMMIT}/v0.1.9-release/g" \
    sqlite-vec.h.tmpl > sqlite-vec.h
```

### 3. Generate the SQLCipher amalgamation

```
cd sqlcipher-4.17.0
./configure --with-tempstore=yes --fts5 --disable-tcl \
            --disable-load-extension --disable-math \
            CFLAGS="-DSQLITE_HAS_CODEC -DSQLCIPHER_CRYPTO_OPENSSL"
make sqlite3.c    # produces sqlite3.c (9.3 MB) + sqlite3.h
```

Then copy `sqlite3.c`, `sqlite3.h`, `sqlite3ext.h` into
`native/sqlite/amalgamation/`.

`--disable-math` is macOS-specific: the SQLCipher configure incorrectly
reports the libm probe as failing on Apple Silicon because `-lm` is a no-op.
On Linux you would omit it.

### 4. Build OpenSSL for Android (per ABI)

```
export ANDROID_NDK_ROOT=~/android-sdk/ndk/27.3.13750724
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH
cd native/sqlite/third_party/openssl-3.5.4

# arm64
./Configure android-arm64 -D__ANDROID_API__=26 no-shared no-tests no-dso no-engine \
  --prefix=$(pwd)/../../build/openssl-android-arm64
make -j8 build_libs && make install_dev

# x86_64
make distclean
./Configure android-x86_64 -D__ANDROID_API__=26 no-shared no-tests no-dso no-engine \
  --prefix=$(pwd)/../../build/openssl-android-x86_64
make -j8 build_libs && make install_dev
```

Build time on Apple M-series (measured): ~60 seconds per ABI. `libcrypto.a`
is ~11 MB per ABI. `no-engine no-dso` are important — they drop the dynamic
provider loader and shave ~1 MB.

Note the `make distclean` between the two ABIs: OpenSSL builds in-tree, so
without it the second `Configure` inherits the first ABI's objects. This
recipe leaves build products in `third_party/openssl-3.5.4/`, which is fine —
the Gradle build extracts its own per-ABI tree from the tarball and never
reads this one (see "Cross-ABI / host-harness pollution" above).

This recipe is for poking at OpenSSL by hand; it is **not** the reproducible
build. Its `--prefix=$(pwd)/…` is exactly the thing
[Reproducibility](#reproducibility) removes from the production Configure
line, so a `.so` built this way will not match the canonical hash.

### 5. Build the .so per ABI

```
cd native/sqlite/build/arm64-v8a
cmake ../.. -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=none
ninja
$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip \
  --strip-all out/libskein_sqlite.so
```

Same for `x86_64` (change `ANDROID_ABI`).

Measured build time: 13-14 s per ABI on Apple M-series.

## Verification

`host_harness.c` (compiled on macOS host, links against `brew openssl@3`) is
run first — it exercises the same code path the Android build uses and asserts
in one process on one connection:

1. `PRAGMA cipher_version;` returns non-empty (`"4.17.0 community"`)
2. `vec_version()` returns `"v0.1.9"`
3. `CREATE VIRTUAL TABLE v USING vec0(e float[4] distance_metric=cosine)`
   inserts 2 vectors and KNN returns the nearer one first
4. `CREATE VIRTUAL TABLE f USING fts5(x)` + `MATCH` returns the row
5. Reopening the file without `PRAGMA key` fails with
   `file is not a database`
6. Raw bytes of the DB file contain none of the inserted plaintext

All 6 pass. See `host-build/host_harness` (Darwin/arm64) for reproduction.

The Android instrumented equivalent is filed as a follow-up (see
`docs/SPIKE_E0_I7_RESULTS.md`), because installing the API 35 arm64 emulator
system image and booting it headless is queued behind AVD provisioning in
this session; there is no code-level reason it would behave differently — the
same source files, same compile flags, same OpenSSL static library.

## Symbol audit (arm64 .so)

`llvm-nm` on the unstripped `.so` shows every expected symbol defined in the
binary (visibility=hidden, so not in the dynamic table until the JNI layer
re-exports them):

```
sqlite3_open                (t)
sqlite3_key / sqlite3_key_v2 (t)     ← SQLCipher active
sqlite3_vec_init            (t)     ← sqlite-vec statically compiled
sqlcipher_extra_init         (t)     ← SQLCipher's mandatory init hook
skein_extra_init             (t)     ← our chain: calls the above then registers vec
```

`llvm-readelf -d` shows NEEDED = `liblog libandroid libm libdl libc` only.
**No `libcrypto.so` dep** — OpenSSL is statically linked as required.

## License compliance

| Component | Version | License | Where noted |
|-----------|---------|---------|-------------|
| SQLCipher (community) | 4.17.0 | BSD-style (Zetetic) | `third_party/sqlcipher-4.17.0/LICENSE.md` — copy into `NOTICE` |
| SQLite core (amalgamated in) | 3.53.3 | Public domain | same |
| sqlite-vec | v0.1.9 | Apache-2.0 or MIT (dual) | `third_party/sqlite-vec-0.1.9/LICENSE-APACHE` |
| OpenSSL | 3.5.4 | Apache-2.0 | `third_party/openssl-3.5.4/LICENSE.txt` |
| Android NDK headers linked | r27c | AOSP (Apache-2.0) | build tool, no runtime copy needed |

All permissive. FOSS-clean. `NOTICE` update is a follow-up (`skein-qz5`).
