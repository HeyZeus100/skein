# Contributing to Skein

Skein welcomes contributions. This document outlines how to participate effectively.

## Developer Certificate of Origin (DCO)

All commits must be signed off using the Developer Certificate of Origin v1.1. This means every commit message must include a `Signed-off-by` line:

```
git commit -s -m "Your commit message"
```

The `-s` flag automatically appends:
```
Signed-off-by: Your Name <your.email@example.com>
```

By signing off, you certify that you have the right to submit the contribution under the Apache 2.0 license, and that you agree to the terms of the DCO. See the full text at: https://developercertificate.org/

A required status check (`dco`) will fail any PR without proper sign-offs. Amend your commits if needed:

```
git commit --amend --no-edit -s
git push --force-with-lease
```

## Issue Tracking

Skein uses **bd** (beads) for task and issue tracking. See the project's [AGENTS.md](AGENTS.md) for quick reference commands:

- `bd ready` — Find available work
- `bd show <id>` — View issue details
- `bd update <id> --claim` — Claim work
- `bd close <id>` — Complete work

For detailed workflow guidance, including agent instructions and the tier model (haiku/sonnet/opus/fable), see `docs/BD_TAXONOMY.md`.

**All PRs should reference a `bd` issue.** Include the issue ID in the branch name and PR description (e.g., `skein-1jm-repo-bootstrap`).

## Opening Issues

When opening a new issue, include:

- **Clear title** — what is the problem or feature?
- **Steps to reproduce** (for bugs) — reproducible example if possible
- **Expected behavior** — what should happen?
- **Actual behavior** — what happens instead?
- **Context** — OS, device model, app version, related `bd` issue ID if any

## Pull Requests

### Before You Start

1. Create a new branch from `main`
2. Ensure your branch references a `bd` issue: `git checkout -b skein-1jm-your-feature`

### Checklist

Before marking your PR ready for review:

- [ ] All commits signed off with `-s`
- [ ] No new `INTERNET` or other forbidden permissions added to the manifest
- [ ] No telemetry, analytics, or crash reporting code added
- [ ] Tests pass (if applicable)
- [ ] `bd` issue referenced in the PR description
- [ ] Spec or plan reference included if applicable (e.g., `E8.I1` from the M0 plan)

### Code Style

Follow the existing codebase style. For Kotlin:
- 4-space indentation
- Naming: `camelCase` for functions/variables, `PascalCase` for classes
- Imports: organize with Android framework, then Kotlin stdlib, then third-party, then project packages

### DCO Reminder

Pull requests without `Signed-off-by` will not be merged. If you forgot to sign off commits:

```bash
# Amend the last commit
git commit --amend --no-edit -s

# Or sign off all commits in your branch
git filter-branch --msg-filter 'sed -e "/^$/a Signed-off-by: Your Name <your.email@example.com>"' main..HEAD
```

## Governance

Skein is BDFL-governed (year 1) by Andrew Herrera. Design decisions, roadmap prioritization, and architectural reviews are made by the BDFL. Contributions follow the Apache 2.0 license; all contributors retain ownership of their work.

## Questions?

If you have questions or need clarification, open an issue or check the project documentation in `docs/`.
