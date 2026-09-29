#!/usr/bin/env python3
"""Verify a local public GGUF's identity without loading it or contacting a device.

This is metadata qualification, not a structural GGUF validator, a template
renderer, or a claim that the declared EOS token terminates native generation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct

from prepare_android_benchmark import MAX_METADATA

MAX_ENTRIES = 100_000
MAX_ARRAY = 2_000_000
MAX_STRING = 1024 * 1024
SCALARS = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f",
           7: "?", 10: "Q", 11: "q", 12: "d"}
DEFAULT_CONTROLS = ("<|im_start|>", "<|im_end|>", "<|endoftext|>",
                    "<start_of_turn>", "<end_of_turn>", "<eos>", "</s>")


class MetadataReader:
    def __init__(self, data):
        self.data = memoryview(data)
        self.offset = 0

    def read(self, count):
        end = self.offset + count
        if count < 0 or end > len(self.data):
            raise ValueError("truncated GGUF metadata or metadata exceeds 64 MiB bound")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def number(self, kind):
        fmt = "<" + kind
        return struct.unpack(fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        size = self.number("Q")
        if size > MAX_STRING:
            raise ValueError("GGUF metadata string exceeds 1 MiB bound")
        return bytes(self.read(size))

    def value(self, kind, keep=False):
        if kind in SCALARS:
            value = self.number(SCALARS[kind])
            return value if keep else None
        if kind == 8:
            value = self.string()
            return value if keep else None
        if kind != 9:
            raise ValueError("unsupported GGUF metadata type")
        element, count = self.number("I"), self.number("Q")
        if count > MAX_ARRAY:
            raise ValueError("GGUF array exceeds entry bound")
        if element not in SCALARS and element != 8:
            raise ValueError("unsupported GGUF array element type")
        if not keep and element in SCALARS:
            self.read(count * struct.calcsize("<" + SCALARS[element]))
            return None
        if keep:
            return [self.value(element, True) for _ in range(count)]
        for _ in range(count):
            self.value(element)
        return None


def metadata_report(data, controls=DEFAULT_CONTROLS):
    reader = MetadataReader(data)
    if bytes(reader.read(4)) != b"GGUF" or reader.number("I") != 3:
        raise ValueError("expected GGUF v3")
    tensors, count = reader.number("Q"), reader.number("Q")
    if tensors > 1_000_000 or count > MAX_ENTRIES:
        raise ValueError("GGUF entry count exceeds bound")
    wanted = {"general.architecture", "general.name", "general.file_type",
              "tokenizer.ggml.model", "tokenizer.ggml.pre", "tokenizer.chat_template",
              "tokenizer.ggml.tokens", "tokenizer.ggml.token_type",
              "tokenizer.ggml.add_bos_token", "tokenizer.ggml.add_eos_token"}
    values, seen = {}, set()
    tokenizer_hash = hashlib.sha256()
    for _ in range(count):
        start = reader.offset
        key = reader.string().decode("utf-8")
        if key in seen:
            raise ValueError("duplicate GGUF metadata key")
        seen.add(key)
        keep = key in wanted or (key.startswith("tokenizer.") and key.endswith("_token_id"))
        value = reader.value(reader.number("I"), keep)
        if keep:
            values[key] = value
        if key.startswith("tokenizer."):
            tokenizer_hash.update(reader.data[start:reader.offset])
    template = values.get("tokenizer.chat_template")
    tokens, types = values.get("tokenizer.ggml.tokens"), values.get("tokenizer.ggml.token_type")
    if not isinstance(template, bytes):
        raise ValueError("missing or invalid embedded chat template")
    if not isinstance(tokens, list) or not tokens or any(not isinstance(t, bytes) for t in tokens):
        raise ValueError("missing or invalid tokenizer token table")
    if (not isinstance(types, list) or len(types) != len(tokens)
            or any(type(t) is not int for t in types)):
        raise ValueError("missing or inconsistent tokenizer token types")
    declared = {}
    for key, value in values.items():
        if not key.endswith("_token_id"):
            continue
        if type(value) is not int or not 0 <= value < len(tokens):
            raise ValueError("declared tokenizer token ID outside vocabulary")
        declared[key] = {"id": value, "text": tokens[value].decode("utf-8"), "type": types[value]}
    selected = set(controls)
    selected_bytes = {text.encode("utf-8") for text in selected}
    control_entries = [{"id": i, "text": token.decode("utf-8"), "type": types[i]}
                       for i, token in enumerate(tokens)
                       if token in selected_bytes]
    identity = {}
    for key in wanted - {"tokenizer.chat_template", "tokenizer.ggml.tokens", "tokenizer.ggml.token_type"}:
        if key in values:
            value = values[key]
            expected = (bool if key.endswith(("add_bos_token", "add_eos_token")) else
                        int if key == "general.file_type" else bytes)
            if type(value) is not expected:
                raise ValueError("invalid scalar identity metadata")
            identity[key] = value.decode("utf-8") if isinstance(value, bytes) else value
    if not identity.get("general.architecture") or not identity.get("tokenizer.ggml.model"):
        raise ValueError("missing model architecture or tokenizer identity")
    return {
        "identity": identity,
        "metadata_end_offset": reader.offset,
        "vocabulary_size": len(tokens),
        "template_sha256": hashlib.sha256(template).hexdigest(),
        "template_bytes": len(template),
        "tokenizer_metadata_sha256": tokenizer_hash.hexdigest(),
        "tokenizer_metadata_hash_definition": (
            "Concatenated raw GGUF key/type/value entry bytes for tokenizer.* keys "
            "in file order, including template; no tensor/header data."
        ),
        "declared_token_ids": declared,
        "control_entries": control_entries,
        "requested_controls_absent": sorted(selected - {entry["text"] for entry in control_entries}),
    }


def qualify(path, expected_sha256, expected_size=None, controls=DEFAULT_CONTROLS):
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha256):
        raise ValueError("expected SHA256 must be 64 lowercase hex characters")
    if expected_size is not None and expected_size < 1:
        raise ValueError("expected size must be positive")
    with Path(path).open("rb") as stream:
        model_fd = stream.fileno()
        initial = os.fstat(model_fd)
        if not stat.S_ISREG(initial.st_mode):
            raise ValueError("model must be a regular file")
        if expected_size is not None and initial.st_size != expected_size:
            raise ValueError("model size differs from expected")
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
        if digest.hexdigest() != expected_sha256:
            raise ValueError("full model SHA256 differs from expected")
        stream.seek(0)
        report = metadata_report(stream.read(MAX_METADATA), controls)
        final = os.fstat(model_fd)
        def identity(info):
            return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)

        if identity(initial) != identity(final):
            raise ValueError("model changed during qualification")
    return {"schema_version": 1, "qualification": "identity-only",
            "model_sha256": expected_sha256, "model_size_bytes": initial.st_size,
            **report, "unmeasured": ["native_load", "native_template_parity", "native_eog",
                                     "generation_stop_behavior", "answer_quality", "memory", "thermals"],
            "upstream_source_revision_and_license": "requires separate artifact-specific review"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--expected-size", type=int)
    parser.add_argument("--control-token", action="append",
                        help="additional artifact-specific control spelling to locate in the vocabulary")
    args = parser.parse_args(argv)
    try:
        report = qualify(args.model, args.expected_sha256, args.expected_size,
                         (*DEFAULT_CONTROLS, *(args.control_token or [])))
    except (OSError, ValueError, UnicodeError, struct.error) as error:
        parser.error(str(error))
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
