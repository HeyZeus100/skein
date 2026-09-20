# bd (Beads) Taxonomy for Skein v1.0

This document defines the labels, priorities, tiers, milestones, and dispatch workflow for Skein's issue tracker (`bd`, v1.0.0). It is the authoritative reference for every agent and human coordinator working on the project.

**Source of truth:** `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` (§4.10). The plan is parsed and seeded into bd by `tools/bd_bootstrap.py` (§11).

---

## Overview

Skein uses bd for issue tracking because it:
- Encodes blocking dependencies with precise granularity (no guessing which issues unblock others).
- Supports fenced metadata blocks in the plan document, so the plan is the single source of truth.
- Allows parallel agents to safely dispatch work via `bd ready`.
- Ships as a single binary with no external database.

**Bootstrap:** Run `python3 tools/bd_bootstrap.py docs/superpowers/plans/2026-09-19-skein-v1-plan.md --apply` to seed the tracker. Re-running on an existing database produces duplicates; run once per database or use `bd init --force` first.

---

## Issue ID Convention

- **bd ID:** `skein-<hash>` — a stable, machine-generated identifier (e.g. `skein-3sd`, `skein-04yz`). Use this in dispatch commands and when referring to work in progress.
- **Plan Key:** `E<epic>.<I<issue>>` (e.g. `E0.I9`, `E4.I3`) — human-readable reference from the plan. Appears in bd issue titles for backward compatibility with the plan document.

Example title: `skein-3sd · E0.I9 Verify the bd import and publish docs/BD_TAXONOMY.md`.

---

## Priorities (P0 – P3)

Priorities are used for **gate enforcement** and **coordination**. All priorities are within a **single milestone**; across milestones, use milestone labels instead (see below).

| Priority | Meaning | Gate Impact |
|---|---|---|
| **P0** | Critical path for this milestone OR blocks others with P0/P1 work | Must be closed to reach the next milestone gate; if open, the gate does not pass |
| **P1** | Required for the milestone gate | Must be closed to pass the gate; typically foundational or prerequisite to later work |
| **P2** | Required for v1.0 but not gate-critical | Can slip to the next milestone without affecting the gate; must be in the release |
| **P3** | Conditional: depends on an M0 decision, or optional if time permits | May not ship in v1.0; a "nice-to-have" |

**Dispatch rule:** In `bd ready`, P0 and P1 issues of the current milestone are prioritized. P2 and P3 are attempted after P0/P1 are cleared or stalled.

---

## Types

| Type | Purpose | Examples |
|---|---|---|
| **epic** | Grouping container for related issues (E0, E1, ..., E10). One epic per subsystem or phase. Not dispatched; unblocked automatically when all children close. | `E4 inference`, `E7 editor` |
| **task** | Specific work item with a clear acceptance criterion and a single agent. | most issues; implement a feature, write docs, run hardware tests |
| **feature** | Alternative to `task` for user-facing additions. | rarely used; `task` is default |
| **bug** / **chore** / **decision** | Allowed by the bootstrap parser, but not used in v1.0. Treat as `task`. | — |

---

## Tier Hints

Tiers estimate **cognitive load** and help an agent self-identify suitable work. They do not strictly bind agent assignment; they are hints for dispatch and planning.

| Tier | Hourly Load | Use Case | Examples |
|---|---|---|---| 
| **haiku** | ~20 lines/hr, <2 h total | Mechanical, well-specified, low complexity; CI YAML, metadata files, size gates, license lists. | `E1.I2` (network permission check), `E1.I3` (CI lint), `E8.I1` (metadata files) |
| **sonnet** | ~5–10 lines/hr, 8–20 h total | Mainline implementation against a locked contract with good tests; Compose screens, SQL, chunking, retrieval, export. | most of `E2` (vault), `E5` (RAG), `E6` (UI), `E7` (editor) |
| **opus** | ~2–5 lines/hr, 16–40 h total | Security-sensitive, correctness-critical, concurrency-heavy: native builds, isolated services, JNI, key wrapping, verifiers, editor offset mapping. | `E0.I7` (SQLCipher build), `E3.I2` (encryption), `E4.I1`, `E4.I3` (inference JNI), `E4.I4` (isolation escape tests) |
| **fable** | ~1–3 lines/hr, 16–50 h total | Synthesis across many artifacts, adversarial review, threat modeling, grant writing, launch announcements. | `E0.I8` (synthesis of MEASUREMENTS.md), `E0.I19` (adversarial contract review), `E3.I13` (security review) |

**Dispatch:** Use `bd ready -l tier:sonnet -l milestone:M1` to find all Sonnet-tier issues ready to work in M1.

---

## Spec Labels (Six Labels from Spec §12.2)

These labels correspond to **risk, resource, and validation** constraints. A single issue may carry multiple labels.

| Label | Meaning | Dispatch Impact |
|---|---|---|
| **parallel-safe** | This issue touches no shared file that another in-flight issue also edits. Pure-Kotlin utilities, docs, CI YAML, metadata. Safe to dispatch without a rebase; changes will not conflict with concurrent work. | Can be safely started alongside other P0/P1 work in the same milestone without coordination. |
| **needs-hardware** | Requires the physical Pixel 9 Pro Fold (the only test device). Examples: on-device inference, biometric unlock, Fold posture validation, device-side performance measurement. | Dispatched only by the dedicated test-runner agent via `bd ready -l needs-hardware`. No parallelism; the device is a serial queue. Max 86 hours of device time in v1; M0 has 32 of those hours (days 1–7). |
| **needs-human-review** | A human must manually verify and sign off before the issue is closed. Examples: provisioning the Fold, signing the release APK, filing F-Droid submissions, sending grant emails. Agent prepares, human executes. | The agent prepares a PR or artifact; the human closes the issue after doing their part. Not in `bd ready` if the human work is blocked. |
| **blocks-others** | Closing this issue unblocks the largest fan-out of downstream work (typically 5+ other issues). Used to identify the critical path. | P0 in the current milestone. Prioritized in `bd ready`. |
| **spike** | Investigative issue; output is a decision, data, or a design, not shipping code. Example: threat model writing, architecture decision record. | May not have a code deliverable; acceptance criteria focus on a document or a recorded decision. |
| **docs** | Documentation deliverable (README, ARCHITECTURE, threat model, etc.). Typically does not produce code. | May require `needs-human-review` if approval gates are involved. |

---

## Plan Labels (Tier and Milestone Tags)

Added by the bootstrap for convenient dispatch queries. Every non-epic issue carries exactly one tier label and one milestone label.

### Tier Labels

```
tier:haiku    —  haiku (mechanical, <2 h)
tier:sonnet   —  sonnet (mainline, 8–20 h)
tier:opus     —  opus (critical, 16–40 h)
tier:fable    —  fable (synthesis, 16–50 h)
```

### Milestone Labels

```
milestone:M0    —  Week 1 (Sep 21–25):  Measurement, device provisioning, contract seed
milestone:M0.5  —  Week 2 (Sep 28–Oct 2):  Contract lock-in, adversarial review, core compilation
milestone:M1    —  Weeks 3–5 (Oct 5–23):  Inference, embedding, native builds, isolation tests
milestone:M2    —  Weeks 6–8 (Oct 26–Nov 13):  Vault, RAG, UI, retrieval eval
milestone:M3    —  Weeks 9–11 (Nov 16–Dec 4):  Editor, chat, export, adversarial security
milestone:M4    —  Week 12 (Dec 7–11):  Release, signing, store submissions, announcements
```

---

## Epics (11 Total)

Every issue belongs to exactly one epic. Epics are groupings; they do not appear in `bd ready` and are not actively worked. They close automatically once all children close.

| Epic | Focus | Owned By | Ready Counts |
|---|---|---|---|
| **E0** | Meta: measurement, contracts, coordination | Coordinator | 23 issues (M0–M1 gates) |
| **E1** | Build: manifest, Gradle, guards, CI, licenses, reproducible builds | Infrastructure | 12 issues |
| **E2** | Vault: SQLCipher, migrations, repository, persona service, documents provider | Vault team | 11 issues |
| **E3** | Security: encryption, keys, StrongBox, verifiers, sigstore, PromptGuard | Security team | 15 issues |
| **E4** | Inference: llama.cpp, JNI, model manager, context budget, thermal governor | Inference team | 16 issues |
| **E5** | RAG: tokenizers, chunker, recalls, PPR, citation parser, ingest worker | RAG team | 19 issues |
| **E6** | UI: Compose shell, tabs, split, fold layout, notifications, model management | UI team | 19 issues |
| **E7** | Editor: live preview, wikilink autocomplete, backlinks, conflict resolution | Editor team | 9 issues |
| **E8** | Distribution: signing, CI/CD, reproducible release, F-Droid, Obtainium | Release | 15 issues |
| **E9** | Docs: user guide, PRIVACY, SECURITY, THREAT_MODEL, README | Documentation | 8 issues |
| **E10** | Testing: contract suites, retrieval eval, prompt injection, E2E, memory pressure | QA | 22 issues |

**Total:** 11 epics + 167 issues = 178 nodes.

---

## Dependencies and Blocking

- **Dependency direction:** `bd dep add <issue> <blocker>` means "issue **depends on** blocker". Blocker must close before issue can run.
- **Implicit parent–child edges:** A child issue has an implicit `blocks` dependency on its epic (added automatically by bootstrap). The epic stays blocked until all children close.
- **Cycle detection:** The bootstrap validates `bd dep cycles` and will refuse import if a cycle exists.
- **Dispatch impact:** `bd ready` only surfaces issues with all dependencies closed.

---

## Dispatch Workflow

### Quick Reference

```bash
# View readiness
bd ready                                # Show all ready issues across all milestones
bd ready -l tier:sonnet -l milestone:M1  # Sonnet-tier issues ready in M1
bd ready -l needs-hardware               # Device-runner agent queries this (serial queue)

# Claim and work
bd show <id>                             # View issue details (description, acceptance criteria, metadata)
bd update <id> --claim                   # Claim ownership; moves to in_progress
bd update <id> --append-notes "..."      # Add progress notes
bd close <id> --reason "..."             # Complete work (must meet acceptance criteria)

# Monitor and coordinate
bd list -l blocks-others --status open   # Show critical-path blockers
bd list -l milestone:M2 --status open    # All open issues in M2
bd dep <id>                              # Show what blocks this issue
bd dep <id> --blocked-by                 # Show what this issue blocks
```

### Five-Step Dispatch

1. **Identify:** Run `bd ready -l tier:sonnet -l milestone:M1` (or your tier/milestone).
2. **Review:** `bd show skein-xxx` to read description, acceptance criteria, interfaces, and files.
3. **Claim:** `bd update skein-xxx --claim`. Status → `in_progress`. You now own it.
4. **Work:** Follow the TDD steps in the plan. Commit with `-s` (DCO sign-off). Push.
5. **Close:** `bd close skein-xxx --reason "Acceptance criteria met: [list]. Files: [list]."` Status → `closed`.

### Coordinator Workflow

The human coordinator orchestrates milestone gates and fan-out. At each gate (`E0.I21`–`E0.I23`):

1. Run `bd list -l milestone:M<N> --status open` to see all open issues in the milestone.
2. Check P0 and P1 issues:
   - If all P0/P1 are closed, the gate passes.
   - If any P0/P1 is open, diagnose blockers with `bd dep <id>`.
3. Prioritize new dispatch:
   - Release ready P2 and P3 issues if time permits.
   - Use wave orchestration: dispatch 3–5 agents per tier into a milestone to avoid rebases and contention.

---

## Anti-Patterns (Do NOT Do This)

| Anti-Pattern | Why | Correct Approach |
|---|---|---|
| Invent new labels (e.g. `backend`, `frontend`, `urgent`) | Labels are curated; ad-hoc labels become stale and fragment dispatch. Use priorities and milestones instead. | Use P0 for critical, `milestone:M<N>` for phase, and the six spec labels. |
| Skip acceptance criteria when closing | Issues without clear acceptance criteria are rework. Every issue must have measurable, testable criteria in the plan. | Read the acceptance criteria; verify every checkbox; include in the close reason. |
| Close without committing | Work is not done until it is in git. Locally closed issues that are not pushed leave the repository in an inconsistent state. | Commit with `-s`, push to origin, then close. |
| Depend on epics directly | Epics are groupings, not tasks. Dependencies should link specific issues to specific issues. | Add `deps: E4.I3, E5.I1` (specific issues) not `deps: E4` (epic). |
| Dispatch an issue without reading its interfaces | Interfaces specify what the issue consumes from and produces for neighbors. Ignoring them causes integration failures. | Read §2 (constraints), §4 (contracts), `docs/ARCHITECTURE.md`, then the issue's Interfaces and Acceptance criteria. |
| Update bd outside the plan | The plan is the single source of truth. Manual edits to bd are lost on the next bootstrap. | Update the plan document, re-run `bd_bootstrap.py --apply`. |

---

## Known Discrepancies

None found in v1.0.0 import (2026-09-20).

- **Plan estimate (§1):** 14 `needs-hardware` issues; **import result:** 14 open `needs-hardware` issues. ✓
- **Total nodes (§11):** 178 (11 epics + 167 issues); **import result:** 178 total. ✓
- **Dependency cycles:** Plan specifies none; bootstrap reports none. ✓

---

## References

- **Plan:** `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` — §2.1 (format), §2.3 (labels/priorities), §4.10 (this taxonomy), §11 (bootstrap script)
- **Bootstrap script:** `tools/bd_bootstrap.py` — parses the plan and seeds bd; validates keys, deps, cycles, and milestones
- **Architecture:** `docs/ARCHITECTURE.md` — contracts, module structure, interfaces
- **Measurements:** `docs/MEASUREMENTS.md` — hardware performance baselines from M0
- **bd documentation:** https://github.com/joshcho/beads (v1.0.0)

---

**Last verified:** 2026-09-20 · 178 nodes, 0 cycles, 14 `needs-hardware`, gate-ready for M0.5.
