# Controlled Knowledge-on construction probes

These two UI turns used the already verified `28354dd25` app and the existing fictional construction notes. The owner explicitly left the Fold untouched. Each turn started in a fresh chat, with Search Knowledge checked immediately before its single Send. No duplicate import or automatic resend was performed.

## Supported cross-note question

At **17:10:43.834 UTC**, the runner sent:

> What blocks the Cedar cabinet order, and who coordinates delivery?

Working and Stop remained visible with zero assistant characters through the last timed observation at **182.79 seconds**. The runner pressed normal Stop once at **17:14:30.703 UTC**, **226.87 seconds** after Send. This exceeded the planned 180-second bound; the orchestration overrun is preserved, not reported as a 180-second stop. By **17:14:36.386 UTC**, the composer was idle and the app said **No answer was saved**.

The Context panel subsequently listed exactly the three fictional construction notes: Delivery Coordination, Cabinet Order and RFI-017. This establishes offered context rows, not an answer, citation correctness or a successful citation tap. The positive rehearsal failed.

Content-free numeric logs in the same time window recorded a 604-token prefill, context capacity 16384, and two batches of 512 and 92 tokens. Prefill began 1.962 seconds after Send; the batches took 40069 and 7572 milliseconds, with the second completed at approximately 49.602 seconds after Send. Prefill duration alone therefore does not explain the full blank UI interval.

The terminal log recorded CANCELLED and elapsed 226769 milliseconds, but its token/TTFT fields were zero. Those cancellation-path fields do not establish that native sampling produced no tokens.

## Missing purchase-order number

At **17:20:43.148 UTC**, the runner sent:

> What is the Cedar cabinet purchase order number?

The UI was still Working with no answer text at **62.736 seconds**. At **70.963 seconds**, the first observed answer was already complete and the composer idle. No Stop was used. These are observation bounds, not exact native TTFT or completion timing.

The answer correctly said the order was unreleased and no purchase-order number was provided. It did not invent a number. It also added the unsupported hedge **“it could be different from one record to another.”** No citation marker was rendered. The Context panel again listed exactly the same three fictional notes. Root visually reviewed the actual synthetic-only screenshot; the complete-answer quality and citation gates remain unmet.

## Evidence identities

All original files remain in the ignored local directory `build/agent-logs/fold/ui-28354dd-20260929T164502Z` of the conference-demo worktree. The table pins the reviewed files without copying raw device metadata or owner previews into Git.

| File | SHA256 |
|---|---|
| `controlled-construction-probe.json` | `9a0177820bced44a464ef55e44f4131920a6f0024bba174ea1ba88e4068b53c0` |
| `construction-answer-observations.json` | `2e59b517a50fb8c5a1c29c99b4e130cd0579bceb342f1fe6c420a2d6f7b1d549` |
| `construction-prefill-numeric.json` | `3480ae22c72f3c5ce93d99636369eb4fbc149f750d78e9237727c8d1e89ea0ad` |
| `construction-terminal-numeric.json` | `89b084949192acde5f55adff484f8be4d8c1b2c09c125e00a988c00199d0eee2` |
| `construction-context-review.json` | `4d5e453a639f2d4affc6b7a069d91abe014fafb05b89ead01745de6684d7b7bb` |
| `controlled-missingpo-probe.json` | `70768829688bec77de48f3b88b86d5dca8cc126587bbfa81f5b4becd53649f6b` |
| `missingpo-answer-observations.json` | `e6d3357ed74c5c24712b0209e9d2918b32d545a2e08cbdb4e50e615ac588c170` |
| `missingpo-context-review.json` | `a2721c02d0717b142ef518514922a5685309bd5271b1ee90fce787794695adf9` |
| `171-private-screen.png` | `dd1203fa2d4c6ad0d6d0ed36f0f04749d8049b3d77bba4265ea871e391b83aeb` |

Source review separately confirmed that CitationParser held ordinary text until a citation or terminal flush, and cancellation could bypass that flush. That defect is being repaired separately; it does not establish the exact native token history of the stopped turn. A later supplied-evidence diagnostic uses a different fixed context profile and must not replace either original UI result.
