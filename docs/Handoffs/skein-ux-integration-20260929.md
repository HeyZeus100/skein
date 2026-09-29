# UX, inference and demo integration — 29 September 2026

The measured application candidate source is
**`5ae91c238612bdd9b1511b3716d34b4fa5960dd2`**, pushed to `main` and
`codex/ux-integration-20260929`. Local checks and the matching-signer APK build pass.
**Candidate released at 10:10:33 UTC for the authorized data-preserving demo update.**
Fresh ordinary Android evidence passes all 281 cases with no failures or skips. The original
`4f1b7d334` candidate was rejected after 19 JNI failures; all original evidence is
preserved and its APKs must not be installed.

The integration preserves main baseline `9fa9f6fdda8a6f624d0e9b248e7a17e92f01890f`,
includes the complete earlier UX batch through `95c6b1ff796b97028cea0e8498d1f687fbe94be8`,
the inference topic through `01258a8693fb71ae94f0ea74a5732471f0a68ce0`, the demo
pack through `4e1045d1572c00ef443db5deffbed9b5741789f6`, and the isolated JNI repair
`21dd588a19bce793184571632bb1a7acc64a3cc7` integrated as `c8b80f423`.

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

## Combined local verification

The [repaired-source bundle](../ux/runs/2026-09-29-integration-5ae91c2/) retains raw
XML, commands, compressed logs, APK metadata and original failed attempts.

| Check | Actual result and source |
|---|---|
| Root `ktlintCheck lint check`, both app/service AndroidTest flavor compilation, shell release compile and Foss Debug assembly | `5ae91c238`: 1,523 tasks; **5,081 passed, 86 skipped, zero failures/errors** across 5,167 actual cases |
| JNI worker red/fixed/full regression | Original source with new guard: 1 failed; fixed: 1 passed; full service: **542 passed, zero failures/errors/skips**, both flavors |
| Foldable host workflow/reviewer tests | `5ae91c238`: **9 passed**; shell syntax and exact spaced-ID quoting verified |
| Ordinary and opt-in APK builds | `5ae91c238`: pass; ordinary/opt-in application APKs are byte-identical |
| Optimization, ELF alignment, both JNI surfaces, content logging, manifest audit | `5ae91c238`: all six pass; initial manifest invocation lacked `aapt2` on PATH and failed before inspection; original retained, same-APK retry with pinned SDK tool passed |
| Strict screenshots | Local `4f1b7d334` and fresh remote `5ae91c238`: **552 unchanged**, zero changed/added/recorded; **1,241 passed, 80 skipped**, zero failures/errors; all 705 remote manifest entries verify |
| Host evaluation / CI reviewer checks before repair | 90 and 12 passed; later fold reviewer changes additionally pass the nine tests above |

The 86 skips are 80 existing screenshot matrix exclusions, four production embedder
contracts pending `skein-079`, and the image-import contract pending `skein-rni` in
both flavors. They are not passes. Both-flavor import-observer, draft, late-attach,
real-worker OOM and new JNI-surface regression cases execute and pass.

All local Gradle/native builds used repository JDK 17, the configured Android SDK,
`--max-workers=2`, pinned submodules and an atomic build lease. No screenshot clear
or record task ran. Gold images, thresholds, frozen labels, tokenizer/model pins
and gitlinks are unchanged. Dependency verification adds 32 artifacts while
preserving all 1,528 existing entries.

## Candidate identity — released for scoped demo update

| Field | Verified value |
|---|---|
| Source | `5ae91c238612bdd9b1511b3716d34b4fa5960dd2` |
| Package/version | `app.skein`, versionCode `1`, versionName `0.1.0` |
| APK bytes | `125280302` |
| APK SHA256 | `1f83b71c5fe60a6dc26fcac7a4c75b49fbf009b6e8c9d972b2176c18081c1323` |
| Single signer certificate SHA256 | `75bcae7118a4635dd0fb153438e5bdbfd7aab5d467b3a0c2bb210eefde5becf9` |
| App-test APK SHA256 | `889db458324339f2f24da8b190a8457192a719df4bb088a52c04bf298e992f44` |
| Native-test APK SHA256 | `bb1b2f496f6c831ced00f20d9dd08d6f571d87fcb48040dfba17f4a7c561f342` |

Immutable APKs remain under the integration worktree's
`build/agent-logs/candidate-5ae91c2-ordinary/` and
`build/agent-logs/candidate-5ae91c2-optin/`. The three APKs have the same verified
single signer, matching the preserved installed APK; production version is not
older. Test APK manifests omit version fields, recorded as null/empty. Targets are
`app.skein.test → app.skein` and self-target `app.skein.inference.service.test`.
Only exact method-filtered synthetic tests are authorized below.

Source attribution is a clean host checkout, not embedded or installed-device
attestation. The original `4f1b7d334` capsules and failed first metadata capture
remain untouched. Their application SHA256 was
`3b4bd5d90522f43f82211ccc446d8369decffa5863703a95a657e54552a8834f` and is rejected.

## Fresh remote verification

All four runs identify exact source `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`.
Fresh screenshot artifacts are verified: 145 XML files contain 1,241 passes,
80 explicit exclusions, zero failures/errors; 552 individual results and all eight
summaries agree. All 705 file hashes match. Artifact digest is
`150e10a582bc1e252f52c695387db4220e1a68576caa64405e9998d74c4f2904`.

Remote CI unit XML is verified: **5,079 passed, 88 skipped, zero failures/errors**,
494 source-bound hashes; both new JNI-surface regression cases pass. The two extra
CI skips are the existing `skein-lds9` cases, locally passing. Unit ZIP digest is
`f467d262e59ba75bb12708846a0b9575dbd58f7cd40ae4484da9907b385abff0`.
Both actual cold native libraries match the retained `2aefda01…` SHA below, with
nine outer/six inner manifest entries verified at source epoch `1790675163`.
Final CI assembly/native guards pass with actual logs inspected. Both unsigned
release APKs are 100,673,247 bytes, SHA256
`d397160b48a73e9265b65d2ce4ea10ba1315bdd0bbd7f149d74dbaa97027f78f`;
all 826 entry names, order, metadata, contents and whole-file bytes match.

Fresh ordinary Android XML contains **281 passed, zero failures/errors/skips**:
app 38, vault 204, inference 39. The exact original 281 identities are present;
all 19 former failures and all six required cancellation/lifecycle cases pass.
The coordinator independently reparsed all three XML files and verified all four
source-manifest entries. Artifact ZIP SHA256 is
`edb5dde75920e189897f2fb75c9c92cb0841d6cf3d7bd8281212acce3863b4ef`.
CI APK hashes identify host-built outputs; they do not attest installed bytes
and differ from the locally signed release candidate.

- [CI 36551451065](https://github.com/HeyZeus100/skein/actions/runs/36551451065)
- [Screenshots 36551451241](https://github.com/HeyZeus100/skein/actions/runs/36551451241)
- [Reproducibility 36551451072](https://github.com/HeyZeus100/skein/actions/runs/36551451072)
- [Ordinary Android 36551469886](https://github.com/HeyZeus100/skein/actions/runs/36551469886)

Generic foldable [run 36553751502](https://github.com/HeyZeus100/skein/actions/runs/36553751502)
was dispatched after ordinary completion, with explicit profile `7.6in Foldable`.
That attempt confirms the exact generic profile exists, then fails before emulator
launch: `emulator -version` cannot load `libpulse.so.0`. The pinned action does
not install that Linux runtime library. Zero tests/APKs/geometry were produced. Original ZIP
SHA256 is `587745d2b5f19d12cf47c254444b4885dfb05773bd409cd9328628067375af0a`.
CI-only repair `69dc5cf8d` installs only `libpulse0` before preflight; ten host
contracts and workflow shell syntax pass. Followup
[run 36554797839](https://github.com/HeyZeus100/skein/actions/runs/36554797839)
has passed library/profile/version preflight; runtime evidence is pending.
Application code/APKs remain at the released `5ae91c238` source. The exact Pixel 9 Pro Fold
profile remains the default and a separate open acceptance gate.

## Retained earlier evidence and repairs

The [first-source bundle](../ux/runs/2026-09-29-integration-4f1b7d3/) records:

- Ordinary run [36546817687](https://github.com/HeyZeus100/skein/actions/runs/36546817687):
  **262 passed, 19 failed, zero errors/skips** across 281 actual cases. App 38/38
  and vault 204/204 pass; service 20/39. All four manifest entries and original ZIP
  digest `6a94f342f898fad355cef244338dd107f51dab7a22a255bd293503d219bd373f` verify.
- Eighteen failures reference `loadModel$lambda$0`; descriptor cancellation fails
  during its ordinary reload through `loadModelFromFd$lambda$0`. Local `javap`
  confirms both default lambdas became native methods before D8. Explicit path
  cancellation, three service lifecycle cases and app lock-after-inspection pass.
- The isolated repair replaces those defaults with a named managed supplier.
  The host regression fails on the original bytecode and passes after repair.
  Both compiled flavors expose exactly 27 native methods, no native dollar-named
  helpers; `getAsBoolean` has the expected managed `false` body. Public JNI
  signatures/cancellation contracts are unchanged. `skein-gg11.36` is closed
  after the fresh actual Android proof described above.
- Original CI [36546803273](https://github.com/HeyZeus100/skein/actions/runs/36546803273):
  **5,077 passed, 88 skipped, zero failures/errors**, all 492 manifest files
  hashed. The two extra skips versus local are existing `skein-lds9` CI-only retry
  cases, locally passing. All 16 critical cases pass. Native/logging/manifest
  guards pass; source JNI checks did not catch generated default helpers.
- Original screenshot run [36546803140](https://github.com/HeyZeus100/skein/actions/runs/36546803140):
  **552 unchanged**, 1,241 passes, 80 explicit exclusions, zero failures/errors;
  all 705 manifest entries verify. ZIP digest
  `cb0a26a44fee5ce4f665bac536a9c7b8850f9fa05f848b4848b92890a6af5909` matches GitHub.
- Original reproducibility [36546803361](https://github.com/HeyZeus100/skein/actions/runs/36546803361):
  directly byte-equal 100,673,247-byte APKs, SHA256
  `3d4930f8aa134ac46bc4c7b672bdf2e18a1c9bff698f1a461dea96d5bff70522`.
  Embedded llama hash `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`
  matches both cold-build log hashes; cold libraries were not retained on success.
  Tag-only SQLCipher regeneration is skipped, not passed.

CI-only `5a3ab9b986950651733e6040b43c7958f0ae25ac` adds the missing worker caps and
retains native success/failure libraries, logs, source and hashes. Corrected run
[36548576075](https://github.com/HeyZeus100/skein/actions/runs/36548576075) independently
verifies both actual 25,314,032-byte native libraries at the `2aefda01…` hash above,
all nine outer/six inner entries, executed CMake builds and clean source context.
Both complete APKs hash to
`cf7261f383637ff17149b54be987cf35012aa734e1374f88c2da012cc6116163`; all 826 entries
and whole-file equality match. Source epoch differs from the original run; equality
is checked within each run. [Corrected evidence](../ux/runs/2026-09-29-repro-evidence-5a3ab9b3/)
closes `skein-m7o9`; one manual topic run does not satisfy three consecutive main commits.

The first foldable attempt [36549916881](https://github.com/HeyZeus100/skein/actions/runs/36549916881)
failed closed before emulator launch: this runner's catalog has neither branded
Pixel profile. Actual review: zero tests, two missing identities, no APKs/geometry.
Original artifact digest is
`3dfe89dd802257b2e2cca62073a32432c5682e9421515362b79f664c18ff7830`.
The explicit generic `7.6in Foldable` choice is now integrated with exact profile,
hinge, runtime geometry and Activity-identity checks. SDK preflight now uses the
same `latest/bin` catalog as the pinned action. No silent fallback is permitted.
Generic deprecated-profile coverage cannot close the branded/full A–G gates.

## Scoped physical handoff and runbook

The owner released Fold HOLD **only for a data-preserving update and demo checks**,
with readiness requested by 17:00 PDT on 29 September. The conference-demo session's
`/root/fts_verification` holds the sole physical runner lease. The coordinator and
source workers perform no physical adb, SSH, installation or testing.

The explicit release is recorded in `coordinator.json` as
`candidate-5ae91c2-release-20260929`; a snapshot is included in the evidence bundle.
The sole runner compares package, signer and
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
Parameters are `gpuLayers=0`, 4096 context, four CPU threads, seed 17, greedy maximum 256 tokens,
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
policy change. The sole physical runner's first Android native attempt also
produced five passing raw parity cases at the same full model/template identity:
four equal token arrays (15/9/25/21 IDs) and literal-control isolation (two safe
versus three unsafe control IDs). Its native EOG classifications match the host.
The coordinator independently inspected the raw public synthetic JSON, SHA256
`cb7e85882891202119fe4deedfdec4149beee329b3f5592c054a644edc1882c4`.
The overall host runner remains **incomplete** because formatted JUnit output and
an unsupported Android instrumentation query failed its verification. Original
evidence is preserved while the demo owner repairs that helper. No application
update, generated natural stopping or answer-quality result follows from this
partial run; the selected app-private model also remains a separate gate. The distributor/base-model license-label discrepancy
remains recorded; this is not blanket artifact-license qualification.

AL-10's audit confirms landed session drafts/selection and turn ownership. Focus
restoration, user-scrolled transcript/jump-to-latest behavior, and entry-retained
presentation state remain incomplete. The drafts and turn controller were not
rebuilt. Concrete source findings at the measured source: `DraftComposerState.kt`
reconstructs `TextFieldValue` from session text/selection, keeping IME composition
local; `ChatBottomBar.kt` has no focus-restoration hook; `MessageList.kt:59`
scrolls to item 0 whenever a nonempty list's count changes, without checking the
reader's scroll position; `ChatScreen.kt:93` remembers and disposes its presentation
view model with composition. These are remaining presentation/lifecycle acceptance
gaps, not a request to replace the stored drafts or turn controller.
AL-11/AL-19 implementation gaps above are covered locally; real keyboard,
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
