# Skein — Pixel 9 Pro Fold M0 Hardware Lab Handoff

**Status:** Physical provisioning substantially complete; ready for orchestrator-led M0 harness adaptation and formal measurement  
**Date:** 2026-09-20  
**Target:** Pixel 9 Pro Fold / GrapheneOS  
**Host repository:** `/Users/andrewherrera/skein`

> This handoff records what was actually established on the physical Fold, what was learned during provisioning, the known-good access paths and artifacts, and how the orchestrator should proceed. Where this conflicts with assumptions in the original M0 instructions, prefer the proven topology below and update the harness—not the security model.

---

## 1. Executive handoff

The Fold is now a functioning Skein hardware test target.

Proven end-to-end paths:

- Mac → Fold over **wired ADB/USB**
- Mac → Termux over **SSH**
- Termux → native ARM64 `llama.cpp`
- `llama.cpp` → **Vulkan**
- Vulkan → **Mali-G715**
- GGUF → successful real local inference

A Qwen 2.5 3B abliterated **Q3_K_M** artifact was used strictly as a smoke-test model. It must **not** silently replace the formal M0 Q4_K_M candidate in the approved plan.

The repository already contains `tools/m0-benchmark/`. Do **not** blindly copy or execute its current `run.sh` on the Fold. It predates the actual topology discovered during provisioning.

### Target harness architecture

1. The **Mac remains the orchestrator and result store**.
2. **SSH executes** `llama-bench` / `llama-cli` inside Termux.
3. **ADB collects** Android thermal, memory, battery, process and system telemetry.
4. Results return to `tools/m0-benchmark/output/` on the Mac.
5. Automated agents use **ADB-forwarded SSH**, not LAN SSH.

---

## 2. Proven topology

```text
MacBook / Skein repo
/Users/andrewherrera/skein
        |
        +-----------------------+
        |                       |
      USB/ADB                  SSH
        |                       |
        v                       v
GrapheneOS / Android       Termux (u0_a214)
        |                       |
        |                       +-- ~/src/llama.cpp/
        |                       +-- ~/skein-device/bin/
        |                       +-- CPU llama.cpp build
        |                       +-- Vulkan llama.cpp build
        |                       +-- /sdcard/skein-bench/models/
        |
        +-- dumpsys thermalservice
        +-- dumpsys meminfo
        +-- dumpsys battery
        +-- logcat
        +-- process/package control
        +-- APK install/test later
```

The Fold is the execution/measurement target. Heavy analysis, aggregation, Git operations, issue management and report generation stay on the Mac.

---

## 3. Access paths

### 3.1 Wired ADB — proven working

`adb devices -l` successfully identified the Fold with status `device`, product/device codename `comet`, and model `Pixel_9_Pro_Fold`.

Use ADB for:

- system telemetry;
- thermal sampling;
- `dumpsys meminfo`;
- battery state;
- logcat;
- process inspection;
- APK installation/testing;
- shared-storage transfer.

Do not use `run-as com.termux`; the installed Termux package is not debuggable:

```text
run-as: package not debuggable: com.termux
```

This is expected Android sandbox behavior and must not be worked around.

### 3.2 Direct LAN SSH — proven working

Termux SSH server runs on port **8022**.

```text
Termux user: u0_a214
Termux home: /data/data/com.termux/files/home
Observed Fold LAN IP during setup: 192.168.86.31
```

Direct SSH succeeded with:

```bash
ssh -p 8022 u0_a214@192.168.86.31
```

### 3.3 Important VPN finding

A VPN enabled on the Fold caused LAN SSH to time out even though `sshd` was already listening. After disabling the VPN, `nc` to port 8022 succeeded and SSH connected immediately.

**Conclusion:** LAN SSH is useful for humans but is not deterministic enough for automation.

### 3.4 Canonical agent transport — ADB-forwarded SSH

Use USB/ADB to tunnel SSH:

```bash
adb forward tcp:18022 tcp:8022
```

Recommended Mac `~/.ssh/config` entry:

```text
Host skein-fold-agent
    HostName 127.0.0.1
    Port 18022
    User u0_a214
    IdentityFile ~/.ssh/skein_fold
    AddKeysToAgent yes
    UseKeychain yes
```

Agent preflight:

```bash
adb devices -l
adb forward tcp:18022 tcp:8022
ssh skein-fold-agent 'whoami && pwd'
```

This should be the supported execution interface for the dedicated hardware runner.

---

## 4. SSH authentication and secret handling

A dedicated ED25519 key was generated on the Mac:

```text
~/.ssh/skein_fold
~/.ssh/skein_fold.pub
```

The public key was installed into Termux with `ssh-copy-id`.

### Rules

- Never expose, print, paste, upload or commit `~/.ssh/skein_fold`.
- Agents invoke the configured SSH host; they do not need private-key contents.
- macOS SSH agent/Keychain handles authentication.
- Do not place SSH keys in the Skein repository.

---

## 5. Termux environment established

The device has a native development environment sufficient for M0 inference work. Known installed/working components include:

- clang
- cmake
- make
- git
- wget / curl
- python
- openssh
- Vulkan headers/loader tooling
- shaderc / `glslc`
- `spirv-headers`
- `spirv-tools`

Provisioning lesson: `spirv-tools` alone was insufficient; CMake also required `spirv-headers`.

### Freeze recommendation

Do not routinely `pkg upgrade`, replace Vulkan components, or install unrelated stacks during formal M0. Any environment change should be deliberate and recorded.

Do not add Docker, Node, Rust, broad Python ML stacks, etc. merely for convenience. Keep the device lab minimal.

---

## 6. GrapheneOS / Android findings

### USB

ADB eventually worked over USB after the device was configured for USB data/file transfer and authorized.

If this breaks later, diagnose in layers:

- Mac does not enumerate Android at all → cable/port/GrapheneOS USB policy.
- Mac enumerates Android but `adb devices` is empty/unauthorized → ADB authorization/configuration.

Do not weaken GrapheneOS security controls unnecessarily.

### Netlink restrictions

Inside Termux, `ip` / `ss` produced netlink permission errors such as:

```text
Cannot open netlink socket: Permission denied
```

Do not root or weaken the device to bypass this. Host-side ADB can retrieve required system/network information.

### Sandbox split

ADB shell cannot enter Termux private app storage. Use:

- **SSH** for Termux-private binaries/builds.
- **ADB** for Android system telemetry and shared storage.

The harness should explicitly embrace this split.

---

## 7. llama.cpp status

Source checkout:

```text
~/src/llama.cpp
```

Known Vulkan binaries:

```text
~/src/llama.cpp/build-vulkan/bin/llama-bench
~/src/llama.cpp/build-vulkan/bin/llama-cli
```

A CPU build also exists.

Stable M0 copies were created:

```text
~/skein-device/bin/llama-bench-cpu
~/skein-device/bin/llama-cli-cpu
~/skein-device/bin/llama-bench-vulkan
~/skein-device/bin/llama-cli-vulkan
```

Hashes were recorded to:

```text
/sdcard/skein-bench/llama-binaries.sha256
```

Treat these stable copies as immutable M0 artifacts unless a deliberate rebuild is required.

### Vulkan proof

`llama.cpp` successfully enumerated:

```text
ggml_vulkan: Found 1 Vulkan devices:
0 = Mali-G715
```

Real inference subsequently completed through this backend.

---

## 8. Smoke model artifact

Working smoke model:

```text
Qwen2.5-3B-Instruct-abliterated-Q3_K_M.gguf
```

Path:

```text
/sdcard/skein-bench/models/Qwen2.5-3B-Instruct-abliterated-Q3_K_M.gguf
```

Observed size:

```text
1,590,475,744 bytes
~1.48 GiB
~3.09B parameters reported by llama-bench
```

SHA-256:

```text
2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12
```

Smoke artifact lineage was a Huihui abliterated Qwen derivative distributed/quantized as GGUF by TensorBlock.

### Classification

```text
purpose: smoke-test
quantization: Q3_K_M
formal_m0_candidate: false
```

The approved plan requires formal Q4_K_M acquisition/measurement. Verify exact source, revision, filename, size, SHA-256 and license before formal benchmarking.

---

## 9. Successful smoke benchmark

Real Vulkan inference completed.

| Metric | Result |
|---|---:|
| Model | Qwen 2.5 3B abliterated Q3_K_M |
| Backend | Vulkan |
| GPU | Mali-G715 |
| `ngl` | 99 |
| pp64 | **23.47 tok/s** |
| tg32 | **5.61 tok/s** |
| pp512 | **6.49 tok/s** |
| tg128 | **5.61 tok/s** |

These are **smoke results only**, not formal M0 conclusions.

Interesting observation: generation remained ~5.61 tok/s across both smoke configurations, while prompt processing dropped materially at pp512. Formal testing should investigate CPU/Vulkan behavior, prompt size, context, thermal state and thread/gpu-layer tuning rather than extrapolating from these runs.

---

## 10. Device workspace

Created:

```text
~/skein-device/
├── bin/
├── scripts/
├── results/
└── tmp/
```

Shared-storage workspace:

```text
/sdcard/skein-bench/
├── models/
├── prompts/
└── results/
```

Recommended roles:

- `~/skein-device/bin/` — immutable known-good executables
- `~/skein-device/scripts/` — only device-side helpers actually needed
- `~/skein-device/results/` — private temporary results
- `/sdcard/skein-bench/models/` — model artifacts
- `/sdcard/skein-bench/prompts/` — interchange prompts
- `/sdcard/skein-bench/results/` — ADB-visible result exchange

---

## 11. Baseline artifacts already captured

Created on the Fold:

```text
/sdcard/skein-bench/device-baseline.txt
/sdcard/skein-bench/llama-binaries.sha256
```

The baseline includes date, Android version, security patch, build fingerprint, CPU ABI, core count, Clang/CMake/glslc versions, llama.cpp commit/description, and model SHA-256.

Pull these into the Mac-side M0 artifact area before formal work:

```bash
adb pull /sdcard/skein-bench/device-baseline.txt
adb pull /sdcard/skein-bench/llama-binaries.sha256
```

Preserve the original baseline. Append additional fields rather than overwriting it unnecessarily.

---

## 12. Existing M0 harness

Mac repository:

```text
tools/m0-benchmark/
├── README.md
├── models.yaml
├── prompts/
├── run.sh
├── thermal-sampler.sh
├── collect-results.py
└── output/
```

This is an engineering measurement harness, **not Skein v1.0**.

An attempted `rsync` was mistakenly run from inside Termux while the current directory was `~/src/llama.cpp`. It therefore searched for a nonexistent `~/src/llama.cpp/tools/m0-benchmark/`. The destination directory was created but no harness files were transferred. No damage occurred.

### Recommendation

Keep orchestration on the Mac. Do not copy the whole harness to the Fold unless inspection shows a specific helper must run device-side.

---

## 13. Required harness adaptation

Before formal M0, inspect and update:

```text
tools/m0-benchmark/run.sh
tools/m0-benchmark/thermal-sampler.sh
tools/m0-benchmark/models.yaml
tools/m0-benchmark/README.md
```

Desired control flow:

```text
Mac run.sh
  -> preflight ADB + ADB-forwarded SSH
  -> verify baseline/binary/model hashes
  -> start ADB telemetry sampler
  -> SSH into Termux and invoke selected llama-bench binary
  -> collect stdout/stderr
  -> sample thermal/memory/battery during execution
  -> stop telemetry
  -> normalize/aggregate on Mac
  -> write per-cell JSON to tools/m0-benchmark/output/
  -> enforce cool-down/thermal gate
  -> next cell
```

Do not assume `/data/local/tmp` is the primary execution environment. The proven inference binaries live in Termux private storage.

---

## 14. Formal M0 recommendations

### Artifact integrity first

For every formal model record:

- upstream model;
- quantizer/distributor;
- immutable revision;
- exact filename;
- size bytes;
- SHA-256;
- license;
- source URL;
- purpose.

Treat GGUF as untrusted parser input. Pin hashes before benchmarking.

### CPU vs Vulkan

Measure both against the exact same model/prompt.

Do not assume Vulkan wins simply because it works.

Capture:

- prompt-processing throughput;
- generation throughput;
- TTFT;
- peak RSS;
- thermal state/headroom;
- sustained behavior;
- failure/OOM behavior.

### Repeatability

Use at least 3 formal runs per cell after a defined thermal cool-down criterion.

### Context

Follow the approved context matrix after verifying the flags against the pinned llama.cpp revision. Establish 4K/8K/16K before over-investing in 24K/32K.

### Thermal control

Prefer a measured thermal preflight gate over a fixed arbitrary sleep. Record plugged/unplugged state. Do not mix power states in an unlabeled comparison.

### Memory

Sample memory during active prompt processing/generation, not only before/after. Resolve the correct process/PID for each run.

### Provenance per cell

Each result should include at least:

```text
device build fingerprint
Android version / security patch
llama.cpp commit
binary SHA-256
model SHA-256
model quantization
backend
full command line
context length
gpu layers
threads
prompt/generation token targets
repeat index
start/end thermal state
pp tok/s
tg tok/s
TTFT
peak RSS
battery/charging state
exit status
timestamp
```

---

## 15. Hardware-runner permissions

Allow the **dedicated hardware runner/orchestrator** to invoke:

```bash
ssh skein-fold-agent '<command>'
adb ...
```

Do not grant every coding subagent ad-hoc device access.

The hardware runner may:

- execute benchmark binaries;
- run approved helper scripts;
- read non-secret device/build metadata;
- collect thermal/memory/battery/process telemetry;
- push/pull benchmark artifacts;
- install/run Skein dev/test APKs when the corresponding issue calls for it;
- append results to issue/harness artifacts.

The hardware runner should **not**, without an explicit issue/human approval:

- root the device;
- unlock/relock the bootloader;
- alter GrapheneOS exploit-protection/security policy;
- disable core security protections;
- change biometric/PIN/duress configuration;
- read/export unrelated personal data;
- install arbitrary software;
- run broad `pkg upgrade`;
- replace Vulkan/toolchain packages;
- delete model artifacts;
- overwrite stable `~/skein-device/bin/*` binaries;
- change VPN/security configuration as a hidden side effect;
- expose SSH keys or credentials.

Device execution remains serial: one hardware issue/run at a time.

---

## 16. Security posture for the lab

The Termux development environment having network access does **not** alter Skein's product requirement: the eventual Skein APK must have no `INTERNET` permission.

Keep a strict conceptual boundary:

```text
Termux lab:
networked development/measurement environment

Skein APK:
offline/no-network production application
```

After M0 and active development sessions, consider stopping `sshd` and restoring normal VPN/security posture. For automation, ADB-forwarded SSH avoids depending on LAN exposure.

---

## 17. Suggested immediate orchestrator actions

1. Read this handoff plus the authoritative design spec and implementation plan.
2. Pull `device-baseline.txt` and `llama-binaries.sha256` into a suitable M0 artifact directory.
3. Verify `ssh skein-fold-agent` through ADB forwarding.
4. Inspect the existing M0 harness before executing it.
5. Refactor the harness to Mac-orchestrated SSH execution + ADB telemetry.
6. Add preflight checks so a formal run refuses to start if:
   - wrong/no ADB device;
   - SSH unavailable;
   - binary hash changed;
   - model hash differs;
   - model artifact is not the declared formal candidate;
   - thermal state is outside the allowed starting envelope.
7. Acquire/pin the formal Q4_K_M model artifact(s) per the approved plan.
8. Preserve the Q3_K_M results as smoke evidence only.
9. Run a small harness dry-run cell before launching the full matrix.
10. Only after dry-run artifacts are correct, execute CPU vs Vulkan × context × repeatability measurements.
11. Feed measured decisions into `docs/MEASUREMENTS.md`; do not hard-code conclusions from the smoke test.
12. File discrepancies between the original M0 plan and the proven physical topology as tracker notes/issues rather than silently changing assumptions.

---

## 18. Suggested harness improvements

While adapting `tools/m0-benchmark`, add:

- a reusable preflight phase;
- transport abstraction (`ssh_host=skein-fold-agent`);
- explicit CPU and Vulkan binary paths;
- SHA verification before every formal run;
- structured JSON result schema;
- timeout and forced-cleanup handling;
- SIGINT cleanup that stops samplers and leaves no orphan benchmark;
- thermal gate/cool-down state machine;
- process/PID discovery;
- machine-readable failure reasons;
- per-cell stdout/stderr capture;
- environment fingerprint attached to each run;
- checkpoint/resume so an interrupted matrix does not restart completed cells;
- a `--smoke` mode for one short cell;
- a `--formal` mode that enforces hashes, repeats and thermal requirements;
- explicit recording of plugged/unplugged state;
- dry-run mode that validates transport/model paths without starting inference.

Recommended principle:

> A formal M0 result should be reproducible from one command on the Mac while the Fold is attached by USB, without requiring manual interaction on the Fold.

---

## 19. Suggested M0 experiment sequence

Do not immediately run the largest matrix.

### Stage A — Harness dry run

- Q3 smoke model only.
- One CPU cell.
- One Vulkan cell.
- Short prompt/generation.
- Verify telemetry, JSON, cleanup and result pullback.

### Stage B — Formal Q4 artifact qualification

- Acquire intended Q4_K_M.
- Verify SHA/source/license/revision.
- Tiny load/generation smoke on CPU and Vulkan.
- Record any incompatibility before full benchmarking.

### Stage C — Backend baseline

Same formal Q4 artifact:

- CPU
- Vulkan

Use identical prompt, generation target and starting thermal envelope.

### Stage D — Context/memory sweep

Test the planned context levels and record:

- whether load/allocation succeeds;
- peak RSS;
- prompt throughput;
- generation throughput;
- thermal effect.

### Stage E — Sustained thermal run

Run long generation against the chosen candidate backend and capture:

- time-to-throttle;
- performance before/after;
- thermal status/headroom;
- battery behavior.

### Stage F — Embedder/GLiNER measurements

Proceed with the M0 embedder/NER/reranker measurements only after the LLM harness path is stable.

---

## 20. Additional project-level recommendations from this provisioning session

### Preserve measured facts over assumptions

Several initial setup expectations turned out to be wrong or incomplete:

- exact Termux Vulkan dependencies needed discovery;
- LAN SSH failed because of VPN state;
- `run-as com.termux` is not available;
- Termux netlink inspection is restricted;
- model repository/filename assumptions caused 401/404s;
- the actual smoke performance differed materially from speculative estimates.

Therefore:

> Any hardware/runtime claim that affects architecture should come from the measured Fold or an explicitly verified source, not from setup prose.

### Keep model identity separate from model lineage

Do not refer only to “Qwen 2.5 3B.” Formal records should include:

```text
base model
instruction derivative
abliterated/modified derivative if any
quantizer/distributor
quantization
exact artifact SHA
```

### Benchmark the production-shaped path later

Termux/CLI M0 is useful for backend selection. It is not the final performance result for Skein.

Once the production `:inference` isolated service exists, repeat key benchmarks through the actual app/service/JNI path and compare against this M0 baseline.

### Protect the core v1 loop

Do not allow hardware benchmarking to expand v1 scope. M0 exists to inform the approved architecture, especially:

```text
unlock
-> write/import
-> index
-> ask/search
-> cited answer
-> edit/revise
-> export
```

---

## 21. Agent runbook snippet

Use this as the starting prompt for the dedicated hardware runner:

```text
You are the sole Skein hardware-runner agent for the Pixel 9 Pro Fold.

Read the M0 hardware handoff, docs/DEVICE.md, docs/DEVICE_RUNNER.md, the relevant bead, and the authoritative design spec before acting.

Canonical device access:
- Android/system: ADB over USB.
- Termux execution: `ssh skein-fold-agent`, reached through `adb forward tcp:18022 tcp:8022`.

Treat the Fold as a controlled measurement target.

You may:
- run approved benchmarks/tests;
- collect non-secret telemetry;
- push/pull benchmark artifacts;
- execute known-good binaries;
- install Skein test APKs only when the claimed issue calls for it.

You may not without explicit human approval:
- root or alter bootloader state;
- weaken GrapheneOS security settings;
- change PIN/biometrics/duress settings;
- run broad package upgrades;
- replace the known-good Vulkan/llama toolchain;
- delete or overwrite model artifacts or stable M0 binaries;
- expose SSH/private-key material;
- access unrelated personal data.

Before every formal run:
1. verify ADB identity;
2. establish ADB-forwarded SSH;
3. verify binary/model SHA-256;
4. capture device/build fingerprint;
5. verify thermal starting condition;
6. record charging/battery state.

After every run:
1. stop samplers;
2. verify no orphan benchmark;
3. persist stdout/stderr and JSON results;
4. attach artifact paths to the bead;
5. report any deviation rather than silently fixing the environment.

Never infer a benchmark result. If a run fails or times out, record it as a failed cell with evidence.
```

---

## 22. Definition of “Fold provisioning complete”

The provisioning issue can be considered complete once the orchestrator verifies:

- [x] GrapheneOS Fold accessible over wired ADB
- [x] Termux SSH operational
- [x] ED25519 key authentication installed
- [ ] `skein-fold-agent` ADB-forwarded SSH alias verified end-to-end
- [x] CPU llama.cpp build exists
- [x] Vulkan llama.cpp build exists
- [x] Mali-G715 enumerates in llama.cpp
- [x] real GGUF inference completed
- [x] stable CPU/Vulkan binary copies created
- [x] binary hashes captured
- [x] device baseline captured
- [x] smoke model hash captured
- [x] smoke benchmark numbers captured
- [ ] baseline artifacts pulled into the Mac-side repo/artifact area
- [ ] existing M0 harness adapted to the proven topology
- [ ] one Mac-driven dry-run benchmark completes end-to-end

The last four unchecked items are orchestrator/harness tasks, not physical-device setup tasks.

---

## 23. Final handoff note

The most important outcome is not merely that llama.cpp runs on the Fold.

We now have a controlled hardware lab with:

```text
Mac orchestration
+ ADB system telemetry
+ SSH Termux execution
+ pinned binaries
+ model hashing
+ real Mali Vulkan inference
+ reproducible smoke evidence
```

The next step is to stop doing ad-hoc terminal work and convert this into a repeatable M0 measurement pipeline. The orchestrator should adapt the harness around the environment that actually works, preserve the security constraints, and use formal measurements—not assumptions—to lock Skein's production inference decisions.
