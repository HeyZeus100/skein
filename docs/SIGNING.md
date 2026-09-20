# Release signing (E1.I9)

This document covers how the Skein release key is generated and stored,
how a release APK is signed, how a user verifies what they downloaded,
and the key rotation policy. It exists because spec §10 requires
reproducible, verifiable releases, and `SECURITY.md` promises a
cryptographic story a user can actually check rather than "trust us."

**Scope**: this is about the *release signing key* used to sign the
distributed APK (`AllowedAPKSigningKeys`, `E8.I6`). It is unrelated to the
per-user vault encryption keys described in `docs/design/` and
`SECURITY.md`'s "Vault encryption" guarantee — those are generated on-device
per install and never leave the device; the release signing key is a single
key the maintainer controls and reuses across releases.

## Threat model, briefly

A user downloading a release APK from GitHub (or building it themselves)
needs to answer two questions:

1. "Is this the file the maintainer actually built and signed?" — answered
   by comparing the APK's SHA-256 against the published `SHA256SUMS` and by
   checking the signing certificate fingerprint against the one published
   here and in `SECURITY.md`.
2. "Did the *maintainer's* key sign it, not an attacker who compromised the
   download channel?" — answered the same way: the certificate fingerprint
   is out-of-band knowledge (published here, and ideally cross-checked via
   a channel other than the download itself, e.g. this doc's git history).

Reproducible builds (`docs/REPRODUCIBLE_BUILDS.md`,
`.github/workflows/reproducible-build.yml`) answer a third, related
question — "does this source tag actually produce these bytes?" — but that
check runs on the *unsigned* APK. Signing happens after, by hand, on a
machine CI never touches.

## Key custody

### Generation

The release key is a single EC (secp256r1 / NIST P-256) key pair,
generated **offline**, on a machine with no network connection, using:

```bash
keytool -genkeypair -keyalg EC -groupname secp256r1 -validity 10000 \
  -keystore skein-release.jks \
  -alias skein-release \
  -dname "CN=Skein Release, OU=Skein, O=Skein Project"
```

Notes on this command:

- `-groupname secp256r1` selects the NIST P-256 curve explicitly (the
  modern `keytool` flag; older `keytool` versions use `-keysize 256` with
  `-keyalg EC` instead, which selects the same curve implicitly).
- `-validity 10000` (~27 years) is intentionally long. Certificate
  expiry is not a meaningful security boundary for an APK signing key —
  Android does not reject an app because its signing certificate has
  "expired" the way TLS does — and a key that outlives its own rotation
  schedule (see below) avoids an unplanned re-signing event. The reasons
  to rotate are operational (suspected compromise, or a scheduled review),
  not expiry.
- No `-storepass`/`-keypass` on the command line: `keytool` will prompt
  interactively, keeping the password out of shell history and `ps`.
- EC over RSA: smaller keys and signatures for the same security margin,
  and it's what `apksigner`/AOSP recommend for new v3+ signing keys.

`keytool -genkeypair` is run once, by a human, on a machine that:

- has never had, and will never have, network access while the keystore
  exists on it (an air-gapped machine, or a machine put into airplane
  mode / physically disconnected for the duration);
- is not the machine that runs CI or holds any CI credentials;
- is wiped or physically destroyed if it's a disposable/one-time setup
  (e.g. a live-boot environment), rather than reused for anything else.

### Storage

The keystore file (`*.jks` — never committed; see `.gitignore`) and its
password are kept **outside this repository**, with two independent
encrypted backups:

1. A **Cryptomator vault** (or equivalent client-side-encrypted container)
   synced to cloud storage the maintainer controls. The vault password is
   never stored alongside the vault itself.
2. An **air-gapped physical disk** (encrypted volume, e.g. LUKS/FileVault/
   VeraCrypt) kept offline and offsite from the maintainer's primary
   residence/workspace (a safe deposit box, a trusted third party, or
   similar) — the point is a backup surviving a single-location disaster
   (fire, theft, flood).

Both backups store the keystore file and, separately from the keystore
file itself, a note of which alias to use and the certificate fingerprint
below (so recovery doesn't depend on remembering `-alias skein-release`).
The keystore password itself is memorized and/or stored in a password
manager, never written down next to the keystore file in either backup.

Neither backup, nor the working copy on the signing machine, is ever
placed on a machine that also has network access at the same time the
keystore file is present on it. When the signing machine needs to fetch
the unsigned APK to sign (e.g. copied over from a build machine via USB
drive) and later needs to upload the signed result, that transfer happens
with the keystore file removed from the transfer medium, or via a medium
never plugged into the signing machine while the keystore is resident on
it. In practice: copy the unsigned APK in, disconnect, sign, copy the
signed APK + `SHA256SUMS` out, disconnect.

### Published fingerprint

The SHA-256 fingerprint of the release signing certificate:

```
TBD
```

This is a placeholder. It is filled in by the maintainer the first time
`tools/release/sign.sh` is run against the real release keystore (the
script prints this fingerprint; copy it here and into `SECURITY.md`
verbatim). Until this is filled in, there is no real release signing key
yet and this document describes the *procedure*, not a live guarantee.

Once filled in, a change to this value must come with:

- a corresponding update to `SECURITY.md`'s published fingerprint (kept
  in sync — see `E9.I3`);
- a bd bead explaining why (see "Rotation policy" below — routine rotation
  is the *only* expected reason; anything else, e.g. a value that changed
  without a matching bead and coordinated release, should be treated as a
  possible supply-chain compromise and investigated, not silently trusted).

## Signing a release

Signing is a **human-run, local step**. CI never has the keystore or its
password — see "CI and the release pipeline" below.

### Prerequisites

- `apksigner` on `PATH` (ships in `$ANDROID_HOME/build-tools/<version>/`;
  match the `compileSdk`/build-tools version this project builds with —
  see `app/build.gradle.kts` and `gradle/libs.versions.toml`).
- The release keystore, restored from one of the two backups above onto
  the signing machine.
- An unsigned release APK, built via `./gradlew assembleFossRelease`
  (or downloaded from the reproducible-build CI artifact, after checking
  the reproducible-build workflow passed for that tag).

### Running `sign.sh`

```bash
tools/release/sign.sh app-foss-release-unsigned.apk \
  --keystore /path/to/skein-release.jks \
  --alias skein-release \
  --out skein-1.0.0-foss.apk
```

The script:

1. Prompts for the keystore password on the terminal, with input echo
   disabled (`stty -echo`). The password is never accepted as a
   command-line argument (it would be visible via `ps` to any other user
   on the machine), never logged, and never written to a file. It's
   forwarded to `apksigner` over `apksigner`'s own stdin
   (`--ks-pass stdin` / `--key-pass stdin`), which is how `apksigner`
   itself recommends avoiding argv/`ps` disclosure.
2. Runs `apksigner sign` with `--v1-signing-enabled false`,
   `--v2-signing-enabled true`, `--v3-signing-enabled true`, and
   `--v4-signing-enabled true`. V1 (JAR signing) is disabled outright — it
   predates `minSdkVersion = 30` by a wide margin and adds nothing but
   attack surface for an app that never supports pre-Nougat devices.
3. Verifies the result with `apksigner verify --verbose` and asserts the
   signature schemes actually enabled are actually present (see "A note
   on `apksigner verify` and scheme reporting" below for why this isn't
   quite the naive "grep for `true` four times" it might look like).
4. Writes `SHA256SUMS` (via `sha256sum` on Linux, `shasum -a 256` on
   macOS — whichever is on `PATH`) covering the signed APK and its
   `.idsig` file.
5. Prints the signing certificate's SHA-256 fingerprint
   (`apksigner verify --print-certs`), which should match the one
   published above and in `SECURITY.md`.

Exit status is non-zero if any of the above fails — a partial/incorrect
signing result should never be silently treated as success.

### A note on `apksigner verify` and scheme reporting

While building `sign.sh`, we found that `apksigner verify`'s
"Verified using vN scheme: true/false" output does **not** mean "is this
signature block present in the file." It means "was this scheme actually
needed and used to satisfy verification for the SDK range being checked,"
which defaults to the range implied by the APK's own
`minSdkVersion` (30, for this app).

Because APK Signature Scheme v3 was introduced in Android P (API 28) and
this app's `minSdkVersion` is already 30, a plain `apksigner verify
--verbose` on a Skein release APK will report:

```
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v4 scheme (APK Signature Scheme v4): false
```

— even though the v2 signature block **is** present and cryptographically
valid (we enabled it, and confirmed this directly). `apksigner` simply
doesn't need it to satisfy verification across API 30+, so it doesn't
report it as "verified." This is correct, expected `apksigner` behavior,
not a signing bug — but it means a script (or a person) that naively
asserts "v2 must say true" against the default output will always fail on
a real Skein build.

`sign.sh` and `tools/release/verify.sh` work around this the same way a
person would if they wanted to double-check v2 specifically: they pass
`apksigner verify --min-sdk-version 24` (API 24 is v2's own minimum,
Android Nougat), which asks `apksigner` to also confirm the file verifies
for that older, hypothetical range — and it does, showing:

```
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

v4 (APK Signature Scheme v4, Android 11 / API 30, used for incremental
installs) has no equivalent CLI verification path at all in the
`apksigner` version shipped in this project's build-tools — `verify` never
reports `v4: true`, regardless of the SDK range requested, because v4
verification is an on-device, install-time check (`adb install
--incremental`), not something `apksigner verify` implements offline. So
instead, both scripts check v4 **structurally**: the APK's companion
`.idsig` file must exist, be non-empty, and begin with the little-endian
`uint32` file-format version `apksigner` writes for the v4 signature
format (`2`, as of this writing). This confirms `apksigner sign` actually
produced a v4 signature file; it does not re-verify the cryptography
inside it (that's what an actual Android 11+ device's incremental
installer does).

If a future `apksigner` version changes either of these behaviors (adds a
CLI v4 check, or changes what "verified" means for satisfied ranges), the
assertions in `sign.sh`/`verify.sh` should be revisited — this section
exists so the next person doesn't have to rediscover this from scratch.

## Reverse verification (`verify.sh`)

`tools/release/verify.sh` is what a downstream reproducer, or an end
user, runs against a signed APK they built or downloaded:

```bash
tools/release/verify.sh skein-1.0.0-foss.apk \
  --fingerprint <the fingerprint published above> \
  --sums SHA256SUMS
```

It re-runs the same scheme checks as `sign.sh` (v1 disabled, v2/v3
verified via the widened SDK range, v4 idsig present and well-formed),
confirms the signing certificate's SHA-256 fingerprint matches the one
given, and — if `--sums` is given — confirms the APK's own file hash
matches the entry in `SHA256SUMS`. It never touches a keystore or private
key; everything it checks is derivable from the public, already-signed
APK plus the published fingerprint.

Exit status is non-zero on any mismatch.

## End-user verification instructions

If you downloaded a Skein release APK and want to confirm it's genuine
before installing it:

1. Download the release APK and the `SHA256SUMS` file from the same
   GitHub release.
2. Check the file hash:
   ```bash
   sha256sum -c SHA256SUMS   # Linux
   shasum -a 256 -c SHA256SUMS   # macOS
   ```
   (Filter to the APK's line first if `SHA256SUMS` also lists the
   `.idsig` file, e.g. `grep skein-1.0.0-foss.apk SHA256SUMS | sha256sum -c -`.)
3. Check the signing certificate fingerprint:
   ```bash
   apksigner verify --print-certs skein-1.0.0-foss.apk | grep 'SHA-256'
   ```
   and compare it, by eye, against the fingerprint published above and in
   `SECURITY.md`. They must match exactly.
4. Optionally, run `tools/release/verify.sh` (this repo) to automate
   steps 2–3 in one command, including the signature-scheme checks:
   ```bash
   tools/release/verify.sh skein-1.0.0-foss.apk \
     --fingerprint <fingerprint from SECURITY.md> \
     --sums SHA256SUMS
   ```

If either check fails, **do not install the APK** — treat it as
potentially tampered with, and report it (see `SECURITY.md`'s reporting
instructions).

## Rotation policy

- The release key is reviewed **every 2 years** for whether rotation is
  warranted (calendar reminder / bd bead recurring on that cadence — file
  a new bead each cycle rather than relying on memory).
- A rotation happens **only** via a bd bead tracking the decision and a
  **coordinated release**: the bead documents why (scheduled review with
  no findings still gets a bead recording "reviewed, no rotation needed,"
  so there's an audit trail either way), the new key is generated per
  this same offline procedure, both this document and `SECURITY.md` are
  updated with the new fingerprint in the same change, and the release
  notes for the first APK signed with the new key call out the rotation
  explicitly so users know to expect a different fingerprint.
- Immediate (out-of-cycle) rotation happens if the key is suspected
  compromised (e.g. a backup medium is lost or accessed by someone
  untrusted) — the same bead + coordinated-release process applies, just
  triggered early, and the security advisory process in `SECURITY.md`
  is used to notify users the *old* key should no longer be trusted.
- **We do not use APK Signature Scheme v3.1 key rotation**
  (`--rotation-min-sdk-version` / `SigningCertificateLineage`). Android
  does not mandate v3.1-style in-band rotation, and adopting it would
  mean maintaining a `SigningCertificateLineage` file and coordinating
  its distribution — complexity this project doesn't need. A rotation
  here means a new release starting from a new key, published the same
  way as the original, not an in-place cryptographic hand-off recognized
  automatically by already-installed copies of the app. Users upgrading
  across a rotation reinstall (Android will refuse to update an app
  in-place across an unrelated signing key, by design) — this is a real
  practical consequence of not using v3.1 rotation, and is documented in
  release notes when a rotation happens.

## StrongBox second attestation

Spec §10 mentions "StrongBox-attested" signing keys. The release signing
key itself is a plain offline EC keystore key (above) — it is not, and
cannot be, a StrongBox key, because StrongBox keys are hardware-bound to
one specific device's secure element and cannot be exported or backed up,
which is incompatible with the offline-generation-plus-backup custody
model this document requires for a key that must survive hardware loss.

The interpretation used here (see `OQ-8` and the `skein-6pf` description)
is instead:

1. The release key's public certificate fingerprint is published (this
   document, `SECURITY.md`).
2. **Independently**, a StrongBox-backed key resident on the maintainer's
   own device (referred to as "the Fold" in planning notes) signs the
   `SHA256SUMS` file as a **second, hardware-backed attestation** — not a
   second APK signature, but a signature *over the SHA-256 manifest*,
   giving a user two independent things to check: the release key's
   signature (software keystore, offline, backed up) and a StrongBox
   attestation (hardware-bound, non-exportable, made on a specific
   physical device) that agree on the same file hashes.

This second attestation is intentionally **out of scope for this bead**.
`tools/release/attest-on-device.sh` is a stub with a `TODO` marking where
the on-device StrongBox signing flow belongs; implementing it (choosing
an attestation format, wiring up the Android Keystore StrongBox API,
deciding how the attestation is published alongside a release) is
tracked as follow-up work — see the bd bead search for `attest-on-device`
or file a new one if none exists yet before starting that work.

## CI and the release pipeline

- `.github/workflows/reproducible-build.yml` builds the **unsigned** foss
  release APK twice from a clean checkout and diffs the SHA-256 sums. It
  never invokes `apksigner sign`, never sees a keystore, and has no
  secrets configured for one. This is what "reproducible" means here:
  independently verifiable *before* any human-controlled signing step
  touches the file.
- The release workflow (`E8.I2`) accepts an **already-signed** APK,
  `SHA256SUMS`, and the idsig file, uploaded by the human who ran
  `sign.sh` locally. It does not sign anything itself.
- If you are auditing this project's CI configuration for signing-key
  exposure: there is nothing to find, by design. Search
  `.github/workflows/` for `apksigner sign`, `keystore`, or `KEYSTORE_*`
  secrets — none should exist. If one ever does, treat it as a security
  incident (the key would need to be treated as compromised and rotated
  per the policy above).
