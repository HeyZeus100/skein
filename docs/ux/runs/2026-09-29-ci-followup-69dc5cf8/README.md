# CI-only source 69dc5cf8 follow-up

Exact source `69dc5cf8d6ca87414cce1808e6d71bf0c43e3567`; the released and installed application remains source `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`, APK SHA256 `1f83b71c5fe60a6dc26fcac7a4c75b49fbf009b6e8c9d972b2176c18081c1323`.

- [CI, screenshot and reproducibility review](ci-repro-screenshots/README.md): actual 5,079 unit passes / 88 skips; 552 unchanged screenshots; actual native and complete release APK pairs byte-equal within this source. Unit cache hits and source-epoch limits are explicit.
- [Failed generic foldable attempt](foldable-failed-36554797839/README.md): actual two failures before Activity launch, zero geometry. Original XML, malformed output, artifact hashes and source-level transport diagnosis preserved.

Each subtree retains its owning review and hash index. The outer index covers every included file. Large binaries remain in the original owning worktrees and are listed with full identities in `EXTERNAL_ARTIFACTS.json`. No physical process dumps, owner-vault content or device screenshots are included. No workflow badge alone is treated as acceptance.
