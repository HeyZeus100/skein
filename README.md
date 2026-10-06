# Skein

Personal knowledge system for Android. Offline. On-device LLM. GrapheneOS-first.

> **Status — 5 October 2026:** Working pre-release; V1 has not shipped. The app has encrypted notes and files, an adaptive workspace, and working on-device chat. Answer quality, complete recovery, and release acceptance remain open. The application version is still `0.1.0`. Read the [current V1 status](docs/V1_STATUS.md) for feature boundaries and the [latest handoff](docs/Handoffs/skein-continuation-20261005.md) before continuing development.

## What Skein is

An offline-first personal knowledge system with an on-device LLM as its interface. Chats, notes, and AI outputs are all first-class documents in a single wiki-shaped vault. Runs entirely on the device — no cloud, no telemetry, no network permission.

**Design targets:** GrapheneOS on Pixel 9 Pro Fold–class hardware (Tensor G4, 16 GB RAM, 256 GB storage).

## What works now

- Chat, Knowledge, Graph, Models and Settings, with adaptive single and split workspaces.
- Markdown notes, wikilinks, backlinks/local graph, file imports and basic extracted-text reading.
- Local-model chat with streaming, Stop/retry, saved history, retained drafts, context inspection and source navigation.
- Model import/management, themes, encrypted-vault access and lock controls.
- Note actions for sharing text and exporting Markdown, DOCX and PDF; complete export safety and device acceptance remain open.

Recent work strengthens source-aware follow-ups, draft/file lifecycles, local foldable tests and internal recovery activation. Reliable cited answers and production semantic embeddings are not yet qualified. Recovery activation is internal and still needs public app integration and real authentication acceptance. Temporary Chats and vision are not implemented; **Knowledge off still saves ordinary chats**. Spaces have a switcher and scoped context, but their management screen remains a placeholder.

The V1 goal is **unlock → write/import → index → search/ask → cite → approve edit → export**. Inline AI edit approval remains unfinished. The [status page](docs/V1_STATUS.md) distinguishes this goal from shipped capability and later Artifact Engine work.

The October 4 checkpoint passed 5,776 local tests with 86 existing skips and 16 bounded encrypted-runtime tests with synthetic authentication. These are implementation/runtime results, not model-quality or physical acceptance. The last verified Fold installation remains `e62f94785`; the latest source has not been installed there. [Evidence and source identities](docs/Handoffs/skein-continuation-20261004.md).

Verification runs locally by default. All eleven hosted workflows are manual-only and require an explicit owner request; this documentation update ran no builds or hosted CI. See the [verification policy](docs/LOCAL_VERIFICATION.md).

## Non-negotiable design principles

- No `INTERNET` permission in the manifest, verifiable via GrapheneOS's per-app network toggle
- No Google Play Services dependency
- No telemetry, no crash reporting, no analytics
- Reproducible builds from the first tagged release
- All model files hash-verified before `mmap`; sigstore attestation supported
- Inference runs in an isolated process (`android:isolatedProcess="true"`)
- `foss` build flavor uses only Apache-2.0 / MIT / permissive dependencies

## Where things live

- **Current product status:** [V1 status](docs/V1_STATUS.md) — implemented features, evidence and open release gates
- **Continuation:** [Latest handoff](docs/Handoffs/skein-continuation-20261005.md) and [handoff index](docs/Handoffs/README.md) — current coordination and historical evidence
- **Roadmap:** [V1 scope and later versions](docs/ROADMAP.md)
- **Architecture:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module map, process topology, startup sequence, and conventions; read this first
- **Changelog:** [`CHANGELOG.md`](CHANGELOG.md) — release history and unreleased changes (Keep A Changelog format)
- **Design spec:** `docs/superpowers/specs/2026-09-19-skein-design.md`
- **Implementation plan:** [`docs/superpowers/plans/2026-09-19-skein-v1-plan.md`](docs/superpowers/plans/2026-09-19-skein-v1-plan.md)
- **Threat model:** `docs/THREAT_MODEL.md` *(pending, delivered by M3)*
- **Privacy notes:** [`docs/PRIVACY.md`](docs/PRIVACY.md)
- **Vault format:** [`docs/VAULT_FORMAT.md`](docs/VAULT_FORMAT.md) — on-disk layout, encryption, data model, and wire format
- **Dependency notes:** [`docs/DEPENDENCY_NOTES.md`](docs/DEPENDENCY_NOTES.md) — pins, re-evaluation procedures, and fallback options for key dependencies (e.g., ONNX Runtime)
- **Security policy:** [`SECURITY.md`](SECURITY.md)
- **Governance:** [`GOVERNANCE.md`](GOVERNANCE.md)
- **Contributing:** [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **M0 benchmark harness:** `tools/m0-benchmark/` — benchmark tooling; the hardware handoff below records setup and source attribution
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
