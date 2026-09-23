# Backup exclusions and Seedvault verification (E3.I7)

Skein ships `android:allowBackup="true"` so the OS backup/restore and
device-to-device (D2D) setup transfer machinery is available at all
(spec §9 scopes backup rather than disabling it outright — see
`docs/design/POST_REVIEW_RESOLUTIONS.md` §4). What actually gets backed
up is controlled entirely by the exclusion rules in:

- `app/src/main/res/xml/data_extraction_rules.xml` — API 31+, referenced by
  `android:dataExtractionRules`. Has separate `<cloud-backup>` and
  `<device-transfer>` sections.
- `app/src/main/res/xml/backup_rules_legacy.xml` — API 30 fallback,
  referenced by `android:fullBackupContent`. `minSdk = 30`, and
  `dataExtractionRules` only exists on API 31+, so this file is required —
  without it, an API 30 device would fall back to backing up *everything*.

## Why this matters for GrapheneOS / Seedvault specifically

Seedvault is GrapheneOS's on-device backup app. It backs up apps using the
same `BackupAgent`/`FullBackup` machinery as Google's D2D "Copy your data"
flow and `adb shell bmgr`, which means it is subject to the *same*
`dataExtractionRules`/`fullBackupContent` exclusions as cloud backup — but
the review that produced `docs/design/POST_REVIEW_RESOLUTIONS.md` §4 flagged
a specific trap: **`<device-transfer>` is a separate section from
`<cloud-backup>`**, and Seedvault's restore/transfer path exercises the
`<device-transfer>` rules, not `<cloud-backup>`. An app that only populates
`<cloud-backup>` (or that only tests via `bmgr backupnow`, which exercises
`<cloud-backup>`) can pass every automated check while Seedvault's D2D-style
transfer still captures the vault database. This is why both files below
name the sensitive paths in **both** sections, and why the manual procedure
below exercises both codepaths.

## What is excluded

Both `data_extraction_rules.xml` and `backup_rules_legacy.xml` exclude:

| Path | Why |
|---|---|
| `domain="database"` (whole domain) | Room/SQLite/SQLCipher database directory |
| `file/vault.db`, `vault.db-wal`, `vault.db-shm`, `vault.db-journal` | The SQLCipher vault and its journal/WAL/SHM siblings, in case a build path ever writes the DB file outside the `database` domain |
| `file/keys/` | Key material — `keys/key-envelope.v1`, the Keystore-wrapped vault master (`skein-txrh`; `docs/VAULT_FORMAT.md` §1). Wrapped-only, never plaintext, but it must not travel without the device's Keystore that unwraps it |
| `file/models/` | Model weights (GGUF, ONNX) |
| `file/attachments/` | User attachments |
| `root/cache/staging_export/` | Export staging cache (`docs/design/POST_REVIEW_RESOLUTIONS.md` §4) — plaintext staged briefly during PDF/DOCX export |
| `external/.` | Defensive — this app never writes to external storage |
| `root/.` | Blanket fail-safe: anything not named above is still excluded by default |

`data_extraction_rules.xml`'s `<cloud-backup>` section additionally excludes
`sharedpref/unlock_state.xml` — unlock state must never leave the device via
cloud sync, even though it is allowed to move with `<device-transfer>` (a
transfer to a new device the same user already controls).

`DataExtractionRulesTest` (`app/src/test/kotlin/app/skein/DataExtractionRulesTest.kt`)
parses both XML files and asserts this exclusion set is present in every
section, and that `<cloud-backup>`, `<device-transfer>`, and
`backup_rules_legacy.xml` all agree (modulo the `unlock_state.xml` cloud-only
rule). `ManifestPolicyTest` asserts the manifest's `android:dataExtractionRules`
and `android:fullBackupContent` attributes point at these two files by name.

Real device verification against Seedvault itself is out of scope for this
issue (it needs the M0-provisioned Fold hardware with GrapheneOS + Seedvault
installed); the procedure below is what a future session with that hardware
should run.

## Manual verification procedure (requires GrapheneOS + Seedvault hardware)

This exercises both the `bmgr`/cloud-backup path and, as closely as `adb`
allows, the D2D/device-transfer path Seedvault actually uses.

### 1. Prerequisites

- A GrapheneOS device (or the Fold once M0-provisioned) with Seedvault set
  up as the active backup transport (Settings → System → Backup) and at
  least one backup destination configured (USB drive or a second device).
- `app.skein` installed in debug or
  dev build, with the vault unlocked and containing at least one document,
  one attachment, and (if present) one downloaded model, so there is
  something in every excluded path to prove is *absent* from the result.
- `adb` with USB debugging enabled.

### 2. Confirm the backup transport and enable backup for the app

```bash
adb shell bmgr list transports
# Confirm the Seedvault transport is selected (marked with *)

adb shell dumpsys backup | grep -A2 app.skein
# Confirm the package is NOT on the ineligible/opted-out list
```

### 3. Force a backup and capture the agent's view

```bash
adb shell bmgr backupnow app.skein

adb logcat -d | grep -iE "BackupManagerService|FullBackup|PerformBackupTask|app\.skein" > backup_log.txt
```

Inspect `backup_log.txt` for the list of files/keys the backup agent
actually processed. Confirm none of the excluded paths above appear:
`vault.db`, `vault.db-wal`, `vault.db-shm`, `vault.db-journal`, any path
under `keys/`, `models/`, `attachments/`, or `cache/staging_export/`, and no
`databases/` directory contents.

### 4. Inspect Seedvault's own record of what it stored

Seedvault exposes a per-app data listing in its restore UI (Settings →
System → Backup → Seedvault → Restore → select the backup set →
`app.skein`). Screenshot or note the listed data categories/size. A backup
set that only contains a trivially small entry (or the `unlock_state.xml`-
excluded `sharedpref/` bucket, which itself should be empty or near-empty)
is consistent with the exclusions working. A backup set whose size roughly
matches the vault DB size is a red flag that an exclusion did not take
effect.

### 5. Exercise the device-transfer path specifically

`bmgr backupnow` alone only proves the `<cloud-backup>` rules. To exercise
`<device-transfer>`:

- Trigger GrapheneOS's "Copy your data" / D2D setup-wizard flow between two
  devices (or the same device's restore-from-backup flow during a fresh
  setup), with Skein installed and populated on the source.
- On the destination, before completing setup, use `adb shell dumpsys
  backup` (as above) or inspect the restore session's logcat
  (`RestoreSession`, `PerformUnifiedRestoreTask`) for the same file list
  check as step 3.
- Confirm the restored app has an empty/absent vault (no `vault.db`),
  no `models/`, `keys/`, or `attachments/` directories, and no
  `cache/staging_export/` contents — i.e., first-run/onboarding state, not
  a magically-restored vault.

### 6. Record the evidence

Save `backup_log.txt`, the Seedvault restore-UI screenshot, and a short
note of the destination device's post-transfer file listing
(`adb shell run-as app.skein ls -R` while unlocked, if debuggable) as the
evidence artifact for this issue and for `docs/PRIVACY.md` (`E9.I2`) to
cite. `E10.I8` (`BackupExfiltrationTest`) automates the marker-string
variant of step 3 against `bmgr` output; this document covers the
Seedvault-specific manual pass that automated JVM/Robolectric tests cannot
reach.
