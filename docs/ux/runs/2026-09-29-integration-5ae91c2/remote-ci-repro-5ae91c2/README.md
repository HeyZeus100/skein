# Final-source CI and reproducibility evidence

Source: `5ae91c238612bdd9b1511b3716d34b4fa5960dd2` (main).
CI: https://github.com/HeyZeus100/skein/actions/runs/36551451065
Reproducibility: https://github.com/HeyZeus100/skein/actions/runs/36551451072

This is a portable copy of the independent review. Original evidence is preserved unchanged at:

`/Users/andrewherrera/skein-worktrees/ux-rb-evidence-20260929/build/agent-logs/final-source-5ae91c238`

## Verified results

- CI: **5,079 passed, 88 skipped, zero failures/errors**, independently parsed from 494 raw XML files. Both `LlamaNativeSurfaceTest.generatedHelpersAreNotNative` flavor cases pass. Skips remain 80 screenshot exclusions, four embedding cases, two vault-import contract cases and two existing `skein-lds9` CI retry assumptions.
- Native pair: two real 25,314,032-byte arm64 libraries hash `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`. Source, submodules, worker flags, logs and all inner/outer native manifest entries were checked.
- Independent release APKs: each 100,673,247 bytes; SHA256 `d397160b48a73e9265b65d2ce4ea10ba1315bdd0bbd7f149d74dbaa97027f78f`. All 826 ZIP entry names/order, metadata and contents also match. Both APK native libraries match the cold pair.
- Downloaded CI debug APK: 125,280,302 bytes; SHA256 `28ffb1599581954686e00089be94e5f71f747eb4bdb518195e6b3dd73e669caf`.

`REVIEW.md` preserves the full original review. `final-run-review.json` records exact job/step outcomes and skips. The unit and reproducibility directories retain compact reviews, exact source inputs, artifact metadata/API digests, contexts, manifests, final and intermediate API snapshots, and review helpers. Every raw log is losslessly gzipped; JSON files larger than 16 KiB are also losslessly gzipped. `COPY_PROVENANCE.json` maps each copied file to its exact original absolute path, size and hash, and records its portable encoding and hash.

## Included raw unit evidence

`ci-36551451065/artifact-11025421464-unit-test-results/archive.zip` is the unchanged 2,519,442-byte raw unit archive, including XML and reports. SHA256:

`f467d262e59ba75bb12708846a0b9575dbd58f7cd40ae4484da9907b385abff0`

Its source manifest is copied separately as `ci-36551451065/artifact-11025421464-unit-test-results/files/build/unit-verification.json.gz`. The raw archive also contains that original uncompressed manifest. Unit evidence is attributed to the host-declared checkout, not installed APK bytes.

## Retained binaries outside Git

No APK, GGUF, native library or artifact larger than 10 MB is included. `EXTERNAL_ARTIFACTS.json` records exact original absolute paths, byte sizes and independently measured SHA256 values for all retained large artifact archives, APKs and libraries, together with their immutable artifact IDs, API archive digests, source identities and download endpoints. All five artifacts' original `metadata.json` and `archive-verification.json` files are included unchanged.

The copied native `SHA256SUMS.txt` and release APK checksum manifests describe complete original artifact contents. Binary entries are intentionally external; use `EXTERNAL_ARTIFACTS.json` to locate them. The bundle's own root `SHA256SUMS` covers every file actually included here. The original `REVIEW-SHA256SUMS.txt` is retained as source evidence and refers to original filenames before portable gzip encoding.

To verify this bundle from its root:

```sh
shasum -a 256 -c SHA256SUMS
```

Use `gzip -dc <file>.gz` to read compressed evidence. To reproduce the raw unit audit without changing this committed bundle, copy the unit lane to a scratch directory and unzip its retained archive into the corresponding artifact `files/` directory; `audit-unit.py` then checks the source manifest, XML checksums and actual cases. The full native/APK audit additionally requires the external immutable archives at their original artifact directory names.

## Acceptance limits

This reviews these two main-source runs. It does not establish three consecutive main reproducibility runs, convert screenshot/embedding skips into passes, attest installed device bytes or release a physical-device candidate. Ordinary instrumentation run `36551469886`, Fold coverage and inference/retrieval/embedding runtime acceptance remain separately reviewed gates. No original evidence, source implementation, thresholds or labels was changed while preparing this copy.
