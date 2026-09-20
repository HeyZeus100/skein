# Spike E0.I7 — SQLCipher + sqlite-vec + FTS5 in one connection

- **Beads issue:** `skein-1ld`
- **Date:** 2026-09-19
- **Tier:** opus
- **Verdict:** ✅ **PROVEN.** Single `.so` with all three features is buildable,
  reproducible, and works in one SQLite connection.
- **Downstream unblocked:** `skein-qz5` (E1.I5 production build), `skein-5hr`
  (E0.I8 MEASUREMENTS), `skein-z71` (E0.I11 vault contracts)

## The question

Spec §5/§6 assumed `androidx.sqlite`'s `BundledSQLiteDriver.addExtension` could
coexist with SQLCipher. Research on 2026-09-19 showed this was false — SQLCipher
4.17.0 is compiled without runtime extension loading, and `BundledSQLiteDriver`
is a vanilla SQLite build with no codec. So the entire vault + RAG plan hinged
on: *can we produce one Android shared object that has SQLCipher (OpenSSL) +
sqlite-vec + FTS5 all statically linked, and open a single connection that uses
all three?*

## Answer

**Yes.** The recipe is:

1. Generate the SQLCipher 4.17.0 amalgamation with `-DSQLITE_HAS_CODEC` and
   `-DSQLCIPHER_CRYPTO_OPENSSL`.
2. Compile the amalgamation with `-DSQLITE_ENABLE_FTS5`,
   `-DSQLITE_OMIT_LOAD_EXTENSION`, `-DSQLITE_EXTRA_INIT=skein_extra_init`, and
   `-DSQLITE_CORE`.
3. Compile `sqlite-vec.c` (v0.1.9) in the same translation unit set — it links
   as a static extension because `SQLITE_CORE` is defined for both files.
4. `skein_extra_init` chains `sqlcipher_extra_init(arg)` (mandatory for SQLCipher)
   then calls `sqlite3_auto_extension((void(*)(void))sqlite3_vec_init)`.
5. Link statically against an OpenSSL 3.5.4 `libcrypto.a` built for the target
   Android ABI with `no-shared no-dso no-engine no-tests`.

Full recipe + reproducible build script: `native/sqlite/README.md`.

## What was proven

The host harness (`native/sqlite/host_harness.c`, compiled as macOS arm64
against the same amalgamation + sqlite-vec + Homebrew OpenSSL) runs the exact
sequence the Android instrumented test will run. Output:

```
PASS cipher_version: 4.17.0 community
PASS vec_version: v0.1.9
PASS KNN nearest is rowid 1
PASS fts5 MATCH found rowid 1
PASS reopen without key fails: file is not a database
PASS raw file has no plaintext (size=77824 bytes)

ALL PASS — SQLCipher + sqlite-vec + FTS5 in one connection.
```

Assertions map to the acceptance criteria:

| Acceptance criterion | Verified how |
|----------------------|--------------|
| `.so` builds for `x86_64` **and** `arm64-v8a` with all the required `-D`s | CMake build succeeds; ELF NEEDED shows no `libcrypto.so`, so libcrypto is statically linked; `llvm-nm` shows `sqlite3_key`, `sqlite3_vec_init`, `skein_extra_init`, `sqlcipher_extra_init`, `fts5_*` all present in `.text` of the arm64 `.so` |
| Open with `PRAGMA key`, create `vec0`, insert, KNN returns nearer first | Host harness step 3 (`PASS KNN nearest is rowid 1`) |
| Create fts5 vtable + MATCH hit | Host harness step 4 |
| `PRAGMA cipher_version` non-empty | Host harness step 1 (`4.17.0 community`) |
| Reopening without the key fails with "file is not a database" | Host harness step 5 (exact string match) |
| Raw DB bytes contain no plaintext of inserted string | Host harness step 6 (linear memmem scan of the file) |
| MEASUREMENTS.md "Database" section | This document (E0.I8 consolidates) |

## Numbers

| Metric | Value | Notes |
|--------|-------|-------|
| SQLCipher core | 4.17.0 (SQLite 3.53.3) | pinned tarball, sha256 `79c0e164…` |
| sqlite-vec | v0.1.9 | pinned tarball, sha256 `9823e737…`; `sqlite-vec.h` generated from `.tmpl` (needs sed pass — see gotcha below) |
| OpenSSL | 3.5.4 | pinned tarball, sha256 `967311f8…` |
| Android NDK | r27c (`27.3.13750724`) | darwin-x86_64 prebuilt toolchain (rosetta-runnable on Apple Silicon) |
| Amalgamation size | 9.3 MB `sqlite3.c`, 677 KB `sqlite3.h` | one-time generation, checked in |
| OpenSSL `libcrypto.a` per ABI | 11 MB | `no-shared no-dso no-engine` shaved ~1 MB |
| OpenSSL build time per ABI | ~60 s | `-j8` on M-series (`darwin-x86_64` toolchain via rosetta) |
| `libskein_sqlite.so` per ABI, unstripped | 14 MB | dominated by libcrypto |
| Same, `llvm-strip --strip-all` | **6.6 MB (arm64), 7.0 MB (x86_64)** | This is the APK weight cost per ABI |
| Same, `-ffunction-sections -Wl,--gc-sections --icf=all` + strip | 6.5 MB (arm64) | Marginal — SQLCipher exports most sqlite3_* so little dead code is reachable |
| CMake+ninja build time per ABI | 13 – 14 s | on M-series |

**APK weight impact for IzzyOnDroid <30 MB budget:** if we ship a
single-ABI split APK (arm64-v8a only), the native DB stack adds **6.6 MB**
uncompressed (~4-5 MB after APK zip). A universal APK carrying both ABIs
would add ~13.6 MB. Recommendation: split APKs and drop x86_64 from
release builds (keep for emulator/CI only).

## Gotchas / surprises

1. **`sqlite-vec.h.tmpl` needs `$VERSION_MAJOR`, `$VERSION_MINOR`,
   `$VERSION_PATCH` substituted, not just `$VERSION`.** The `Makefile` in the
   upstream repo does this via a shell substitution the release tarball
   doesn't ship. First compile fails with `use of undeclared identifier '$'`
   in `sqlite-vec.c` at the `SQLITE_VEC_VERSION_MAJOR` macro. Fix in
   README.md step 2. `bd remember` filed.
2. **SQLCipher's own EXTRA_INIT is mandatory.** Attempting
   `-DSQLITE_EXTRA_INIT=your_hook` without calling `sqlcipher_extra_init`
   from your hook triggers a hard `#error` at line 110844 of the
   amalgamation. Our `skein_extra_init.c` chains them:
   ```c
   int skein_extra_init(const char *arg) {
     int rc = sqlcipher_extra_init(arg);
     if (rc != SQLITE_OK) return rc;
     return sqlite3_auto_extension((void(*)(void))sqlite3_vec_init);
   }
   ```
   Mirror `sqlcipher_extra_shutdown` from a `skein_extra_shutdown` too.
   `bd remember` filed.
3. **SQLCipher `./configure` fails `Cannot find libm functions` on Apple
   Silicon** because macOS clang folds `-lm` into `-lSystem`. Workaround:
   pass `--disable-math` to the amalgamation-generating configure only.
   No effect on the amalgamation contents (math functions are compiled in
   from `sqlite3.c`).
4. **Visibility.** With `-fvisibility=hidden` (default in the CMakeLists),
   `sqlite3_open` etc. are `HIDDEN` in the dynsym table. The vendored
   androidx JNI in E1.I5 will re-export them via a `SQLITE_API` override or
   linker `--version-script`. This is a JNI layer concern, not a build
   concern.
5. **OpenSSL 3.x compat with SQLCipher `crypto_openssl.c`.** SQLCipher
   4.17.0's `crypto_openssl.c` uses OpenSSL 1.1+ API (`EVP_CIPHER_CTX_new`,
   `HMAC_CTX_new`) — works cleanly against 3.5.4. No deprecation warnings.
   No FIPS provider needed.
6. **`no-engine no-dso`** on OpenSSL configure is important. Default
   OpenSSL Android build ships FIPS + legacy providers as dynamic modules
   the loader would look for at runtime — pointless in a single-.so
   application, and inflates the static lib. Explicit disable saves ~1 MB
   and eliminates a runtime-load code path we can't audit.
7. **NDK r27c toolchain is `darwin-x86_64`, not native arm64.** It runs
   under Rosetta on Apple Silicon. This is Google's shipping choice; no
   arm64-native NDK darwin toolchain exists as of r27. Build time on M-series
   is still ~13 s per ABI so this is a non-issue in practice.

## Android emulator run

Acceptance criterion requires the assertions to run on the API 35 emulator.
This spike ran them via a native harness pushed to `/data/local/tmp`
(equivalent code path to what the JNI-wrapped `.so` will execute in an
instrumented test):

- Installed `system-images;android-35;google_atd;arm64-v8a` (~700 MB)
- Installed `emulator` package (darwin-aarch64, ~500 MB)
- Created AVD `skein_spike` (Pixel 6 profile)
- Booted headless:
  `emulator -avd skein_spike -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`
- Boot took ~90 seconds to reach `sys.boot_completed=1`
- Verified: `ro.build.version.sdk=35`, `ro.product.cpu.abi=arm64-v8a`
- Cross-compiled `android_harness_arm64` from the same
  `amalgamation/sqlite3.c` + `sqlite-vec.c` + `skein_extra_init.c` sources,
  statically linked against the Android arm64 `libcrypto.a`
- `adb push` + `adb shell` — output:

```
PASS cipher_version: 4.17.0 community
PASS vec_version: v0.1.9
PASS KNN nearest is rowid 1
PASS fts5 MATCH found rowid 1
PASS reopen without key fails: file is not a database
PASS raw file has no plaintext (size=77824 bytes)

ALL PASS — SQLCipher + sqlite-vec + FTS5 in one connection.
```

That directly proves every acceptance-criterion assertion on the API 35
arm64-v8a emulator in one connection. What is **not** in this spike:

- The `core/vault` Gradle `androidTest` source set — no `core/vault/build.gradle.kts`
  exists yet.
- The vendored `androidx.sqlite` JNI layer that surfaces `libskein_sqlite.so`
  to Kotlin via `SkeinSQLiteDriver` — that is E1.I5 territory
  (`skein-qz5`). Wiring it here would duplicate the production task.

Filed as follow-ups.

## Follow-ups filed

- `skein-e2ki` (P0) — vendor `androidx.sqlite` JNI + `BundledSQLiteDriver`
  derivative pointed at `libskein_sqlite.so` (folds into E1.I5 / `skein-qz5`)
- `skein-k3b2` (P1) — instrumented `androidTest` porting `host_harness.c` to
  Kotlin once E1.I5 lands the `core/vault` Gradle module
- `skein-kf8r` (P1) — aggregate SQLCipher/OpenSSL/sqlite-vec/androidx-jni
  license notices into top-level `NOTICE`
- `skein-2lq9` (P2) — decide whether to check in the generated amalgamation
  or regenerate it in CI

## Reproduction

```
# From repo root, given NDK r27c at ~/android-sdk/ndk/27.3.13750724:
brew install cmake ninja openssl@3
cd native/sqlite
# See README.md for the full 5-step recipe.
```

Final artifacts:

- `native/sqlite/prebuilt/arm64-v8a/libskein_sqlite.so` — sha256 `582d290b…`
- `native/sqlite/prebuilt/x86_64/libskein_sqlite.so` — sha256 `c69c7f97…`
- `native/sqlite/prebuilt/darwin-arm64/host_harness` — smoke-test binary that
  reproduces the six ALL PASS lines above.
