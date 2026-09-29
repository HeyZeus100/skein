# Final-source CI and reproducibility evidence

Source: `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`, push to main.

## CI 36551451065 — verified success

https://github.com/HeyZeus100/skein/actions/runs/36551451065

- Unit artifact `11025421464`, raw ZIP SHA256 `f467d262e59ba75bb12708846a0b9575dbd58f7cd40ae4484da9907b385abff0`, matches API digest.
- Independently parsed **494 raw XML files: 5,167 cases = 5,079 passed, 88 skipped, 0 failures, 0 errors**. All retained XML bytes/hashes and exact source SHA match `unit-verification.json`; no missing XML entries. Declared suite totals match actual testcase elements.
- `LlamaNativeSurfaceTest.generatedHelpersAreNotNative` passes in **both DevDebug and FossDebug** XML. This is a host reflection guard, not on-device JNI execution.
- Skips: 80 screenshot exclusions, 4 embedding cases, 2 vault-import contract cases, 2 `skein-lds9` CI retry assumptions. The last two are the same MainActivity retry test in each flavor and explain the coordinator-reported local 5081/86 versus CI 5079/88 delta. Every identity/reason is retained in `ci-36551451065/unit-audit.json`.
- Actual CI log records explicit `--max-workers=2` check and debug assemble commands, native -O2/-O3 guard for 34 translation units, 16KiB ELF LOAD alignment, 27 llama and 25 SQLite JNI symbols matching declared externals, no-content-logging check, and manifest audit success. Synthetic reviewer test logs retain intentional negative-fixture FAIL output; this is not retrieval-quality acceptance.
- Debug APK artifact `11024887299`, raw ZIP SHA256 `ebbbd4a19e164c1f48a97bf89f240c89db121bfacfbbd5ef31fd0ff7957fd4c4`, matches API digest. APK is 125,280,302 bytes, SHA256 `28ffb1599581954686e00089be94e5f71f747eb4bdb518195e6b3dd73e669caf`. This identifies the downloaded CI output, not installed device bytes.

## Reproducibility 36551451072 — independently verified equality

https://github.com/HeyZeus100/skein/actions/runs/36551451072

- Native artifact `11025312519`, ZIP SHA256 `8be802c81ef3cb9e06b66b768f98fbf6cef296725e4d8c001e444d87da0013fa`, matches API digest. All 9 outer and 6 inner manifest entries independently rehash, with no omitted retained files.
- Both real ELF64 little-endian AArch64 libraries are 25,314,032 bytes, SHA256 `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`. Source context is clean and exact, SOURCE_DATE_EPOCH `1790675163`, all pinned submodules match, shader patches are on and exit is 0. Actual native compile logs succeed (3m02s, 1m53s), with no FROM-CACHE. Retained source-script SHA verifies its only per-build Gradle invocation includes `--max-workers=2`; normal Gradle logs do not echo that argv separately.
- Release artifact A `11025013318`, ZIP SHA256 `37024c94df63add75b0eeb4e94e4758b7318b956e57934323144e840b8d42a16`; B `11024698367`, ZIP SHA256 `fff84b5eb068ba1f8d46ac4df207127099b0ff20f66457ba785ecb307e35808a`. Both match API digests and verify their APK checksum manifests, exact source SHA/timestamp, and different checkout depths.
- Both unsigned release APKs are 100,673,247 bytes, SHA256 **`d397160b48a73e9265b65d2ce4ea10ba1315bdd0bbd7f149d74dbaa97027f78f`**. Independent local comparator confirms all **826** entry names/order, ZIP metadata, contents and whole-file equality. CI's separate comparator and SHA check also pass. Both embedded arm64 llama libraries match the cold pair above.
- Actual release logs show `--no-build-cache --max-workers=2`, successful builds (8m59s and 10m48s), and no FROM-CACHE. Original logs and all raw archives are retained unchanged.

## Limits and preserved skips

Exact workflow/job/step skips are in `final-run-review.json`: tag-only SQLCipher regeneration and conditional difference-classification/diffoscope uploads. This review establishes these two runs, not three consecutive main reproducibility runs. Unit screenshot/embedding skips are not promoted to passes. Runtime inference, retrieval/embedding quality, Fold coverage and ordinary instrumentation candidate release remain separate acceptance decisions; run `36551469886` was not reviewed or released by this agent. No CI dispatch, local Gradle/native build, source mutation, device operation, Beads change or push was performed.
