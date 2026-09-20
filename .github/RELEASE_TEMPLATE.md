## Skein <version>

<one-line summary>

### Artifact

- APK: `skein-<version>-foss-arm64-v8a.apk`
- SHA-256: `<hash>`
- Reproducible-build verification: `diffoscope <official> <rebuilt>`
- Signing key fingerprint: `<fingerprint>`

### Changes

<pull from CHANGELOG.md's version section>

### Installation

- **GitHub Releases**: download and sideload via ADB or file manager
  ```bash
  adb install skein-<version>-foss-arm64-v8a.apk
  ```
- **Obtainium**: [add config link]
- **F-Droid**: [coming or link]
- **Accrescent**: [coming or link]

### Verification

1. Verify the APK's SHA-256 matches the value above:
   ```bash
   shasum -a 256 skein-<version>-foss-arm64-v8a.apk
   ```

2. (Optional) Reproduce the build following [`docs/VERIFICATION.md`](../docs/VERIFICATION.md)

3. (Optional) Verify the signing key fingerprint via `aapt2`:
   ```bash
   aapt2 dump badging skein-<version>-foss-arm64-v8a.apk | grep certificate
   ```

### Non-goals reminder

Skein is designed with these invariants:

- **No `INTERNET` permission** — verify via GrapheneOS's per-app network toggle
- **No Google Play Services dependency**
- **No telemetry, no crash reporting, no analytics**
- **Reproducible build** — official and rebuilt APKs are byte-identical

### Known issues

Open issues tagged `release-note`: [filter in issues](https://github.com/andrewherrera/skein/issues?q=label%3Arelease-note)

### Full changelog

See [`CHANGELOG.md#<version>`](../CHANGELOG.md) for complete details of all changes in this release.
