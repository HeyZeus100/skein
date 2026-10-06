# Changelog

All notable changes to Skein are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No release has shipped. The application currently declares version `0.1.0`; this is not a V1 acceptance or release announcement. The entries below summarize the recent continuation work; dated handoffs retain the detailed history and original failures.

### Implemented through 4 October 2026

- Adaptive single/split workspaces, local-model chat, retained drafts and source/context navigation.
- Bounded provenance-aware follow-up retrieval through the actual send path, including edited/deleted source handling. Relevance and answer-quality acceptance remain open.
- Safer file deletion and surviving AI-output editor handling, including bounded encrypted integration checks; unsupported survivor cases remain refused.
- Internal recovery preparation, immutable v2 envelope reading, fresh-proof activation and observed transaction outcomes that preserve original ciphertext and recovery evidence. Public recovery and real Android authentication remain unfinished.
- Local foldable test transport with nonce-bound request/ACK exchanges and accepted pre-unlock Activity geometry on the local Pixel Fold compatibility profile. Exact Pixel 9 Pro Fold and physical acceptance remain open.
- Local verification by default and manual-only hosted workflows under the owner's CI cost policy.

### Documentation — 5 October 2026

- Replaced stale no-chat and implied-delivery claims in the README and roadmap.
- Added [current V1 status](docs/V1_STATUS.md), an [updated continuation handoff](docs/Handoffs/skein-continuation-20261005.md) and a [handoff index](docs/Handoffs/README.md).
- Preserved the distinction between implementation, runtime tests, model quality and physical acceptance. No application code, model, runtime, workflow or device change accompanies this documentation update.

See the [October 4 evidence handoff](docs/Handoffs/skein-continuation-20261004.md) for measured sources and the [V1 status](docs/V1_STATUS.md) for remaining release gates.
