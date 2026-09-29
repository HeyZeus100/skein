# Final v4 host runner acceptance

PASS for the exact reviewed host helper and its refreshed preparation manifest. No blocking source regression remains. This acceptance is limited to source and host evidence; it does not release a physical run or claim answer completion/quality.

Original: /Users/andrewherrera/skein-worktrees/conference-demo-20260929/build/agent-logs/fold/20260929T081355Z-preflight-89bcc0/physical-runner-preparation-v4-final/

| Artifact | Bytes | SHA256 |
|---|---:|---|
| physical_synthetic.py | 42981 | 5433e207b752dc63b4d398dfb6daa2e97cc1ac5c04c037590c83bf74bcb9330f |
| test_physical_synthetic.py | 37714 | 289ba756920b3d7d23df21c16ad4a4cfc9535e109e63f5a57bcb07c965512ba7 |
| prepared-manifest.json | verified from original | 7d80e8fe477fbe52a8112b21a56161db098872b214781d31fb6547343aab6f54 |
| host-safety-tests-final.log | 5318 | 5082e00976bac1c2583ccf393f7b2d8660decad6b23a0a6fd1092ece626fe416 |

All 12 final manifest entries independently pass size/hash verification. The new v3-v4 diff body matches the actual original source files. Initial dbfd7aa4 helper and 28-test evidence remain separately preserved, including my original read-only review. They are not used as proof of the revised implementation. Pre-refresh manifest/template copies in this review directory are explicitly labeled and retained for chronology; prepared-manifest.json is the refreshed final artifact.

## Three final guards

1. validate now unconditionally requires explicit test-only policy. install_for_phase independently rejects absent/false policy and every non-answer phase before querying/installing anything. The legacy app/native installer fallback is removed. After exact current app SHA verification, app_test is the only installed package. Original data-preserving install-r, certificate/package validation and post-install bytes remain intact.
2. verify_parity_reuse additionally requires the prior released app hash, current release app hash and pinned5ae app identity to agree; missing or changed prior app identity fails closed.
3. verify_native_eog requires the exact classification-only/unmeasured schema, declared EOS151645 with literal boolean true, and unique exact singleton controls <|im_end|>/151645, <|endoftext|>/151643 and </s>/128247. Corresponding classifications require matching integer IDs and literal true. Missing/extra/malformed controls, duplicate spellings, changed IDs, integer-as-boolean values, or generated-stop claims fail. This classifies known tokens only; no generated stopping or answer quality is inferred.

AST comparison confirms only install_for_phase, validate and verify_parity_reuse changed relative to the initial reviewed implementation, with verify_native_eog added. All other function/class ASTs remain identical. Thus the earlier reviewed frozen identity, staging, privacy, timeout, fixture, collection and final-answer checks remain present rather than merely assumed from a successful workflow.

## Preserved release and output contract

Production and ordinary source remain exact5ae; test source is separately aee46e4d6c391bddbfb55296117889e1f79afff6 in the final preparation manifest. The release must bind the actual newly built test APK independently, without implying ordinary5ae tests executed against that new test-source commit. The host identity SHA625212a9771344d4069f06209fee7cf75b5e4bb375542932d43081b72c4463f2 remains pinned, with full model SHA/size and explicit host-declared BLAKE3 provenance. The service integrity guarantee remains full stream/mapped SHA; host BLAKE3 correctness is the independently reviewed host evidence obligation.

Same native APK, prior successful pinned native summary/report/release, source/llama/overlay/model/template identity and five raw token cases remain required. Raw prior summary data are not copied into the new summary. The transient process query continues to retain only minimized proof/byte counts/hashes, not raw output. No broad metadata collection was introduced.

Fixed four-case fixture, seed17, context4096, threads4, max_tokens256, 180-second case and 900-second outer deadlines remain unchanged. No suite invocation, automatic retry, owner registry/model access, input overwrite, data clear, label or threshold edit is added. The unchanged canonical preparer is pinned; the config only gains distinct test_build_sha and host_model_identity after existing identities are checked, and preexisting new fields are refused.

Progress is collected first, even following timeout/failure, while partial raw bytes and collection errors remain separate. Phase records never claim setup success. Runtime completion still requires exact JUnit success, no outer timeout, verified remote stop, all remotely hash-verified output files, matching app/test source/APK/model/template/fixture/host provenance, and four complete unique case/seed rows with valid measured generation/count/stop behavior. A well-formed but partial phase stream does not bypass these gates. Partial answers cannot pass. quality_assessed remains false; manual factual/citation review remains necessary.

## Actual tests and evidence

- Owner final log: 31 unique tests, all31pass, zero failures/errors/skips, exit0. Names independently matched to exact final test source. This is distinct from retained initial28 evidence.
- Independent guarded subset: 15pass, zero failures/errors/skips, consisting of all12 new v4/final cases plus3 safe existing pure regressions. No external subprocess attempted. Exact copied source/tests were imported, never main()/--execute. Process audit/subprocess guards would refuse command escape. The remaining full-suite tests reference the restricted retained v2 process fixture and were not replayed.
- Seven independent negative probes reject wrong test source, source provenance, BLAKE3, BLAKE3 provenance, evidence digest, test APK digest, and partial3-of4rows.
- Actual synthetic native-parity.json independently rehashed to34e0dd8f53e6a659131b84fbe696aeaec9a8bcbe7208e09e3ab3bf876547f727, then passed both the final strict EOG guard and five-case token guard. Only this synthetic token report was parsed. No raw v2 summary, process dump, or review embedding those contents was opened; prior summary/release identity checks were hash-only in the earlier review.

Source/mock review did not rebuild anything, rehash the model, use physical commands, operate Beads, alter originals, commit or push. The coordinator owns the final immutable release and exact new test APK attribution. The release template remains NOT_RELEASED; no runtime gate is closed by this report.
