# Emulator evidence and bridge repair, 2026-09-29

This capsule records source `321a51fdb0d7f6624e0fb957b819b968965d5e3f`: the failed foldable run and the passing ordinary instrumentation run. It also records the subsequent host-only repair `13ceb88ae81d21b829c5ec3e8104b021f33ab848`. The repair's local tests do not establish an emulator runtime pass.

| Run | Actual testcase elements | Result and scope |
| --- | --- | --- |
| [Foldable 36584103370](https://github.com/HeyZeus100/skein/actions/runs/36584103370) | 2 total, 0 passed, 2 failures, 0 errors/skips | Explicit generic `7.6in Foldable`, API 35. No accepted request, console command, acknowledgment or geometry observation. |
| [Ordinary 36584109986](https://github.com/HeyZeus100/skein/actions/runs/36584109986) | 281 total, 281 passed, 0 failures/errors/skips | App 38, vault 204, inference-service 39. Exact identities match prior passing run 36551469886, without missing, extra or duplicate cases. |

The ordinary check independently confirms all 19 formerly failing JNI cases and six recorded critical cases passed. Its collector contains exactly four current files: the three module XML reports and the review JSON. All four byte counts and hashes match; no historical documentation XML fills the inventory. See [ordinary publication review](ordinary-passed-36584109986/publication-review.json) and [original verification manifest](ordinary-passed-36584109986/artifact/build/instrumentation-verification.json).

The ordinary manifest's four APK hashes are **declarations only**: its uploaded archive contains no APK bytes. The foldable artifact includes two build APKs, which were independently rehashed and ZIP-checked outside Git. Neither lane independently measured installed APK bytes. Source attribution is the GitHub run API `head_sha` plus the host-declared checkout, not APK provenance attestation.

## Failed foldable attempt

The controller recorded `started`, then `fatal: malformed request JSON`. Both per-test logcats preserve the primary acknowledgment timeout, followed by the cleanup exception retained in XML. The initial malformed input was not logged, so its literal content is unknown. A final valid sequence-2 request exists, but no acknowledgment or geometry file was produced; their captured missing-file diagnostics remain unchanged. See the [publication review](foldable-failed-36584103370/publication-review.json), [original workflow review](foldable-failed-36584103370/artifact/build/foldable-evidence/review.json), and [protocol events](foldable-failed-36584103370/artifact/build/foldable-evidence/console-events.jsonl).

The published ADB source explains a supported mechanism: `exec-out` merges stderr into stdout and returns zero without the remote process's status. A pre-install package or request-file error therefore reaches the JSON decoder as a successful read. The baseline regression reproduces this behavior. The repair uses fixed-argument `adb shell -T`, preserving remote status and separate stderr; only exit 1, empty stdout, and either exact allowed unavailable diagnostic wait. Other errors, mixed output, malformed JSON and unexpected stderr remain fatal. All existing protocol, timeout, identity and mutation guards remain.

See [repair review](bridge-read-repair-13ceb88ae/repair-review.json), [patch](bridge-read-repair-13ceb88ae/repair.patch.gz), [official-source pins and hashes](bridge-read-repair-13ceb88ae/source-proof/REVIEW-v2.json), [red log](bridge-read-repair-13ceb88ae/checks/remote-read-before.log.gz), and [37-test green log](bridge-read-repair-13ceb88ae/checks/remote-read-after-all-host-final.log.gz). Official source is protocol evidence; it does not attest the downloaded CI ADB binary's build commit. No device or build was run for this repair.

## Archive identity

| Archive | Bytes | SHA-256 |
| --- | ---: | --- |
| Foldable artifact 11041317265 | 156518407 | `715ac7b0b939a66335edf120f4278742b9a5984c7a9b7d36eb19fa1dc9f2e910` |
| Foldable run logs ZIP | 105495 | `db4afea22d02b6e42c16c8da9d2358809e0e486f690b38e045889dba25467272` |
| Ordinary artifact 11041491749 | 973048 | `14bf246b978617f81dea8e7735a09ca23dfbb0b889f6bc8fbc52db09aa2c0f50` |
| Ordinary run logs ZIP | 89632 | `bd790fdefd7eeadd6ce14d69faf8f512907882191ff336ebbb758edb33141868` |

Both artifact hashes and sizes match the captured GitHub API digests; all four archives passed ZIP integrity checks. The log endpoint supplies no digest, so those ZIP hashes are local measurements. Original archives remain outside Git. The [foldable](foldable-failed-36584103370/archive-hashes.json) and [ordinary](ordinary-passed-36584109986/archive-hashes.json) inventories retain API references.

## Publication boundaries and verification

Only selected CI reports, source proof, and relevant logs are published. No APK, physical-device capture, full-device logcat, broad emulator property dump, vault/model file or personal worktree path is included. Public GitHub metadata, CI runner paths, synthetic test identities, emulator serials, and protocol run IDs/nonces remain where relevant. Existing GitHub credential masking is preserved. Local path prefixes in copied host logs are replaced with role placeholders; every transformation and original byte hash is listed in [COPY_PROVENANCE.json](COPY_PROVENANCE.json). Original evidence was not rewritten.

Run `shasum -a 256 -c SHA256SUMS` in this directory. Gzip files are deterministic compressed text; the provenance inventory includes both published and decompressed hashes. [PUBLICATION_CHECK.json](PUBLICATION_CHECK.json) records the independent copy, XML, manifest, privacy-pattern and link checks performed before publication.

AL-16 remains open: this failed generic-profile run proves no geometry transition. Pixel 9 Pro Fold profile acceptance, real IME/focus, unlocked A–G, hinge-angle/sensor assertions, system-server heap privacy, and physical Fold acceptance are outside these results. The ordinary pass does not establish those gates or release a new candidate. Retrieval quality and embedding gates are unchanged.
