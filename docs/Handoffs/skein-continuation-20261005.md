# Skein continuation, 5 October 2026

This is a **documentation-only continuation** requested after the owner asked what V1 now looks like. It refreshes the README, roadmap and changelog, adds [current V1 status](../V1_STATUS.md), and provides a [handoff index](README.md). No application, test, workflow, model or runtime code changed; no build, emulator, physical-device action or hosted CI dispatch was performed.

The verified input publication is **`223168fd4966500ec5f77ef0089962dcc74bd047`** on live `origin/main`, owner `main` and the integration worktree. The authoritative `six_priorities_20260930` record agreed and its build queue was idle before root claimed documentation issue `skein-1vsn`. Root read AGENTS and ran `bd prime`. The commit containing this handoff is a later documentation publication, not a new tested application checkpoint. Verify the actual Git publication and coordination record on resume rather than reusing the input hash as the current tip.

## What V1 means now

Skein is a functioning pre-release offline vault/chat workspace; its declared version remains `0.1.0`, and V1 has not shipped. Chat, Knowledge, Graph, Models and Settings are wired. Markdown notes, local chat/streaming, retained drafts, source inspection, split workspaces and export actions exist. Spaces have scoped handling/a switcher, while management remains a placeholder.

The V1 journey remains **unlock → write/import → index → search/ask → cite → approve edit → export**. Inline AI approval is still open and is distinct from the V2 structured Artifact Engine. Export UI/backend presence does not close staging or device acceptance. Answer quality remains a major gap; public recovery is not yet wired. Temporary Chats and vision remain unimplemented. Knowledge off still saves ordinary chats. Do not infer a runtime/default-model change from multimodal research.

The [status page](../V1_STATUS.md) is the current product summary; Beads owns task tracking. This refresh reconciles documentation and does not close broader feature or release gates.

## Source and artifact identities carried forward

| Layer | Exact identity | Accepted boundary |
| --- | --- | --- |
| Prior Git publication | `223168fd4966500ec5f77ef0089962dcc74bd047` | October 4 implementation/evidence/handoff published to main |
| Measured application | `52acc3114720e2ae136a80f4f74cb1bd5ec33c2e` | 5,776 local passes, 86 skips, 575 XML; lint/check/FOSS build and requested instrumentation compilation |
| Encrypted runtime source | `5591995bbad94ce7fa184a452767055bb3f7649e` | 16/16 native SQLCipher recovery/proof cases; synthetic keys/authentication only |
| Last verified physical source | `e62f94785ef8e696d8ee59745fd299fe1f83cc4a` | Historical Fold installation; no current connection or unlock assumed |

The runtime source differs from the application source only in an instrumentation fixture; production application files are byte-identical. The retained FOSS host APK SHA-256 is `0c97db51f44ab280e75e8561d54466d4e6f58796140f00da6e45c318778112b1` (128,112,723 bytes). The DEV vault instrumentation APK SHA-256 is `df3c0f6a1900c4e3c69ed951db3394c62fc0c3583d636a39410c076172a101a4` (53,800,503 bytes). Neither host artifact hash proves installed bytes. The historical physical APK SHA-256 remains `4052c0a8ba6363baa1f7387fcce0703ad0ca73e1355a7c7b22512583bca4d4a4`.

Read the [October 4 handoff](skein-continuation-20261004.md) and its [publication manifest](../ux/runs/2026-10-04-recovery-activation/publication-manifest.json), [host comparison](../ux/runs/2026-10-04-recovery-activation/full-local-09/prior-comparison-resolved.json), and [encrypted runtime review](../ux/runs/2026-10-04-recovery-activation/encrypted-attempt02-pass/independent-review.json). The host gate includes executed, cached and up-to-date tasks; both changed vault unit tasks executed. The host skip identities/reasons remain; one raw skip trace differs only in a generated reflection accessor number. Original formatting/compilation failures, the first runtime fixture failure and host mutation controls remain retained, not relabelled as successes.

October 3's 310 ordinary encrypted passes and two local foldable cases remain historical. The latter covers six nonce-bound posture exchanges and five Activity observations on a local Pixel Fold compatibility profile before unlock. Exact Pixel 9 Pro Fold, unlocked workspace/IME/privacy, Linux and physical gates remain distinct and open. No earlier result was rerun for this documentation update.

## Recovery continuation

Closed bounded children remain closed: internal activation implementation `skein-gg11.29.2.2.1`, local integration `.29.2.2.2`, and native/synthetic runtime `.29.2.2.3.1`. Broader `.29.2.2` and `.29.2.2.3` acceptance remains open. Production exclusion, fresh private-ciphertext proof, both authenticated wrap readbacks and observed transaction outcomes are implemented internally. A result grants no key/session authority. Original ciphertext, original aliases and retained old/new recovery evidence are preserved. Successful activation atomically replaces the active envelope with the verified v2 record; uncertain outcomes require fresh inspection. There is no automatic rollback or evidence deletion.

The next existing child is **`skein-gg11.29.2.2.3.2`**, actual process-death/restart acceptance with isolated synthetic fixtures and measured process identities/exits/restart observations. Fresh-lease reconciliation in the same process is not restart evidence. Process termination and injected IO errors do not establish power-loss durability.

Then follow the [source-bound legacy plan](../ux/runs/2026-10-04-recovery-activation/legacy-next-slice.json) under `.29.2.4`: a continuous production reservation must span surviving-factor unwrap, preparation and activation. Naively chaining current preparation and activation releases exclusion between them; a late cancellation check does not repair that gap. The legacy fixed-alias path and UnlockManager/public recovery contract remain unchanged. An interrupted caller may lose the post-rename result and must treat state as unknown until fresh observation. App/per-use Android authentication `.29.2.3` and physical `.29.3` are separate gates. Never reset the owner vault to test recovery.

## Other open priorities

| Work | Current evidence and next boundary |
| --- | --- |
| Context/relevance | `skein-gg11.32.2/.3` are closed for real-send wiring and three scripted encrypted cases. Physical `.32.4`, relevance and abstention remain open |
| Answer/model quality | `skein-gg11.30`, `.31`, `skein-66be` remain open. Answer80 is frozen, unexecuted/ungraded. Exact owner E4B bytes, tokenizer/template/EOG behavior and actual generated answers remain unqualified |
| Embedding | Independent reference acquisition/execution and `skein-lbw → skein-079 → skein-hwsa`, with decision `skein-5hr`, remain open. No active qualified production embedding backend or full hybrid acceptance |
| File lifecycle | Bounded AIOUT encrypted integration is accepted; CHAT/ATTACHMENT survivor `.27.2.2.3` and physical `.27.2.3` remain open |
| Fold/focus | Local adapter `.12pt.2.1` is accepted; exact-profile `.12pt.2.2`, physical `.12pt.3` and focus `skein-xtov.24.10.5` remain open |
| Temporary Chats | `skein-4v3c.1` review is complete; `.2/.3/.4` implementation/integration/acceptance remain open |
| Vision | `skein-m6v.1` feasibility and `skein-gg11.30.1` native harness are bounded completed work. `skein-m6v`, `skein-rni`, `skein-0xat`, actual runtime and quality remain open |
| V1 completion | Inline selection AI `skein-2cd`, export staging/integration and release/documentation/distribution gates remain open |

Fresh40 stays consumed regression evidence with 20/40 strict successes, 12 false absence admissions and six unexecuted contextual cases in that evaluation. The later scripted context cases do not change those results. Scheduled reused validation retains 9/12 answers, 6/12 absence rejections and three false rejections. Preserve every failure, label and threshold; never present reused cases as blind validation or retrieval results as generated-answer acceptance.

## Coordination, cost and preservation

Root owns Beads, shared-file integration and the serialized local build queue. `/root/fold_transport` is the **only physical/device runner**. The documentation review workers have read-only scopes; no implementation workers or test lanes are running for this update. Future workers need isolated worktrees and disjoint ownership, and must send builds to the coordinator's queue.

The [owner's local verification policy](../LOCAL_VERIFICATION.md) remains binding: all eleven hosted workflows are manual-only, with no dispatch absent an explicit owner request. Git publication does not authorize a hosted run. Leave Linux, independent reproducibility and any other unrun acceptance gates open. Do not configure a paid service or self-hosted runner as an inferred workaround.

The Fold is not needed for the next local recovery work. Before hardware work, the sole runner must check current connection and normal owner unlock; the earlier unlock acknowledgement is historical. Preserve owner content, drafts, models, Senses changes, Untitled, logos, stashes, old/recovery worktrees and every original evidence capsule. Do not clear stashes, prune worktrees or reset data in response to generic cleanup instructions.

The documentation update snapshots all 21 owner-file hashes/lengths, full status and stashes and verifies them again when publishing. Checks are documentation diff/links, source/evidence identity and independent review, not a new application test run. Resume from the actual pushed documentation commit recorded in coordination. Beads is locally versioned; there is no configured Dolt remote, and Git publication must not be described as Beads synchronization.

On resume, read AGENTS, run `bd prime`, verify live `origin/main` and `six_priorities_20260930` in `/Users/andrewherrera/skein-session-coordination/20260929/coordinator.json`, inspect queue/leases and claim only the relevant open Bead. Keep implementation, runtime, model quality and physical acceptance separate in every handoff.
