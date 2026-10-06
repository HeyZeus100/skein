# Skein V1 status

Updated **5 October 2026**. Skein is a working pre-release offline knowledge application. V1 has not shipped; the application still declares version `0.1.0`.

This is a product/status summary, not a new acceptance run. The underlying publication is `223168fd4966500ec5f77ef0089962dcc74bd047`. The [latest handoff](Handoffs/skein-continuation-20261005.md) records how to resume, while [October 4](Handoffs/skein-continuation-20261004.md) preserves the measured evidence. Beads remains the task tracker.

## The product now

Skein combines encrypted notes and files with a local AI assistant. Its five current destinations are Chat, Knowledge, Graph, Models and Settings. Single and split workspaces retain their navigation and drafts. No cloud inference, telemetry or network permission is introduced by this work.

| Area | Implemented in current source | Acceptance or delivery limit |
| --- | --- | --- |
| Workspace | Adaptive navigation, independently selected workspaces, split/swap, retained chat drafts and transcript position | Full unlocked Fold, keyboard/focus and privacy journeys remain open |
| Knowledge | Markdown notes, wikilinks, backlinks/local graph, imports and a basic extracted-text file reader | Full PDF/DOCX document viewing/editing is not provided; some file-deletion survivor cases remain safely refused |
| Chat | Imported local-model send/stream/Stop/retry, history, context inspector and source-opening routes | Reliable generated answers and rendered citations are not accepted merely because the UI and persistence code exist |
| Retrieval | Space-bound turns, exact prompt budgeting, evidence policy and source-aware follow-ups through the actual send path | Relevance/abstention gates still fail; production embeddings and full hybrid retrieval remain unqualified |
| Vault | Encrypted persistence, lock controls, model verification and internal existing-envelope recovery components | New recovery activation is internal: legacy migration, public app flow, real per-use authentication and physical recovery remain open |
| Export | Note actions for sharing text, Markdown, DOCX and PDF | Complete staging/cleanup, integration and device acceptance remain open |
| Spaces | Persona-backed switcher and scoped turn context | Settings still shows Spaces management as “Coming soon” |
| Temporary Chats | Boundary review and separate implementation/acceptance issues | Not implemented. Knowledge off still saves ordinary chats and drafts |
| Vision | Pinned feasibility comparison of llama.cpp/mtmd, LiteRT-LM and ONNX paths | No implemented/qualified vision bridge or accepted exact E4B artifact; no runtime/default-model switch |

Source anchors: [destinations and Space switching](../app/src/main/kotlin/app/skein/shell/NavShell.kt), [Spaces settings](../feature/settings/src/main/kotlin/app/skein/feature/settings/SettingsScreen.kt), and the [editor module](../feature/editor/src/main/kotlin/app/skein/feature/editor/). These establish code/UI presence, not physical acceptance. The [workspace evidence](Handoffs/skein-ux-integration-20260929.md) and [physical demo evidence](Handoffs/skein-conference-demo-final-20260929.md) retain their earlier source attribution. The demo produced useful answers to known notes with zero rendered citations; it did not qualify general accuracy.

## What recent updates changed

Follow-up retrieval now uses bounded, session-attested context from an earlier cited turn, revalidating its Space and current source revision. Three scripted encrypted integration cases passed, including edited and deleted sources. Scripted engines establish application behavior, not model understanding or answer quality.

File-lifecycle work saves supported dirty AI-output editors before deletion, reloads surviving metadata and preserves unrelated drafts. Seven bounded encrypted integration cases were accepted on October 3. Unsupported CHAT/ATTACHMENT survivor qualification remains open.

Recovery gained production exclusion, candidate proof against private encrypted content, authenticated proof-to-wrap binding, immutable v2 reading and internal activation. Publication reports observed old/new/unknown transaction state and retains old/new evidence. It does not grant an unlocked session or replace separate normal authentication. The app's legacy/public recovery path has not been migrated.

Fold tooling now establishes nonce-bound readiness and posture acknowledgements before evaluating Activity geometry. The accepted local run covers a Pixel Fold compatibility profile before vault unlock; it does not establish the exact Pixel 9 Pro Fold or unlocked workspace experience.

These changes are mostly reliability work. They do not imply a new installation on the owner's Fold.

## Verification snapshot

| Evidence | Result | Boundary |
| --- | --- | --- |
| October 4 local host gate, application source `52acc3114720e2ae136a80f4f74cb1bd5ec33c2e` | 5,776 passed, 86 skipped; 575 actual XML files; lint/check/FOSS assembly and requested instrumentation compilation passed | Local verification; skip identities/reasons retained. One generated reflection frame differs in a skip trace, documented in the comparison |
| October 4 encrypted runtime, source `5591995bbad94ce7fa184a452767055bb3f7649e` | 16/16 passed with actual SQLCipher/native proof and filesystem transactions | Synthetic factor keys/auth callbacks; no actual process restart, power-loss or hardware-authentication acceptance |
| October 3 ordinary encrypted suite | 310 passed, including seven AI-output file-lifecycle cases | Historical result at application source `4b5c6e43b5944d0461c27fe1c7283079b9794a9a`; not rerun October 4/5 |
| October 3 local foldable suite | Two cases passed, six posture exchanges, five Activity geometry observations | Test source `f1364bd2ba02e3f916953d10c775cafb61f03b78`; local compatibility profile/pre-unlock only |
| Physical Fold | Last verified source `e62f94785ef8e696d8ee59745fd299fe1f83cc4a` | Latest application changes have not been installed or physically accepted; current connection/unlock is not assumed |

The two October 4 test sources differ only in the instrumentation fixture; production application source files are byte-identical. The host gate includes executed, cached and up-to-date tasks; both changed vault unit tasks executed. The count is not a claim that every suite executed freshly. Original failures and mutation controls remain preserved. See [October 4](Handoffs/skein-continuation-20261004.md) and [October 3](Handoffs/skein-continuation-20261003.md) for actual artifacts, hashes and independent reviews.

The Fresh40 retrieval set remains **consumed regression evidence**: 20/40 strict successes, 12 false absence admissions and six contextual cases unexecuted in that evaluation. Its failures, labels and thresholds are retained. Later scripted context integration does not retroactively execute those six cases. Fresh40 is not generated-answer validation and must never be relabelled blind. Answer80 is frozen and unexecuted/ungraded. Test counts do not establish model accuracy.

## V1 finish line

The intended user journey remains **unlock → write/import → index → search/ask → cite → approve edit → export**, completed on the target device with the security boundaries intact.

| Open gate | Existing Beads and meaning |
| --- | --- |
| Safe public recovery | `skein-gg11.29`: actual process restart, legacy/public contract migration, real Android authentication, app flow and physical acceptance. Accepted bounded implementation children stay closed |
| Useful, supported answers | `skein-gg11.30`, `.31`, `.32`, `skein-66be`: actual-model evaluation, source support/citations, relevance and abstention; qualify the exact model artifact/template/stopping behavior |
| Semantic retrieval | `skein-lbw → skein-079 → skein-hwsa`, with `skein-5hr`: independent reference evidence, service integration and the recorded backend decision before full hybrid enablement |
| Editing and export | `skein-2cd` inline AI actions/approval; `skein-efwt`, `skein-xg6d`, `skein-j8iy` export staging/integration verification. V1 inline approval is separate from the V2 Artifact Engine |
| Device and lifecycle acceptance | `skein-12pt`, `skein-xtov.24.10.5`, `skein-xtov.27.2.2.3` and broader parents: exact Fold/unlocked journeys, focus and unsupported surviving writers |
| Release | `skein-2zz`, `skein-5ih` and documentation/distribution issues: accepted release artifact, reproducibility, security/user documentation and distribution execution |

Temporary Chats (`skein-4v3c`) is an additional owner-requested feature with open implementation, integration and acceptance. Vision (`skein-m6v`, `skein-rni`, `skein-0xat`) remains conditional on qualified model support; research has not enabled it. This refresh does not silently defer or accept either feature.

The next dependency-ready recovery step is actual process-death/restart acceptance, followed by legacy/public integration. Answer quality and the complete device journey remain central release priorities. No completion percentage or release date is inferred from the number of closed implementation issues.

## Verification cost and later scope

[Local checks are the default](LOCAL_VERIFICATION.md). All eleven hosted workflows are manual-only; no hosted run is authorized by a push or by this documentation refresh. Local checks must retain their normal rigor, and unrun Linux/reproducibility/physical gates remain open. The October 5 refresh runs documentation checks only and consumes no hosted CI minutes.

Structured DOCX/PDF editing through the Artifact Engine, research agents, sync, voice and model routing remain later scope in the [roadmap](ROADMAP.md). They are not working V1 capabilities.
