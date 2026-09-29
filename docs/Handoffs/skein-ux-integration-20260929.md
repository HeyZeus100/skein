# UX, inference and demo integration — 29 September 2026

The combined application source is **`4f1b7d334173544feb47190b3e2e7efc4a5e1688`**,
pushed to `main` and `codex/ux-integration-20260929`. It preserves main baseline
`9fa9f6fdda8a6f624d0e9b248e7a17e92f01890f` and integrates the complete earlier UX
batch through `95c6b1ff796b97028cea0e8498d1f687fbe94be8`, the verified inference
topic through `01258a8693fb71ae94f0ea74a5732471f0a68ce0`, and the conference demo
pack through `4e1045d1572c00ef443db5deffbed9b5741789f6`.

The first APK is captured but **rejected for release**: actual ordinary Android
XML found 19 JNI default-callback failures. Original evidence is retained and
`skein-gg11.36` is claimed for the inference owner. A corrected source and fresh
runtime verification are required before any physical installation.

## Delivered behavior

Expanded inspector and Connections overlays consume remaining IME insets and
retain scrolling through peek/expanded transitions. Tabletop chat controls,
timeline filters and collapsed peeks avoid the separating crease. Separate
Material dialogs and menus, including model deletion, rename, About, license,
recovery, Space, row, note, persona and idle-timeout surfaces, receive the shell's
window partitions. Autocomplete uses the caret's partition and scrolls within
available window space. Flat layouts retain their existing placement.

Actual screen-position tests exposed dialog double-centering and a zero-size
menu-anchor error missed by the earlier window-attribute assertions. The final
correction positions the platform window once and measures the true popup origin.
Both original failures remain retained. Tests cover actual 2× resource font scale,
IME clearance, a narrow partition, Cancel focus and outside dismissal.

NavShell now observes the application-owned import coordinator. Copying survives
shell disposal; import completion or unlock adoption refreshes visible Chat
readiness. Current import state takes precedence over delayed rescue messages.
Only the captured result can be dismissed, and adoption no longer claims that an
existing default model was replaced. Production Activity tests exercise the real
document-picker callback, progress across recreation, completion while Chat stays
visible, one copy/default write, and delayed rescue after a newer refusal.

The inference owner supplied revocable inspection/load transactions, descriptor
ownership, native progress cancellation, terminal isolated-process teardown,
same-epoch binding protection, foreground copy/adoption and session attachment
guards. Independent review found and resolved late attachment after HIGH/close
and a pre-existing worker self-deadlock during OOM cleanup. Real-worker JVM tests
prove cleanup and subsequent loading; they are not real-device OOM evidence.
See the [inference execution report](skein-inference-lifecycle-20260929.md).

## Local verification

The [evidence bundle](../ux/runs/2026-09-29-integration-4f1b7d3/) retains raw XML,
Roborazzi JSON, compressed command logs, APK metadata and SHA256 inventories.
Independent review rehashed all 1,687 entries across the two broad-check archives
and screenshot archive without a mismatch.

| Check at the combined source | Actual result |
|---|---|
| Root `ktlintCheck lint check`; ordinary app/service AndroidTest compilation for Dev/Foss; shell release compilation | Pass; 1,448 tasks in the final run; **5,079 passed, 86 skipped, zero failures/errors** |
| Strict `--no-build-cache verifyRoborazziDebug --continue` | Pass; **552 unchanged**, zero changed/added/recorded; **1,241 passed, 80 skipped**, zero failures/errors |
| Opt-in foldable AndroidTest compilation and explicit app ktlint | Pass; compilation only |
| Host evaluation/qualification tests; CI evidence-reviewer tests | 90 and 12 passed |
| Local Foss Debug application and opt-in app/native test APK builds | Pass; application APK identical before/after opt-in build |
| Native optimization, ELF alignment, both JNI surfaces, content logging, manifest audit | All six guards pass |

The 86 broad-check skips are 80 existing screenshot-matrix exclusions, four
production embedder contract cases pending `skein-079`, and the image-import
contract pending `skein-rni` in both flavors. They are not passes. No new functional
test was skipped. Actual XML includes both-flavor production import-observer,
draft retention, three late-attachment cases and the real-worker OOM regression.

All local builds used repository JDK 17, SDK `/Users/andrewherrera/android-sdk`, explicit
`--max-workers=2`, initialized pinned submodules and an atomic `build.lock` lease.
Logs and helpers remain in their owning worktrees. No screenshot clear/record task
ran. Golden images, comparison thresholds, frozen retrieval labels, model/tokenizer
pins and all gitlinks are unchanged. Dependency verification adds 32 artifacts and
preserves all 1,528 previous artifact entries.

## Candidate identity

| Field | Verified value |
|---|---|
| Source | `4f1b7d334173544feb47190b3e2e7efc4a5e1688` |
| App package/version | `app.skein`, versionCode `1`, versionName `0.1.0` |
| APK bytes | `125280302` |
| APK SHA256 | `3b4bd5d90522f43f82211ccc446d8369decffa5863703a95a657e54552a8834f` |
| Single signer certificate SHA256 | `75bcae7118a4635dd0fb153438e5bdbfd7aab5d467b3a0c2bb210eefde5becf9` |
| App-test APK SHA256 | `889db458324339f2f24da8b190a8457192a719df4bb088a52c04bf298e992f44` |
| Native-test APK SHA256 | `096c4fb1a7365362992e3d88caf17c3e8d56be92f42bee0ffb51aca8af3c7ddb` |

Immutable local copies are under this integration worktree's
`build/agent-logs/candidate-4f1b7d3-ordinary/` and
`build/agent-logs/candidate-4f1b7d3-optin2/`. Both test APKs use the same signer;
their generated manifests omit version fields, recorded as null/empty rather than
invented versions. The first capture helper rejected that omission; its partial
capture and explanation remain intact. Production version checking was not relaxed.

An independent audit verified the preserved installed APK's hash and matching
single signer, all seven candidate libraries, their ARM64 identity, uncompressed
16 KiB ZIP offsets and 16 KiB ELF segments, and all 27 llama/25 SQLite JNI exports.
Only the approved foreground-service permissions were added; isolated, non-exported
inference services remain intact. Source attribution is from the clean host build,
not an embedded or installed-device source attestation.

## Remote and physical evidence

Exact-source runs dispatched or triggered after the main push:

- [CI 36546803273](https://github.com/HeyZeus100/skein/actions/runs/36546803273)
- [Screenshots 36546803140](https://github.com/HeyZeus100/skein/actions/runs/36546803140)
- [Reproducibility 36546803361](https://github.com/HeyZeus100/skein/actions/runs/36546803361)
- [Ordinary instrumentation 36546817687](https://github.com/HeyZeus100/skein/actions/runs/36546817687)

Remote screenshot verification is complete: the actual Verify, untouched-golden and
size-budget steps pass; 145 XML files contain 1,241 passes, 80 explicit assumption
exclusions, zero failures/errors. All 552 individual JSON results are `unchanged`,
and all eight summaries agree. All 705 manifest entries match their bytes and hashes.
The downloaded artifact digest is
`cb0a26a44fee5ce4f665bac536a9c7b8850f9fa05f848b4848b92890a6af5909`.
The non-blocking job's overall workflow status was not used as the acceptance gate.

Remote CI unit XML contains **5,077 passes, 88 skips, zero failures/errors** across
492 independently hashed files. The two additional skips are the existing
`skein-lds9` CI-only failed-open retry cases; both pass locally. All 16 critical
both-flavor draft, import-observer, late-attachment and OOM-worker cases pass.
CI assembly/native guards still await final review. Ordinary instrumentation
**failed**: 281 actual cases comprise app 38/38, vault 204/204 and service 20/39;
19 failures, no errors/skips, missing or duplicate identities. The actual archive
SHA256 is `6a94f342f898fad355cef244338dd107f51dab7a22a255bd293503d219bd373f`,
matching GitHub's digest; all four recorded manifest files hash correctly.

Eighteen tests hit `LlamaNative.loadModel$lambda$0`; the descriptor-cancellation
case fails during its ordinary reload at line 140 through
`loadModelFromFd$lambda$0`. Both default suppliers were compiled as synthetic
`native` methods, independently confirmed by local `javap`, and have no JNI
implementation. Explicit path cancellation, all three new service lifecycle cases
and app lock-after-inspection pass. This is a source regression, not a flaky run.
The existing candidate is not installable under the verification contract.
Original failed ZIP, traces, source, logs and bytecode inspection are preserved in
`remote-ordinary-36546817687-FAILED/`; no unchanged retry was dispatched.

Original reproducibility produced two directly byte-equal 100,673,247-byte release
APKs, SHA256 `3d4930f8aa134ac46bc4c7b672bdf2e18a1c9bff698f1a461dea96d5bff70522`.
Their contexts identify source `4f1b7d334`, epoch `1790672335` and different checkout
depths. The embedded release llama library hashes to
`2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`, matching both
cold-build log hashes. Those original cold-build libraries were not retained by
the legacy success path, so their log hashes alone are runner-reported evidence.
The tag-only SQLCipher source job is skipped on this main run, not passed.

The audit also found two older reproducibility Gradle commands without the required
worker cap. CI-only integration `5a3ab9b986950651733e6040b43c7958f0ae25ac` adds
`--max-workers=2` and retains both native libraries, logs, source context and hashes
on success and failure. Six helper self-tests, shell syntax and workflow checks pass.
[Corrected reproducibility run 36548576075](https://github.com/HeyZeus100/skein/actions/runs/36548576075)
is separate from the preserved original run. It does not change application inputs
or the frozen `4f1b7d334` candidate. Its actual evidence review is pending.

Foldable compatibility runtime follows ordinary instrumentation with explicitly
selected `pixel_fold`; the SDK catalog lacks the exact `pixel_9_pro_fold` profile,
and no silent fallback is allowed.

The owner released Fold HOLD **only for a data-preserving update and demo checks**,
with readiness requested by 17:00 PDT on 29 September. The conference-demo session's
`/root/fts_verification` holds the sole physical runner lease. The coordinator and
source workers perform no physical adb, SSH, installation or testing.

After an explicit coordinator release, that runner compares package, signer and
version again, preserves the installed APK, performs only `install -r`, and verifies
installed bytes against the released digest. No uninstall, clear, reset, downgrade
workaround or security weakening is authorized. Owner unlock follows the normal
authentication path. The [demo runbook](../demo/conference-2026/README.md) defines
the fictional notes, truthful fallbacks and actual rehearsal observations.

The approved opt-in sequence uses only
`SyntheticTemplateParityTest#publicModelNativeTokenParity` in the separate native
test package, then `SyntheticAnswerBenchmarkTest#suppliedEvidenceThroughTheIsolatedApk`
in an idle window after update, before UI rehearsal. Never run the whole test APK.
Use fresh package-local synthetic directories, the verified public model copy and
the four-case gold-free demo input. Keep the emulator-only helper's guard intact.
Parameters are 4096 context, four CPU threads, seed 17, greedy maximum 256 tokens,
`stop=[]`, 180 seconds per case and a 900-second host watchdog. A timeout stops the
sequence; ending host adb alone does not prove remote cleanup. Inspect all answer
rows, terminal reasons, five parity cases and native EOG classifications even if
JUnit succeeds. This does not measure live retrieval or owner-vault behavior.

## Model identity and remaining acceptance

The copied public Qwen Q3 file is 1,590,475,744 bytes with SHA256
`2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12`.
The coordinator and inference owner independently hashed it and verified the
unchanged identity report. Four host-native template/token cases pass; native EOS
and EOT are 151645, and the host classifies 151645/151643/128247 as control/EOG.
The last is upstream normalization of the stored `</s>` spelling, not a tokenizer
policy change. Android JNI, actual natural stopping and the selected app-private
model are separate gates. The distributor/base-model license-label discrepancy
remains recorded; this is not blanket artifact-license qualification.

AL-10's audit confirms landed session drafts/selection and turn ownership. Focus
restoration, user-scrolled transcript/jump-to-latest behavior, and entry-retained
presentation state remain incomplete. The drafts and turn controller were not
rebuilt. AL-11/AL-19 implementation gaps above are covered locally; real keyboard,
display-swap and physical posture acceptance remain open. AL-15 adds production
Activity retention/Bundle privacy cases; AL-16 adds a two-test unopened-vault gate,
not the full unlocked A–G, recursive privacy or real IME matrix. The broader
[Fold acceptance runbook](skein-fold-coverage-20260929.md) requires separate scope
beyond the authorized demo.

Inference `.19`/`.20` retain unmeasured Android lifecycle/FGS/process-death cases;
model readiness `.28`/`.30`, CPU/Vulkan decisions `.26`/`.27`, formal M0 and the
Q4/Gemma matrix remain open. Retrieval quality remains failed/open under
`skein-gg11.32`; full hybrid is **INELIGIBLE**, with the existing embedding chain
`skein-lbw → skein-079 → skein-hwsa` and validation `skein-9744` unchanged. Neither
the demo input nor host/native identity evidence changes those gates.

Beads remains the task tracker; coordinator/inference/demo/hardware JSON files are
coordination records. Existing stashes, owner files and recovery worktrees remain
preserved. The recorded missing Dolt remote was honored without retrying push.
