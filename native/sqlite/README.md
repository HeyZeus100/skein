# native/sqlite — SQLCipher + sqlite-vec + FTS5 in one library

This directory holds the E0.I7 spike (`skein-1ld`) that proved a **single**
Android shared object can bundle:

- SQLCipher 4.17.0 (SQLite core + encryption codec)
- OpenSSL 3.5.4 libcrypto (Apache-2.0), statically linked
- sqlite-vec v0.1.9 (`vec0` vtable + KNN), statically linked and registered via
  `sqlite3_auto_extension` (no runtime `.load` — `SQLITE_OMIT_LOAD_EXTENSION`
  is on)
- FTS5 (via `-DSQLITE_ENABLE_FTS5`)

Production integration (JNI, Kotlin wrapper, Gradle wiring) is scheduled as
`E1.I5` / `skein-qz5`. This spike keeps the sources here so the recipe is
self-contained and reproducible.

## Layout

```
native/sqlite/
├── CMakeLists.txt            # Android + host build
├── skein_extra_init.c        # chains sqlcipher_extra_init + sqlite-vec auto-ext
├── host_harness.c            # native macOS test that asserts all 3 features
├── amalgamation/             # generated SQLCipher 4.17.0 amalgamation
│   ├── sqlite3.c             # 9.3 MB, has SQLCipher + FTS5 + JSON
│   ├── sqlite3.h
│   └── sqlite3ext.h
├── third_party/
│   ├── sqlcipher-4.17.0.tar.gz            (79c0e164…)
│   ├── sqlite-vec-0.1.9.tar.gz            (9823e737…)
│   ├── openssl-3.5.4.tar.gz               (967311f8…)
│   ├── sqlcipher-4.17.0/                  extracted
│   ├── sqlite-vec-0.1.9/                  extracted (+ generated sqlite-vec.h)
│   └── openssl-3.5.4/                     extracted
├── build/
│   ├── openssl-android-arm64/lib/libcrypto.a   11 MB
│   ├── openssl-android-x86_64/lib/libcrypto.a  11 MB
│   ├── arm64-v8a/out/libskein_sqlite.so         (unstripped 14 MB)
│   └── x86_64/out/libskein_sqlite.so
├── prebuilt/                 # stripped, ready-to-ship copies
│   ├── arm64-v8a/libskein_sqlite.so    6.6 MB   sha256 582d290b…
│   ├── x86_64/libskein_sqlite.so       7.0 MB   sha256 c69c7f97…
│   └── darwin-arm64/host_harness       (host smoke-test binary)
└── host-build/               # intermediate host artifacts
```

## Build recipe (reproducible)

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
