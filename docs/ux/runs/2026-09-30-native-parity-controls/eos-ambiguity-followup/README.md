# Declared EOS ambiguity follow-up

Independent peer review of `beb07e696` found that the host preparer enforced
unique start/end spellings but could accept a distinct declared EOS whose
spelling occurred twice in the vocabulary. The original unexpected acceptance
is retained in `raw-followup-evidence.zip`, alongside the peer review and its
457-byte metadata-only GGUF counterexample (no model tensors).

The qualifier now counts occurrences of declared token spellings in one bounded
vocabulary pass. Preparation requires the declared EOS spelling to occur exactly
once, regardless of the duplicate token's type. The exact retained peer fixture
now fails with `declared EOS spelling is ambiguous in the vocabulary`.

All 15 affected Python tests pass, including duplicate CONTROL and normal-token
EOS cases. Only four host Python files changed; no Kotlin, production code,
models or defaults changed. The earlier 554 JVM and 113 Python results remain
original evidence, not a new execution. No Gradle, native, hosted CI or device
run occurred in this follow-up. Artifact/runtime and answer-quality acceptance
remain open. See `verification.json` for exact source and archive hashes.
