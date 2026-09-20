# Security Policy

## Overview

Skein is an offline-first personal knowledge system with a commitment to privacy and transparency. This document describes how security vulnerabilities are handled and the scope of our security guarantees.

## Security Guarantees

Skein maintains the following design-level security properties:

1. **No network access**: The app has no `INTERNET` permission and performs no network operations. This is enforced by the manifest and verifiable via GrapheneOS network controls.

2. **No telemetry**: The app does not collect, transmit, or analyze any user data, crash reports, or analytics. This is an architectural guarantee, not a configuration option.

3. **No Google Play Services dependency**: The app functions identically with or without sandboxed GMS and includes no proprietary runtime dependencies.

4. **Process isolation**: Inference engines run in isolated processes (`android:isolatedProcess="true"`) to contain the blast radius of model-parsing exploits.

5. **Reproducible builds**: All releases are reproducible from the source tag, enabling independent verification.

6. **Vault encryption**: User data is encrypted at rest using SQLCipher with StrongBox-backed keys.

## Reporting Vulnerabilities

**Do not open a public GitHub issue for security vulnerabilities.** Instead, please report privately to:

**Email:** `andrew@aherrera.us`

When reporting, please include:

- A clear description of the vulnerability
- Steps to reproduce (if applicable)
- The affected version(s)
- Any potential impact assessment

### What to Expect

- **Acknowledgment:** Within 48 hours
- **Investigation:** We will assess the severity and scope
- **Patch timeline:** Critical vulnerabilities will be patched within 14 days; high-priority within 30 days
- **Disclosure:** Coordinated disclosure is preferred. We will notify you before publishing a patch

### PGP Key

<TODO: Add PGP key fingerprint and public key link when available>

## Scope

### In Scope

- Vulnerabilities in Skein's code or dependencies that affect user privacy, data integrity, or system stability
- Bypasses of the `INTERNET` permission restriction or manifest guards
- Cryptographic weaknesses in vault encryption or model verification
- Unintended telemetry or network access

### Out of Scope

- Social engineering or phishing attacks
- Denial-of-service attacks targeting device resources (battery, storage)
- Hypothetical attacks requiring rooting or flashing a custom kernel
- Vulnerabilities in the Android OS, device firmware, or dependencies like llama.cpp (report directly to their maintainers)
- Reports without reproducible steps on a supported device

## Supported Versions

Security patches are released for:

- The latest tagged release and the `main` branch
- Development builds (prerelease tags) on a best-effort basis

## Third-Party Dependencies

Skein relies on several open-source projects:

- **llama.cpp** (MIT) — Inference engine
- **sqlite-vec** (Apache 2.0) — Vector storage
- **GLiNER** (Apache 2.0) — Entity extraction
- **SQLCipher** (Proprietary; used via open-source binding)

We track security advisories for these projects and will patch or work around issues as needed. Audit reports and dependency lists are tracked in `docs/THIRD_PARTY_AUDIT.md` and updated during each release cycle.

## Coordinated Disclosure

We practice coordinated disclosure:

1. Reporter privately discloses vulnerability
2. We confirm receipt and begin investigation
3. Once a patch is ready, we notify the reporter and set a disclosure date
4. We publish the patch and credit the reporter (unless they prefer anonymity)

The standard disclosure window is 90 days from report date, unless a shorter timeline is justified by active exploitation or imminent threat.

## History

No security vulnerabilities have been reported as of 2026-09-19.
