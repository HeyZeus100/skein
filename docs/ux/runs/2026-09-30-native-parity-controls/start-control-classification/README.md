# Explicit start-control classification

The explicit control contract now requires native classification to equal the
expected EOG value: turn-start must be non-EOG, while turn-end and declared EOS
must be EOG. The earlier false parameter merely omitted an EOG assertion.
This test-only correction leaves the no-manifest ChatML path unchanged.

Local lint, both full inference-service unit variants and both opt-in AndroidTest
Kotlin compilations pass. The 32 actual XML files contain 556 passes with no
failures, errors or skips: all prior 554 identities plus the new start-as-EOG
rejection method in each variant. Both instrumentation compilations executed;
no instrumentation, native model, hosted workflow or device run occurred.

`verification.json` binds the two changed Kotlin files and raw archive. The
archive SHA256 is
`2957a8c24166adbce0d51fa8a2948d60e8b7c99c247071fdf1252243e79cddae`.
Python files are unchanged from `f1867d6a2`; their 15-method affected-suite result
is retained without a new execution claim. All actual artifact, native runtime,
generated stopping, quality and physical acceptance gates remain open.
