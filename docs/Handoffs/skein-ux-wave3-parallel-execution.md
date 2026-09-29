# Parallel UX Wave 3 execution

The parallel UX batch is verified on `codex/ux-wave3-parallel-20260928`, based on
`bcd2b3264805b9559d01622af1d17abe3ae6fc9a`. The measured application source is
`af5aa4dc7e1cababa7d52b544a5a7a92834fb63f`. Later commits only preserve this report
and its evidence. The retrieval session retains `main` and the CI/emulator queue.
This branch has not been merged to `main`. **Fold remains HOLD.**

## Delivered behavior

- **AL-11 (`skein-xtov.24.11`):** removed the unlocked Activity's global safeDrawing
  wrapper while retaining the gate-screen wrappers. Entries rebase system insets
  from their measured window bounds. Composers consume IME union navigation bars;
  adjacent lists retain their height. Lists own bottom content padding. Composer
  line limits use live window dimensions, and transcript layout uses reverse order
  with stable message keys. Numeric size measurements avoid the Nav3 resize loop
  observed with BoxWithConstraints subcomposition. Peek and expanded entries keep
  one content call site, preserving remembered state.
- **AL-14 (`skein-xtov.24.14`):** exact ACTION_MAIN notification URI allowlist for
  Models and Knowledge. Pending destinations are memory-only enums, applied after
  unlock and restored-stack sanitisation; lock/reset clears them. Consumed launch
  intents do not replay after recreation. Notifications dismiss search, sheets and
  an open drawer, including same-destination taps. Knowledge reports actual ingest
  progress and pending semantic indexing without presenting it as complete.
- **AL-19 (`skein-xtov.24.19`), partial:** measured tabletop rail controls avoid the
  hinge, with accessible scrollable partitions at 1.3x and 2x text. Search, custom
  sheets and key-based secondary panes use the specified hinge partition. Book
  guards constrain a crossing single pane while preserving an already-split list.
  Window-origin translation accounts for rails and ancestor offsets. Restricted
  content waits for its measured origin. The debug overlay displays/logs geometry
  only; its release counterpart compiles as a no-op.

The coordinator integrated all three isolated branches and corrected the shared
inset/hinge boundaries. Actual host regressions cover a bounded collapsed peek,
remembered state across peek/expanded/peek, tabletop inspector/source placement,
book split preservation, flat restoration, and pointer blocking behind search.
Actual MainActivity tests cover the unlocked root's IME invariant and search field,
Close button and final-result clearance through navigation-only/IME/navigation-only
changes. These are JVM tests with synthetic insets and posture, not physical Fold
or real keyboard evidence.

## Verification

Evidence is in
[`docs/ux/runs/2026-09-29-wave3-parallel-af5aa4d`](../ux/runs/2026-09-29-wave3-parallel-af5aa4d/).
Raw JUnit XML is preserved in ZIP archives; per-suite JSON counts inspect actual
`testcase` children, including failure, error and skipped nodes. Roborazzi JSON is
preserved per module. `SHA256SUMS` covers the evidence files.

| Check | Actual result |
|---|---|
| App and seven affected feature modules: check, explicit ktlintCheck, AndroidTest Kotlin compilation; shell release Kotlin compilation | Exit 0; 1,068 Gradle tasks; 1,415 passing cases, 80 skipped, 0 failures/errors |
| Independent screenshot compare | Exit 0; 552 unchanged images, no recorded/added/changed images |
| Strict screenshot verify, no build cache | Exit 0; 344 Gradle tasks; 552 unchanged images; 1,217 passing cases, 80 skipped, 0 failures/errors |
| Submodule pin guard and source diff whitespace check | Pass; all three pinned submodules unchanged |
| Committed screenshot goldens and comparison tolerance | Unchanged |

The 80 skips are existing parameterized screenshot-matrix exclusions, such as a
rail on a phone or Fold-only states on a phone. They remain counted as skips, not
passes. Their source guards were unchanged. No new regression or functional test
was skipped. Broad-check passes by module: app 474 (both debug flavors), shell 210,
chat 254, editor 248, graph 62, models 42, settings 76, timeline 49. Screenshot verify
also ran the unchanged design-system module (276 passing cases).

The screenshot JSON totals are design system 189, chat 164, editor 68, graph 18,
models 38, settings 18, shell 37, timeline 20. Existing outer conversation and inner
landing goldens were visually inspected; this batch does not claim the new hinge
paths have physical-device visual acceptance. No goldens were re-recorded and no
thresholds were relaxed.

The first local integration formatting failure is preserved, together with the
successful targeted, retention, Activity-search, broad and screenshot logs. Worker
attempts, including the earlier resize-loop and coordinate failures, remain in
those workers' build/agent-logs directories. They are not relabelled as passes.

Commands used JDK 17 at
`/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home`, the SDK
at `/Users/andrewherrera/android-sdk`, and `--max-workers=2`. Exact task lists and
exit statuses are retained in the compressed logs and adjacent `.exit` files.
Instrumentation tests were compiled, not run. No emulator, adb, SSH, CI dispatch
or PR creation was performed by this UX session.

## Acceptance and integration boundary

**AL-14's scoped implementation and local checks are complete on this branch.**
Main integration and its exact combined-head CI still belong to the active
retrieval coordinator. The wider Wave 3 privacy and runtime gates remain open;
the targeted notification Bundle scan is not the complete recursive D7 privacy
matrix.

**AL-11 stays in progress.** Expanded inspector/Connections overlays still need
explicit remaining-IME ownership and coverage; future centered palette, rename
and attachment surfaces need their specified inset behavior. Retained composer
focus restoration belongs to AL-10. Real display-swap focus/keyboard survival,
one-tap reopening and hardware typing remain device gates. With the synthetic
40%-height IME, stock outer landscape left approximately **89 dp** of transcript,
below the proposed 120 dp fallback criterion. This does not settle the real-IME
measurement or authorize a header-hiding fallback. Existing scroll-follow policy
was not redesigned here; AL-10/C5 owns user-scrolled follow/jump behavior.

**AL-19 stays in progress.** Platform Dialog/Popup windows are separate from these
Compose guards: current model-delete confirmations, About/license/recovery dialogs
and the Space popup still need partition-aware placement. Remaining feature-level
fixed controls, chips/FABs and collapsed peeks need complete hinge-intersection
coverage. There is no drag handle currently exposed. Physical confirmation that
flat reports non-separating/non-occluding remains required. The P2 tabletop redesign
is outside this batch.

The retrieval coordinator can integrate this branch after its current exact-head
checks, then rerun checks for the combined changes and own the single CI queue.
This is Wave 3 foundation work, not completion of the UX overhaul or approval to
update the Fold. Retrieval quality, embedding eligibility, later UX waves and
physical acceptance retain their existing Beads gates.

## Preserved work and handoff

The integration tree is
`/Users/andrewherrera/skein-worktrees/ux-wave3-parallel-20260928`. Worker recovery trees
`ux-insets-20260928`, `ux-deeplinks-20260928` and `ux-hinges-20260928` remain under the
same worktrees directory. Their original signed commits and logs remain available.
The integration branch contains the cherry-picked work and coordinator fixes.

No retrieval/native/embedding implementation, original execution evidence, gold
labels, quality thresholds, owner Untitled/logos files, prior stash or earlier
recovery worktree was changed by this UX batch. Beads writes are serialized from
the primary repository. No Dolt remote is configured; per the established project
exception, Beads stays local and a failing Dolt push is not retried.
