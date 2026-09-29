# Repaired combined candidate evidence

Application source `5ae91c238612bdd9b1511b3716d34b4fa5960dd2`; JNI repair source
`21dd588a19bce793184571632bb1a7acc64a3cc7`, integrated as `c8b80f423`.
The final source additionally includes only foldable workflow/helper/test/docs changes.
Local combined check: 5,081 passes, 86 known skips, zero failures/errors; both app
and service AndroidTest flavors compile. Nine foldable host tests pass.

Candidate APKs were **released for the scoped data-preserving demo update** at
2026-09-29T10:10:33Z after all 281 fresh ordinary Android cases passed with no
failures or skips. CI, screenshot and reproducibility actual artifacts also pass. See [the integration report](../../../Handoffs/skein-ux-integration-20260929.md).
Metadata inventories describe retained metadata only; full immutable APKs and
original hash inventories remain under the integration worktree build/agent-logs
candidate-5ae91c2-{ordinary,optin}/ directories. Their immutable `identity.json`
files retain the historical `CAPTURED_NOT_RELEASED` status. The subsequent
authorization is in `candidate-release.json`; the original captures are unchanged.

The first manifest guard invocation failed before inspection because aapt2 was
missing from PATH. Its original log/result remains; the retry used pinned SDK
build-tools36.0.0 on PATH, audited the same immutable APK and passed. The other
five guards passed on their first invocation. Native-guards-reviewed.json records
this distinction; the failed invocation was not rewritten.

The JNI worker ZIP retains actual failing-baseline1case, fixed1case, complete
542-case service XML, commands and bytecode evidence. The original4f Android
failure is retained separately in the first-candidate evidence bundle.
Foldable's initial exact-profile failure is also retained: zero runtimecases,
not a pass. The generic-profile followup preserves exactfailclosed behavior.

All `.gz` files are lossless copies. Outer `SHA256SUMS` identifies the portable
bundle; nested original inventories may refer to owning-worktree files or original
uncompressed names. No owner vault contents or physical screenshots are included.
