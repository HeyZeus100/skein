ADB request-read source review

The original bridge used `exec-out`, whose client copies the remote stream to stdout and returns zero without the remote exit status. A pre-install `run-as` error therefore looks like successful non-JSON output. The exact first bad payload was not recorded by run 36584103370. The realistic fake reproduces this mechanism against the original implementation.

The repair requests `shell -T`, preserving separate stderr and remote exit status. Only exit 1 with empty stdout and an exact unknown-package or fixed request-file missing diagnostic waits; permissions, other paths/statuses, mixed output, malformed JSON, and unexpected stderr fail closed. Existing bounds and mutation guards are unchanged.

These official published sources document the protocol; they do not attest the CI binary build provenance. Full runtime posture/geometry remains unverified. See REVIEW.json for pins, line references, and byte hashes.
