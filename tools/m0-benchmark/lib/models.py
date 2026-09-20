#!/usr/bin/env python3
"""Minimal stdlib-only reader for tools/m0-benchmark/models.yaml.

models.yaml is deliberately restricted to a small YAML subset — a mapping
of top-level keys to lists of flat mappings (scalar, quoted-scalar, or
`[a, b, c]` bracketed-list values) — so it can be parsed without adding a
PyYAML dependency (the M0 harness is pure shell + Python stdlib work).

Do not extend models.yaml with nested mappings, multi-line scalars, or
anchors/aliases: this parser will not understand them and will raise
ValueError rather than silently misparsing the manifest.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

_LIST_RE = re.compile(r"^\[(.*)\]$")
_INT_RE = re.compile(r"^-?\d+$")
_FLOAT_RE = re.compile(r"^-?\d+\.\d+$")


def parse_scalar(raw: str):
    """Parse one YAML-subset scalar into a Python value."""
    raw = raw.strip()
    if raw == "":
        return None
    m = _LIST_RE.match(raw)
    if m:
        inner = m.group(1).strip()
        if not inner:
            return []
        return [parse_scalar(part) for part in inner.split(",")]
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in ("'", '"'):
        return raw[1:-1]
    if raw == "true":
        return True
    if raw == "false":
        return False
    if raw == "TBD" or raw == "~" or raw == "null":
        return None
    if _INT_RE.match(raw):
        return int(raw)
    if _FLOAT_RE.match(raw):
        return float(raw)
    return raw


def parse_manifest(text: str) -> dict:
    """Parse the models.yaml subset into {top_key: [ {field: value}, ... ]}."""
    top: dict[str, list[dict]] = {}
    current_key: str | None = None
    current_item: dict | None = None

    for raw_line in text.splitlines():
        line = raw_line.split(" #", 1)[0].rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue

        if not line.startswith((" ", "\t")) and line.endswith(":"):
            current_key = line[:-1].strip()
            top[current_key] = []
            current_item = None
            continue

        stripped = line.strip()
        if stripped.startswith("- "):
            if current_key is None:
                raise ValueError(f"list item outside of a top-level key: {raw_line!r}")
            current_item = {}
            top[current_key].append(current_item)
            stripped = stripped[2:]

        if current_item is None:
            raise ValueError(f"mapping line outside of a list item: {raw_line!r}")
        if ":" not in stripped:
            raise ValueError(f"expected 'key: value', got {raw_line!r}")

        key, _, value = stripped.partition(":")
        current_item[key.strip()] = parse_scalar(value)

    return top


def load(path: Path) -> dict:
    return parse_manifest(path.read_text())


def find(items: list[dict], **filters):
    for item in items:
        if all(item.get(k) == v for k, v in filters.items()):
            return item
    return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("manifest", type=Path)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_model = sub.add_parser("model", help="print one model entry as JSON")
    p_model.add_argument("model_id")

    p_binary = sub.add_parser("binary", help="print one binary entry as JSON")
    p_binary.add_argument("--backend", required=True)
    p_binary.add_argument("--kind", default="bench")

    sub.add_parser("list-models", help="print all model entries as JSON")

    args = ap.parse_args(argv)
    manifest = load(args.manifest)

    if args.cmd == "model":
        entry = find(manifest.get("models", []), id=args.model_id)
        if entry is None:
            print(f"no such model: {args.model_id}", file=sys.stderr)
            return 1
        print(json.dumps(entry))
        return 0

    if args.cmd == "binary":
        entry = find(manifest.get("binaries", []), backend=args.backend, kind=args.kind)
        if entry is None:
            print(f"no such binary: backend={args.backend} kind={args.kind}", file=sys.stderr)
            return 1
        print(json.dumps(entry))
        return 0

    if args.cmd == "list-models":
        print(json.dumps(manifest.get("models", [])))
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
