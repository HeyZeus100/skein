# Retrieval repair: fresh-session handoff

**Owner paused work to move to a fresh session.** Resume only when instructed.
This checkpoint supersedes the active-state wording in the earlier
[UX/accuracy handoff](skein-ux-accuracy-resume-handoff.md), whose historical
evidence and preservation requirements still apply. **Fold remains HOLD.**

## Exact stopping point

The preceding implementation/evidence batch is committed and pushed through
`21b714141b20f9edd0a947371742b423d73fa1d0`. This checkpoint is a documentation-only
commit after it. Read the current Git head when resuming.

The owner approved the next batch with “go”: graph discovery and FTS verification
in parallel, then weak-evidence rejection, with opt-in instrumentation lane
separation. Before implementation started, the owner requested this pause.
AGENTS.md and the handoff were read, `bd prime` ran, the four issues below were
claimed, and relevant retrieval contracts were inspected. **No new repair code,
tests, worktrees, CI dispatches or device actions were started in this batch.**
All three prior agents are completed and idle. There is no running assignment
to recover. The tracked worktree was clean before this documentation update;
owner-owned `Untitled/` and `logos/` remain untracked and untouched.

Beads remains the task tracker. `skein-rw52`, `skein-vj5r`, `skein-u66y` and
`skein-gg11.32` remain in progress, with pause comments. `skein-9744` also remains
in progress. No repair acceptance criterion has been satisfied by this pause.

## Bounded assignments on resumption

Use one coordinator and three isolated agents, with fresh worktrees rooted at
the current main head. Only the coordinator runs `bd` because embedded Dolt
locks exclusively. Agent commits are reviewed and integrated by the coordinator.
Coordinate any shared `core/model/.../Vault.kt` contract edits before proceeding.

| Owner | Assignment |
|---|---|
| Retrieval agent | `skein-rw52`: diagnose and repair production graph seed discovery, with regression coverage for an ordinary query phrase against a longer source title. Preserve bounded traversal, eligibility and Space filtering. Evaluate unchanged corpus and ablations. |
| Native/accuracy agent | `skein-vj5r`: replace the ambiguous ranked ingest probe with bounded row-specific FTS verification or equivalent real consistency evidence. Include both a crowded healthy index with more than 50 matches and a genuine missing-posting/trigger negative control. |
| Sole CI runner | `skein-u66y`: explicitly separate opt-in retrieval instrumentation from the ordinary lane without hiding failures or skips. After coordinator release of a stable pushed head, run the relevant ordinary and dedicated retrieval lanes and review actual XML/JSON. No other agent dispatches CI or uses adb/SSH. |
| Coordinator | Review raw relevance signals and implement measured weak-evidence rejection under `skein-gg11.32`; coordinate contracts, review/integrate commits, run required checks, retain new measurements and push verified work. Keep broader follow-up retrieval/embedder acceptance open where unfinished. |

Useful starting points:

- `GraphRecall.findSeeds` uses `QueryNgrams` (capitalized contiguous 1–3-token
  phrases), exact `VaultRepository.findByTitle`, and entity lookup. The diagnostic
  has no entity extractor. “Project Alder” does not exactly match “Project Alder
  brief”. Verify the hypothesis through the production path. Existing
  `VaultRepository.searchTitles(prefix, limit)` may avoid a new API, but inspect
  its actual semantics and ambiguity handling first.
- `IngestSteps.indexLexical` selects the longest ASCII word from the first chunk
  and calls global `bm25(probe, 50)`. The sanitizer makes the word a prefix.
  A healthy newly written row below rank 50 warns just like a missing posting.
  Repeated fixture vocabulary makes crowding plausible; the old report cannot
  establish the cause of each warning. Increasing the cap or suppressing the
  warning is not a verification repair.
- `RetrievalServiceImpl.retrieveContext` recalls, ranks and assembles without a
  relevance gate. `LexicalRecall` normalizes each query's strongest score to 1;
  a cutoff on that normalized value cannot reject all-weak results. Preserve
  raw/source-specific signals, calibrate on development data and validate on
  separate cases. Avoid fixture-specific words or manufactured gold labels.
- `RealRetrievalEvaluationTest` currently lives under ordinary vault
  `androidTest`; its opt-in assumption was serialized as an XML failure.
  Inspect the existing synthetic benchmark source-set pattern, vault Gradle,
  `.github/workflows/retrieval-diagnostic.yml` and
  `tools/eval/run_real_retrieval.py` before choosing explicit lane separation.

Read `docs/RETRIEVAL_EVAL.md`, `docs/ANSWER_EVIDENCE_PROVENANCE.md`, and the current
[execution report](skein-ux-accuracy-execution.md) before changing contracts.

## Baseline and verification boundaries

Raw evidence is committed under
`docs/eval/runs/2026-09-28-resumed-56a46f5/`; its README and SHA256SUMS index the
unmodified artifacts. Runtime source was `56a46f5`:

- Ordinary run `36502596076`: 264 passes plus one nonexecuted opt-in assumption
  serialized as an XML failure, zero skips. Do not describe this as 265 passes.
- Structural synthetic run `36504291918`: 12/12 completed cases, all native
  token/control predicates pass. This establishes neither model factuality nor
  performance parity with the older workload.
- Real retrieval run `36505374600`: integrity/scope/provenance/anchor/determinism
  checks pass; all ranking gates fail. Lexical recall@8/nDCG@8 is
  `0.616667/0.612120`, graph `0/0`, default `0.600000/0.601604`. Graph returns
  nothing for all 76 queries. Lexical/default reject none of 16 absence queries
  and falsely reject 2 of 60 answerable queries. All 508 ingest warnings remain.
- Gold labels and thresholds (recall@8 ≥ 0.75, nDCG@8 ≥ 0.60) stay unchanged.
  Full hybrid stays **INELIGIBLE** without a real production embedder. Existing
  embedder work is `skein-lbw` → `skein-079` → `skein-hwsa`; do not duplicate it.
- Drawer date fixtures were fixed in `7b2786d`; CI, actual screenshot job and
  reproducibility passed `4a6c3e2`. Native citation retention `skein-i1y1`, draft
  storage `skein-xtov.24.10.1` and date drift `skein-p76i` are already closed.

Run checks appropriate to the new code, including each touched module's `check`,
explicit `ktlintCheck`, unit tests and affected AndroidTest compilation. Inspect
actual test artifacts, not only workflow status. Preserve original failures and
raw evidence, including the two original logs' trailing blank lines. Never use
`clearRoborazziDebug`; it deletes committed goldens.

Use JDK
`/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home`,
SDK `/Users/andrewherrera/android-sdk`, Gradle `--max-workers=2`, and per-worktree
`build/agent-logs/` logs with real exit codes. Fresh Android worktrees need
`local.properties` and initialized pinned submodules; do not change gitlinks.

## Preservation and delivery

Fold remains untouched: no physical-device reads, SSH, installs, UI interactions
or generations in this batch. Retain offline operation, no INTERNET permission,
inference isolation, encryption and bounded lock behavior. Preserve all recovery
trees, previous agent worktrees, old stashes, `Untitled/`, `logos/`, original
failed runs and inactive pause checkpoints. No broad cleanup or worktree sweep.

Previous agent worktrees remain available for evidence only:
`/private/tmp/skein-codex-resume-retrieval`,
`/private/tmp/skein-codex-resume-screenshots`,
`/private/tmp/skein-codex-resume-native`, and
`/private/tmp/skein-codex-resume-runner`. Their prior changes are integrated.

Commit and push verified work without another permission request. End with
`git pull --rebase`, Git push and confirmation that main is up to date with
origin. No Dolt remote is configured; `bd dolt push` already failed with
`origin not found`. Do not invent a destination or repeatedly retry. Local Beads
is not backed up by Git. Update issue status only against actual acceptance;
keep broader quality, Qwen, lifecycle and physical-device gates open.
