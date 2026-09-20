# Skein

Personal knowledge system for Android. Offline. On-device LLM. GrapheneOS-first.

> **Status:** Pre-alpha. Design spec approved 2026-09-19. Implementation plan generation in progress. No shipping releases yet.

## What Skein is

An offline-first personal knowledge system with an on-device LLM as its interface. Chats, notes, and AI outputs are all first-class documents in a single wiki-shaped vault. Runs entirely on the device — no cloud, no telemetry, no network permission.

**Design targets:** GrapheneOS on Pixel 9 Pro Fold–class hardware (Tensor G4, 16 GB RAM, 256 GB storage).

## Non-negotiable design principles

- No `INTERNET` permission in the manifest, verifiable via GrapheneOS's per-app network toggle
- No Google Play Services dependency
- No telemetry, no crash reporting, no analytics
- Reproducible builds from the first tagged release
- All model files hash-verified before `mmap`; sigstore attestation supported
- Inference runs in an isolated process (`android:isolatedProcess="true"`)
- `foss` build flavor uses only Apache-2.0 / MIT / permissive dependencies

## Where things live

- **Changelog:** [`CHANGELOG.md`](CHANGELOG.md) — release history and unreleased changes (Keep A Changelog format)
- **Design spec:** `docs/superpowers/specs/2026-09-19-skein-design.md`
- **Implementation plan:** `docs/superpowers/plans/` *(generation in progress)*
- **Threat model:** `docs/THREAT_MODEL.md` *(pending, delivered by M3)*
- **Privacy notes:** `docs/PRIVACY.md` *(pending)*
- **Security policy:** [`SECURITY.md`](SECURITY.md)
- **Governance:** [`GOVERNANCE.md`](GOVERNANCE.md)
- **Contributing:** [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **M0 benchmark harness:** `tools/m0-benchmark/` *(refactor in progress under `skein-79od` per the hardware handoff below)*
- **Fold hardware lab handoff:** [`docs/Handoffs/skein-fold-m0-hardware-handoff.md`](docs/Handoffs/skein-fold-m0-hardware-handoff.md) — authoritative record of the proven Mac + ADB + SSH-to-Termux topology
- **Design docs:** [`docs/design/`](docs/design/) — Artifact Engine sketch, post-review resolutions, lock policy vs. background indexing, vault tool primitives, amalgamation policy, skill guardrails
- **Task tracking:** `bd` (beads) — see `bd ready` for available work

## License

Apache 2.0. See [`LICENSE`](LICENSE).

## Contributing

Contributor DCO (`Signed-off-by`) required. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for guidelines, [`GOVERNANCE.md`](GOVERNANCE.md) for decision-making, and [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) for community standards.

## Distribution (once shipping)

- GitHub Releases (signed APK, primary)
- Obtainium manifest
- Accrescent
- IzzyOnDroid
- F-Droid main

Not distributed on: Play Store, GrapheneOS official repo.
