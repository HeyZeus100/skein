#!/usr/bin/env python3
"""Prepare an explicit control manifest from fully verified local GGUF bytes; never load or run a model."""
import argparse
import json
from pathlib import Path
import re

from qualify_model_artifact import qualify

PROFILES = {
    "chatml": ("<|im_start|>", "<|im_end|>"),
    # Google Gemma 4 prompt-formatting reference, not the older Gemma start_of_turn format.
    "gemma4-e4b": ("<|turn>", "<turn|>"),
}
E4B_NAME = re.compile(r"(?:^|[^a-z0-9])gemma[ ._-]*4[ ._-]*e4b(?:$|[^a-z0-9])", re.I)


def prepare(model, expected_sha256, expected_size, profile):
    if profile not in PROFILES:
        raise ValueError("unsupported control profile")
    start, end = PROFILES[profile]
    report = qualify(model, expected_sha256, expected_size, (start, end))
    identity = report["identity"]
    architecture, name = identity["general.architecture"], identity.get("general.name", "")
    if profile == "gemma4-e4b" and (architecture != "gemma4" or not E4B_NAME.search(name)):
        raise ValueError("Gemma 4 E4B architecture and name are not established by this artifact")
    if profile == "chatml" and architecture not in {"llama", "qwen2"}:
        raise ValueError("unsupported ChatML artifact architecture")
    if set(report["requested_controls_in_template"]) != {start, end}:
        raise ValueError("required profile controls are absent from the embedded template")

    def control(spelling):
        entries = [entry for entry in report["control_entries"] if entry["text"] == spelling]
        if len(entries) != 1 or entries[0]["type"] != 3:
            raise ValueError("profile control is not one unambiguous declared CONTROL token")
        return {"spelling": spelling, "id": entries[0]["id"], "type": entries[0]["type"]}

    literal, turn_end = control(start), control(end)
    eos = report["declared_token_ids"].get("tokenizer.ggml.eos_token_id")
    if not eos or eos["type"] != 3 or not eos["text"] or len(eos["text"].encode("utf-8")) > 128:
        raise ValueError("declared EOS control is unavailable")
    eog = [turn_end]
    if eos["id"] != turn_end["id"]:
        eog.append({"spelling": eos["text"], "id": eos["id"], "type": eos["type"]})
    elif eos["text"] != end:
        raise ValueError("declared EOS and turn-end spelling disagree")
    if literal["id"] in {entry["id"] for entry in eog}:
        raise ValueError("turn-start and EOG controls overlap")
    controls = [literal, *eog]
    if len({entry["spelling"] for entry in controls}) != len(controls):
        raise ValueError("ambiguous duplicate control spelling")
    manifest = {
        "schema_version": 1,
        "qualification": "metadata-only-native-parity-controls",
        "profile": profile,
        "model_sha256": report["model_sha256"],
        "model_size_bytes": report["model_size_bytes"],
        "template_sha256": report["template_sha256"],
        "tokenizer_metadata_sha256": report["tokenizer_metadata_sha256"],
        "vocabulary_size": report["vocabulary_size"],
        "architecture": architecture,
        "model_name": name,
        "literal_control": literal,
        "eog_controls": eog,
        "declared_eos_id": eos["id"],
    }
    if len((json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")) > 16_384:
        raise ValueError("control manifest exceeds 16 KiB bound")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--expected-size", required=True, type=int)
    parser.add_argument("--profile", required=True, choices=tuple(PROFILES))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        manifest = prepare(args.model, args.expected_sha256, args.expected_size, args.profile)
        with args.output.open("x", encoding="utf-8") as output:
            output.write(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
