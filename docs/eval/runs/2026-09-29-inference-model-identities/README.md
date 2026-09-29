# Local public model identity evidence, 2026-09-29

This bundle records host-only verification of a public Qwen2.5 3B Instruct
Abliterated Q3_K_M artifact. It is **identity-only evidence**, not native runtime,
answer-quality, current app-private model selection or formal M0 acceptance.

The separate conference-demo hardware runner supplied a workstation copy from
shared storage and published its paths in
`skein-session-coordination/20260929/demo.json`, `model_qualification`. This
inference reviewer accessed only those local files. The coordinator separately
records scoped user authorization for a sole runner's data-preserving demo
update/checks; this review performed no device, emulator, native load, build,
installation, generation or model download.

| File | Provenance |
|---|---|
| `qwen-q3-shared-copy.qualification.json` | Exact original 1,945-byte runner report, unchanged. SHA-256 `592dfabd8d239473cf181cdc9e9c9b876c5da77e196d3431643787b4f3ade878`. Runner declares qualifier source `b63ac7d1cf4271594d186f3daae046143bb10c9e`. |
| `host-reverification.json` | Separate review record: independently streamed full-file SHA-256 and size, then reran the qualifier at `8a6208998b5c52db99af0ce1306a8ab2a4bc3e3e`; reproduced report bytes exactly match the original. Tool script digest and verification time are retained. |
| `SHA256SUMS` | Hashes of both JSON files and this README; excludes itself. |

The full GGUF is 1,590,475,744 bytes with SHA-256
`2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12`.
Its raw 2,507-byte template hashes to
`cd8e9439f0570856fd70470bf8889ebd8b5d1107207f67a5efb46e342330527f`;
the report defines and records a separate raw tokenizer-metadata digest.
Declared EOS is 151645 (`<|im_end|>`, type 3); the different `</s>` spelling at
128247 is type 1. Metadata declarations do not prove `LlamaNative.isEog`, exact
native template/token parity or successful generation stopping.

This public shared-storage copy is **not verification of the currently selected
app-private artifact**. Upstream source/conversion revision and artifact-specific
license review remain open. No Q4/Gemma or memory/thermal results are present.
The complete model stays in the runner's ignored workstation cache, not Git.
Earlier reports and their failures have not been changed.

Verify this bundle from its directory with:

```sh
shasum -a 256 -c SHA256SUMS
```

See the [readiness handoff](../../../Handoffs/skein-inference-model-readiness-20260929.md)
for the dated identity table, remaining evaluation requests and acceptance gates.
