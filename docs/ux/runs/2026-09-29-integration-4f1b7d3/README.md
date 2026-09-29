# Combined integration evidence, 29 September 2026

Measured application source: `4f1b7d334173544feb47190b3e2e7efc4a5e1688`.
See [the integration report](../../../Handoffs/skein-ux-integration-20260929.md)
for scope, exact counts, skipped cases, artifact identity and remaining gates.

Raw ZIPs preserve actual XML/JSON; `.gz` files are lossless compressed copies of
logs/reviews. The final outer SHA256SUMS covers portable files in this directory.
Worker manifests retain original paths and hashes; large APKs, libraries and the
public 1.59 GB GGUF remain in ignored, owning-worktree evidence directories rather
than this git bundle. Candidate metadata inventories cover only retained metadata;
original capsule SHA256SUMS also cover local APK files and remain intact there.

Local evidence lives under
`/Users/andrewherrera/skein-worktrees/ux-integration-20260929/build/agent-logs/`.
The source-specific ordinary and opt-in APK capsules are immutable. All captured
APKs are host-attributed to a clean source checkout, not embedded source attestation.
The remote manifest likewise identifies the runner checkout; physical installed-byte
identity requires the separate sole hardware runner's recorded comparison.

Earlier worker failures, the first test-APK capture failure and the exploratory
wrong-key screenshot summary remain at their original paths. Later successful
checks do not erase or rewrite them. No gold images, labels or thresholds changed.
No owner screenshots, vault contents, tokens, personal identifiers or secret keys
are included here. Qwen identity records concern the public shared model copy.

Remote screenshot artifacts are retained under `remote-screenshots-36546803140`;
its downloaded ZIP hash matches GitHub's artifact digest. Compressed review includes
all 80 explicit parameter-assumption exclusions. Other remote lanes are recorded
as they finish; pending lanes are never represented by workflow success alone.
