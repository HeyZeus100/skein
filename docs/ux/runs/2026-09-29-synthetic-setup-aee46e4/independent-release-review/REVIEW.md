# Independent concrete test-only release review

PASS: no identity or scope mismatch found in the concrete coordinator release. This is a read-only host acceptance, not physical execution. The sole external demo owner must still acknowledge liveness and refresh the device lease/current installed identity before its authorized run.

Original capsule: /Users/andrewherrera/skein-worktrees/ux-integration-20260929/build/agent-logs/candidate-aee46e4-testonly/

Release SHA256: **34dfaaa13beafeff8a4234de54decdb7c7bbc20d3e65f450c9656b0659b17e67**.
Identity JSON SHA256: **e398a4c3457df9103754ef3cbed010cfec9c99a42a77111a9f4a8a4537cb0be6**.
Scope review SHA256: **0e3821d7241a975b5b9259e533fe09d8149c21cd21b102a67bd57523d5a8fc45**.

## Independently checked

- All three APKs were fully rehashed. Production app remains125,280,302B, SHA1f83b71c5fe60a6dc26fcac7a4c75b49fbf009b6e8c9d972b2176c18081c1323. Native test remains130,230,126B, SHAbb1b2f496f6c831ced00f20d9dd08d6f571d87fcb48040dfba17f4a7c561f342. Replacement app test is91,163,348B, SHA9575f53a36e6dbd1dcd9f4cdcfe4af6407a74d1566343bf5bb29ca0bc9edef95; root copy equals the original worker artifact.
- Six independent local APK commands succeeded: three verbose apksigner verifications, two aapt2 package inspections, and the test APK manifest inspection. All three have exactly one signer with certificate75bcae7118a4635dd0fb153438e5bdbfd7aab5d467b3a0c2bb210eefde5becf9. Package identities match; replacement test package app.skein.test targets app.skein. These are host tools only; no adb or runner CLI was called.
- Compared all67 ZIP payload entries against prior test APK889db458324339f2f24da8b190a8457192a719df4bb088a52c04bf298e992f44. No added/removed entries; only classes7.dex differs (173,652B to229,572B; new payload SHAf925a9ee553172577398387402a788777a9790339288d63fc1f65c3a0f44fb70). No native libraries in either test APK. This is entry-byte equality, not a claim of whole-APK equality or embedded source attestation.
- Actual Git diff5ae→aee46e4d6c391bddbfb55296117889e1f79afff6 is exactly the three approved opt-in files. Their bytes equal integrated8ae202a095a0725a0190195e7cf9f470ec39a4ac and mainaf0c9f94c93e14235096d22d57388431dfdb7027. Test checkout was clean. The release correctly keeps app/ordinary source5ae separate from test sourceaee46; no implication that old ordinary tests executed new test-source code.
- Rehashed and reparsed all three ordinary XML files: app38, vault204, inference-service39; total281 unique cases, zero failures/errors/skips. All seven required cases are present and unskipped. Rehashed the four-case gold-free fixture, canonical preparer and tokenizer overlay pins; identities match the frozen reviewed profile.
- All12 copied final-runner entries match the refreshed preparation manifest7d80e8fe477fbe52a8112b21a56161db098872b214781d31fb6547343aab6f54. Exact helper remains5433e207b752dc63b4d398dfb6daa2e97cc1ac5c04c037590c83bf74bcb9330f, preserving final31-mock acceptance and independent15-safe-case/7-rejection-probe evidence from the earlier final review.
- Ran only the strict test-only policy function against this concrete release with subprocess calls denied; it passes. Host identity and scope pins match. Prior release app/native/source/llama/overlay identities agree. Prior summary SHA was checked without parsing its contents. The actual synthetic native report passed the five-case and final EOG guards again. No broad summary/process-dump data were read.

The immutable identity.json status CAPTURED_NOT_RELEASED describes the artifact capture checkpoint. The separately hashed release.json now supplies explicit RELEASED_FOR_PHYSICAL_SYNTHETIC authorization; the capture record is preserved rather than rewritten. root-release-validation.json contains the same identity object and correct release digest.

## Boundaries

The concrete scope is one exact supplied-evidence method, app.skein.test install-r only, with current production app/native bytes retained, fresh run/input/output, no whole suite or automatic retry, and unchanged900-second host/180-second case limits, context4096, four threads, seed17 and256-token cap. The reviewed runner rejects legacy/non-answer install policy, mismatched app bytes, unreviewed host identity, invalid native reuse, malformed progress and partial final output. Runtime completion and answer quality remain unmeasured until an actual new result passes independent review.

This review did not reread/hash the1.59GB public model; that full host SHA check is recorded by coordinator validation, while its reviewed identity evidence and pins were independently checked here. The model/BLAKE3 host evidence remains distinct from device service SHA integrity and from timing/quality claims. No builds, source edits, physical actions, Beads, commits or pushes occurred.

Detailed evidence: independent-identity-review.json, local-apk-verification.json with six retained stdout/stderr pairs, and independent-policy-review.json. SHA256SUMS covers all copied release records and review outputs.
