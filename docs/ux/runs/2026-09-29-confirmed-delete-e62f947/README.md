# Confirmed deletion evidence

Exact application source: `e62f94785ef8e696d8ee59745fd299fe1f83cc4a`. The [execution handoff](../../../Handoffs/skein-confirmed-deletion-20260929.md) describes the feature, tests and boundaries. [Status](status.json) distinguishes automated results, released bytes, installation and physical acceptance. [Review receipts](automated-review-receipts.json) pin the inspected original evidence in preserved local worktrees.

The signed Fold APK is 128,085,735 bytes, SHA-256 `4052c0a8ba6363baa1f7387fcce0703ad0ca73e1355a7c7b22512583bca4d4a4`. A single app-only replacement completed at 01:34:10 UTC on 30 September. Root independently rehashed the copied predecessor and replacement APKs. First-install time, data directory, CE/DE inodes and version metadata agree; UID was unavailable. The updater passed 46 host mocks and a host-only dry run before execution. No model import, app-data clear or unrelated APK installation occurred.

Actual local XML: 5,234 passes / 86 skips / zero failures and errors, with explicit ktlint/lint/check success. Actual CI XML: 5,232 passes / 88 unchanged skips / zero failures and errors. CI reused ten unrelated unit-task caches; local reports also include reused outputs. Fresh strict UX produced 1,333 passes / 80 unchanged skips and 552 unchanged screenshots. Ordinary instrumentation produced 281 unique passes with no skips/failures/errors or opt-in diagnostics. Downloaded unsigned release APKs and cold native libraries were byte-identical within their respective pairs. Those release bytes are separate from the installed signed debug bytes.

The original raw artifacts remain under the following preserved worktrees:

- `ux-integration-20260929/build/agent-logs/`: focused/full local reports, candidate inventory, root reviews, immutable app-only release and host validation.
- `delete-chat-ui-20260929/build/agent-logs/remote-delete-e62f947/`: full UX and reproducibility archives, actual XML/JSON, APK/native pairs, API responses and sealed peer reviews.
- `delete-lifecycle-20260929/build/agent-logs/remote-delete-e62f947/`: full CI and ordinary archives, actual XML/JSON, API responses and sealed peer reviews.
- `conference-demo-20260929/build/agent-logs/fold/physical-update-e62f94785-20260930T013355Z/`: single-install record and both copied APKs.

Physical deletion checks are pending normal vault unlock. Existing owner content, nine fictional demo notes, original nine-link root draft, models/defaults, all failures and recovery worktrees are preserved. No file/source-note deletion, full Fold/M0, retrieval/embedding, rendered-citation or broad-model-quality acceptance is claimed.
