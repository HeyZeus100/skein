# Governance

Skein's governance structure is designed to move fast in year 1, then evolve toward broader community stewardship. This document describes how the project is run, how decisions are made, and what the path forward looks like.

## Year 1 — Benevolent Dictator For Life (BDFL)

**BDFL:** Andrew Herrera (@HeyZeus100, @andrew-aherrera-us)

Skein is governed by a single decision-maker (the BDFL) for year 1 (September 2026 through September 2027). The BDFL has final authority over:

- **Roadmap and priorities** — which features ship in which release
- **Architectural decisions** — design docs, APIs, subsystem structure
- **Release timing and scope** — what goes into v1.0, v2.0, etc.
- **Contributor status** — invitations to join as committers or maintainers
- **Code of Conduct enforcement** — interpretation and escalation

The BDFL is not a dictator in practice: all substantive decisions are discussed publicly on GitHub, rationale is documented, and contributors are heard. But when consensus cannot be reached, the BDFL decides.

### Why BDFL for Year 1?

A single decision-maker enables fast execution during Skein's most critical phase — shipping a complete, hardened product on schedule. Distributed governance is slower, which matters less once the product is proven. After year 1, the governance model will evolve toward broader community stewardship.

## Decision-Making Process

### Day-to-Day Changes

- **Small bug fixes, documentation, and refactors:** Merged by any committer (if one is appointed) or by the BDFL. No design document required.
- **Dependency updates:** Merged if tests pass and licenses remain compliant. Coordinated with the security review process.
- **Test improvements and CI/CD changes:** Merged if they increase reliability or speed without risk.

### Feature Additions and Architectural Changes

- **Proposed by:** Any contributor via GitHub issue or discussion.
- **Discussed:** On GitHub. The BDFL and community provide feedback. Major changes may require a design document under `docs/design/`.
- **Decided by:** The BDFL, after considering input from contributors and co-maintainers.
- **Documented:** The decision is recorded in the issue or design doc. Rationale is always public.

### Design Documents

Substantial architectural changes or new major features should include a design document:

- **Path:** `docs/design/<feature-name>.md`
- **Contents:** Problem statement, proposed solution, alternatives considered, rationale, interfaces, and testing strategy
- **Review:** Discussed on GitHub before implementation starts
- **Approval:** The BDFL or designated technical lead
- **Visibility:** Linked from relevant `bd` issues and the roadmap

Examples (from roadmap and plan):
- `docs/design/VAULT_TOOL_PRIMITIVES.md` — v2 agent tool primitives
- `docs/design/ARTIFACT_ENGINE.md` — the v2 document-editing pipeline

## What Happens After Year 1

By September 2027, Skein will have:

1. Shipped v1.0 to production
2. Accumulated ≥3 non-author contributors with merged PRs
3. Built a sustainable release and security-response process

At that point, the governance model will evolve. **Intended path (not locked in yet):**

- **Meritocratic committer model:** Contributors with sustained, high-quality contributions become committers (push access + design review authority).
- **Committer consensus:** Design decisions shift from BDFL unilateral → committer consensus with appeals to the BDFL for tiebreakers.
- **Maintainers group:** Invite ≥2 additional maintainers from the committer pool for load sharing and succession planning.
- **Decision log:** Start a publicly readable log of design decisions and their outcomes.

This is the *intended* direction, not a binding commitment. The actual evolution will depend on community size, contribution quality, and what works in practice. The BDFL will solicit committer and contributor feedback before making the transition.

## Security Response

Security vulnerabilities are handled per `SECURITY.md`:

- **Reporting:** Private email to `andrew@aherrera.us` — never public GitHub issues
- **Triage:** The BDFL (or a designated security lead) confirms the report within 48 hours
- **Fix timeline:** Critical (14 days), High (30 days), Medium (45 days)
- **Disclosure:** Coordinated. We notify reporters before publishing; standard window is 90 days

The BDFL is responsible for deciding:

- **Severity classification** — does this affect user privacy, data integrity, or system availability?
- **Fix strategy** — is a patch needed, or is the threat mitigated by Skein's architecture?
- **Communication** — how and when to disclose; whether to credit the reporter

## License Stability

Skein is licensed under Apache 2.0. This is a **permanent decision**:

**Skein will never be relicensed without the explicit, written consent of all contributors who have code in the codebase.**

### Why This Matters

- All contributors own their work; relicensing requires their consent
- Skein uses a DCO (Developer Certificate of Origin), not a CLA (Contributor License Agreement)
- Apache 2.0 is permissive and stable; there is no pressure to change
- Contributors can trust that their work will remain under a free license forever

If a future maintainer proposes relicensing (e.g., to a stricter license or proprietary model), **it requires unanimous consent from all authors**. This is intentional friction — licensing is not a technical decision, and it must not be made lightly.

### Dependencies

All dependencies in the `foss` build flavor must have permissive licenses (Apache 2.0, MIT, BSD, ISC, or equivalent). Proprietary or GPL dependencies are not allowed in the main codebase (though they may exist in separate build flavors with clear disclaimers).

This is checked at build time by `./gradlew licenseAudit`.

## Code of Conduct and Enforcement

Skein adheres to the Contributor Covenant (v2.1). See `CODE_OF_CONDUCT.md` for the full text and enforcement guidelines.

### Escalation Path

1. **First violation:** The BDFL or a designated community leader sends a private written warning, clarifying the violation and why it was inappropriate. Public apology may be requested.
2. **Pattern or serious violation:** Warning with consequences (e.g., temporary ban from interactions or community spaces).
3. **Severe or repeated violation:** Temporary or permanent ban from the project.

### Reporting

Report violations to `andrew@aherrera.us`. All reports are reviewed promptly and fairly. The reporter's privacy and safety are respected.

## How to Become a Contributor

There is no formal barrier to contributing. Here's the path:

1. **Read:** Skim `CONTRIBUTING.md`, `docs/BD_TAXONOMY.md`, and `docs/ROADMAP.md`.
2. **Find or open work:** Run `bd ready` to find available tasks, or open a GitHub issue to propose new work.
3. **Claim and code:** `bd update skein-xxx --claim`, write code and tests, push a PR.
4. **Get reviewed:** The BDFL or a committer reviews your code for correctness, style, and fit with the roadmap.
5. **Merge:** If approved, your PR merges. You are now a contributor.

### Sustained Contribution → Committer Invitation

If you accumulate a track record of:

- **Quality:** PRs that pass review on first or second round, with few revisions
- **Volume:** Multiple non-trivial PRs (not single-line fixes)
- **Reliability:** Committed to fixing bugs you introduce; responsive to feedback
- **Community:** Respectful, constructive interactions in issues and PRs

The BDFL may invite you to become a committer (push access, design review authority). This invitation is:

- **Unsolicited** — you do not apply; the BDFL identifies candidates
- **Discussed privately** — the BDFL will reach out
- **Optional** — you may accept or decline
- **Time-limited** — committer status may be revisited based on ongoing activity

Becoming a committer does not change your legal rights or obligations; all code is still under Apache 2.0 and DCO. It means:

- You can push directly to branches and merge PRs (after review)
- You participate in design decisions and architecture discussions
- You help on-board new contributors
- You share release and security-response responsibilities

## Amendments to This Document

Changes to governance are rare and substantive. The process:

1. **Propose:** File a GitHub issue or discussion with the change
2. **Discuss:** The BDFL and community provide feedback
3. **Decide:** The BDFL decides, with rationale
4. **Document:** The amendment is published and referenced in release notes

Amendments do not retroactively change how past decisions were made.

## Contact

- **Questions about governance:** Open a GitHub issue or discussion.
- **Security concerns:** Email `andrew@aherrera.us`.
- **Code of Conduct violations:** Email `andrew@aherrera.us` (all reports confidential).
- **Feedback on this document:** GitHub discussions or issues welcome.
