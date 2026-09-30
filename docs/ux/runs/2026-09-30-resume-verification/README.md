# Resume verification, 30 September 2026

This capsule records source/evidence review and validation after installed e62's [bounded physical Delete acceptance](../2026-09-30-delete-physical-e62/README.md). Initial application changes are at `b62977f3a`; the later [blank-PDF fix](../2026-09-30-pdf-empty/README.md) is at `9d5f7b725`. Neither new candidate is installed on the Fold. Every report keeps its own source attribution.

| Evidence | Result and scope |
|---|---|
| `baseline-audit.json`, `ux-inference-baseline-review.json` | Original deletion, UX, inference and retrieval evidence rehashed and actual records inspected. Failed retrieval/Fold gates remain failures. |
| `cache-control.json`, `cache-root-review.json` | Baseline, cache restoration, intentional forbidden-literal failure, exact restore and fresh pass. Expected failure XML is retained alongside the restored fresh result. |
| `auth-worker-focused-review.json`, `auth-static-review.json` | 136 focused passes, zero failures/skips; bounded missing-key classification and safe explicit reset review. Parent recovery/runtime gate remains open. |
| `combined-local-full.json` | 5,260 passes, 86 skips, zero failures/errors in 518 XML reports; lint/check and AndroidTest compilation passed. Cached/up-to-date results included. |
| `combined-local-screenshots.json` | Fresh no-build-cache execution: 1,339 passes, 80 skips, 552 unchanged images. Actual XML/JSON sealed in the owning checkout's ignored `screenshots-actual.zip`. |
| `combined-local-independent-review.json` | All old skip identities retained; 26 added occurrences match the auth changes. Explicitly distinguishes 363 retained original XML files from 155 later-overwritten files. |
| `remote-ci-review.json`, `remote-ci-binary-review.json` | At `90ec3f9c7`, actual 518 XML: 5,258 passes, 88 unchanged skips; all changed task variants executed. Actual APK permissions and native alignment checked. |
| `remote-ux-review.json`, `remote-repro-review.json`, `remote-ux-repro-independent-review.json` | At `90ec3f9c7`, actual fresh UX and byte-equal APK/native pair results independently verified. All archive digests and declared payload hashes inspected. |
| `manifest-scope-*-review.json` | Historical unit/screenshot archives excluded while all current project roots remain covered; 41 host tests pass. |
| `privacy-*.json` | Source, existing e62 XML and peer review for corrected privacy claims; no new device/security acceptance. |
| `extraction-assessment-root-review.json` | 21 pinned upstream and six Skein source hashes verified for the owner-requested MarkItDown/Marker comparison; zero conversions or model executions. |
| `candidate2-local-full.json`, `candidate2-local-independent-review.json` | After PDF fix `9d5f7b725`: 5,266 passes, 86 skips, zero failures/errors; all 518 actual XML files sealed in `candidate2-local-unit-actual.zip`. Exact skip identities retained; all six added PDF occurrences pass. |
| `candidate2-ci-review.json`, `candidate2-ci-root-review.json`, `candidate2-ci-binary-review.json` | At `605b0dfc2`, actual 518 XML: 5,264 passes, 88 unchanged skips; APK/guard/artifact checks pass. Cached results remain identified. |
| `candidate2-ux-repro-review.json`, `candidate2-ux-repro-root-review.json` | At `605b0dfc2`, 1,339 passes, 80 skips, 552 unchanged images; actual APK/native pairs byte-equal. Archive digests, manifest bytes and XML independently rechecked. |
| `candidate2-ordinary-independent-review.json`, `candidate2-ordinary-root-review.json` | API digest, actual XML, source, four manifest payloads and 15 required steps verified; all 281 prior case identities retained. |
| `recovery-next-step-static-review.json` | 22 exact-source records rehashed; outlines safe credential fallback and existing-envelope proof/persistence requirements. Static planning only; P0 remains open. |
| `continuation-public-review.json` | 52 local links, public capsule hashes and popup XML reviewed; physical and broader-gate boundaries retained. |

Ordinary instrumentation run `36664671504` at `605b0dfc2` has 281 actual passes with zero failures/errors/skips and exact identity parity with accepted e62. All three connected tasks executed. `candidate2-ordinary-*.xml`, the host review and manifest are byte-equal copies of original artifact members; the root review maps original paths to public copies. APK hashes in the manifest remain host-declared because APK bytes were absent. This is not new Fold acceptance.

The first combined local manifest (`combined-local-full.json` at `b62977f3a`) was parsed from actual module reports before the screenshot gate; 155 XML files in eight screenshot modules were subsequently overwritten by that gate. The earlier manifest is a count/digest snapshot, not a preserved raw copy of those overwritten bytes. Remaining original reports and fresh screenshot raw records are independently reviewable. Archived historical copies were excluded from counts; an initial overbroad discovery was rejected and retained privately as NOT-GATE.

Source guard logic, screenshots, gold labels, retrieval thresholds, model defaults and owner content are unchanged. No local hardware acceptance is inferred from host tests. See the [continuation handoff](../../../Handoffs/skein-orchestrator-continuation-20260930.md) for remaining work and coordination.
