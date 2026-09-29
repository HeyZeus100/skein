# Scheduled ordinary Android verification — 36567852296

Run: https://github.com/HeyZeus100/skein/actions/runs/36567852296
Event: `schedule`; source `aef738b11fb9869e2e5d4b58df629885b511a359`.
This run was scheduled automatically; the coordinator did not dispatch it.

The three current module XML files contain **281 passed, zero failures/errors/skips**:
app **38**, core/vault **204**, inference-service **39**. Exact identities match the
281-case passing run at released source `5ae91c2`, with no missing, added or duplicate cases.
All six required lifecycle/native cancellation cases and all 19 formerly failing identities pass.
Each actual job/step succeeded. Logs record execution of all three connected tasks on the
API 35 emulator and no connected task marked FROM-CACHE or UP-TO-DATE; Gradle succeeds in 10m33s.
The current XML suite timestamps fall within this execution (12:36:52–12:40:03 UTC).

Original artifact `connected-test-reports`, ID **11033760549**, is **1,144,441 bytes**;
SHA256 **`a9d97b45fbbaa2f5f34d3ead2ace4ea43362c5700e20607e5e239c7a6e72f3ce`**
matches GitHub API size/digest/source. The original ZIP and full logs remain in the owning
worktree evidence directory. No APK bytes are included in this capsule.

## Historical XML included by broad collection globs

The original archive contains six XML files: **three current module XML files and three
historical XML copies under `docs/ux/runs/.../remote-ordinary-36551469886/raw/`**.
All three historical copies exactly match the earlier 5ae evidence. They were excluded from
the 281-case count, identity comparison and critical-case proof.

All **seven** source-manifest records rehash correctly: three current XML, the current
runner-review JSON, and three historical XML. Four APK hashes are separately host-declared;
they are not four retained files or installed-package attestations. The source manifest's
SHA256 is `c9296737b886252260cba2c35cd35bfcb6e706890283665bc56e0679b8410716`.

The collection issue comes from recursive globs in `.github/workflows/emulator.yml:251–252`
and `tools/ci/verification-manifest.py:17–21`. The ordinary verifier already anchors the three
module roots. A proposed follow-up restricts upload/manifest/APK inventory to those roots and
adds a historical-docs regression fixture. No source or retained evidence was changed here.
`historical-xml-contamination-review.json` records the exact distinction and hashes.

## Scope and retained metadata

Ten production/ordinary-test/workflow/reviewer source objects are identical to released
`5ae91c238612bdd9b1511b3716d34b4fa5960dd2`; `source-comparison.json` records object IDs.
This is new ordinary emulator execution at aef source, not a candidate replacement, installed
APK attestation, latest opt-in helper result, physical Fold proof, full inference-lifecycle
acceptance, public-Qwen readiness, or retrieval/embedding gate. Existing skips and open gates
outside this lane are unaffected.

The portable capsule contains only metadata and the three current raw XML files. Original
API metadata, raw ZIP, extracted reports, historical XML, source scripts and full logs remain
under `ux-hinge-windows-20260929/build/agent-logs/remote-36567852296`. `COPY_PROVENANCE.json`
verifies each copied/decompressed stream; `SHA256SUMS` covers capsule files only. The upstream
manifest still describes the complete original artifact, including historical XML omitted
from this capsule. Read gzip metadata with `gzip -dc`.
