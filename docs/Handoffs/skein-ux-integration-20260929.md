# UX, inference and demo integration — 29 September 2026

The latest pushed combined UI source is **`28354dd25bb4995930ff46a71752f43f8a0d5df3`** on
`main` and `codex/ux-integration-20260929`. **Released at 16:42:58 UTC for the
owner-authorized data-preserving update and demo checks.** The sole demo runner
may install the exact APK below using `install -r`, verify copied installed bytes,
complete normal unlock and synthetic UI rehearsal, then perform one bounded
four-case answer attempt. The runner installed it and independently rehashed the
copied installed APK to the exact candidate digest. The previous APK, original
update-parser failure and unchanged installation/data-directory metadata are
preserved. The owner confirmed normal unlock and unfolding at 16:50 UTC. Initial
physical observations confirm repeated New chat, Chat and Knowledge sidebar
collapse, preserved fictional notes and no teal crease. Note+chat and Swap retained the synthetic draft and selected title; two distinct
fictional notes and note+graph were also observed side by side. Hiding split and
returning to Chat retained the draft and Knowledge choice. Live answers remain
pending; this is not full adaptive acceptance. See the historical
[verified-update checkpoint](../demo/conference-2026/runs/2026-09-29-workspace-update/README.md).
The earlier `5ae91c238` release and all original failed attempts remain preserved.

The owner-requested UI adds independently selectable workspaces for note/chat,
two different notes, graph and other destinations; each retains its navigation,
drafts and entry state. Split/collapse and Swap move stable owners. Selecting a
note or chat already owned by the other workspace activates that owner, including
objects retained below an inspector or another destination. Both workspaces share
the authorized vault session and model/import services, and both reset on vault
reset. Hidden workspaces stay composed without focus, semantics, navigation-back
ownership or separate dialog/popup windows. Model changes in one workspace refresh
Chat readiness in the other. The existing drafts and turn controller remain in
place; the first-send addition captures a Knowledge boolean before admission.

New chat works across single, dual and triple layouts. Chat and Knowledge have
collapsible lists and accessible header controls. The first draft exposes Search
Knowledge before sending; each logical draft retains its own choice. The enabled
teal hinge debug guide is removed. Split placement retains physical book/tabletop
partitions and falls back to the active workspace when two readable panes do not
fit. Root Knowledge filters are content-free state that clears on stopped lock;
private text stays out of the saved navigation Bundle.

[Local workspace evidence](../ux/runs/2026-09-29-workspace-28354dd2/README.md)
contains the actual XML, source/provenance records, before/after images and synthetic
production Activity captures. The combined check at code source `0401d15f190` passed
**1,684 cases with 44 existing conditional skips**, no failures/errors, explicit
lint for all six affected modules and both opt-in AndroidTest Kotlin compilations.
`28354dd25` changes only the 110 reviewed current goldens. All **552** images verify
unchanged afterward; XML has 1,274 passes and 80 conditional skips. Seven screenshot
module test tasks executed in that final run; Models reused the separately preserved
38-unchanged fresh focused verification. No threshold, frozen baseline or gold label
was changed.

The first comparison's **114 changed images** are preserved. All 74 Chat/Knowledge
changes were reviewed as the requested header/sidebar/first-send changes, and all
36 icon-gallery changes as three added pinned icons and corresponding pagination.
The remaining four Models changes exposed a parent focus-policy regression; the
fix preserves visible descendants' own input-mode focus policy. Models then matched
its original goldens, which were not recorded. Every one of the 110 recorded images
is byte-identical to its reviewed earlier actual image. Original fixture, semantic
measurement and capture-filename failures remain retained with their corrected
checks; they are not relabelled as passes. The [font measurement proof](../ux/runs/2026-09-29-workspace-font-proof/README.md)
retains pinned Compose source and the two original diagnostic failures: semantics
reconstructs parent-width text layout, so its overflow flag is not a glyph-clipping
measurement. The corrected test requires the full one-line label and character
bounds inside Text and the button. Existing touch/hinge thresholds remain intact;
the one-pixel tolerance applies only to that new character-containment check.

The [fold observer evidence](../ux/runs/2026-09-29-ci-followup-8a1fe20/README.md)
preserves run `36588307563`: two failures, four successful causal console exchanges,
zero Activity geometry. The repaired test launches MainActivity before control,
reads its Configuration and current window bounds, and preserves the same Activity
through guarded cleanup. It retains the original thresholds, setup/security
assertions, five geometry records and six-exchange protocol. Host checks and both
Kotlin compilations pass. The [fresh run `36598598934`](../ux/runs/2026-09-29-foldable-failed-36598598934-28354dd2/README.md)
failed both actual XML cases with zero skips/errors and zero accepted geometry
records. The live Activity stayed 1768 × 2208 px / 674 × 841 dp after CLOSED,
despite the AVD declaring an 884 px folded region. Four causal console exchanges
succeeded; six required exchanges and the compact-cover assertion did not complete.
Retained framework lines show state transitions applying the same single-display
layout. The cause is not isolated to emulator/image policy or app behavior. No
retry, profile change, synthetic resize or threshold relaxation was made. AL-16
remains open; this compatibility profile cannot establish exact Pixel 9 Pro Fold
or unlocked A–G acceptance.
[Earlier secondary checks](../ux/runs/2026-09-29-secondary-checks-8a1fe20/README.md)
are explicitly attributed to the source before these workspace changes.

## Current candidate identity and runbook

| Field | Verified value |
| --- | --- |
| App and test source | `28354dd25bb4995930ff46a71752f43f8a0d5df3` |
| Application | `app.skein`, versionCode `1`, versionName `0.1.0` |
| App APK bytes / SHA-256 | `127098252` / `8053e8e10ee6dbe7b9e650506971bf30b93e64861bbdff1237420680d10b4eb9` |
| Single signer certificate SHA-256 | `75bcae7118a4635dd0fb153438e5bdbfd7aab5d467b3a0c2bb210eefde5becf9` |
| Opt-in app-test bytes / SHA-256 | `91337045` / `0891fce37751f92a2e79a1c46424e3d463fbdef0903dce64aa9933f9be472cf2` |
| Immutable v5 release SHA-256 | `7ed145637790495702d1d30b124c58b160db97d8a583ec8bde7db3a84df9dc38` |
| Reviewed v5 runner SHA-256 | `b69a00a94032d00edcf84a4f79f028daa625717847aa8d616fa719b6ab5f82e7` |

The signed APKs are in the integration worktree's
`build/agent-logs/candidate-28354dd-optin/`; the concrete release and host-only
validation are in `build/agent-logs/candidate-28354dd-release-v5/`. Ordinary and
opt-in **application** APK bytes match. All six native/manifest guards pass, and
the signer matches the preserved installed application. The source is attributed
to a checked clean build checkout, not embedded APK attestation. Host release
validation rehashed the actual APKs/model/evidence and ran only six local SDK
metadata commands; it made no device connection.

[Ordinary run 36592802616](https://github.com/HeyZeus100/skein/actions/runs/36592802616)
has **281 actual XML passes**, no failures/errors/skips, the exact expected case
set, all 19 prior JNI cases and all seven critical cancellation/lock/tokenization
cases. Its current four-file manifest is exact; uploaded APK hashes are
**declarations only**, since that archive contains no APK bytes. See the
[ordinary capsule](../ux/runs/2026-09-29-ci-followup-28354dd2/README.md).

The [fresh secondary-check capsule](../ux/runs/2026-09-29-secondary-checks-28354dd2/README.md)
verifies all six artifact ZIP API digests and actual contents. CI `36592770882`
has **5,130 passes, 88 unchanged skips**, zero failures/errors; 12 populated test
tasks executed and 16 were cached. Screenshot run `36592770755` executed all eight
module test/verify tasks with no build cache: **552 unchanged**, 1,274 passes and
80 unchanged skips, all 711 manifest hashes valid. Reproducibility run
`36592770702` retains two byte-identical unsigned release APKs: `100740719` bytes,
SHA-256 `99edfeebf7828749ad7c47c50d5bbcb4e1ba06d42d5c3b93e22a3edbb1f5cfd8`,
with all 829 ZIP entries equal. Two actually cold-built native libraries also
match each other and the embedded release library at `2aefda01…`. The tag-only
SQLCipher job is skipped, and no new native negative-control claim is made.

The [Qwen/native reuse capsule](../ux/runs/2026-09-29-qwen-reuse-28354dd2/README.md)
retains the complete source, binary-entry and model identity comparison. The Qwen
file remains `1590475744` bytes, SHA-256 `2c5f9a12…`, BLAKE3 `db158ff6…`,
and template SHA-256 `cd8e9439…` as detailed below. Its original identity evidence
is reused explicitly, with full device SHA-256 and size still required. Prior
five-case native token/EOG classification evidence is bound to the **exact frozen
native-test APK `bb1b2f49…`**. All seven current app native libraries and that
native-test library match the measured baseline. Rebuilt native-test and app-test
payloads also match their frozen counterparts (79 and 67 entries respectively);
container placement/padding/signing differences do not relabel them byte-identical
APKs. This establishes bounded reuse, not newly measured generated stopping,
answers, live retrieval or model/license eligibility.

The demo runner owns the physical lease. Preserve app data and the previous APK;
no uninstall, clear, reset, downgrade workaround or security weakening. Rehearse
fresh New chat twice, both sidebar controls, note+chat/two distinct notes/note+graph,
Split/collapse/Swap retention, absent teal debug crease, and Knowledge off before
first send with synthetic content. Keep any owner-content screenshot local and
untranscribed. After the UI session yields the slot, v5 verifies already-installed
app bytes and may update only the test package for the exact answer method. It
uses the unchanged four gold-free cases, CPU four threads, context 4096, seed 17,
greedy 256 tokens, empty stop list, 180 seconds per case and 900 seconds outer
limit. Preserve partial setup progress, all rows/manifest/JUnit/exit/cleanup facts;
no automatic retry and no threshold or gold changes. Mechanical completion alone
is not answer/citation quality acceptance.

## Original installed release — `5ae91c238`

The measured application candidate source is
**`5ae91c238612bdd9b1511b3716d34b4fa5960dd2`**, pushed to `main` and
`codex/ux-integration-20260929`. Local checks and the matching-signer APK build pass.
**Candidate released at 10:10:33 UTC for the authorized data-preserving demo update.**
Fresh ordinary Android evidence passes all 281 cases with no failures or skips.
The sole demo runner has installed the candidate using `install -r`; the
coordinator independently rehashed the copied installed app/test APKs and verified
exact release identity. The original app APK backup is intact. The physical answer
check timed out after 901.58 seconds with no valid rows or manifest; model/demo
readiness remains unproven under `skein-gg11.37`. The original
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
passed preflight, booted the AVD and reached both expected tests. Actual XML
contains two failures, zero errors/skips: Espresso Device could not obtain a gRPC
port, before Activity launch. The AVD identity/hinge configuration passed; no valid
geometry was recorded. Original ZIP SHA256 is
`a46232cae68190f6a839e47d8ef7a521440836d605d6f9ac6a3a250bcad701c0`.
A bounded test-control repair is integrated at `2e1dcbafd645d4bb9348340905949bc5cae90acf`: the app retains its no-network
permission invariant, so target-process TCP gRPC is unsuitable. The selected
alternative uses emulator-only framework state overrides and rotation with
unchanged Activity/geometry assertions, while retaining APKs through evidence
collection. This measures framework state changes, not hinge-sensor actuation. Explicit app
ktlint and both affected AndroidTest compilations pass, alongside all ten host
contracts. The original lint failure and corrected check are retained in the
[transport bundle](../ux/runs/2026-09-29-fold-transport-2e1dcbaf/). Fresh generic
[run 36559029738](https://github.com/HeyZeus100/skein/actions/runs/36559029738)
finished with two failures before Activity launch. The catalog and committed
CLOSED state are valid, but the actual window remains 674×841dp through the
15-second geometry check. APK retention preserves the catalog; original artifact
SHA256 is `022aa3b620c1238e68f1a7d8f32fd175add3c1c50c28b193fa146363244d4e7b`.
Framework override alone does not actuate this generic folded region. A bounded
host-console fold/unfold transport proposal is under review, with assertions
unchanged. [Complete follow-up evidence](../ux/runs/2026-09-29-followup-2e1dcbaf/)
also verifies fresh screenshots and the actual matching native/release pairs.
Application code/APKs remain at the released `5ae91c238` source. The exact Pixel 9 Pro Fold
profile remains the default and a separate open acceptance gate.

The separate [69dc CI follow-up bundle](../ux/runs/2026-09-29-ci-followup-69dc5cf8/)
verifies 5,079 unit passes / 88 skips, 552 unchanged screenshots and actual matching
native/release pairs. Unit results include 28 FROM-CACHE task lines; fresh screenshot
and cold reproducibility lanes are distinguished. Both release APKs hash to
`14d0184d7bf029b219261440eed47eb954d0a5d0c907e67ddbbfe305e12cf2cd`.
The changed source epoch means this is within-source equality, not equality to the
released 5ae APK. No replacement candidate is implied.

The later scheduled ordinary run [36567852296](https://github.com/HeyZeus100/skein/actions/runs/36567852296)
at `aef738b11fb9869e2e5d4b58df629885b511a359` independently passes all **281 current
cases**, zero failures/errors/skips; actual connected tasks execute. The three
current module XML files have the exact expected identities. Its artifact manifest
also captures three historical XML files under committed `docs/` evidence; these
are excluded from the current count. All seven manifest records hash correctly;
four separate built-APK hashes are declarations. Original ZIP SHA256 is
`a9d97b45fbbaa2f5f34d3ead2ace4ea43362c5700e20607e5e239c7a6e72f3ce`.
[Current-only raw XML and review](../ux/runs/2026-09-29-followup-2e1dcbaf/scheduled-ordinary-36567852296/)
retain that distinction. Claimed `skein-op8v` narrows future collection/upload paths
without changing runtime acceptance or deleting historical evidence.

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
partial run; the selected app-private model also remains a separate gate.

The fresh second native attempt reports host completion and five passing parity
cases; its raw JSON SHA256 is
`34e0dd8f53e6a659131b84fbe696aeaec9a8bcbe7208e09e3ab3bf876547f727`.
The coordinator independently rechecked those rows. A separate host-helper review
found that the package-filtered process dump still emits some global metadata.
The demo owner acknowledged the finding after the answer phase had started with
the frozen v2 driver. Existing broad captures and reviews embedding them remain
local, ignored, unreviewed and unshared; no extra queries are authorized. A separate
host-only v3 helper will retain structured guard results, categories and hashes
without raw global metadata. Required in-flight cleanup is not interrupted. The candidate
release already permits the subsequent data-preserving update and exact answer
check, with unchanged limits. The app update succeeded with `install -r`, exit 0.
The copied installed app is 125,280,302 bytes with the released `1f83b71c…` digest;
the installed test APK matches `889db458…`. The pre-update 123,290,168-byte backup
remains at `b610ca50…`. All three complete digests were independently rehashed by
the coordinator and recorded in `physical-installed-apks.json`. No root device
commands were run.

The subsequent normal MainActivity launch completed in 2,480 ms, but the sole
runner observed the system keyguard still showing. The demo session has requested
normal owner authentication. Vault unlock, the new shell, model availability and
UI rehearsal remain unobserved; no keyevents, security changes or screenshots were
used to bypass that dependency. See the [physical checkpoint](../demo/conference-2026/runs/2026-09-29-physical/).

The answer phase hit the unchanged 900-second host watchdog at 901.58 seconds:
exit 124, JUnit not passed, zero valid answer rows and no remote run manifest.
The sole runner verified remote shutdown with consecutive absent-PID checks and
no active instrumentation. The later `Process crashed` marker followed cleanup;
it does not prove a spontaneous native crash. The original invalid collector
outputs, synthetic instrumentation log and minimal review are preserved under
`physical-answer-timeout/`; no automatic retry occurred.

Source triage under claimed `skein-gg11.37` observes that the run manifest is
written before engine session/load/case operations. Earlier full-file SHA256 and
pure Kotlin BLAKE3 hashing are outside all per-case timeouts and emit no progress.
Absent output therefore points toward pre-manifest setup, subject to excluding
output-path/write failure; a slow hash is a hypothesis, not a measured cause.
Native parity exercises SHA256 and native loading without this app-harness BLAKE3
pass or Binder path. Privacy-safe phase/byte progress is needed before a retry;
no hashes, limits, frozen fixtures or quality thresholds may be weakened. The demo owner's isolated opt-in-only helper is verified at `aee46e4d6c391bddbfb55296117889e1f79afff6`
and integrated at `8ae202a095a0725a0190195e7cf9f470ec39a4ac`. It adds early phase
progress and optional explicitly bound host BLAKE3 identity, retaining full device
SHA256/byte count and the old BLAKE3 path when omitted. All 546 app cases pass;
explicit lint and both AndroidTest compilations pass. Root combined checks use
both opt-in flags and record cached unit reuse separately.

The [test-only release](../ux/runs/2026-09-29-synthetic-setup-aee46e4/) was published
at **14:05:29 UTC**. The new test APK is 91,163,348 bytes, SHA256
`9575f53a36e6dbd1dcd9f4cdcfe4af6407a74d1566343bf5bb29ca0bc9edef95`, with the matching
single signer and expected target. Only `classes7.dex` differs inside its archive;
no native libraries are present. The application and native test APK remain exact
5ae artifacts. The final host runner passes 31 mocks plus independent checks,
allows only test-package installation and pins explicit prior parity reuse.
The sole demo runner acknowledged the release at **14:12:38 UTC**, reverified
host artifact pins and completed host-only validation. The owner has now unlocked
normally; the demo session reports a usable split-pane shell and a normal picker
import of staged fictional construction notes into a fresh synthetic setup. The
current UI workload retains the device slot. Instrumentation waits for safe idle
and rechecks installed identity before the one permitted exact-method attempt.
Unchanged 900/180-second limits and all four cases apply. Actual retry progress
and generated-answer evidence are pending;
no production crypto or inference implementation changed.

The distributor/base-model license-label discrepancy
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

Inference `.19`/`.20` retain remaining long-load, foreground-service and process-death
acceptance beyond the selected Android cases proven above;
model readiness `.28`/`.30`, CPU/Vulkan decisions `.26`/`.27`, formal M0 and the
Q4/Gemma matrix remain open. Retrieval quality remains failed/open under
`skein-gg11.32`; full hybrid is **INELIGIBLE**, with the existing embedding chain
`skein-lbw → skein-079 → skein-hwsa` and validation `skein-9744` unchanged. Neither
the demo input nor host/native identity evidence changes those gates.

Beads remains the task tracker; coordinator/inference/demo/hardware JSON files are
coordination records. Existing stashes, owner files and recovery worktrees remain
preserved. The recorded missing Dolt remote was honored without retrying push.

The follow-up console transport is integrated at `f667a31cb73e9b4d349fc1b90b372b62166d5df4`,
with a formatting-only correction at **`17348bb45b96d22693c4ce9aeda266905accdb27`**.
[Local console evidence](../ux/runs/2026-09-29-fold-console-17348bb4/) preserves the
initial lint failure and corrected explicit app check/lint/both AndroidTest compile
pass (803 tasks; 21 executed). All 35 combined host tests pass. The retained 80 XML
files contain 546 passing cases; these unit tasks were up to date, so this is reuse
of previous test evidence. Both source reviewers approved the fixed-serial CI-only
transport, bounded shutdown, timeout poisoning and chronological six-action
protocol verification. Actual emulator geometry remains pending.

At 14:29–14:32 UTC the demo session relayed additional explicit owner requests.
Claimed `skein-za8d` covers fresh New chat identity, removal of the enabled teal
hinge debug guide, collapsible Chat/Knowledge lists and first-send Knowledge off.
Claimed `skein-pknl` covers a separate independently selectable split workspace
(e.g. note/chat or two distinct notes). The demo owner holds only the bounded
landing/first-send controller seam; isolated coordinator workers own navigation
and shell state/presentation. No draft or turn controller rebuild is planned.
The sole physical runner is paused. The prior released app/test artifacts remain
immutable; a new app requires new verification and an explicit release before
further physical rehearsal. Private owner screenshots are not published.
