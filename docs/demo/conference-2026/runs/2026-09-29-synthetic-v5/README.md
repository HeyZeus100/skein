# Fold supplied-evidence diagnostic v5

The single released four-case attempt completed on source `28354dd25` on 29 September 2026. All four rows are valid, generated rather than skipped, and have consistent isolated-engine token counts. Actual raw instrumentation output reports **one passing test**. This is mechanical execution evidence; **answer quality is mixed and the demo is not signed off**.

The prior 901.58-second timeout remains preserved. This run used the reviewed test-only progress/host-identity repair, verified the already installed application bytes, and replaced only its test package. It did not change the production app or access the owner vault. The frozen limits and questions were unchanged: CPU4, GPU0, context4096, seed17, greedy maximum256 tokens, no custom stops, 180 seconds per case and 900 seconds overall.

## Actual results

| Case | Prompt / generated tokens | Native TTFT | Row elapsed | Reported stop | Quality observation |
|---|---|---|---|---|---|
| Construction synthesis | 463 / 113 | 53.171s | 90.844s | EOS | Names RFI-017 and Nora, but omits finish-selection approval, echoes the quoted-document disclaimer, uses confused wording, and has no citations. |
| Missing PO | 331 / 256 | 37.823s | 111.525s | LENGTH | Initially acknowledges absence, then muddles the RFI/order identifier and repeats. Citations are present, but do not make the answer acceptable. |
| Aviation | 301 / 12 | 34.455s | 37.824s | EOS | Correct missing propeller log with valid `[1]`. |
| Mycology | 395 / 24 | 45.169s | 52.491s | EOS | Correct 21.1g reading, missing citation and explicit synthetic qualification. |

All supplied sources survived into the finalized prompts. A context list is not a rendered citation. Native TTFT excludes earlier setup and UI transport; these timings do not measure the first visible UI answer. EOS is the reported engine reason, not proof of which exact EOG token terminated each answer. No change to sampling, prompt policy, retrieval thresholds, gold or fixture labels follows from these observations.

The 43 valid setup progress events show engine load complete at **7113ms**. The host instrumentation command completed without timeout in **299.547s**; the entire wrapper took approximately **329.64s**, including installation/identity/collection work. The runner verified shutdown of the three exact target processes with two consecutive absent-PID checks and no active instrumentation. Root independently inspected the actual JSON/JSONL and raw JUnit output, rehashed both copied installed APKs, checked the profile and source bindings, and matched fixture excerpt bytes to their supplied revision identifiers.

## Evidence

- [Actual answer rows](answers.jsonl) contain only the frozen fictional supplied evidence and generated answers.
- [Run manifest](run_manifest.json) records the exact source, model/template, app/test identity and profile. Host declarations and device measurements are distinguished.
- [Setup progress](setup-progress.jsonl) preserves the early phase observations.
- [Raw instrumentation output](instrumentation.log) contains the actual one-test outcome.
- [Independent review](independent-review.json) records the checks, scope and remaining gates.
- [SHA256 manifest](sha256.json) pins these unmodified copied artifacts.

This does not prove live retrieval, owner-model selection, offline operation, a successful citation tap, repeated rehearsal or formal Fold acceptance. Historical timeout cause remains unestablished; the new setup observations must not be projected backward. The live construction UI failure and mixed negative result remain separately recorded in [construction probes](../2026-09-29-workspace-update/construction-probes.md).
