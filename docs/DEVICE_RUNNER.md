# Device Runner — Operating Contract for the Pixel 9 Pro Fold

This is the operating manual for the **one dedicated hardware-runner agent**
that owns the Pixel 9 Pro Fold. It distills:

- [`docs/Handoffs/skein-fold-m0-hardware-handoff.md`](Handoffs/skein-fold-m0-hardware-handoff.md)
  §15 (permissions matrix), §21 (agent runbook snippet), §22 (definition of
  "provisioning complete");
- the 9-point M0 hardware execution rules saved via `bd memories` on
  2026-09-20 (memory key `m0-hardware-execution-rules-per-docs-handoffs-skein`
  — run `bd memories m0-hardware` to read them verbatim before every session);
- [`tools/m0-benchmark/README.md`](../tools/m0-benchmark/README.md), the
  actual harness this runbook drives.

Read all three before touching the device. If anything here conflicts with
the handoff, the handoff wins — file a `bd note` on the conflict rather than
silently picking one.

## Who this is for

Exactly one Sonnet agent at a time. Device execution is **serial**: one
hardware issue/run at a time (handoff §15, execution rule 2). Other
subagents consume the artifacts this agent produces (JSON results, logs,
telemetry) — they do not get ad-hoc device access.

## Canonical device access

```text
Android/system telemetry  : ADB over USB          (adb devices -l, adb shell ...)
Termux execution          : ssh skein-fold-agent   (via adb forward tcp:18022 tcp:8022)
```

`skein-fold-agent` is a pre-configured `Host` alias in `~/.ssh/config`
pointing at the ADB-forwarded tunnel. **Never read `~/.ssh/skein_fold`
directly, print it, paste it, or commit it.** The macOS SSH agent/Keychain
handles authentication; you only ever need the alias name.

Preflight before every session:

```bash
adb devices -l
adb forward tcp:18022 tcp:8022
ssh skein-fold-agent 'whoami && pwd'
```

`tools/m0-benchmark/lib/preflight.sh` automates this (and the thermal/hash
checks below) — `run.sh` calls it before every matrix.

## Claim / run / report loop

1. `bd ready -l needs-hardware` — find the next hardware-tagged issue. Serial
   queue: do not start a second hardware issue while one is in flight.
2. `bd update <id> --claim`.
3. Run the harness for that issue's scope, e.g.:
   ```bash
   cd tools/m0-benchmark
   ./run.sh --smoke            # or --formal, per the issue
   ```
4. Attach artifact paths (the `output/<date>/<cell>/` directories and any
   `docs/MEASUREMENTS.md` update) to the bead via `bd note`.
5. `bd close <id> --reason "..."` on success, or reopen/re-note with findings
   on failure. A failed cell (OOM/timeout/thermal-abort) with a clean
   `result.json` is **not** grounds to silently retry — record it and move
   on, or escalate per below.

### Results template (attach to the bead note)

```text
Device build id:      <adb shell getprop ro.build.fingerprint>
App/harness commit:   <git rev-parse HEAD>
Mode:                 smoke | formal
Artifacts path:       tools/m0-benchmark/output/<date>/
Cells run:            <n>  (pass: <n>, failed: <n>)
Pass/fail per cell:   <cell_key>: ok | <failure_reason>
Deviations:           <anything that didn't match this runbook or the handoff>
```

## What the runner MAY do

- Run approved benchmarks and instrumented test suites via
  `tools/m0-benchmark/run.sh` and other checked-in `tools/device/*` scripts.
- Collect non-secret device/build telemetry (thermal, meminfo, battery,
  logcat, process state).
- Push/pull benchmark artifacts (models, prompts, results) to/from the
  device's shared storage or Termux workspace.
- Execute the pinned, known-good binaries under `~/skein-device/bin/` on
  the device.
- Install and run Skein dev/test APKs **when the claimed issue calls for
  it**.
- Append results/artifacts to the issue via `bd note`.

## What the runner MUST NOT do (without explicit human approval)

- Root the device, or unlock/relock the bootloader.
- Weaken GrapheneOS exploit-protection or other security policy.
- Change biometric/PIN/duress configuration.
- Read or export unrelated personal data.
- Install arbitrary software, or run a broad `pkg upgrade` in Termux — the
  environment is **frozen** (execution rule 3); a deliberate change needs a
  claimed bead that says so.
- Replace the pinned Vulkan/llama.cpp toolchain or rebuild the binaries
  under `~/skein-device/bin/`.
- Delete or overwrite model artifacts, or overwrite
  `tools/m0-benchmark/output/baseline/*` (immutable — execution rule 4:
  append a new dated baseline file instead, never edit the original).
- Run `--formal` against the Q3_K_M smoke artifact, or any model whose
  `formal_m0_candidate` is not `true` in `models.yaml` (execution rule 5).
  `run.sh` refuses this in code — do not work around the refusal.
- Change VPN/network/security configuration as a side effect of anything
  else — handoff §3.3/§16 found this actively breaks LAN SSH determinism.
- Expose, print, or relocate `~/.ssh/skein_fold` (execution rule 7), or use
  direct LAN SSH for automation (execution rule 8 — ADB-forwarded SSH via
  `skein-fold-agent` is canonical; LAN SSH is for human debugging only).
- Silently retry a failed cell to make a number look better (execution
  rule 6). Record the failure with full provenance instead.
- Expand v1 scope. M0 hardware work informs architecture decisions; it is
  not itself a place to add product features.

Anything in this list that turns out to be necessary gets a `bd note`
proposing it and a human decision — never a silent workaround.

## Preflight (mandatory before every formal run)

Matches `tools/m0-benchmark/lib/preflight.sh`:

1. Exactly one ADB device, state `device` (authorized, online).
2. `ssh skein-fold-agent` reachable.
3. Binary SHA-256 (over SSH, Termux-private storage) matches
   `models.yaml`'s `binaries:` entry, which itself must match
   `output/baseline/llama-binaries-2026-09-20.sha256`.
4. Model SHA-256 (over ADB, shared storage) matches the `models.yaml`
   entry for the model being run — and for `--formal`, that entry's
   `sha256` must be pinned (not `TBD`) and `formal_m0_candidate: true`.
5. Thermal starting envelope OK for the mode (`--smoke`: loose, ≤0.90
   headroom; `--formal`: strict, ≤0.50 headroom).
6. Record charging/battery state (`dumpsys battery`) alongside the run.

`run.sh` exits non-zero with a specific reason if any check fails — do not
bypass it by running the underlying `adb`/`ssh` commands manually.

## After every run

1. Stop telemetry samplers (`lib/telemetry.sh:telemetry_stop`).
2. Verify no orphan benchmark process remains in Termux
   (`lib/telemetry.sh:telemetry_ensure_no_orphan_bench` — `run.sh` runs
   this automatically on both normal exit and `SIGINT`/`SIGTERM`).
3. Persist stdout/stderr and the per-cell `result.json` under
   `tools/m0-benchmark/output/<date>/<cell>/`.
4. Attach artifact paths to the bead.
5. Report any deviation from this runbook or the handoff rather than
   silently adjusting the environment to make it go away.

Never infer a benchmark result. If a run fails or times out, the recorded
`result.json` with its `failure_reason` **is** the result.

## Escalation rules

| Condition | Action |
|---|---|
| Thermal abort (cool-down never reaches target within the wait window) | Record the cell as failed with `failure_reason: thermal-abort`; stop the matrix rather than pushing through a hot device; let the device cool before resuming with `--resume`. |
| Storage full (device or Termux) | Stop immediately — do not delete model artifacts to free space; `bd note` the issue and escalate to a human. |
| Device offline / ADB drops mid-run | Treat the in-flight cell as failed (`exit_status` non-zero, `failure_reason: ssh-transport-error` or similar); do not attempt automatic reconnection loops — re-run preflight from scratch once the device is back. |
| Hash mismatch (binary or model) | Stop — this means the environment drifted from the frozen baseline or manifest. `bd note` with the observed vs. expected hash; do not "fix" the mismatch by re-pinning the manifest without human review. |
| Anything in "MUST NOT do" above seems necessary | Stop, `bd note` the proposal, wait for a human decision. |

## Agent runbook snippet (paste this when launching the runner)

```text
You are the sole Skein hardware-runner agent for the Pixel 9 Pro Fold.

Read docs/Handoffs/skein-fold-m0-hardware-handoff.md, docs/DEVICE_RUNNER.md
(this file), the relevant bead, and `bd memories m0-hardware-execution-rules`
before acting.

Canonical device access:
- Android/system: ADB over USB.
- Termux execution: `ssh skein-fold-agent`, reached through
  `adb forward tcp:18022 tcp:8022`.

Treat the Fold as a controlled measurement target, one hardware issue at a
time. Use tools/m0-benchmark/run.sh — do not call adb/ssh ad hoc when the
harness already covers the operation.

You may: run approved benchmarks/tests, collect non-secret telemetry,
push/pull benchmark artifacts, execute known-good pinned binaries, install
Skein test APKs when the claimed issue calls for it.

You may not without explicit human approval: root or alter bootloader
state; weaken GrapheneOS security settings; change PIN/biometrics/duress
settings; run broad package upgrades; replace the pinned Vulkan/llama
toolchain; delete or overwrite model artifacts, the baseline, or stable
M0 binaries; run --formal against the Q3_K_M smoke artifact; expose
SSH/private-key material; access unrelated personal data.

Before every formal run: verify ADB identity, establish ADB-forwarded SSH,
verify binary/model SHA-256, capture device/build fingerprint, verify
thermal starting condition, record charging/battery state.

After every run: stop samplers, verify no orphan benchmark, persist
stdout/stderr and JSON results, attach artifact paths to the bead, report
any deviation rather than silently fixing the environment.

Never infer a benchmark result. If a run fails or times out, record it as
a failed cell with evidence — do not silently retry.
```

## Definition of done for a hardware-runner session

Cross-check against handoff §22 before closing a hardware bead:

- [ ] `skein-fold-agent` ADB-forwarded SSH alias verified end-to-end this
      session.
- [ ] Preflight passed (or its failure is the recorded result).
- [ ] Every attempted cell has a `result.json` under `output/<date>/` —
      including failed ones.
- [ ] No orphan benchmark process left running in Termux.
- [ ] Baseline files under `output/baseline/` unchanged.
- [ ] Artifact paths attached to the bead; deviations filed as `bd note`.
- [ ] Bead closed (or reopened with findings) — not left `IN_PROGRESS`
      with no note.
