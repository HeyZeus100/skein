# Corrected reproducibility run review

Run: https://github.com/HeyZeus100/skein/actions/runs/36548576075
Source: `5a3ab9b986950651733e6040b43c7958f0ae25ac`; manual topic-branch run; final conclusion success.

- Native artifact ID `11023678920`: raw ZIP SHA256 `20c9cdfc3d9c409c7b453a183a9424767b84698f02d89322c6ea5640fca9323d` matches the API digest. Outer manifest 9/9 entries and inner manifest 6/6 entries independently rehashed, no omitted files.
- Two actual ELF64 little-endian AArch64 libraries are each 25,314,032 bytes and hash `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`. Both cold native build logs record executed CMake native tasks and success (3m41s, 2m32s), without FROM-CACHE or build failure.
- Native source context is clean, source timestamp is `1790673628`, all three submodule SHAs match pinned source, shader patches are on, and exit status is 0. Retained script SHA256 `3f3421ec9562cceefd6341921c65841108e5419bc80b083c67345117c3729098` matches the reviewed source whose only loop Gradle command includes `--max-workers=2`. Normal native Gradle logs do not separately echo argv.
- APK artifact A `11024241923`: raw ZIP SHA256 `84e716d9b4ae6200cb79dffdb937bfc2c47764ff0481b0f21a5ccb4e912685ab`; B `11023498781`: `587db1a606ffd7fd3ae8adebc612dd78879ec85e0fac1c6fcc9aa723f3eb3ad8`. Both ZIPs match API digests, and both APK checksum manifests verify.
- Both unsigned release APKs are 100,673,247 bytes and hash `cf7261f383637ff17149b54be987cf35012aa734e1374f88c2da012cc6116163`. Independent local comparison verifies all 826 entry names/order, ZIP metadata, contents and whole-file equality. Each embedded arm64 llama library matches the two cold libraries above.
- Both APK contexts name the exact source SHA/timestamp and different checkout depths. Actual job logs show `--no-build-cache --max-workers=2`; both builds succeed (9m56s, 10m19s), with no FROM-CACHE or build-failure markers. CI's independent final comparator also reports IDENTICAL and its SHA check is OK.

Preserved skips: tag-only SQLCipher regeneration; diffoscope and difference upload on an identical comparison; Gradle Kotlin configuration-error checks. Exact job/step list is in `final-run-review.json`, with per-build log skips in `build-log-review.json`.

Limits: this proves one manual topic-branch run and two cold arm64 native outputs. It does not satisfy the separate three-consecutive-main-commits criterion or device/inference/retrieval/embedding acceptance. No local Gradle/native builds, CI dispatches, device operations, Beads changes or pushes were performed for this review. A local audit helper's initial run.log/directory-glob error is preserved separately; it was corrected without changing downloaded evidence.
